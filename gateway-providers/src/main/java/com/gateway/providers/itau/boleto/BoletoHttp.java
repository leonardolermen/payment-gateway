package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
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
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The transport the three boleto clients share: the credential's HttpClient (mTLS in production),
 * the Bearer token for THIS API's token URL, the Itaú headers, and the timeout mapping. Status
 * handling stays in each client because the three APIs disagree on what 200/202/204/404 mean.
 */
final class BoletoHttp {
  private static final Pattern ITAU_UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final ItauTokenClient tokens;
  private final ItauEndpoints endpoints;
  private final KeyStore trustStore;
  private final Duration readTimeout;

  BoletoHttp(ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout) {
    this.tokens = tokens; this.endpoints = endpoints; this.trustStore = trustStore; this.readTimeout = readTimeout;
  }

  HttpRequest.Builder request(String pathAndQuery) {
    return HttpRequest.newBuilder(URI.create(endpoints.apiBase() + pathAndQuery)).timeout(readTimeout);
  }

  HttpResponse<String> send(ItauCredentials creds, HttpRequest.Builder b) {
    HttpClient http = tokens.httpClientFor(creds, endpoints, trustStore);
    b.header("Authorization", "Bearer " + tokens.tokenFor(creds, endpoints, trustStore).value())
        .header("x-itau-correlationID", correlationId())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json");
    // The query and instruction OpenAPIs declare x-itau-apikey required; the sandbox credential has none (NOTES.md).
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
    // A rejected token must not be reused: the next call fetches a fresh one (and a fresh HttpClient).
    if (res.statusCode() == 401) tokens.evict(creds.fingerprint());
    return res;
  }

  private static String correlationId() {
    String fromMdc = MDC.get("correlationId");
    return fromMdc != null && ITAU_UUID.matcher(fromMdc).matches() ? fromMdc : UUID.randomUUID().toString();
  }

  static String seg(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
  static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
