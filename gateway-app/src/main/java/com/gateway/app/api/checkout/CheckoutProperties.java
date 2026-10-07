package com.gateway.app.api.checkout;

import com.gateway.billing.order.checkout.CheckoutLinks;
import com.gateway.billing.order.checkout.CheckoutToken;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl where the payer-facing front serves /pay/; the token is appended as is
 * @param corsOrigins exact origins allowed to call /v1 from a browser; empty = no CORS at all
 * @param rateLimitPerMinute per client IP on /v1/checkout; a payer needs a handful per minute
 */
@ConfigurationProperties("gateway.checkout")
public record CheckoutProperties(String baseUrl, List<String> corsOrigins, int rateLimitPerMinute)
    implements CheckoutLinks {
  public CheckoutProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "http://localhost:5173/pay/";
    }

    if (corsOrigins == null) {
      corsOrigins = List.of();
    }

    // GATEWAY_CORS_ORIGINS= or a trailing comma binds an empty string; registering it as an
    // origin would turn "CORS off" into a configuration that looks on.
    corsOrigins =
        corsOrigins.stream().map(String::strip).filter(origin -> !origin.isEmpty()).toList();

    if (rateLimitPerMinute <= 0) {
      rateLimitPerMinute = 60;
    }
  }

  public String urlFor(String token) {
    return baseUrl + token;
  }

  /** Billing's port: the link an invoice's event carries (spec 2026-10-07 §3). */
  @Override
  public String urlFor(CheckoutToken token) {
    return urlFor(token.value());
  }
}
