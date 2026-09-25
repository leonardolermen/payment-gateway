package com.gateway.merchants.domain;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.security.Secret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * A merchant's API key. We store the hash, not the key: whoever reads the database cannot call the
 * API. The prefix (12 chars: {@code gk_live_} + 4) exists only to find the row before comparing the
 * hash — and so the merchant can recognise the key in a dashboard without seeing it whole.
 *
 * <p>SHA-256 with a pepper rather than bcrypt: the key carries 26 random chars (130 bits), so brute
 * force is impossible even with a fast hash, and bcrypt would cost ~100 ms per authenticated request.
 */
public record ApiKey(String id, MerchantId merchantId, ApiKeyEnvironment environment, String prefix, String hash,
                     boolean active, Instant expiresAt, Instant createdAt) {

  public record Issued(ApiKey apiKey, Secret plainKey) {}

  public static Issued issue(MerchantId merchantId, ApiKeyEnvironment environment, String pepper) {
    String plain = environment.keyPrefix() + Ulid.next();
    ApiKey k = new ApiKey(Ulid.next(), merchantId, environment, prefixOf(plain), hashOf(plain, pepper), true, null, Instant.now());
    return new Issued(k, Secret.of(plain));
  }

  public static String hashOf(String plainKey, String pepper) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(pepper.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(md.digest(plainKey.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String prefixOf(String plainKey) {
    return plainKey.substring(0, Math.min(12, plainKey.length()));
  }

  public static Optional<ApiKeyEnvironment> environmentOf(String plainKey) {
    for (ApiKeyEnvironment e : ApiKeyEnvironment.values()) if (plainKey != null && plainKey.startsWith(e.keyPrefix())) return Optional.of(e);
    return Optional.empty();
  }

  public ApiKey revoke() {
    return new ApiKey(id, merchantId, environment, prefix, hash, false, expiresAt, createdAt);
  }

  /** Rotation: the old key gets a deadline (up to 24 h) instead of dying on the spot. */
  public ApiKey expiringAt(Instant when) {
    return new ApiKey(id, merchantId, environment, prefix, hash, active, when, createdAt);
  }

  public boolean isValid(Instant now) {
    return active && (expiresAt == null || now.isBefore(expiresAt));
  }
}
