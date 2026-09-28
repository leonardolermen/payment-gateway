package com.gateway.payments.payment.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec §6, POST /v1/payments/{id}/capture: one capture per sale, 20 cents to the authorized. */
class CardCaptureIntegrationTest extends ServiceIntegrationTestBase {
  static final String APPROVES = "4024007153763171";

  @Autowired CardCapture capture;

  Payment authorized(long cents) {
    return paymentService.create(
        cardCommand(cents, new CardChoice.NewCard(card(APPROVES), false), null, false));
  }

  static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
    assertThatThrownBy(call)
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo(code);
  }

  @Test
  void aTotalCaptureCompletesWithTheAuthorizedAmount() {
    Payment payment = authorized(10000);

    Payment captured = capture.capture(merchant, payment.id(), null);

    assertThat(captured.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(captured.paidAmount()).isEqualTo(Money.brl(10000));
    assertThat(captured.card().capturedAmount()).isEqualTo(10000L);
    assertThat(outboxTypes(payment.id()))
        .containsExactly("payment.authorized", "payment.completed");
  }

  @Test
  void aPartialCaptureCompletesWithWhatWasCaptured() {
    Payment payment = authorized(10000);

    Payment captured = capture.capture(merchant, payment.id(), Money.brl(6000));

    assertThat(captured.paidAmount()).isEqualTo(Money.brl(6000));
    assertThat(cards.sale(payment.card().paymentId()).capturedAmount()).isEqualTo(Money.brl(6000));
  }

  /**
   * The query host lags the PUT: the answer carries no CapturedAmount. The requested 50 is what the
   * Cielo captured; the authorized 100 would let refunds above 50 through.
   */
  @Test
  void aPartialCaptureWhoseAnswerLacksTheAmountKeepsTheRequestedOne() {
    Payment payment = authorized(100);
    cards.nextCaptureAnswersWithoutAmount();

    Payment captured = capture.capture(merchant, payment.id(), Money.brl(50));

    assertThat(captured.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(captured.paidAmount()).isEqualTo(Money.brl(50));
  }

  /** A cancel that won the race: the capture's completion is INVALID_STATE, not a 500. */
  @Test
  void aCaptureLandingOnACanceledPaymentIsInvalidState() {
    Payment payment = authorized(10000);
    cards.duringNextCapture(
        () ->
            jdbc.update(
                "UPDATE payments.payments SET status = 'CANCELED' WHERE id = ?", payment.id()));

    assertCode(() -> capture.capture(merchant, payment.id(), null), "INVALID_STATE");
  }

  /**
   * "Após uma captura, não é possível realizar capturas adicionais" (capturar-apos-autorizacao).
   */
  @Test
  void aSecondCaptureIsAlreadyCaptured() {
    Payment payment = authorized(10000);
    capture.capture(merchant, payment.id(), Money.brl(6000));

    assertCode(() -> capture.capture(merchant, payment.id(), Money.brl(1000)), "ALREADY_CAPTURED");
    assertThat(cards.callsFor(payment.card().paymentId()))
        .containsOnlyOnce("capture:" + payment.card().paymentId());
  }

  @Test
  void anAutomaticallyCapturedPaymentIsAlreadyCaptured() {
    Payment payment = newCard(10000, APPROVES);

    assertCode(() -> capture.capture(merchant, payment.id(), null), "ALREADY_CAPTURED");
  }

  @Test
  void aPixPaymentCannotBeCaptured() {
    Payment pix = newCharge(10000);

    assertCode(() -> capture.capture(merchant, pix.id(), null), "CAPTURE_NOT_ALLOWED");
  }

  /** Twenty cents is the Cielo's floor ("valor inferior a 20 centavos … não são liquidadas"). */
  @Test
  void anAmountOutsideTwentyCentsToTheAuthorizedIsRefusedBeforeTheCielo() {
    Payment payment = authorized(10000);

    assertCode(
        () -> capture.capture(merchant, payment.id(), Money.brl(19)), "CAPTURE_AMOUNT_INVALID");
    assertCode(
        () -> capture.capture(merchant, payment.id(), Money.brl(10001)), "CAPTURE_AMOUNT_INVALID");
    assertThat(cards.callsFor(payment.card().paymentId()))
        .noneMatch(call -> call.startsWith("capture:"));
  }

  @Test
  void aCaptureWhoseAnswerWasLostIsReadBackFromTheQuery() {
    Payment payment = authorized(10000);
    cards.setStatus(payment.card().paymentId(), CardStatus.PAID);
    cards.failNextCaptureWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo PUT timed out", null));

    Payment captured = capture.capture(merchant, payment.id(), null);

    assertThat(captured.status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  void aCaptureThatTimedOutAndDidNotLandStaysAuthorized() {
    Payment payment = authorized(10000);
    cards.failNextCaptureWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo PUT timed out", null));

    assertCode(() -> capture.capture(merchant, payment.id(), null), "PROVIDER_TIMEOUT");
    assertThat(paymentQueries.get(merchant, payment.id()).status())
        .isEqualTo(PaymentStatus.AUTHORIZED);
  }
}
