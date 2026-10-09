package com.gateway.app.api.provider.dto;

import com.gateway.app.api.provider.MerchantProviderService.ProviderStatus;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.merchants.credential.ProviderCredential;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One provider as the panel shows it. {@code fingerprint} is the first 8 hex of the stored SHA-256:
 * enough to say "set on …" and notice a change, not to compare outside. {@code secretsSet} says
 * which secrets have a value; {@code fields} is the non-secret part of the payload. Before anything
 * is stored every secret reads false and {@code fields} is empty. {@code notificationKeySet} is
 * null for a provider that has no such key.
 */
public record ProviderStatusResponse(
    String provider,
    List<PaymentMethod> methods,
    boolean configured,
    Instant updatedAt,
    String fingerprint,
    Map<String, Boolean> secretsSet,
    Map<String, String> fields,
    ProbeOutcomeResponse lastTest,
    Boolean notificationKeySet) {
  private static final int FINGERPRINT_PREFIX = 8;

  public static ProviderStatusResponse from(ProviderStatus status) {
    return status
        .credential()
        .map(credential -> configured(status, credential))
        .orElseGet(() -> unconfigured(status));
  }

  private static ProviderStatusResponse configured(
      ProviderStatus status, ProviderCredential credential) {
    return new ProviderStatusResponse(
        status.provider().name(),
        status.entry().methods(),
        true,
        credential.updatedAt(),
        credential.fingerprint() == null
            ? null
            : credential.fingerprint().substring(0, FINGERPRINT_PREFIX),
        credential.secretsSet(),
        credential.publicFields(),
        ProbeOutcomeResponse.from(credential.lastTest()),
        status.notificationKeySet());
  }

  private static ProviderStatusResponse unconfigured(ProviderStatus status) {
    Map<String, Boolean> noSecrets = new LinkedHashMap<>();
    for (String field : status.entry().secretFields()) {
      noSecrets.put(field, false);
    }

    return new ProviderStatusResponse(
        status.provider().name(),
        status.entry().methods(),
        false,
        null,
        null,
        noSecrets,
        Map.of(),
        null,
        status.notificationKeySet());
  }
}
