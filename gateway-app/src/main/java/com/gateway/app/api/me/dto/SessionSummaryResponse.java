package com.gateway.app.api.me.dto;

import com.gateway.merchants.session.Session;
import java.time.Instant;

/** One signed-in device; {@code current} is the one making this request. */
public record SessionSummaryResponse(
    String id,
    String ip,
    String userAgent,
    Instant createdAt,
    Instant lastUsedAt,
    boolean current) {

  public static SessionSummaryResponse of(Session session, String currentSessionId) {
    return new SessionSummaryResponse(
        session.id(),
        session.ip(),
        session.userAgent(),
        session.createdAt(),
        session.lastUsedAt(),
        session.id().equals(currentSessionId));
  }
}
