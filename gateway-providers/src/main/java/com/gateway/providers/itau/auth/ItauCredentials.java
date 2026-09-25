package com.gateway.providers.itau.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.kernel.security.Secret;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * A merchant's Itaú credential: six values for Pix (docs/providers/itau/NOTES.md) plus the boleto
 * account data (spec 2026-09-25 §4): {@code beneficiary_id} (agência 4 + conta 7 + DAC 1, required
 * to issue a boleto), {@code wallet_code} (carteira, 109 is the only one the product documents) and
 * {@code species_code} (espécie, 01 = DM). The sandbox shape has only client_id/client_secret/pix_key;
 * production requires x_itau_apikey, certificate_pem and private_key_pem — see
 * {@link #requireProductionShape()}; a boleto issue requires beneficiary_id — see {@link #requireBoletoShape()}.
 */
public record ItauCredentials(
    String clientId, Secret clientSecret, String apiKey, String certificatePem, Secret privateKeyPem, String pixKey,
    String beneficiaryId, String walletCode, String speciesCode, String fingerprint) {

  private static final Pattern API_KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  private static final Pattern BENEFICIARY = Pattern.compile("^\\d{12}$");
  private static final Pattern WALLET = Pattern.compile("^\\d{3}$");
  private static final Pattern SPECIES = Pattern.compile("^\\d{2}$");
  static final String DEFAULT_WALLET = "109";
  static final String DEFAULT_SPECIES = "01";

  public ItauCredentials {
    requireNonBlank(clientId, "client_id");
    if (clientSecret == null) throw new IllegalArgumentException("missing required field: client_secret");
    requireNonBlank(pixKey, "pix_key");
    if (apiKey != null && !API_KEY.matcher(apiKey).matches()) {
      // The value is not echoed: a key with a stray character is still the merchant's live key, and
      // this message reaches the admin API's 422 body and the log.
      throw new IllegalArgumentException("x_itau_apikey does not match the Itau format (a UUID)");
    }
    boolean hasCert = certificatePem != null && !certificatePem.isBlank();
    boolean hasKey = privateKeyPem != null;
    if (hasCert != hasKey) {
      throw new IllegalArgumentException(hasCert ? "certificate_pem present without private_key_pem" : "private_key_pem present without certificate_pem");
    }
    if (beneficiaryId != null && !BENEFICIARY.matcher(beneficiaryId).matches()) {
      throw new IllegalArgumentException("beneficiary_id must be 12 digits (agencia + conta + DAC)");
    }
    if (walletCode == null) walletCode = DEFAULT_WALLET;
    if (!WALLET.matcher(walletCode).matches()) throw new IllegalArgumentException("wallet_code must be 3 digits");
    if (speciesCode == null) speciesCode = DEFAULT_SPECIES;
    if (!SPECIES.matcher(speciesCode).matches()) throw new IllegalArgumentException("species_code must be 2 digits");
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("missing required field: " + field);
  }

  public boolean hasCertificate() { return certificatePem != null && privateKeyPem != null; }

  public boolean hasBeneficiary() { return beneficiaryId != null; }

  /** Called when the endpoint requires mTLS (LIVE): fails fast instead of at the first handshake. */
  public void requireProductionShape() {
    if (!hasCertificate()) throw new IllegalArgumentException("production credential missing certificate_pem/private_key_pem");
    if (apiKey == null) throw new IllegalArgumentException("production credential missing x_itau_apikey");
  }

  /** Called before an issue: a boleto needs the beneficiary account, and the bank's 400 would name a field the merchant never sent. */
  public void requireBoletoShape() {
    if (!hasBeneficiary()) throw new IllegalArgumentException("beneficiary_id");
  }

  /**
   * SHA-256 hex of the whole decrypted payload, computed once in {@link #parse}: the cache key for
   * the OAuth token and the mTLS HttpClient. It used to hash only client_id + certificate_pem, so a
   * rotated client_secret or private key (same id, same certificate) kept hitting the cached token
   * and the HttpClient built with the OLD key until the token expired or a 401 evicted it. Covering
   * every byte means any change to the credential (a rotated secret or key, a new beneficiary) is a
   * new cache entry. Not a secret by itself, but derived from one: never log it ({@link #toString}
   * leaves it out).
   */
  @Override
  public String fingerprint() {
    return fingerprint;
  }

  @Override
  public String toString() {
    // apiKey authenticates every call with the client id, and beneficiaryId is the merchant's bank
    // account (agência + conta): neither belongs in a log line or an exception built from this.
    return "ItauCredentials[clientId=" + clientId + ", clientSecret=***, apiKey=" + (apiKey == null ? "null" : "***")
        + ", certificatePem=" + (certificatePem == null ? "null" : "***") + ", privateKeyPem=***, pixKey=" + pixKey
        + ", beneficiaryId=" + (beneficiaryId == null ? "null" : "***") + ", walletCode=" + walletCode + ", speciesCode=" + speciesCode + "]";
  }

  public static ItauCredentials parse(byte[] json) {
    String fingerprint = sha256Hex(json);
    Raw raw = new ObjectMapper().readValue(json, Raw.class);
    if (raw.clientId == null || raw.clientId.isBlank()) throw new IllegalArgumentException("missing required field: client_id");
    if (raw.clientSecret == null || raw.clientSecret.isBlank()) throw new IllegalArgumentException("missing required field: client_secret");
    if (raw.pixKey == null || raw.pixKey.isBlank()) throw new IllegalArgumentException("missing required field: pix_key");
    return new ItauCredentials(
        raw.clientId,
        Secret.of(raw.clientSecret),
        raw.apiKey,
        blankToNull(raw.certificatePem),
        // A blank key is a missing key: "" used to pass as present, pair with a certificate, and
        // fail only at the first mTLS handshake instead of here.
        blankToNull(raw.privateKeyPem) == null ? null : Secret.of(raw.privateKeyPem),
        raw.pixKey,
        blankToNull(raw.beneficiaryId),
        blankToNull(raw.walletCode),
        blankToNull(raw.speciesCode),
        fingerprint);
  }

  private static String blankToNull(String s) {
    return s == null || s.isBlank() ? null : s;
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Intermediate shape for Jackson: snake_case wire names, nothing validated yet. */
  private record Raw(
      @JsonProperty("client_id") String clientId,
      @JsonProperty("client_secret") String clientSecret,
      @JsonProperty("x_itau_apikey") String apiKey,
      @JsonProperty("certificate_pem") String certificatePem,
      @JsonProperty("private_key_pem") String privateKeyPem,
      @JsonProperty("pix_key") String pixKey,
      @JsonProperty("beneficiary_id") String beneficiaryId,
      @JsonProperty("wallet_code") String walletCode,
      @JsonProperty("species_code") String speciesCode) {}
}
