package com.gateway.payments.dispute;

import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.DivergenceOrigin;
import com.gateway.payments.reconciliation.DivergenceQuery;
import com.gateway.payments.reconciliation.DivergenceStatus;
import com.gateway.payments.reconciliation.Divergences;
import com.gateway.payments.reconciliation.ReconciliationDivergence;
import java.time.Instant;
import java.util.List;

/**
 * The merchant's side of the divergence queue. Every read is scoped twice: the row must be a
 * MERCHANT one and its payment the caller's. Anything else is a 404, never a 403, so a probe cannot
 * tell another merchant's dispute (or one of our SYSTEM rows) from an id that never existed.
 */
public class DisputeService {
  private final Divergences divergences;
  private final PaymentRepository payments;
  private final DisputeEvents disputeEvents;
  private final UnitOfWork unitOfWork;

  public DisputeService(
      Divergences divergences,
      PaymentRepository payments,
      DisputeEvents disputeEvents,
      UnitOfWork unitOfWork) {
    this.divergences = divergences;
    this.payments = payments;
    this.disputeEvents = disputeEvents;
    this.unitOfWork = unitOfWork;
  }

  /**
   * The row and its {@code dispute.updated} commit together: an open dispute the merchant was never
   * told about, or an event for a dispute that rolled back on the unique index, are both worse than
   * a retry.
   */
  public Dispute open(MerchantId merchantId, String paymentId, DisputeReason reason, String note) {
    Payment payment = owned(merchantId, paymentId);

    ReconciliationDivergence dispute =
        unitOfWork.inTransaction(
            () -> {
              ReconciliationDivergence opened = divergences.openDispute(payment, reason, note);
              disputeEvents.updated(merchantId, opened);

              return opened;
            });

    return Dispute.from(dispute);
  }

  public Dispute get(MerchantId merchantId, String id) {
    ReconciliationDivergence divergence = divergenceOrNotFound(id);

    boolean isMerchantsDispute =
        divergence.origin() == DivergenceOrigin.MERCHANT
            && payments.findByMerchantAndId(merchantId, divergence.paymentId()).isPresent();
    if (!isMerchantsDispute) {
      throw new NotFoundException("dispute", id);
    }

    return Dispute.from(divergence);
  }

  public DisputePage list(
      MerchantId merchantId, DivergenceStatus status, Instant since, String after, int limit) {
    DivergenceQuery query =
        new DivergenceQuery(
            status, DivergenceOrigin.MERCHANT, null, merchantId.value(), since, after, limit);

    List<Dispute> page = divergences.list(query).stream().map(Dispute::from).toList();

    String nextCursor = page.size() == query.limit() ? page.getLast().id() : null;

    return new DisputePage(page, nextCursor);
  }

  /**
   * Rethrown with the dispute's name: "divergence not found" for an unknown id next to "dispute not
   * found" for a SYSTEM one would tell a probe which ids exist.
   */
  private ReconciliationDivergence divergenceOrNotFound(String id) {
    try {
      return divergences.get(id);
    } catch (NotFoundException e) {
      throw new NotFoundException("dispute", id);
    }
  }

  private Payment owned(MerchantId merchantId, String paymentId) {
    return payments
        .findByMerchantAndId(merchantId, paymentId)
        .orElseThrow(() -> new NotFoundException("payment", paymentId));
  }
}
