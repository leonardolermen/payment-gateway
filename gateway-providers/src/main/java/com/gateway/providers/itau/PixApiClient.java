package com.gateway.providers.itau;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.dto.*;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Clock;
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
  private static final Pattern ITAU_UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final ItauTokenClient tokens;
  private final ItauEndpoints endpoints;
  private final KeyStore trustStore;
  private final Duration readTimeout;
  @SuppressWarnings("unused") private final Clock clock; // part of the planned signature; time today comes from the token client
  private final ObjectMapper mapper = new ObjectMapper();

  PixApiClient(ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout, Clock clock) {
    this.tokens = tokens; this.endpoints = endpoints; this.trustStore = trustStore; this.readTimeout = readTimeout; this.clock = clock;
  }

  CobResponse putCob(ItauCredentials c, String txid, CobRequest body) {
    return send(c, request("/cob/" + seg(txid)).PUT(json(body)), CobResponse.class, false).orElseThrow();
  }

  Optional<CobResponse> getCob(ItauCredentials c, String txid) {
    return send(c, request("/cob/" + seg(txid)).GET(), CobResponse.class, true);
  }

  CobResponse patchCob(ItauCredentials c, String txid, Map<String, Object> patch) {
    return send(c, request("/cob/" + seg(txid)).method("PATCH", json(patch)), CobResponse.class, false).orElseThrow();
  }

  DevolucaoResponse putDevolucao(ItauCredentials c, String e2eid, String id, DevolucaoRequest body) {
    return send(c, request("/pix/" + seg(e2eid) + "/devolucao/" + seg(id)).PUT(json(body)), DevolucaoResponse.class, false).orElseThrow();
  }

  Optional<DevolucaoResponse> getDevolucao(ItauCredentials c, String e2eid, String id) {
    return send(c, request("/pix/" + seg(e2eid) + "/devolucao/" + seg(id)).GET(), DevolucaoResponse.class, true);
  }

  CobList listCob(ItauCredentials c, Instant inicio, Instant fim, int page, int pageSize) {
    String q = "?inicio=" + enc(DateTimeFormatter.ISO_INSTANT.format(inicio)) + "&fim=" + enc(DateTimeFormatter.ISO_INSTANT.format(fim))
        + "&paginacao.paginaAtual=" + page + "&paginacao.itensPorPagina=" + pageSize;
    return send(c, request("/cob" + q).GET(), CobList.class, false).orElseThrow();
  }

  private HttpRequest.Builder request(String pathAndQuery) {
    return HttpRequest.newBuilder(URI.create(endpoints.apiBase() + pathAndQuery)).timeout(readTimeout);
  }

  private HttpRequest.BodyPublisher json(Object body) {
    return HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8);
  }

  /** {@code emptyOn404}: only reads treat "not found" as an answer; on a write it is an error. */
  private <T> Optional<T> send(ItauCredentials creds, HttpRequest.Builder b, Class<T> type, boolean emptyOn404) {
    HttpClient http = tokens.httpClientFor(creds, endpoints, trustStore);
    b.header("Authorization", "Bearer " + tokens.tokenFor(creds, endpoints, trustStore).value())
        .header("x-itau-correlationID", correlationId())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json");
    // Production requires it; the sandbox documents no apikey (NOTES.md "Sandbox authentication").
    if (creds.apiKey() != null) b.header("x-itau-apikey", creds.apiKey());
    HttpRequest req = b.build();
    HttpResponse<String> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpTimeoutException e) {
      throw new ProviderException(ProviderException.Code.TIMEOUT, "Itaú " + req.method() + " timed out", e);
    } catch (IOException e) {
      throw new ProviderException(ProviderException.Code.UNAVAILABLE, "Itaú " + req.method() + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProviderException(ProviderException.Code.UNAVAILABLE, "interrupted calling Itaú", e);
    }
    int status = res.statusCode();
    if (status >= 200 && status < 300) return Optional.of(mapper.readValue(res.body(), type));
    if (status == 404 && emptyOn404) return Optional.empty();
    // A rejected token must not be reused: the next call fetches a fresh one (and a fresh HttpClient).
    if (status == 401) tokens.evict(creds.fingerprint());
    throw ItauErrors.from(status, res.body());
  }

  private static String correlationId() {
    String fromMdc = MDC.get("correlationId");
    return fromMdc != null && ITAU_UUID.matcher(fromMdc).matches() ? fromMdc : UUID.randomUUID().toString();
  }

  private static String seg(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
  private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
