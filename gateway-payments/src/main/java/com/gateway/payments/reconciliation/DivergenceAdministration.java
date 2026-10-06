package com.gateway.payments.reconciliation;

import com.gateway.payments.dispute.DisputeEvents;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.persistence.PaymentRepository;
import java.util.List;

/**
 * The operator's side of the queue. Lives here, not in the controller, because of one rule: a
 * decision on a MERCHANT row is news to that merchant, so it emits {@code dispute.updated} in the
 * same transaction as the state change. A SYSTEM row is ours alone and emits nothing.
 */
public class DivergenceAdministration {
  private final Divergences divergences;
  private final PaymentRepository payments;
  private final DisputeEvents disputeEvents;

  public DivergenceAdministration(
      Divergences divergences, PaymentRepository payments, DisputeEvents disputeEvents) {
    this.divergences = divergences;
    this.payments = payments;
    this.disputeEvents = disputeEvents;
  }

  public ReconciliationDivergence review(String id, String by) {
    return divergences.review(id, by, this::notifyMerchant);
  }

  public ReconciliationDivergence resolve(
      String id, DivergenceResolution resolution, String note, String by) {
    return divergences.resolve(id, resolution, note, by, this::notifyMerchant);
  }

  /**
   * One payment read per row: the page is at most {@link DivergenceQuery#MAX_LIMIT} and the
   * operator's queue is short. A join belongs here the day it is not.
   */
  public List<DivergenceDetail> list(DivergenceQuery query) {
    return divergences.list(query).stream()
        .map(divergence -> new DivergenceDetail(divergence, paymentOf(divergence)))
        .toList();
  }

  public DivergenceDetail detail(String id) {
    ReconciliationDivergence divergence = divergences.get(id);

    return new DivergenceDetail(divergence, paymentOf(divergence));
  }

  private void notifyMerchant(ReconciliationDivergence divergence) {
    if (divergence.origin() != DivergenceOrigin.MERCHANT) {
      return;
    }

    disputeEvents.updated(paymentOf(divergence).merchantId(), divergence);
  }

  /** The foreign key guarantees the payment; a miss here is a broken database, not a 404. */
  private Payment paymentOf(ReconciliationDivergence divergence) {
    return payments
        .findById(divergence.paymentId())
        .orElseThrow(
            () -> new IllegalStateException("payment " + divergence.paymentId() + " missing"));
  }
}
