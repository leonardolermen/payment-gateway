package com.gateway.payments.idempotency;

import com.gateway.kernel.ids.MerchantId;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
   * HMAC-SHA256 hex of the canonical request body under a server key — used to detect a
   * same-key-different-body conflict. Keyed, not a bare SHA-256: a card request body carries PAN
   * and CVV, and an unkeyed digest of a body whose other fields are guessable is brute-forceable
   * back to the PAN from the idempotency table (PCI DSS 3.5.1 wants a keyed hash for that).
   */
  public static String hashOf(String canonicalBody, byte[] hmacKey) {
    if (hmacKey == null || hmacKey.length == 0) {
      throw new IllegalArgumentException("the idempotency HMAC key is missing");
    }

    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
      return HexFormat.of().formatHex(mac.doFinal(canonicalBody.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 not available", e);
    }
  }
}
