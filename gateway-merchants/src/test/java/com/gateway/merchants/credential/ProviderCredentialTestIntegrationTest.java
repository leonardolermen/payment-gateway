package com.gateway.merchants.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
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
        Map.of("clientSecret", true, "certificate", false),
        Map.of("client_id", "x"));

    ProviderCredential found =
        credentials.find(merchant.id(), Provider.ITAU, ApiKeyEnvironment.TEST).orElseThrow();

    assertThat(found.fingerprint()).isEqualTo(fingerprint);
    assertThat(found.secretsSet())
        .containsEntry("clientSecret", true)
        .containsEntry("certificate", false);
    assertThat(found.publicFields()).containsExactly(Map.entry("client_id", "x"));
    assertThat(found.lastTest()).isNull();
  }

  @Test
  void recordTestOnAMissingCredentialIsAnError() {
    Merchant merchant = merchants.create("Probe Store D");
    ProviderCredential.ProbeOutcome outcome =
        new ProviderCredential.ProbeOutcome(true, "authenticated", Instant.now());

    assertThatThrownBy(
            () ->
                credentials.recordTest(
                    merchant.id(), Provider.CIELO, ApiKeyEnvironment.TEST, "any", outcome))
        .isInstanceOfSatisfying(
            DomainException.class,
            failure -> assertThat(failure.code()).isEqualTo("PROVIDER_CREDENTIALS_MISSING"));
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

    boolean recorded =
        credentials.recordTest(
            merchant.id(),
            Provider.ITAU,
            ApiKeyEnvironment.TEST,
            Sha256.hex("{}".getBytes(StandardCharsets.UTF_8)),
            new ProviderCredential.ProbeOutcome(true, "authenticated", checkedAt));
    assertThat(recorded).isTrue();

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
  void aVerdictForAReplacedCredentialIsNotRecorded() {
    Merchant merchant = merchants.create("Probe Store E");
    byte[] before = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
    byte[] after = "{\"v\":2}".getBytes(StandardCharsets.UTF_8);
    credentials.store(merchant.id(), Provider.ITAU, ApiKeyEnvironment.TEST, before);
    // The probe went out with "before"; a PUT landed "after" while it was at the bank.
    credentials.store(merchant.id(), Provider.ITAU, ApiKeyEnvironment.TEST, after);

    boolean recorded =
        credentials.recordTest(
            merchant.id(),
            Provider.ITAU,
            ApiKeyEnvironment.TEST,
            Sha256.hex(before),
            new ProviderCredential.ProbeOutcome(true, "authenticated", Instant.now()));

    assertThat(recorded).isFalse();
    assertThat(
            credentials
                .find(merchant.id(), Provider.ITAU, ApiKeyEnvironment.TEST)
                .orElseThrow()
                .lastTest())
        .isNull();
  }

  /**
   * A row stored before V104 has no fingerprint. There is nothing to compare the probe against, so
   * the tested fingerprint is adopted with the verdict: the row stops being legacy on its first
   * test, and the next probe compares as usual.
   */
  @Test
  void aLegacyRowAdoptsTheTestedFingerprintWithItsVerdict() {
    Merchant merchant = merchants.create("Probe Store F");
    byte[] plaintext = "{\"legacy\":true}".getBytes(StandardCharsets.UTF_8);
    credentials.store(
        merchant.id(), Provider.ITAU, ApiKeyEnvironment.TEST, plaintext, null, Map.of(), Map.of());
    assertThat(
            credentials
                .find(merchant.id(), Provider.ITAU, ApiKeyEnvironment.TEST)
                .orElseThrow()
                .isLegacy())
        .isTrue();

    boolean recorded =
        credentials.recordTest(
            merchant.id(),
            Provider.ITAU,
            ApiKeyEnvironment.TEST,
            Sha256.hex(plaintext),
            new ProviderCredential.ProbeOutcome(true, "authenticated", Instant.now()));

    assertThat(recorded).isTrue();
    ProviderCredential found =
        credentials.find(merchant.id(), Provider.ITAU, ApiKeyEnvironment.TEST).orElseThrow();
    assertThat(found.isLegacy()).isFalse();
    assertThat(found.fingerprint()).isEqualTo(Sha256.hex(plaintext));
    assertThat(found.lastTest().detail()).isEqualTo("authenticated");
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
    assertThat(found.publicFields()).isEmpty();
    assertThat(credentials.decrypt(merchant.id(), Provider.ITAU, ApiKeyEnvironment.LIVE))
        .hasValueSatisfying(bytes -> assertThat(bytes).isEqualTo(plaintext));
  }
}
