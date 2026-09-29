package com.gateway.payments.payment.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Spec §6: cancel on AUTHORIZED is a void; on COMPLETED it is refused — that is a refund. */
class CardVoidIntegrationTest extends ServiceIntegrationTestBase {
  static final String APPROVES = "4024007153763171";

  @Test
  void cancellingAnAuthorizationVoidsItAtTheCielo() {
    Payment payment =
        paymentService.create(
            cardCommand(10000, new CardChoice.NewCard(card(APPROVES), false), null, false));

    Payment canceled = paymentCancellation.cancel(merchant, payment.id());

    assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
    assertThat(cards.sale(payment.card().paymentId()).status()).isEqualTo(CardStatus.VOIDED);
    assertThat(outboxTypes(payment.id())).containsExactly("payment.authorized", "payment.canceled");
  }

  @Test
  void aCapturedPaymentIsRefundedNotCanceled() {
    Payment payment = newCard(10000, APPROVES);

    assertThatThrownBy(() -> paymentCancellation.cancel(merchant, payment.id()))
        .isInstanceOf(DomainException.class)
        .satisfies(
            thrown -> {
              assertThat(((DomainException) thrown).code()).isEqualTo("INVALID_STATE");
              assertThat(thrown.getMessage()).contains("refund");
            });
    assertThat(cards.callsFor(payment.card().paymentId()))
        .noneMatch(call -> call.startsWith("void:"));
  }
}
