package com.gateway.app.api.admin.dto;

import com.gateway.merchants.domain.ApiKey;

/** The plain key appears here once: {@link ApiKey} only ever stores the hash. */
public record ApiKeyIssuedResponse(String id, String prefix, com.gateway.merchants.domain.ApiKeyEnvironment environment, String key, String warning) {
  public static ApiKeyIssuedResponse from(ApiKey.Issued issued) {
    ApiKey k = issued.apiKey();
    return new ApiKeyIssuedResponse(k.id(), k.prefix(), k.environment(), issued.plainKey().reveal(),
        "Store it now: this value cannot be retrieved again.");
  }
}
