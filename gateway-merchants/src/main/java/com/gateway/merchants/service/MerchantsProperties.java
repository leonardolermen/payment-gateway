package com.gateway.merchants.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record MerchantsProperties(String masterKey, String apiKeyPepper, Duration apiKeyRotationOverlap) {
  public MerchantsProperties {
    if (apiKeyRotationOverlap == null) apiKeyRotationOverlap = Duration.ofHours(24);
    if (apiKeyPepper == null || apiKeyPepper.isBlank()) {
      throw new IllegalArgumentException("gateway.api-key-pepper is missing");
    }
  }
}
