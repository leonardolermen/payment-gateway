package com.gateway.payments.support;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.payments.TestApp;
import com.gateway.payments.payment.BoletoSettlement;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentCancellation;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.PixSettlement;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardCustomerData;
import com.gateway.payments.payment.create.CardDataFactory;
import com.gateway.payments.payment.create.CreateBolecodePayment;
import com.gateway.payments.payment.create.CreateCardPayment;
import com.gateway.payments.payment.create.CreatePixPayment;
import com.gateway.payments.payment.create.PayerData;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * One container and one context for every service test class: each test uses a fresh merchant and
 * filters rows by its own ids, so the shared database never leaks between tests.
 */
@SpringBootTest(classes = TestApp.class)
public abstract class ServiceIntegrationTestBase {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired protected MutableClock clock;
  @Autowired protected RecordingPixProvider bank;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected PaymentService paymentService;
  @Autowired protected PaymentQueries paymentQueries;
  @Autowired protected PaymentCancellation paymentCancellation;
  @Autowired protected PixSettlement pixSettlement;
  @Autowired protected BoletoSettlement boletoSettlement;
  @Autowired protected RecordingBoletoProvider boletos;
  @Autowired protected RecordingCardProvider cards;

  protected MerchantId merchant;

  @BeforeEach
  void freshMerchant() {
    clock.reset();
    merchant = MerchantId.next();
  }

  protected Payment newCharge(long cents) {
    return paymentService.create(
        new CreatePixPayment(
            merchant,
            ProviderEnvironment.TEST,
            Money.brl(cents),
            "order-1",
            "a test charge",
            "123.456.789-09",
            null));
  }

  protected static PayerData payer() {
    return new PayerData(
        "Joao da Silva",
        "12345678901",
        new PayerData.AddressData("Rua das Flores 10", "Centro", "Sao Paulo", "SP", "01310100"));
  }

  protected Payment newBolecode(long cents) {
    return paymentService.create(
        new CreateBolecodePayment(
            merchant,
            ProviderEnvironment.TEST,
            Money.brl(cents),
            "order-1",
            "Pedido 1",
            payer(),
            null,
            null));
  }

  /** Sandbox-like test cards (reference/credito-sandbox): the last digit steers the answer. */
  protected static final String APPROVES = "4024007153763171";

  protected static final String INSUFFICIENT_FUNDS = "4024007153760052";

  protected static final YearMonth CARD_TEST_MONTH = YearMonth.of(2026, 9);

  protected static CardData card(String number) {
    return CardDataFactory.from(number, "JOAO DA SILVA", "12/2030", "123", null, CARD_TEST_MONTH);
  }

  protected CreateCardPayment cardCommand(
      long cents, CardChoice choice, Integer installments, Boolean capture) {
    return new CreateCardPayment(
        merchant,
        ProviderEnvironment.TEST,
        Money.brl(cents),
        "order-1",
        "Pedido 1",
        choice,
        installments,
        capture,
        "LOJA42",
        new CardCustomerData("Joao da Silva", "12345678901", "joao@example.com"));
  }

  /** A captured-by-default card payment; the number's last digit steers RecordingCardProvider. */
  protected Payment newCard(long cents, String number) {
    return paymentService.create(
        cardCommand(cents, new CardChoice.NewCard(card(number), false), null, null));
  }

  protected List<String> outboxTypes(String aggregateId) {
    return jdbc.queryForList(
        "SELECT event_type FROM payments.outbox WHERE aggregate_id = ? ORDER BY created_at, id",
        String.class,
        aggregateId);
  }

  protected String outboxPayload(String aggregateId, String type) {
    return jdbc.queryForObject(
        "SELECT payload FROM payments.outbox WHERE aggregate_id = ? AND event_type = ?",
        String.class,
        aggregateId,
        type);
  }
}
