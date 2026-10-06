package com.gateway.app.api.checkout;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl where the payer-facing front serves /pay/; the token is appended as is
 * @param corsOrigins exact origins allowed to call /v1 from a browser; empty = no CORS at all
 * @param rateLimitPerMinute per client IP on /v1/checkout; a payer needs a handful per minute
 */
@ConfigurationProperties("gateway.checkout")
public record CheckoutProperties(String baseUrl, List<String> corsOrigins, int rateLimitPerMinute) {
  public CheckoutProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "http://localhost:5173/pay/";
    }

    if (corsOrigins == null) {
      corsOrigins = List.of();
    }

    if (rateLimitPerMinute <= 0) {
      rateLimitPerMinute = 60;
    }
  }

  public String urlFor(String token) {
    return baseUrl + token;
  }
}
