package com.gateway.payments.support;

import com.gateway.payments.payment.PaymentService;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.boleto.Address;
import com.gateway.kernel.provider.boleto.Payer;
import com.gateway.payments.TestApp;
import com.gateway.payments.payment.Payment;
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
public abstract class ServiceIntegrationTestBase {

  @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired protected MutableClock clock;
  @Autowired protected RecordingPixProvider bank;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected PaymentService paymentService;
  @Autowired protected RecordingBoletoProvider boletos;

  protected MerchantId merchant;

  @BeforeEach
  void freshMerchant() {
    clock.reset();
    merchant = MerchantId.next();
  }

  protected Payment newCharge(long cents) {
    return paymentService.createCharge(
        new PaymentService.CreateCharge(merchant, ProviderEnvironment.TEST, Money.brl(cents), "order-1", "a test charge", "123.456.789-09", null));
  }

  protected static Payer payer() {
    return new Payer("Joao da Silva", "12345678901", new Address("Rua das Flores 10", "Centro", "Sao Paulo", "SP", "01310100"));
  }

  protected Payment newBolecode(long cents) {
    return paymentService.createBolecode(
        new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(cents), "order-1", "Pedido 1", payer(), null, null));
  }

  protected List<String> outboxTypes(String aggregateId) {
    return jdbc.queryForList("SELECT event_type FROM payments.outbox WHERE aggregate_id = ? ORDER BY created_at, id", String.class, aggregateId);
  }

  protected String outboxPayload(String aggregateId, String type) {
    return jdbc.queryForObject("SELECT payload FROM payments.outbox WHERE aggregate_id = ? AND event_type = ?", String.class, aggregateId, type);
  }
}
