package com.gateway.providers.cielo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.security.Secret;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CieloCredentialsTest {
  static final String MERCHANT_ID = "11111111-2222-3333-4444-555555555555";
  static final String MERCHANT_KEY = "A".repeat(40);

  static byte[] json(String id, String key) {
    return ("{\"merchant_id\":\"" + id + "\",\"merchant_key\":\"" + key + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void parsesBothFieldsAndNeverPrintsTheKey() {
    CieloCredentials credentials = CieloCredentials.parse(json(MERCHANT_ID, MERCHANT_KEY));

    assertThat(credentials.merchantId()).isEqualTo(MERCHANT_ID);
    assertThat(credentials.merchantKey().reveal()).isEqualTo(MERCHANT_KEY);
    assertThat(credentials.fingerprint()).hasSize(64);
    assertThat(credentials.toString()).doesNotContain(MERCHANT_KEY).contains("merchantKey=***");
  }

  @Test
  void theIdMustBeAGuid() {
    assertThatThrownBy(() -> CieloCredentials.parse(json("not-a-guid", MERCHANT_KEY)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("merchant_id must be a GUID");
  }

  /**
   * The docs call the key a GUID of 40 characters; their own example is 40 upper-case letters with
   * no hyphen (plan D18). The rule is the example's: 40 letters or digits.
   */
  @Test
  void theKeyIsFortyLettersOrDigitsAndIsNotEchoed() {
    assertThat(CieloCredentials.parse(json(MERCHANT_ID, "aB3".repeat(13) + "x")).merchantKey())
        .isNotNull();
    assertThatThrownBy(() -> CieloCredentials.parse(json(MERCHANT_ID, "SHORT-KEY")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("merchant_key must be 40 letters or digits")
        .hasMessageNotContaining("SHORT-KEY");
  }

  /**
   * The invariant lives in the compact constructor, not in {@code parse}, so a direct {@code new}
   * cannot bypass it — the rule the global code standard requires and {@code ItauCredentials}
   * already follows.
   */
  @Test
  void directConstructionIsValidatedTooNotOnlyParse() {
    assertThatThrownBy(() -> new CieloCredentials(MERCHANT_ID, Secret.of("SHORT-KEY"), "fp"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("merchant_key must be 40 letters or digits");
  }

  @Test
  void aMissingFieldIsNamedFirst() {
    assertThatThrownBy(() -> CieloCredentials.parse("{}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("merchant_id is required");
    assertThatThrownBy(
            () ->
                CieloCredentials.parse(
                    ("{\"merchant_id\":\"" + MERCHANT_ID + "\"}").getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("merchant_key is required");
  }
}
