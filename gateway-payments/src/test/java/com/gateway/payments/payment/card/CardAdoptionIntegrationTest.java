package com.gateway.payments.payment.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.support.FailableSavedCardRepository;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import com.gateway.payments.support.TestSealer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Review ruling, Task 8 fix round 1: an approved sale always commits. Saving the card is best
 * effort — the money already moved at the Cielo, and a 500 here would make the merchant retry with
 * a new key and charge the payer twice.
 */
class CardAdoptionIntegrationTest extends ServiceIntegrationTestBase {
  @Autowired TestSealer sealer;
  @Autowired FailableSavedCardRepository savedCardRepository;

  int savedCards() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payments.cards WHERE merchant_id = ?",
        Integer.class,
        merchant.value());
  }

  @Test
  void aSealerFailureStillCompletesThePaymentWithoutACard() {
    sealer.failNextSealWith(new IllegalStateException("key service unavailable"));

    Payment payment =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), true), null, null));

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.card().cardId()).isNull();
    assertThat(outboxTypes(payment.id())).containsExactly("payment.completed");
    assertThat(savedCards()).isZero();
  }

  @Test
  void aRepositoryFailureStillAuthorizesThePaymentWithoutACard() {
    savedCardRepository.failNextInsertWith(new IllegalStateException("insert failed"));

    Payment payment =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), true), null, false));

    assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(payment.card().cardId()).isNull();
    assertThat(outboxTypes(payment.id())).containsExactly("payment.authorized");
    assertThat(savedCards()).isZero();
  }
}
