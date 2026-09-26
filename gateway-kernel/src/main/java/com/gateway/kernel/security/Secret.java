package com.gateway.kernel.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * A sensitive value (API key, client secret, certificate) that never shows up in logs, exceptions
 * or {@code toString}. Whoever needs the value calls {@link #reveal()} on purpose — the method name
 * is the warning.
 */
public final class Secret {
  private final String value;

  private Secret(String value) {
    this.value = value;
  }

  public static Secret of(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("empty secret");
    }
    return new Secret(value);
  }

  public String reveal() {
    return value;
  }

  @Override public String toString() {
    return "***";
  }

  /** Constant-time comparison: comparing API keys with String.equals leaks the length of the common prefix. */
  @Override public boolean equals(Object o) {
    return o instanceof Secret s && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), s.value.getBytes(StandardCharsets.UTF_8));
  }

  @Override public int hashCode() {
    return Objects.hash(value);
  }
}
