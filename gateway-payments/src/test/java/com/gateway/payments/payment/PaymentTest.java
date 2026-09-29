package com.gateway.payments.payment;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.boleto.*;
import com.gateway.payments.payment.card.CardDetails;
import com.gateway.payments.payment.pix.PixDetails;
import java.time.*;
import org.junit.jupiter.api.Test;

class PaymentTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);

  Payment fresh() {
    return Payment.create(
        MerchantId.next(),
        ProviderEnvironment.TEST,
        "ITAU",
        Money.brl(15990),
        "order-8812",
        "Order 8812",
        null,
        3600,
        clock);
  }

  @Test
  void txidIsTheIdAndFitsBacen() {
    Payment payment = fresh();
    assertThat(payment.id()).matches("[a-zA-Z0-9]{26,35}");
    assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
    // create() already produces the initial "created" event: version starts at 1, not 0.
    assertThat(payment.version()).isEqualTo(1);
    assertThat(payment.createdEvent().sequence()).isEqualTo(1);
  }

  @Test
  void eventsCarryAMonotonicSequenceAndBumpTheVersion() {
    Payment payment = fresh();
    PaymentEvent e1 =
        payment.markPending(
            new PixDetails(payment.id(), "000201…", "pix.example.com/x", null),
            Instant.parse("2026-09-24T13:00:00Z"));
    PaymentEvent e2 =
        payment.markCompleted(
            "E12345678202009091221kkkkkkkkkkk",
            Money.brl(15990),
            Instant.parse("2026-09-24T12:30:00Z"),
            EventSource.PROVIDER_WEBHOOK);
    // sequence 1 is the "created" event produced by create(); pending is 2, completed is 3.
    assertThat(e1.sequence()).isEqualTo(2);
    assertThat(e2.sequence()).isEqualTo(3);
    assertThat(payment.version()).isEqualTo(3);
    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.pix().endToEndId()).isEqualTo("E12345678202009091221kkkkkkkkkkk");
    assertThat(e2.type()).isEqualTo("completed");
  }

  @Test
  void refusedTransitionsThrow() {
    Payment payment = fresh();
    assertThatThrownBy(
            () ->
                payment.markCompleted(
                    "E1", Money.brl(1), Instant.now(), EventSource.PROVIDER_WEBHOOK))
        .isInstanceOf(IllegalStateException.class);
    payment.markPending(new PixDetails(payment.id(), "x", "y", null), Instant.now());
    assertThatThrownBy(
            () -> payment.markCompleted("E1", Money.brl(1), Instant.now(), EventSource.API))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void webhookOnTerminalStateIsRecordedAndIgnored() {
    Payment payment = fresh();
    payment.markPending(new PixDetails(payment.id(), "x", "y", null), Instant.now());
    payment.markCanceled(EventSource.API);
    var ignored = payment.recordIgnored("late webhook E1", EventSource.PROVIDER_WEBHOOK);
    assertThat(ignored).isPresent();
    assertThat(ignored.get().type()).isEqualTo("ignored");
    assertThat(payment.status()).isEqualTo(PaymentStatus.CANCELED);
  }

  @Test
  void unconfirmedWebhookOnPendingIsRecordedWithoutATransition() {
    Payment payment = fresh();
    assertThatThrownBy(() -> payment.recordIgnored("too early", EventSource.PROVIDER_WEBHOOK))
        .isInstanceOf(IllegalStateException.class);
    payment.markPending(new PixDetails(payment.id(), "x", "y", null), Instant.now());
    assertThat(payment.recordIgnored("unconfirmed webhook E1", EventSource.PROVIDER_WEBHOOK))
        .isPresent();
    assertThat(payment.status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void expiredThenPaidByTheBankCompletes() {
    Payment payment = fresh();
    payment.markPending(new PixDetails(payment.id(), "x", "y", null), Instant.now());
    payment.markExpired(EventSource.EXPIRATION_JOB);
    payment.markCompleted("E1", Money.brl(15990), Instant.now(), EventSource.RECONCILIATION);
    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  void eventPayloadsEscapeEmbeddedStrings() {
    Payment payment = fresh();
    PaymentEvent event = payment.markFailed("bank said \"no\" \\ line\nbreak", EventSource.SYSTEM);
    assertThat(event.payload())
        .isEqualTo("{\"reason\":\"bank said \\\"no\\\" \\\\ line\\nbreak\"}");
    assertThat(event.payload()).doesNotContain("\n");
  }

  @Test
  void refundsAreProjectedNotTransitions() {
    Payment payment = fresh();
    payment.markPending(new PixDetails(payment.id(), "x", "y", null), Instant.now());
    payment.markCompleted("E1", Money.brl(15990), Instant.now(), EventSource.PROVIDER_WEBHOOK);
    payment.applyRefund(Money.brl(5000));
    assertThat(payment.partiallyRefunded()).isTrue();
    assertThat(payment.fullyRefunded()).isFalse();
    payment.applyRefund(Money.brl(10990));
    assertThat(payment.fullyRefunded()).isTrue();
    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThatThrownBy(() -> payment.applyRefund(Money.brl(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  Payment bolecode() {
    BoletoDetails b =
        new BoletoDetails(
            "00000042",
            null,
            null,
            null,
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31),
            null);
    return Payment.createBolecode(
        MerchantId.next(),
        ProviderEnvironment.TEST,
        "ITAU",
        Money.brl(12990),
        "order-42",
        "Pedido 42",
        null,
        b,
        BoletoDates.endOfDay(LocalDate.of(2026, 10, 31)),
        clock);
  }

  @Test
  void aBolecodeStartsWithItsNumberAndNoTxid() {
    Payment payment = bolecode();
    assertThat(payment.method()).isEqualTo(PaymentMethod.BOLECODE);
    assertThat(payment.boleto().nossoNumero()).isEqualTo("00000042");
    assertThat(payment.pix().txid()).isNull();
    assertThat(payment.status()).isEqualTo(PaymentStatus.CREATED);
    assertThat(payment.version()).isEqualTo(1);
    assertThat(payment.createdEvent().payload())
        .contains("\"method\":\"BOLECODE\"")
        .contains("\"nossoNumero\":\"00000042\"");
    assertThat(fresh().method()).isEqualTo(PaymentMethod.PIX);
    assertThat(fresh().boleto()).isNull();
  }

  @Test
  void pendingBolecodeCarriesBothSidesAndPaidByBoletoSetsPaidVia() {
    Payment payment = bolecode();
    PixDetails pix = new PixDetails("BL15000005206109000000000000042", "000201…", null, null);
    BoletoDetails issued =
        payment
            .boleto()
            .withIssued("uuid-1", "1".repeat(47), "1".repeat(44), LocalDate.of(2026, 10, 30));
    PaymentEvent pending =
        payment.markPendingBolecode(
            pix, issued, BoletoDates.endOfDay(LocalDate.of(2026, 10, 30)), EventSource.API);
    assertThat(pending.type()).isEqualTo("pending");
    assertThat(payment.boleto().paymentLimitDate()).isEqualTo(LocalDate.of(2026, 10, 30));
    assertThat(payment.boleto().linhaDigitavel()).hasSize(47);
    assertThat(payment.pix().txid()).startsWith("BL");

    PaymentEvent done =
        payment.markCompletedByBoleto(
            Money.brl(12990),
            Instant.parse("2026-10-05T12:00:00Z"),
            "01",
            EventSource.PROVIDER_POLL);
    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.boleto().paidVia()).isEqualTo(PaidVia.BOLETO);
    assertThat(payment.paidAmount()).isEqualTo(Money.brl(12990));
    assertThat(payment.pix().endToEndId()).isNull();
    assertThat(done.payload())
        .contains("\"paidVia\":\"BOLETO\"")
        .contains("\"paidChannel\":\"01\"");
  }

  @Test
  void pixOnABolecodeSetsPaidViaPixAndBoletoCompletionIsRefusedOnPix() {
    Payment payment = bolecode();
    payment.markPendingBolecode(
        new PixDetails("BL1", "emv", null, null),
        payment.boleto(),
        Instant.parse("2026-11-01T02:59:59Z"),
        EventSource.API);
    PaymentEvent e =
        payment.markCompleted(
            "E123", Money.brl(12990), Instant.now(), EventSource.PROVIDER_WEBHOOK);
    assertThat(payment.boleto().paidVia()).isEqualTo(PaidVia.PIX);
    assertThat(e.payload()).contains("\"paidVia\":\"PIX\"");

    Payment pix = fresh();
    pix.markPending(new PixDetails(pix.id(), "x", "y", null), Instant.now());
    assertThatThrownBy(
            () ->
                pix.markCompletedByBoleto(
                    Money.brl(1), Instant.now(), null, EventSource.PROVIDER_POLL))
        .isInstanceOf(IllegalStateException.class);
  }

  Payment card() {
    return Payment.createCard(
        MerchantId.next(),
        ProviderEnvironment.TEST,
        "CIELO",
        Money.brl(10000),
        "order-42",
        "Order 42",
        null,
        CardDetails.requested(3, "VISA", "3171", null),
        clock);
  }

  static CardDetails authorized(CardDetails requested) {
    return new CardDetails(
        "6f8d1753-86bb-4dc0-9ebb-09a29093e1fb",
        "1124060407175",
        "663864",
        "182738",
        requested.brand(),
        requested.last4(),
        requested.installments(),
        null,
        null,
        null);
  }

  @Test
  void aCardPaymentHasNoPixSideAndNoExpiry() {
    Payment payment = card();

    assertThat(payment.method()).isEqualTo(PaymentMethod.CARD);
    assertThat(payment.pix()).isNull();
    assertThat(payment.boleto()).isNull();
    assertThat(payment.expiresAt()).isNull();
    assertThat(payment.card().installments()).isEqualTo(3);
    assertThat(payment.createdEvent().payload()).contains("\"method\":\"CARD\"");
  }

  @Test
  void authorizedThenPartiallyCaptured() {
    Payment payment = card();
    payment.markAuthorized(authorized(payment.card()), EventSource.API);
    assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);

    PaymentEvent captured =
        payment.markCaptured(
            Money.brl(6000), Instant.parse("2026-09-24T12:30:00Z"), EventSource.API);

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.paidAmount()).isEqualTo(Money.brl(6000));
    assertThat(payment.card().capturedAmount()).isEqualTo(6000L);
    assertThat(captured.payload()).contains("\"paidVia\":\"CARD\"").contains("\"paidAmount\":6000");
  }

  @Test
  void anAutomaticCaptureGoesStraightToCompleted() {
    Payment payment = card();

    payment.markCompletedByCard(
        authorized(payment.card()),
        Money.brl(10000),
        Instant.parse("2026-09-24T12:00:01Z"),
        EventSource.API);

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.card().paymentId()).isEqualTo("6f8d1753-86bb-4dc0-9ebb-09a29093e1fb");
  }

  @Test
  void aDeclineFailsAndKeepsTheDeclineCode() {
    Payment payment = card();

    PaymentEvent failed =
        payment.markDeclined(
            authorized(payment.card()).withDecline("INSUFFICIENT_FUNDS"), EventSource.API);

    assertThat(payment.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payment.card().declineCode()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(failed.payload())
        .isEqualTo("{\"reason\":\"CARD_DECLINED\",\"declineCode\":\"INSUFFICIENT_FUNDS\"}");
  }

  /** Spec §4: the refunds' sum is capped by paid_amount, which a partial capture makes smaller. */
  @Test
  void aCardRefundIsCappedByWhatWasCaptured() {
    Payment payment = card();
    payment.markAuthorized(authorized(payment.card()), EventSource.API);
    payment.markCaptured(Money.brl(6000), Instant.parse("2026-09-24T12:30:00Z"), EventSource.API);

    payment.applyRefund(Money.brl(6000));

    assertThat(payment.fullyRefunded()).isTrue();
    assertThatThrownBy(() -> payment.applyRefund(Money.brl(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cardTransitionsRefuseAPixPayment() {
    Payment pix = fresh();

    assertThatThrownBy(
            () ->
                pix.markAuthorized(CardDetails.requested(1, "VISA", "3171", null), EventSource.API))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("markAuthorized on a PIX payment");
  }
}
