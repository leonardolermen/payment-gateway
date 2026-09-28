package com.gateway.payments.reconciliation;

import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.card.CardStatusSync;
import com.gateway.payments.payment.persistence.PaymentRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The card side of the periodic pass (spec §8). There is no listing API at the Cielo, so each card
 * payment is one GET — which is why the window and the cap are the card's own. Then
 * CAPTURE_OVERDUE: an authorization older than cardCaptureDeadline that the query still shows
 * authorized. The sync runs first, so one captured at the Cielo and never notified is completed,
 * not flagged.
 *
 * <p>Separate from ReconciliationService, which lists Pix charges by merchant; the two share
 * nothing but the job that runs them.
 */
public class CardReconciliation {
  private static final Logger log = LoggerFactory.getLogger(CardReconciliation.class);

  private final PaymentRepository payments;
  private final CardStatusSync statusSync;
  private final Divergences divergences;
  private final PaymentsProperties properties;

  public CardReconciliation(
      PaymentRepository payments,
      CardStatusSync statusSync,
      Divergences divergences,
      PaymentsProperties properties) {
    this.payments = payments;
    this.statusSync = statusSync;
    this.divergences = divergences;
    this.properties = properties;
  }

  /** Returns how many payments moved or got a new divergence. */
  public int reconcile(Instant now) {
    Map<String, Payment> candidates = new LinkedHashMap<>();
    for (Payment payment :
        payments.findByMethodAndStatusIn(
            PaymentMethod.CARD,
            EnumSet.of(PaymentStatus.AUTHORIZED, PaymentStatus.COMPLETED),
            now.minus(properties.cardReconciliationLookback()),
            properties.cardReconciliationCap())) {
      candidates.put(payment.id(), payment);
    }
    // AUTHORIZED is card-only, so the status query needs no method filter.
    Instant overdueBefore = now.minus(properties.cardCaptureDeadline());
    for (Payment payment :
        payments.findByStatusCreatedBefore(
            PaymentStatus.AUTHORIZED, overdueBefore, properties.cardReconciliationCap())) {
      candidates.put(payment.id(), payment);
    }

    int changed = 0;
    for (Payment payment : candidates.values()) {
      try {
        changed += reconcileOne(payment, overdueBefore);
      } catch (RuntimeException e) {
        // One merchant's revoked credential must not stop the pass for everyone else.
        log.warn("card reconciliation failed for payment {}", payment.id(), e);
      }
    }

    return changed;
  }

  private int reconcileOne(Payment payment, Instant overdueBefore) {
    statusSync.sync(payment, EventSource.RECONCILIATION);

    Payment after = payments.findById(payment.id()).orElseThrow();
    if (after.status() != payment.status()) {
      return 1;
    }

    boolean overdue =
        after.status() == PaymentStatus.AUTHORIZED && after.createdAt().isBefore(overdueBefore);
    if (overdue
        && divergences.open(
            after,
            "CAPTURE_OVERDUE",
            "authorized since " + after.createdAt() + ", never captured")) {
      return 1;
    }

    return 0;
  }
}
