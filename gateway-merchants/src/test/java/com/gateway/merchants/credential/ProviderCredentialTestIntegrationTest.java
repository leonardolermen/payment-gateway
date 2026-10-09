package com.gateway.merchants.credential;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.security.Sha256;
import com.gateway.merchants.TestApp;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApp.class)
@Testcontainers
class ProviderCredentialTestIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired MerchantService merchants;
  @Autowired ProviderCredentialService credentials;

  @Test
  void storesFingerprintAndSecretsSetWithNoTestYet() {
    Merchant merchant = merchants.create("Probe Store A");
    byte[] plaintext = "{\"client_id\":\"x\"}".getBytes(StandardCharsets.UTF_8);
    String fingerprint = Sha256.hex(plaintext);

    credentials.store(
        merchant.id(),
        Provider.ITAU,
        ApiKeyEnvironment.TEST,
        plaintext,
        fingerprint,
        Map.of("clientSecret", true, "certificate", false));

    ProviderCredential found =
        credentials.find(merchant.id(), Provider.ITAU, ApiKeyEnvironment.TEST).orElseThrow();

    assertThat(found.fingerprint()).isEqualTo(fingerprint);
    assertThat(found.secretsSet())
        .containsEntry("clientSecret", true)
        .containsEntry("certificate", false);
    assertThat(found.lastTest()).isNull();
  }

  @Test
  void recordTestIsVisibleOnFind() {
    Merchant merchant = merchants.create("Probe Store B");
    credentials.store(
        merchant.id(),
        Provider.ITAU,
        ApiKeyEnvironment.TEST,
        "{}".getBytes(StandardCharsets.UTF_8));
    Instant checkedAt = Instant.parse("2026-10-09T12:00:00Z");

    credentials.recordTest(
        merchant.id(),
        Provider.ITAU,
        ApiKeyEnvironment.TEST,
        new ProviderCredential.ProbeOutcome(true, "authenticated", checkedAt));

    ProviderCredential.ProbeOutcome outcome =
        credentials
            .find(merchant.id(), Provider.ITAU, ApiKeyEnvironment.TEST)
            .orElseThrow()
            .lastTest();
    assertThat(outcome.ok()).isTrue();
    assertThat(outcome.detail()).isEqualTo("authenticated");
    assertThat(outcome.checkedAt()).isEqualTo(checkedAt);
  }

  @Test
  void fourArgStoreKeepsWorkingWithDerivedFingerprint() {
    Merchant merchant = merchants.create("Probe Store C");
    byte[] plaintext = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    credentials.store(merchant.id(), Provider.ITAU, ApiKeyEnvironment.LIVE, plaintext);

    ProviderCredential found =
        credentials.find(merchant.id(), Provider.ITAU, ApiKeyEnvironment.LIVE).orElseThrow();
    assertThat(found.fingerprint()).isEqualTo(Sha256.hex(plaintext));
    assertThat(found.secretsSet()).isEmpty();
    assertThat(credentials.decrypt(merchant.id(), Provider.ITAU, ApiKeyEnvironment.LIVE))
        .hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(plaintext));
  }
}
