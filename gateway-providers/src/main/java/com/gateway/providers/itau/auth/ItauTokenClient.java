package com.gateway.providers.itau.auth;

import com.gateway.kernel.provider.ProviderException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OAuth2 client credentials at Itaú's STS, over mTLS with the merchant's dynamic certificate
 * (docs/providers/itau/NOTES.md). Tokens live 300 s; we cache one per credential fingerprint and
 * refresh 60 s early. One HttpClient per fingerprint too: the SSLContext carries the merchant's
 * private key, so clients are never shared across merchants. The fingerprint hashes the whole
 * credential payload ({@link ItauCredentials#fingerprint}), so a rotated secret or key is a new
 * entry at once instead of reusing the token and TLS context built with the old one.
 */
public class ItauTokenClient {
  private record Entry(HttpClient http, AccessToken token) {}
  private final Map<String, Entry> cache = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Duration connectTimeout, readTimeout;
  private final ObjectMapper mapper = new ObjectMapper();

  public ItauTokenClient(Clock clock, Duration connectTimeout, Duration readTimeout) {
    this.clock = clock; this.connectTimeout = connectTimeout; this.readTimeout = readTimeout;
  }

  public AccessToken tokenFor(ItauCredentials creds, ItauEndpoints endpoints, KeyStore trustStore) {
    URI tokenUrl = endpoints.tokenUrl();
    if (endpoints.mutualTls()) {
      creds.requireProductionShape();
    }
    String key = creds.fingerprint() + "|" + tokenUrl;
    Entry e = cache.get(key);
    Instant now = clock.instant();
    if (e != null && e.token() != null && e.token().usableAt(now)) {
      return e.token();
    }
    HttpClient http = e != null ? e.http() : newHttpClient(creds, endpoints.mutualTls(), trustStore);
    AccessToken fresh = fetch(http, creds, tokenUrl, now);
    cache.put(key, new Entry(http, fresh));
    return fresh;
  }

  /** The sandbox has no client certificate (NOTES.md "Sandbox authentication"); production always does. */
  private HttpClient newHttpClient(ItauCredentials creds, boolean mutualTls, KeyStore trustStore) {
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(connectTimeout);
    if (mutualTls) {
      builder.sslContext(PemKeyStores.mutualTls(creds.certificatePem(), creds.privateKeyPem().reveal(), trustStore));
    }
    return builder.build();
  }

  /** Also the HttpClient: a credential replaced by the merchant must not keep the old key alive. */
  public void evict(String fingerprint) {
    cache.keySet().removeIf(k -> k.startsWith(fingerprint + "|"));
  }

  public HttpClient httpClientFor(ItauCredentials creds, ItauEndpoints endpoints, KeyStore trustStore) {
    tokenFor(creds, endpoints, trustStore);
    return cache.get(creds.fingerprint() + "|" + endpoints.tokenUrl()).http();
  }

  private AccessToken fetch(HttpClient http, ItauCredentials creds, URI tokenUrl, Instant now) {
    String form = "grant_type=client_credentials&client_id=" + enc(creds.clientId()) + "&client_secret=" + enc(creds.clientSecret().reveal());
    HttpRequest req = HttpRequest.newBuilder(tokenUrl).timeout(readTimeout)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build();
    HttpResponse<String> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      throw new ProviderException(ProviderException.Code.TIMEOUT, "token request timed out", e);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new ProviderException(ProviderException.Code.UNAVAILABLE, "token request failed: " + e.getMessage(), e);
    }
    if (res.statusCode() == 401 || res.statusCode() == 403) {
      throw new ProviderException(ProviderException.Code.UNAUTHENTICATED, res.statusCode(), null, "STS rejected the credentials");
    }
    if (res.statusCode() >= 500) {
      throw new ProviderException(ProviderException.Code.UNAVAILABLE, res.statusCode(), null, "STS unavailable");
    }
    if (res.statusCode() != 200) {
      throw new ProviderException(ProviderException.Code.UNKNOWN, res.statusCode(), null, "unexpected STS status");
    }
    JsonNode body = mapper.readTree(res.body());
    String token = body.path("access_token").asText(null);
    if (token == null || token.isBlank()) {
      throw new ProviderException(ProviderException.Code.UNKNOWN, 200, null, "STS response without access_token");
    }
    long expiresIn = body.path("expires_in").asLong(300);
    return new AccessToken(token, now.plusSeconds(expiresIn));
  }

  private static String enc(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
