package com.gateway.providers.itau;

import com.gateway.kernel.security.Secret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A merchant's Itaú credential: six values, see docs/providers/itau/NOTES.md; the Pix key is the
 * receiving account's key and is configuration, but it lives here so a merchant's Itaú setup is
 * one object. The sandbox shape has only {@code client_id}, {@code client_secret}, {@code pix_key};
 * production requires the other three ({@code x_itau_apikey}, {@code certificate_pem},
 * {@code private_key_pem}) — see {@link #requireProductionShape()}.
 */
public record ItauCredentials(
    String clientId, Secret clientSecret, String apiKey, String certificatePem, Secret privateKeyPem, String pixKey) {

  private static final Pattern API_KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  public ItauCredentials {
    requireNonBlank(clientId, "client_id");
    if (clientSecret == null) throw new IllegalArgumentException("missing required field: client_secret");
    requireNonBlank(pixKey, "pix_key");
    if (apiKey != null && !API_KEY.matcher(apiKey).matches()) {
      throw new IllegalArgumentException("x_itau_apikey does not match the Itau format: " + apiKey);
    }
    boolean hasCert = certificatePem != null && !certificatePem.isBlank();
    boolean hasKey = privateKeyPem != null;
    if (hasCert != hasKey) {
      throw new IllegalArgumentException(hasCert ? "certificate_pem present without private_key_pem" : "private_key_pem present without certificate_pem");
    }
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("missing required field: " + field);
  }

  public boolean hasCertificate() {
    return certificatePem != null && privateKeyPem != null;
  }

  /** Called when the endpoint requires mTLS (LIVE): fails fast instead of at the first handshake. */
  public void requireProductionShape() {
    if (!hasCertificate()) throw new IllegalArgumentException("production credential missing certificate_pem/private_key_pem");
    if (apiKey == null) throw new IllegalArgumentException("production credential missing x_itau_apikey");
  }

  /** SHA-256 hex of clientId + certificatePem: cache key for the OAuth token, never the secret. */
  public String fingerprint() {
    MessageDigest digest;
    try {
      digest = MessageDigest.getInstance("SHA-256");
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
    byte[] hash = digest.digest((clientId + "\n" + (certificatePem == null ? "" : certificatePem)).getBytes(StandardCharsets.UTF_8));
    return HexFormat.of().formatHex(hash);
  }

  @Override
  public String toString() {
    return "ItauCredentials[clientId=" + clientId + ", clientSecret=***, apiKey=" + apiKey
        + ", certificatePem=" + (certificatePem == null ? "null" : "***") + ", privateKeyPem=***, pixKey=" + pixKey + "]";
  }

  public static ItauCredentials parse(byte[] json) {
    Raw raw = new ObjectMapper().readValue(json, Raw.class);
    if (raw.clientId == null || raw.clientId.isBlank()) throw new IllegalArgumentException("missing required field: client_id");
    if (raw.clientSecret == null || raw.clientSecret.isBlank()) throw new IllegalArgumentException("missing required field: client_secret");
    if (raw.pixKey == null || raw.pixKey.isBlank()) throw new IllegalArgumentException("missing required field: pix_key");
    return new ItauCredentials(
        raw.clientId,
        Secret.of(raw.clientSecret),
        raw.apiKey,
        raw.certificatePem,
        raw.privateKeyPem == null ? null : Secret.of(raw.privateKeyPem),
        raw.pixKey);
  }

  /** Intermediate shape for Jackson: snake_case wire names, nothing validated yet. */
  private record Raw(
      @JsonProperty("client_id") String clientId,
      @JsonProperty("client_secret") String clientSecret,
      @JsonProperty("x_itau_apikey") String apiKey,
      @JsonProperty("certificate_pem") String certificatePem,
      @JsonProperty("private_key_pem") String privateKeyPem,
      @JsonProperty("pix_key") String pixKey) {}
}
