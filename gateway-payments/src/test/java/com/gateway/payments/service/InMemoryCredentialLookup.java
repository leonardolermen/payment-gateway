package com.gateway.payments.service;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.CredentialLookup;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Test-only: every merchant has an Itau TEST credential and no LIVE one. */
public class InMemoryCredentialLookup implements CredentialLookup {
  @Override
  public Optional<ProviderCredentials> find(MerchantId merchantId, String provider, ProviderEnvironment env) {
    if ("ITAU".equals(provider) && env == ProviderEnvironment.TEST) {
      return Optional.of(new ProviderCredentials("{\"client_id\":\"test\"}".getBytes(StandardCharsets.UTF_8), env));
    }
    return Optional.empty();
  }
}
