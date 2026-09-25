package com.gateway.providers.itau.auth;

import java.time.Duration;
import java.time.Instant;

public record AccessToken(String value, Instant expiresAt) {
  /** Refresh a minute early: a token that expires mid-request costs a 401 and a retry against the bank. */
  boolean usableAt(Instant now) { return now.isBefore(expiresAt.minus(Duration.ofSeconds(60))); }
  @Override public String toString() {
    return "AccessToken[***, expiresAt=" + expiresAt + "]";
  }
}
