package com.gateway.merchants.usertoken;

import com.gateway.kernel.ids.MerchantId;
import java.time.Instant;
import java.util.Map;

/** userId is null for an INVITE: the person does not exist yet. */
public record UserToken(
    String id,
    String userId,
    MerchantId merchantId,
    Kind kind,
    String tokenHash,
    Map<String, String> payload,
    Instant expiresAt,
    Instant usedAt,
    Instant createdAt) {
  public enum Kind {
    VERIFY_EMAIL,
    RESET_PASSWORD,
    INVITE
  }
}
