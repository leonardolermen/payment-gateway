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

  /** The refresh being replaced is kept as previous: its reappearance is how theft shows up. */
  public Session rotated(
      String newAccessHash,
      String newRefreshHash,
      Instant newAccessExpiresAt,
      Instant newRefreshExpiresAt,
      Instant now) {
    return new Session(
        id,
        userId,
        newAccessHash,
        newRefreshHash,
        refreshHash,
        newAccessExpiresAt,
        newRefreshExpiresAt,
        ip,
        userAgent,
        createdAt,
        now,
        revokedAt);
  }

  public Session touched(Instant now) {
    return new Session(
        id,
        userId,
        accessHash,
        refreshHash,
        previousRefreshHash,
        accessExpiresAt,
        refreshExpiresAt,
        ip,
        userAgent,
        createdAt,
        now,
        revokedAt);
  }

  public Session revoked(Instant now) {
    return new Session(
        id,
        userId,
        accessHash,
        refreshHash,
        previousRefreshHash,
        accessExpiresAt,
        refreshExpiresAt,
        ip,
        userAgent,
        createdAt,
        lastUsedAt,
        now);
  }
}
