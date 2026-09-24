package com.gateway.kernel.provider;

/** Opaque per-provider secret blob (Itaú: client_id/secret/apikey/cert/key as JSON — see NOTES.md). */
public record ProviderCredentials(byte[] payload, ProviderEnvironment environment) {
  @Override public String toString() { return "ProviderCredentials[" + environment + ", payload=***]"; }
}
