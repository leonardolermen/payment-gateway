package com.gateway.providers.itau.pix;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.TransportFailure;
import com.gateway.providers.itau.ItauErrors;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.pix.dto.*;
import com.gateway.providers.itau.pix.dto.CobList;
import com.gateway.providers.itau.pix.dto.CobRequest;
import com.gateway.providers.itau.pix.dto.CobResponse;
import com.gateway.providers.itau.pix.dto.DevolucaoRequest;
import com.gateway.providers.itau.pix.dto.DevolucaoResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

/**
 * Itaú Pix v2 over HTTP, one environment per instance. No retry here on purpose: PUT /cob and PUT
 * /devolucao are idempotent by our own txid/refund id, so the caller (payments) owns retry policy
 * and can decide with the state it has; a hidden retry here would double the latency budget.
 */
class PixApiClient {
  // Itaú's own regex for x-itau-correlationID / x-itau-apikey (NOTES.md, "Authentication").
  private static final Pattern ITAU_UUID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final ItauTokenClient tokens;
  private final ItauEndpoints endpoints;
  private final KeyStore trustStore;
  private final Duration readTimeout;
  private final ObjectMapper mapper = new ObjectMapper();

  PixApiClient(
      ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout) {
    this.tokens = tokens;
    this.endpoints = endpoints;
    this.trustStore = trustStore;
    this.readTimeout = readTimeout;
  }

  CobResponse putCob(ItauCredentials credentials, String txid, CobRequest body) {
    return send(credentials, request("/cob/" + seg(txid)).PUT(json(body)), CobResponse.class, false)
        .orElseThrow();
  }

  Optional<CobResponse> getCob(ItauCredentials credentials, String txid) {
    return send(credentials, request("/cob/" + seg(txid)).GET(), CobResponse.class, true);
  }

  CobResponse patchCob(ItauCredentials credentials, String txid, Map<String, Object> patch) {
    return send(
            credentials,
            request("/cob/" + seg(txid)).method("PATCH", json(patch)),
            CobResponse.class,
            false)
        .orElseThrow();
  }

  DevolucaoResponse putDevolucao(
      ItauCredentials credentials, String e2eid, String id, DevolucaoRequest body) {
    return send(
            credentials,
            request("/pix/" + seg(e2eid) + "/devolucao/" + seg(id)).PUT(json(body)),
            DevolucaoResponse.class,
            false)
        .orElseThrow();
  }

  Optional<DevolucaoResponse> getDevolucao(ItauCredentials credentials, String e2eid, String id) {
    return send(
        credentials,
        request("/pix/" + seg(e2eid) + "/devolucao/" + seg(id)).GET(),
        DevolucaoResponse.class,
        true);
  }

  CobList listCob(
      ItauCredentials credentials, Instant inicio, Instant fim, int page, int pageSize) {
    String query =
        "?inicio="
            + enc(DateTimeFormatter.ISO_INSTANT.format(inicio))
            + "&fim="
            + enc(DateTimeFormatter.ISO_INSTANT.format(fim))
            + "&paginacao.paginaAtual="
            + page
            + "&paginacao.itensPorPagina="
            + pageSize;
    return send(credentials, request("/cob" + query).GET(), CobList.class, false).orElseThrow();
  }

  private HttpRequest.Builder request(String pathAndQuery) {
    return HttpRequest.newBuilder(URI.create(endpoints.apiBase() + pathAndQuery))
        .timeout(readTimeout);
  }

  private HttpRequest.BodyPublisher json(Object body) {
    return HttpRequest.BodyPublishers.ofString(
        mapper.writeValueAsString(body), StandardCharsets.UTF_8);
  }

  /**
   * {@code emptyOn404}: only reads treat the Pix API's "not found" as an answer; on a write it is
   * an error.
   */
  private <T> Optional<T> send(
      ItauCredentials credentials, HttpRequest.Builder builder, Class<T> type, boolean emptyOn404) {
    HttpClient http = tokens.httpClientFor(credentials, endpoints, trustStore);
    builder
        .header(
            "Authorization",
            "Bearer " + tokens.tokenFor(credentials, endpoints, trustStore).value())
        .header("x-itau-correlationID", correlationId())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json");

    // Production requires it; the sandbox documents no apikey (NOTES.md "Sandbox authentication").
    if (credentials.apiKey() != null) {
      builder.header("x-itau-apikey", credentials.apiKey());
    }
    HttpRequest request = builder.build();
    HttpResponse<String> response;

    try {
      response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpTimeoutException e) {
      throw new ProviderException(
          ProviderException.Code.TIMEOUT, "Itaú " + request.method() + " timed out", e);
    } catch (IOException e) {
      throw new ProviderException(
          ProviderException.Code.UNAVAILABLE,
          TransportFailure.describe("Itaú " + request.method(), request.uri(), e),
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProviderException(
          ProviderException.Code.UNAVAILABLE, "interrupted calling Itaú", e);
    }

    int status = response.statusCode();

    if (status >= 200 && status < 300) {
      try {
        return Optional.of(mapper.readValue(response.body(), type));
      } catch (RuntimeException e) {
        throw new ProviderException(
            ProviderException.Code.UNKNOWN, "unreadable provider response", e);
      }
    }

    // Only the Pix API's own "not found" is an answer. A 404 from a wrong base URL or a proxy page
    // would otherwise read as "charge does not exist" and reconciliation would drop paid charges.
    if (status == 404 && emptyOn404 && ItauErrors.isPixNotFound(response.body())) {
      return Optional.empty();
    }

    // A rejected token must not be reused: the next call fetches a fresh one (and a fresh
    // HttpClient).
    if (status == 401) {
      tokens.evict(credentials.fingerprint());
    }
    throw ItauErrors.from(status, response.body());
  }

  private static String correlationId() {
    String fromMdc = MDC.get("correlationId");
    return fromMdc != null && ITAU_UUID.matcher(fromMdc).matches()
        ? fromMdc
        : UUID.randomUUID().toString();
  }

  private static String seg(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
