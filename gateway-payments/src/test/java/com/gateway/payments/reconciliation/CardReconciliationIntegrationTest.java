package com.gateway.payments.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.card.CardStatusSync;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec §8: an authorization nobody captured, and a card sale that moved at the Cielo. */
class CardReconciliationIntegrationTest extends ServiceIntegrationTestBase {
  static final String APPROVES = "4024007153763171";

  @Autowired CardReconciliation reconciliation;
  @Autowired PaymentRepository payments;
  @Autowired CardStatusSync statusSync;
  @Autowired Divergences divergences;

  Payment authorized() {
    return paymentService.create(
        cardCommand(10000, new CardChoice.NewCard(card(APPROVES), false), null, false));
  }

  List<String> divergences(Payment payment) {
    return jdbc.queryForList(
        "SELECT provider_status FROM payments.reconciliation_divergences WHERE payment_id = ?",
        String.class,
        payment.id());
  }

  /**
   * The Cielo does not expire an authorization and the limit stays held on the payer's card, so
   * after cardCaptureDeadline (5 days) a human is told (spec §11) — once, not every 15 minutes.
   */
  @Test
  void anAuthorizationPastTheDeadlineIsCaptureOverdue() {
    Payment payment = authorized();

    clock.advance(Duration.ofDays(4));
    reconciliation.reconcile(clock.instant());
    assertThat(divergences(payment)).isEmpty();

    clock.advance(Duration.ofDays(2));
    reconciliation.reconcile(clock.instant());
    reconciliation.reconcile(clock.instant());

    assertThat(divergences(payment)).containsExactly("CAPTURE_OVERDUE");
    assertThat(paymentQueries.get(merchant, payment.id()).status())
        .isEqualTo(PaymentStatus.AUTHORIZED);
  }

  @Test
  void aCaptureTheNotificationNeverDeliveredIsFoundByTheQuery() {
    Payment payment = authorized();
    cards.setStatus(payment.card().paymentId(), CardStatus.PAID);

    reconciliation.reconcile(clock.instant());

    Payment after = paymentQueries.get(merchant, payment.id());
    assertThat(after.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(
            jdbc.queryForObject(
                "SELECT source FROM payments.payment_events WHERE payment_id = ? AND type = 'completed'",
                String.class,
                payment.id()))
        .isEqualTo("RECONCILIATION");
  }

  @Test
  void anOverdueAuthorizationCapturedAtTheCieloIsCompletedNotFlagged() {
    Payment payment = authorized();
    cards.setStatus(payment.card().paymentId(), CardStatus.PAID);

    clock.advance(Duration.ofDays(6));
    reconciliation.reconcile(clock.instant());

    assertThat(paymentQueries.get(merchant, payment.id()).status())
        .isEqualTo(PaymentStatus.COMPLETED);
    assertThat(divergences(payment)).isEmpty();
  }

  /** Fix round 1: when the cap bites, the newest sale is the one most likely to be unnotified. */
  @Test
  void withTheCapReachedTheNewestSaleIsTheOneSynced() {
    Payment older = authorized();
    clock.advance(Duration.ofMinutes(1));
    Payment newer = authorized();
    cards.setStatus(older.card().paymentId(), CardStatus.PAID);
    cards.setStatus(newer.card().paymentId(), CardStatus.PAID);
    CardReconciliation capped =
        new CardReconciliation(payments, statusSync, divergences, withCardCap(1));

    capped.reconcile(clock.instant());

    assertThat(paymentQueries.get(merchant, newer.id()).status())
        .isEqualTo(PaymentStatus.COMPLETED);
    assertThat(paymentQueries.get(merchant, older.id()).status())
        .isEqualTo(PaymentStatus.AUTHORIZED);
  }

  /** Fix round 1: once flagged, an overdue authorization is not re-read every run. */
  @Test
  void anOverdueAuthorizationAlreadyFlaggedIsNotReadAgain() {
    Payment payment = authorized();
    clock.advance(Duration.ofDays(6));
    reconciliation.reconcile(clock.instant());
    int findsAfterFlagging = cards.callsFor(payment.card().paymentId()).size();

    reconciliation.reconcile(clock.instant());

    assertThat(divergences(payment)).containsExactly("CAPTURE_OVERDUE");
    assertThat(cards.callsFor(payment.card().paymentId())).hasSize(findsAfterFlagging);
  }

  private static PaymentsProperties withCardCap(int cap) {
    PaymentsProperties defaults = PaymentsProperties.defaults();
    return new PaymentsProperties(
        defaults.defaultExpiresInSeconds(),
        defaults.expirationGrace(),
        defaults.reconciliationLookback(),
        defaults.reconciliationMinAge(),
        defaults.idempotencyTtl(),
        defaults.jobMaxAttempts(),
        defaults.jobLease(),
        defaults.outboxLease(),
        defaults.stuckCreatedAfter(),
        defaults.refundPollMaxAttempts(),
        defaults.refundNotFoundGrace(),
        defaults.reconcileLease(),
        defaults.boletoPollEvery(),
        defaults.boletoPollMaxAttempts(),
        defaults.boletoPollGraceAfterLimit(),
        defaults.boletoDefaultDueInDays(),
        defaults.boletoDefaultPaymentLimitDays(),
        defaults.boletoMaxPaymentLimitDays(),
        defaults.cardCaptureDeadline(),
        defaults.cardReconciliationLookback(),
        cap);
  }
}
