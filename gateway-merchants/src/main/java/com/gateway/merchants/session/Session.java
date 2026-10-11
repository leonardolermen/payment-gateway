package com.gateway.merchants.session;

import java.time.Instant;

public record Session(
    String id,
    String userId,
    String accessHash,
    String refreshHash,
    String previousRefreshHash,
    Instant accessExpiresAt,
    Instant refreshExpiresAt,
    String ip,
    String userAgent,
    Instant createdAt,
    Instant lastUsedAt,
    Instant revokedAt) {

  public boolean isLive(Instant now) {
    return revokedAt == null && refreshExpiresAt.isAfter(now);
  }

  public boolean accessValid(Instant now) {
    return isLive(now) && accessExpiresAt.isAfter(now);
  }
}
