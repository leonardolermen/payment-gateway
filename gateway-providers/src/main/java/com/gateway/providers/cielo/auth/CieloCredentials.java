package com.gateway.providers.cielo.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.kernel.security.Secret;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
 */
public record CieloCredentials(String merchantId, Secret merchantKey, String fingerprint) {
  private static final Pattern GUID =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern FORTY_ALPHANUMERIC = Pattern.compile("^[A-Za-z0-9]{40}$");

  public static CieloCredentials parse(byte[] json) {
    Raw raw = new ObjectMapper().readValue(json, Raw.class);

    if (raw.merchantId() == null || raw.merchantId().isBlank()) {
      throw new IllegalArgumentException("merchant_id is required");
    }
    if (!GUID.matcher(raw.merchantId()).matches()) {
      throw new IllegalArgumentException("merchant_id must be a GUID");
    }
    if (raw.merchantKey() == null || raw.merchantKey().isBlank()) {
      throw new IllegalArgumentException("merchant_key is required");
    }
    if (!FORTY_ALPHANUMERIC.matcher(raw.merchantKey()).matches()) {
      throw new IllegalArgumentException("merchant_key must be 40 letters or digits");
    }

    return new CieloCredentials(raw.merchantId(), Secret.of(raw.merchantKey()), sha256Hex(json));
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

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Raw(
      @JsonProperty("merchant_id") String merchantId,
      @JsonProperty("merchant_key") String merchantKey) {}
}
