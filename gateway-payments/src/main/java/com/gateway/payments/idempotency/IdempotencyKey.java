package com.gateway.payments.idempotency;

import com.gateway.kernel.ids.MerchantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;

/**
 * An in-flight-or-done record for one {@code (merchant, key)} pair, so a retried request with the
 * same idempotency key gets the original response instead of creating a second charge.
 */
public record IdempotencyKey(
    MerchantId merchantId,
    String key,
    String requestHash,
    IdempotencyStatus status,
    Integer responseCode,
    String responseBody,
    String resourceId,
    Instant createdAt) {

  public static IdempotencyKey begin(
      MerchantId merchantId, String key, String requestHash, Clock clock) {
    return new IdempotencyKey(
        merchantId,
        key,
        requestHash,
        IdempotencyStatus.IN_PROGRESS,
        null,
        null,
        null,
        clock.instant());
  }

  public IdempotencyKey finish(int code, String body, String resourceId) {
    return new IdempotencyKey(
        merchantId, key, requestHash, IdempotencyStatus.DONE, code, body, resourceId, createdAt);
  }

  /**
   * SHA-256 hex of the canonical request body — used to detect a same-key-different-body conflict.
   */
  public static String hashOf(String canonicalBody) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(canonicalBody.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
