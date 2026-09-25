package com.gateway.payments.payment;

import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.payment.pix.PixDetails;
import com.gateway.payments.payment.boleto.*;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.*;
import org.junit.jupiter.api.Test;

class PaymentTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);

  Payment fresh() {
    return Payment.create(MerchantId.next(), ProviderEnvironment.TEST, "ITAU", Money.brl(15990), "order-8812", "Order 8812", null, 3600, clock);
  }

  @Test
  void txidIsTheIdAndFitsBacen() {
    Payment p = fresh();
    assertThat(p.id()).matches("[a-zA-Z0-9]{26,35}");
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    // create() already produces the initial "created" event: version starts at 1, not 0.
    assertThat(p.version()).isEqualTo(1);
    assertThat(p.createdEvent().sequence()).isEqualTo(1);
  }

  @Test
  void eventsCarryAMonotonicSequenceAndBumpTheVersion() {
    Payment p = fresh();
    PaymentEvent e1 = p.markPending(new PixDetails(p.id(), "000201…", "pix.example.com/x", null), Instant.parse("2026-09-24T13:00:00Z"));
    PaymentEvent e2 = p.markCompleted("E12345678202009091221kkkkkkkkkkk", Money.brl(15990), Instant.parse("2026-09-24T12:30:00Z"), EventSource.PROVIDER_WEBHOOK);
    // sequence 1 is the "created" event produced by create(); pending is 2, completed is 3.
    assertThat(e1.sequence()).isEqualTo(2);
    assertThat(e2.sequence()).isEqualTo(3);
    assertThat(p.version()).isEqualTo(3);
    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(p.pix().endToEndId()).isEqualTo("E12345678202009091221kkkkkkkkkkk");
    assertThat(e2.type()).isEqualTo("completed");
  }

  @Test
  void refusedTransitionsThrow() {
    Payment p = fresh();
    assertThatThrownBy(() -> p.markCompleted("E1", Money.brl(1), Instant.now(), EventSource.PROVIDER_WEBHOOK)).isInstanceOf(IllegalStateException.class);
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    assertThatThrownBy(() -> p.markCompleted("E1", Money.brl(1), Instant.now(), EventSource.API)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void webhookOnTerminalStateIsRecordedAndIgnored() {
    Payment p = fresh();
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    p.markCanceled(EventSource.API);
    var ignored = p.recordIgnored("late webhook E1", EventSource.PROVIDER_WEBHOOK);
    assertThat(ignored).isPresent();
    assertThat(ignored.get().type()).isEqualTo("ignored");
    assertThat(p.status()).isEqualTo(PaymentStatus.CANCELED);
  }

  @Test
  void unconfirmedWebhookOnPendingIsRecordedWithoutATransition() {
    Payment p = fresh();
    assertThatThrownBy(() -> p.recordIgnored("too early", EventSource.PROVIDER_WEBHOOK)).isInstanceOf(IllegalStateException.class);
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    assertThat(p.recordIgnored("unconfirmed webhook E1", EventSource.PROVIDER_WEBHOOK)).isPresent();
    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void expiredThenPaidByTheBankCompletes() {
    Payment p = fresh();
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    p.markExpired(EventSource.EXPIRATION_JOB);
    p.markCompleted("E1", Money.brl(15990), Instant.now(), EventSource.RECONCILIATION);
    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  void eventPayloadsEscapeEmbeddedStrings() {
    Payment p = fresh();
    PaymentEvent event = p.markFailed("bank said \"no\" \\ line\nbreak", EventSource.SYSTEM);
    assertThat(event.payload()).isEqualTo("{\"reason\":\"bank said \\\"no\\\" \\\\ line\\nbreak\"}");
    assertThat(event.payload()).doesNotContain("\n");
  }

  @Test
  void refundsAreProjectedNotTransitions() {
    Payment p = fresh();
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    p.markCompleted("E1", Money.brl(15990), Instant.now(), EventSource.PROVIDER_WEBHOOK);
    p.applyRefund(Money.brl(5000));
    assertThat(p.partiallyRefunded()).isTrue();
    assertThat(p.fullyRefunded()).isFalse();
    p.applyRefund(Money.brl(10990));
    assertThat(p.fullyRefunded()).isTrue();
    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThatThrownBy(() -> p.applyRefund(Money.brl(1))).isInstanceOf(IllegalArgumentException.class);
  }

  Payment bolecode() {
    BoletoDetails b = new BoletoDetails("00000042", null, null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
    return Payment.createBolecode(MerchantId.next(), ProviderEnvironment.TEST, "ITAU", Money.brl(12990), "order-42", "Pedido 42", null, b,
        BoletoDates.endOfDay(LocalDate.of(2026, 10, 31)), clock);
  }

  @Test void aBolecodeStartsWithItsNumberAndNoTxid() {
    Payment p = bolecode();
    assertThat(p.method()).isEqualTo(PaymentMethod.BOLECODE);
    assertThat(p.boleto().nossoNumero()).isEqualTo("00000042");
    assertThat(p.pix().txid()).isNull();
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    assertThat(p.version()).isEqualTo(1);
    assertThat(p.createdEvent().payload()).contains("\"method\":\"BOLECODE\"").contains("\"nossoNumero\":\"00000042\"");
    assertThat(fresh().method()).isEqualTo(PaymentMethod.PIX);
    assertThat(fresh().boleto()).isNull();
  }

  @Test void pendingBolecodeCarriesBothSidesAndPaidByBoletoSetsPaidVia() {
    Payment p = bolecode();
    PixDetails pix = new PixDetails("BL15000005206109000000000000042", "000201…", null, null);
    BoletoDetails issued = p.boleto().withIssued("uuid-1", "1".repeat(47), "1".repeat(44), LocalDate.of(2026, 10, 30));
    PaymentEvent pending = p.markPendingBolecode(pix, issued, BoletoDates.endOfDay(LocalDate.of(2026, 10, 30)), EventSource.API);
    assertThat(pending.type()).isEqualTo("pending");
    assertThat(p.boleto().paymentLimitDate()).isEqualTo(LocalDate.of(2026, 10, 30));
    assertThat(p.boleto().linhaDigitavel()).hasSize(47);
    assertThat(p.pix().txid()).startsWith("BL");

    PaymentEvent done = p.markCompletedByBoleto(Money.brl(12990), Instant.parse("2026-10-05T12:00:00Z"), "01", EventSource.PROVIDER_POLL);
    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(p.boleto().paidVia()).isEqualTo(PaidVia.BOLETO);
    assertThat(p.paidAmount()).isEqualTo(Money.brl(12990));
    assertThat(p.pix().endToEndId()).isNull();
    assertThat(done.payload()).contains("\"paidVia\":\"BOLETO\"").contains("\"paidChannel\":\"01\"");
  }

  @Test void pixOnABolecodeSetsPaidViaPixAndBoletoCompletionIsRefusedOnPix() {
    Payment p = bolecode();
    p.markPendingBolecode(new PixDetails("BL1", "emv", null, null), p.boleto(), Instant.parse("2026-11-01T02:59:59Z"), EventSource.API);
    PaymentEvent e = p.markCompleted("E123", Money.brl(12990), Instant.now(), EventSource.PROVIDER_WEBHOOK);
    assertThat(p.boleto().paidVia()).isEqualTo(PaidVia.PIX);
    assertThat(e.payload()).contains("\"paidVia\":\"PIX\"");

    Payment pix = fresh();
    pix.markPending(new PixDetails(pix.id(), "x", "y", null), Instant.now());
    assertThatThrownBy(() -> pix.markCompletedByBoleto(Money.brl(1), Instant.now(), null, EventSource.PROVIDER_POLL)).isInstanceOf(IllegalStateException.class);
  }
}
