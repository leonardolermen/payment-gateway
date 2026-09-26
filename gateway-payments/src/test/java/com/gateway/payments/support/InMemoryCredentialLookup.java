package com.gateway.payments.support;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.CredentialLookup;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** Test-only: every merchant has an Itau TEST credential and no LIVE one. */
public class InMemoryCredentialLookup implements CredentialLookup {
  @Override
  public Optional<ProviderCredentials> find(
      MerchantId merchantId, String provider, ProviderEnvironment env) {
    if ("ITAU".equals(provider) && env == ProviderEnvironment.TEST) {
      return Optional.of(
          new ProviderCredentials(
              ("{\"client_id\":\"test\",\"beneficiary_id\":\"" + beneficiaryOf(merchantId) + "\"}")
                  .getBytes(StandardCharsets.UTF_8),
              env));
    }
    return Optional.empty();
  }

  /**
   * A distinct 12-digit account per merchant: the Bolecode txid is derived from it, and a shared
   * account would give every test merchant's boleto 00000001 the same txid in the shared context.
   */
  public static String beneficiaryOf(MerchantId merchantId) {
    return String.format(
        "%012d", Math.floorMod(merchantId.value().hashCode() * 2654435761L, 1_000_000_000_000L));
  }
}
