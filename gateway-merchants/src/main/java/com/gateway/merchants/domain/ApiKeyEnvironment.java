package com.gateway.merchants.domain;

/** Key environment: {@code test} routes to the FakePixProvider — the gateway's own sandbox. */
public enum ApiKeyEnvironment {
  LIVE("gk_live_"), TEST("gk_test_");
  private final String keyPrefix;
  ApiKeyEnvironment(String p) { this.keyPrefix = p; }
  public String keyPrefix() {
    return keyPrefix;
  }
}
