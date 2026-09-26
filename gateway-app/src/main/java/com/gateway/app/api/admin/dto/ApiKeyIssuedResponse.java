package com.gateway.app.api.admin.dto;

import com.gateway.merchants.apikey.ApiKey;

/** The plain key appears here once: {@link ApiKey} only ever stores the hash. */
public record ApiKeyIssuedResponse(
    String id,
    String prefix,
    com.gateway.merchants.apikey.ApiKeyEnvironment environment,
    String key,
    String warning) {
  public static ApiKeyIssuedResponse from(ApiKey.Issued issued) {
    ApiKey apiKey = issued.apiKey();
    return new ApiKeyIssuedResponse(
        apiKey.id(),
        apiKey.prefix(),
        apiKey.environment(),
        issued.plainKey().reveal(),
        "Store it now: this value cannot be retrieved again.");
  }
}
