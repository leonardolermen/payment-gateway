package com.gateway.merchants.credential;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.crypto.Encrypted;
import java.time.Instant;
import java.util.Map;

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
    String fingerprint,
    Map<String, Boolean> secretsSet,
    Map<String, String> publicFields,
    ProbeOutcome lastTest,
    boolean active,
    Instant createdAt,
    Instant updatedAt) {
  /** Result of the last "test connection"; {@code detail} is one of our fixed phrases. */
  public record ProbeOutcome(boolean ok, String detail, Instant checkedAt) {}

  public static ProviderCredential create(
      MerchantId merchantId,
      Provider provider,
      ApiKeyEnvironment environment,
      Encrypted payload,
      String fingerprint,
      Map<String, Boolean> secretsSet,
      Map<String, String> publicFields) {
    Instant now = Instant.now();
    return new ProviderCredential(
        Ulid.next(),
        merchantId,
        provider,
        environment,
        payload,
        fingerprint,
        Map.copyOf(secretsSet),
        Map.copyOf(publicFields),
        null,
        true,
        now,
        now);
  }

  /** New secrets invalidate the previous test: it vouched for a different blob. */
  public ProviderCredential withPayload(
      Encrypted next,
      String nextFingerprint,
      Map<String, Boolean> nextSecretsSet,
      Map<String, String> nextPublicFields) {
    return new ProviderCredential(
        id,
        merchantId,
        provider,
        environment,
        next,
        nextFingerprint,
        Map.copyOf(nextSecretsSet),
        Map.copyOf(nextPublicFields),
        null,
        active,
        createdAt,
        Instant.now());
  }

  /**
   * Stored before V104/V105 (self-service): no fingerprint, and {@code secretsSet} and {@code
   * publicFields} are the migration's empty defaults, not a summary of the payload. The next store
   * recomputes all three; a test adopts the fingerprint it was obtained for.
   */
  public boolean isLegacy() {
    return fingerprint == null;
  }

  public ProviderCredential withLastTest(ProbeOutcome outcome) {
    return withLastTest(fingerprint, outcome);
  }

  /** For a legacy row: the verdict and the fingerprint it vouches for, together. */
  public ProviderCredential withLastTest(String testedFingerprint, ProbeOutcome outcome) {
    return new ProviderCredential(
        id,
        merchantId,
        provider,
        environment,
        payload,
        testedFingerprint,
        secretsSet,
        publicFields,
        outcome,
        active,
        createdAt,
        updatedAt);
  }

  public ProviderCredential deactivate() {
    return new ProviderCredential(
        id,
        merchantId,
        provider,
        environment,
        payload,
        fingerprint,
        secretsSet,
        publicFields,
        lastTest,
        false,
        createdAt,
        Instant.now());
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
