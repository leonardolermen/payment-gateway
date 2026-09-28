package com.gateway.providers.cielo.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.kernel.security.Secret;
import com.gateway.kernel.security.Sha256;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * A merchant's Cielo E-commerce credential: the two headers every call carries
 * (reference/gerenciamento-de-credenciais). Same shape in sandbox and production — only the host
 * differs — so there is no environment rule here.
 *
 * <p>Every validation message starts with the field name: the provider turns it into the
 * CREDENTIALS_INCOMPLETE {@code providerType}, and the admin API's 422 names the field. The value
 * itself is never echoed: a key with a typo is still the merchant's key.
 *
 * <p>Validation lives in the compact constructor, not in {@link #parse}, so a direct {@code new}
 * cannot skip it — the same rule {@code ItauCredentials} follows, and the one this class is
 * mirroring.
 */
public record CieloCredentials(String merchantId, Secret merchantKey, String fingerprint) {
  private static final Pattern GUID =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern FORTY_ALPHANUMERIC = Pattern.compile("^[A-Za-z0-9]{40}$");

  public CieloCredentials {
    if (merchantId == null || merchantId.isBlank()) {
      throw new IllegalArgumentException("merchant_id is required");
    }
    if (!GUID.matcher(merchantId).matches()) {
      throw new IllegalArgumentException("merchant_id must be a GUID");
    }
    if (merchantKey == null) {
      throw new IllegalArgumentException("merchant_key is required");
    }
    if (!FORTY_ALPHANUMERIC.matcher(merchantKey.reveal()).matches()) {
      throw new IllegalArgumentException("merchant_key must be 40 letters or digits");
    }
  }

  public static CieloCredentials parse(byte[] json) {
    Raw raw = new ObjectMapper().readValue(json, Raw.class);

    // Blank stays null here so the compact constructor is the one place that decides it is
    // "required" — Secret.of itself would reject a blank value with an unrelated message.
    Secret merchantKey =
        raw.merchantKey() == null || raw.merchantKey().isBlank()
            ? null
            : Secret.of(raw.merchantKey());

    return new CieloCredentials(raw.merchantId(), merchantKey, Sha256.hex(json));
  }

  /**
   * Hash of the whole payload, as ItauCredentials does: not a secret by itself but derived from
   * one, so {@link #toString} leaves it out.
   */
  @Override
  public String fingerprint() {
    return fingerprint;
  }

  @Override
  public String toString() {
    return "CieloCredentials[merchantId=" + merchantId + ", merchantKey=***]";
  }

  private record Raw(
      @JsonProperty("merchant_id") String merchantId,
      @JsonProperty("merchant_key") String merchantKey) {}
}
