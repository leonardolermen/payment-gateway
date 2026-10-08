package com.gateway.merchants;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record MerchantsProperties(
    String masterKey, String apiKeyPepper, Duration apiKeyRotationOverlap, Auth auth) {
  public MerchantsProperties {
    if (apiKeyRotationOverlap == null) {
      apiKeyRotationOverlap = Duration.ofHours(24);
    }
    if (apiKeyPepper == null || apiKeyPepper.isBlank()) {
      throw new IllegalArgumentException("gateway.api-key-pepper is missing");
    }
    if (auth == null) {
      auth = new Auth(null, null);
    }
  }

  /** Dashboard session lifetimes, bound from {@code gateway.auth.*}. */
  public record Auth(Duration accessTtl, Duration refreshTtl) {
    public Auth {
      if (accessTtl == null) {
        accessTtl = Duration.ofMinutes(15);
      }
      if (refreshTtl == null) {
        refreshTtl = Duration.ofDays(30);
      }
    }
  }
}
