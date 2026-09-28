package com.gateway.merchants.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.security.Secret;
import com.gateway.merchants.TestApp;
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
class InboundNotificationKeyServiceIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired InboundNotificationKeyService keys;
  @Autowired JdbcTemplate jdbc;

  @Test
  void matchesOnlyTheKeyThatWasSetAndStoresOnlyItsHash() {
    MerchantId merchant = MerchantId.next();
    keys.set(merchant, "CIELO", Secret.of("s3cr3t-header-value"));

    assertThat(keys.matches(merchant, "CIELO", "s3cr3t-header-value")).isTrue();
    assertThat(keys.matches(merchant, "CIELO", "s3cr3t-header-valuE")).isFalse();
    assertThat(keys.matches(merchant, "CIELO", null)).isFalse();
    assertThat(keys.matches(MerchantId.next(), "CIELO", "s3cr3t-header-value")).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT key_hash FROM merchants.inbound_notification_keys WHERE merchant_id = ?",
                String.class,
                merchant.value()))
        .hasSize(64)
        .doesNotContain("s3cr3t");
  }

  @Test
  void settingAgainReplacesTheKey() {
    MerchantId merchant = MerchantId.next();
    keys.set(merchant, "CIELO", Secret.of("old"));
    keys.set(merchant, "CIELO", Secret.of("new"));

    assertThat(keys.matches(merchant, "CIELO", "old")).isFalse();
    assertThat(keys.matches(merchant, "CIELO", "new")).isTrue();
  }

  @Test
  void aMerchantWithoutAKeyMatchesNothing() {
    assertThat(keys.matches(MerchantId.next(), "CIELO", "anything")).isFalse();
  }
}
