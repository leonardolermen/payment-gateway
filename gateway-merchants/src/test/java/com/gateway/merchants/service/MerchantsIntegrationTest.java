package com.gateway.merchants.service;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.errors.DomainException;
import com.gateway.merchants.TestApp;
import com.gateway.merchants.domain.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApp.class)
@Testcontainers
class MerchantsIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired MerchantService merchants;
  @Autowired ApiKeyService apiKeys;
  @Autowired ProviderCredentialService credentials;
  @Autowired JdbcTemplate jdbc;

  @Test
  void issuesAuthenticatesAndRotates() {
    Merchant m = merchants.create("Acme Store");
    ApiKey.Issued k1 = apiKeys.issue(m.id(), ApiKeyEnvironment.LIVE);

    ApiKeyService.Authenticated a = apiKeys.authenticate(k1.plainKey().reveal()).orElseThrow();
    assertThat(a.merchantId()).isEqualTo(m.id());
    assertThat(a.environment()).isEqualTo(ApiKeyEnvironment.LIVE);
    assertThat(apiKeys.authenticate("gk_live_DOESNOTEXIST00000000000000")).isEmpty();
    assertThat(apiKeys.authenticate("garbage")).isEmpty();

    ApiKey.Issued k2 = apiKeys.rotate(m.id(), ApiKeyEnvironment.LIVE);
    // both valid during the overlap window
    assertThat(apiKeys.authenticate(k1.plainKey().reveal())).isPresent();
    assertThat(apiKeys.authenticate(k2.plainKey().reveal())).isPresent();
    ApiKey old = apiKeys.list(m.id()).stream().filter(k -> k.id().equals(k1.apiKey().id())).findFirst().orElseThrow();
    assertThat(old.expiresAt()).isAfter(Instant.now());
  }

  @Test
  void atMostTwoActiveKeysPerEnvironment() {
    Merchant m = merchants.create("Store B");
    apiKeys.issue(m.id(), ApiKeyEnvironment.TEST);
    apiKeys.issue(m.id(), ApiKeyEnvironment.TEST);
    assertThatThrownBy(() -> apiKeys.issue(m.id(), ApiKeyEnvironment.TEST))
        .isInstanceOf(DomainException.class).extracting("code").isEqualTo("API_KEY_LIMIT");
  }

  @Test
  void suspendedMerchantDoesNotAuthenticate() {
    Merchant m = merchants.create("Store C");
    ApiKey.Issued k = apiKeys.issue(m.id(), ApiKeyEnvironment.LIVE);
    merchants.suspend(m.id());
    assertThat(apiKeys.authenticate(k.plainKey().reveal())).isEmpty();
  }

  @Test
  void databaseHoldsNeitherPlainKeyNorPlainCredential() {
    Merchant m = merchants.create("Store D");
    ApiKey.Issued k = apiKeys.issue(m.id(), ApiKeyEnvironment.LIVE);
    byte[] cred = "{\"client_id\":\"abc123\",\"client_secret\":\"itau-secret\"}".getBytes(StandardCharsets.UTF_8);
    credentials.store(m.id(), Provider.ITAU, ApiKeyEnvironment.LIVE, cred);

    String apiKeyDump = String.join("|", jdbc.queryForList("SELECT hash || prefix FROM merchants.api_keys", String.class));
    assertThat(apiKeyDump).doesNotContain(k.plainKey().reveal());
    byte[] ciphertext = jdbc.queryForObject("SELECT ciphertext FROM merchants.provider_credentials WHERE merchant_id = ?", byte[].class, m.id().value());
    assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain("itau-secret").doesNotContain("abc123");

    assertThat(credentials.decrypt(m.id(), Provider.ITAU, ApiKeyEnvironment.LIVE)).contains(cred);
    assertThat(credentials.decrypt(m.id(), Provider.ITAU, ApiKeyEnvironment.TEST)).isEmpty();
  }

  @Test
  void storingAgainReplacesThePayload() {
    Merchant m = merchants.create("Store E");
    credentials.store(m.id(), Provider.ITAU, ApiKeyEnvironment.LIVE, "v1".getBytes());
    credentials.store(m.id(), Provider.ITAU, ApiKeyEnvironment.LIVE, "v2".getBytes());
    assertThat(credentials.list(m.id())).hasSize(1);
    assertThat(credentials.decrypt(m.id(), Provider.ITAU, ApiKeyEnvironment.LIVE)).contains("v2".getBytes());
  }
}
