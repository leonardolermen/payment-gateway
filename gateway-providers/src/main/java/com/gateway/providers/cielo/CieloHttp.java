package com.gateway.providers.cielo;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.cielo.auth.CieloCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

/**
 * The transport the sales and card clients share: the two credential headers, RequestId, JSON, and
 * the transport-failure mapping. No mTLS and no OAuth at the Cielo
 * (reference/gerenciamento-de-credenciais), so one HttpClient serves every merchant.
 *
 * <p>No retry: an authorization that timed out may have landed, and only the flow (which knows the
 * MerchantOrderId) can ask. Exception messages carry the method, never a body or a header.
 */
public final class CieloHttp {
  private static final Pattern GUID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final HttpClient http;
  private final Duration readTimeout;
  private final ObjectMapper mapper = new ObjectMapper();

  public CieloHttp(Duration connectTimeout, Duration readTimeout) {
    this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    this.readTimeout = readTimeout;
  }

  public HttpRequest.Builder request(URI base, String pathAndQuery) {
    return HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(readTimeout);
  }

  public HttpRequest.BodyPublisher json(Object body) {
    return HttpRequest.BodyPublishers.ofString(
        mapper.writeValueAsString(body), StandardCharsets.UTF_8);
  }

  public HttpResponse<String> send(CieloCredentials credentials, HttpRequest.Builder builder) {
    HttpRequest request =
        builder
            .header("MerchantId", credentials.merchantId())
            .header("MerchantKey", credentials.merchantKey().reveal())
            .header("RequestId", requestId())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build();

    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpTimeoutException e) {
      throw new ProviderException(
          ProviderException.Code.TIMEOUT, "Cielo " + request.method() + " timed out", e);
    } catch (IOException e) {
      throw new ProviderException(
          ProviderException.Code.UNAVAILABLE,
          "Cielo " + request.method() + " failed: " + e.getClass().getSimpleName(),
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProviderException(
          ProviderException.Code.UNAVAILABLE, "interrupted calling the Cielo", e);
    }
  }

  public <T> T read(HttpResponse<String> response, Class<T> type) {
    try {
      return mapper.readValue(response.body(), type);
    } catch (RuntimeException e) {
      throw new ProviderException(ProviderException.Code.UNKNOWN, "unreadable Cielo response", e);
    }
  }

  /** RequestId is 36 characters: the correlation id when it is a GUID, a new one otherwise. */
  private static String requestId() {
    String fromMdc = MDC.get("correlationId");
    return fromMdc != null && GUID.matcher(fromMdc).matches()
        ? fromMdc
        : UUID.randomUUID().toString();
  }
}
