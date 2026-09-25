package com.gateway.providers.itau.auth;

import static org.assertj.core.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ItauCredentialsTest {
  static final String JSON = """
      {"client_id":"11111111-2222-3333-4444-555555555555","client_secret":"s3cr3t","x_itau_apikey":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
       "certificate_pem":"-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----","private_key_pem":"-----BEGIN PRIVATE KEY-----\\nMIIE\\n-----END PRIVATE KEY-----",
       "pix_key":"60701190000104"}""";

  @Test void parsesAllSixFields() {
    ItauCredentials c = ItauCredentials.parse(JSON.getBytes(StandardCharsets.UTF_8));
    assertThat(c.clientId()).startsWith("11111111");
    assertThat(c.clientSecret().reveal()).isEqualTo("s3cr3t");
    assertThat(c.apiKey()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    assertThat(c.pixKey()).isEqualTo("60701190000104");
    assertThat(c.toString()).doesNotContain("s3cr3t").doesNotContain("MIIE");
  }

  @Test void missingFieldNamesTheField() {
    assertThatThrownBy(() -> ItauCredentials.parse("{\"client_id\":\"x\"}".getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("client_secret");
  }

  @Test void apiKeyMustMatchItauRegex() {
    assertThatThrownBy(() -> ItauCredentials.parse(JSON.replace("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "not-a-uuid").getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("x_itau_apikey");
  }

  @Test void sandboxShapeHasNoCertificate() {
    ItauCredentials c = ItauCredentials.parse("{\"client_id\":\"sbx-id\",\"client_secret\":\"sbx-secret\",\"pix_key\":\"60701190000104\"}".getBytes());
    assertThat(c.hasCertificate()).isFalse();
    assertThat(c.apiKey()).isNull();
    assertThatThrownBy(c::requireProductionShape).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("certificate_pem");
    assertThatThrownBy(() -> ItauCredentials.parse("{\"client_id\":\"x\",\"client_secret\":\"y\",\"pix_key\":\"k\",\"certificate_pem\":\"c\"}".getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("private_key_pem");
  }

  @Test void fingerprintChangesWhenAnyFieldChanges() {
    ItauCredentials a = ItauCredentials.parse(JSON.getBytes());
    assertThat(a.fingerprint()).hasSize(64).isEqualTo(ItauCredentials.parse(JSON.getBytes()).fingerprint());
    for (String[] change : new String[][] {
        {"s3cr3t", "other"}, {"MIIE", "MIIF"}, {"MIIB", "MIIC"}, {"60701190000104", "60701190000105"},
        {"11111111-2222", "11111111-9999"}, {"aaaaaaaa-bbbb", "aaaaaaaa-ffff"}}) {
      ItauCredentials b = ItauCredentials.parse(JSON.replace(change[0], change[1]).getBytes());
      assertThat(b.fingerprint()).as("changing %s", change[0]).isNotEqualTo(a.fingerprint());
    }
    assertThat(a.toString()).doesNotContain(a.fingerprint());
  }

  @Test void blankPrivateKeyCountsAsMissing() {
    assertThatThrownBy(() -> ItauCredentials.parse(JSON.replace("-----BEGIN PRIVATE KEY-----\\nMIIE\\n-----END PRIVATE KEY-----", "  ").getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("without private_key_pem");
  }

  static final String WITH_BOLETO = "{\"client_id\":\"sbx-id\",\"client_secret\":\"sbx-secret\",\"pix_key\":\"60701190000104\","
      + "\"beneficiary_id\":\"150000052061\",\"wallet_code\":\"109\",\"species_code\":\"01\"}";

  @Test void parsesTheBoletoFields() {
    ItauCredentials c = ItauCredentials.parse(WITH_BOLETO.getBytes());
    assertThat(c.beneficiaryId()).isEqualTo("150000052061");
    assertThat(c.walletCode()).isEqualTo("109");
    assertThat(c.speciesCode()).isEqualTo("01");
    assertThat(c.hasBeneficiary()).isTrue();
    c.requireBoletoShape();
  }

  @Test void walletAndSpeciesDefaultWhenAbsent() {
    ItauCredentials c = ItauCredentials.parse(WITH_BOLETO.replace(",\"wallet_code\":\"109\",\"species_code\":\"01\"", "").getBytes());
    assertThat(c.walletCode()).isEqualTo("109");
    assertThat(c.speciesCode()).isEqualTo("01");
  }

  @Test void pixOnlyCredentialHasNoBeneficiaryAndSaysSo() {
    ItauCredentials c = ItauCredentials.parse("{\"client_id\":\"sbx-id\",\"client_secret\":\"sbx-secret\",\"pix_key\":\"60701190000104\"}".getBytes());
    assertThat(c.hasBeneficiary()).isFalse();
    assertThatThrownBy(c::requireBoletoShape).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("beneficiary_id");
  }

  @Test void boletoFieldsAreValidatedByRegex() {
    assertThatThrownBy(() -> ItauCredentials.parse(WITH_BOLETO.replace("150000052061", "1500000520").getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("beneficiary_id");
    assertThatThrownBy(() -> ItauCredentials.parse(WITH_BOLETO.replace("\"109\"", "\"1090\"").getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wallet_code");
    assertThatThrownBy(() -> ItauCredentials.parse(WITH_BOLETO.replace("\"01\"", "\"1\"").getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("species_code");
  }

  @Test void beneficiaryChangesTheFingerprint() {
    assertThat(ItauCredentials.parse(WITH_BOLETO.getBytes()).fingerprint())
        .isNotEqualTo(ItauCredentials.parse(WITH_BOLETO.replace("150000052061", "150000052062").getBytes()).fingerprint());
  }
}
