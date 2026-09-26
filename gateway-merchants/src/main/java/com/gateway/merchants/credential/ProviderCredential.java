package com.gateway.merchants.credential;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.crypto.Encrypted;
import java.time.Instant;

/**
 * The merchant's credential at a provider (Itaú client_id/secret/certificate, for instance), always
 * encrypted. The domain never sees the plaintext: {@code ProviderCredentialService} decrypts at the
 * moment of the bank call, and only it does.
 */
public record ProviderCredential(
    String id,
    MerchantId merchantId,
    Provider provider,
    ApiKeyEnvironment environment,
    Encrypted payload,
    boolean active,
    Instant createdAt,
    Instant updatedAt) {
  public static ProviderCredential create(
      MerchantId m, Provider p, ApiKeyEnvironment e, Encrypted payload) {
    Instant now = Instant.now();
    return new ProviderCredential(Ulid.next(), m, p, e, payload, true, now, now);
  }

  public ProviderCredential withPayload(Encrypted next) {
    return new ProviderCredential(
        id, merchantId, provider, environment, next, active, createdAt, Instant.now());
  }

  public ProviderCredential deactivate() {
    return new ProviderCredential(
        id, merchantId, provider, environment, payload, false, createdAt, Instant.now());
  }

  @Override
  public String toString() {
    return "ProviderCredential["
        + id
        + ", "
        + merchantId.value()
        + ", "
        + provider
        + ", "
        + environment
        + ", payload=***]";
  }
}
