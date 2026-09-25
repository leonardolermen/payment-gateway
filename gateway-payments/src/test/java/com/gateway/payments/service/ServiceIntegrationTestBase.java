package com.gateway.payments.service;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.TestApp;
import com.gateway.payments.domain.Payment;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One container and one context for every service test class: each test uses a fresh merchant
 * and filters rows by its own ids, so the shared database never leaks between tests.
 */
@SpringBootTest(classes = TestApp.class)
abstract class ServiceIntegrationTestBase {

  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired MutableClock clock;
  @Autowired RecordingPixProvider bank;
  @Autowired JdbcTemplate jdbc;
  @Autowired PaymentService paymentService;

  MerchantId merchant;

  @BeforeEach
  void freshMerchant() {
    clock.reset();
    merchant = MerchantId.next();
  }

  Payment newCharge(long cents) {
    return paymentService.createCharge(
        new PaymentService.CreateCharge(merchant, ProviderEnvironment.TEST, Money.brl(cents), "order-1", "a test charge", "123.456.789-09", null));
  }

  List<String> outboxTypes(String aggregateId) {
    return jdbc.queryForList("SELECT event_type FROM payments.outbox WHERE aggregate_id = ? ORDER BY created_at, id", String.class, aggregateId);
  }

  String outboxPayload(String aggregateId, String type) {
    return jdbc.queryForObject("SELECT payload FROM payments.outbox WHERE aggregate_id = ? AND event_type = ?", String.class, aggregateId, type);
  }
}
