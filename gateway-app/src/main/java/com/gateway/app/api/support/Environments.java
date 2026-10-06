package com.gateway.app.api.support;

import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.merchants.apikey.ApiKeyEnvironment;

/**
 * The API key's environment decides which bank credential a request reaches. Every controller that
 * calls a provider maps it the same way, so the mapping lives in one place.
 */
public final class Environments {
  private Environments() {}

  public static ProviderEnvironment toProvider(ApiKeyEnvironment env) {
    return env == ApiKeyEnvironment.LIVE ? ProviderEnvironment.LIVE : ProviderEnvironment.TEST;
  }

  /** The way back, for code that holds an order (a provider environment) and reads credentials. */
  public static ApiKeyEnvironment toApiKey(ProviderEnvironment env) {
    return env == ProviderEnvironment.LIVE ? ApiKeyEnvironment.LIVE : ApiKeyEnvironment.TEST;
  }
}
