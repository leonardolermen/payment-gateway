package com.gateway.payments.inbox;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.card.CardNotification;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.card.CardStatusSync;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.Divergences;
import com.gateway.payments.refund.RefundState;
import com.gateway.payments.refund.persistence.RefundRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stored Cielo notification (spec §8). The payment is found by PaymentId within the merchant
 * whose URL was called; not found is "not ours" — another system on the same Cielo store, or a sale
 * from before the gateway — and the entry ends IGNORED (Review Focus 5).
 */
public class CardNotifications {
  private static final Logger log = LoggerFactory.getLogger(CardNotifications.class);
  private static final Duration OWN_REFUND_WINDOW = Duration.ofHours(24);

  private final PaymentRepository payments;
  private final CardStatusSync statusSync;
  private final Divergences divergences;
  private final RefundRepository refunds;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public CardNotifications(
      PaymentRepository payments,
      CardStatusSync statusSync,
      Divergences divergences,
      RefundRepository refunds,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.payments = payments;
    this.statusSync = statusSync;
    this.divergences = divergences;
    this.refunds = refunds;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  /** Returns whether the notification was about one of this merchant's payments. */
  public boolean apply(MerchantId merchantId, String provider, CardNotification notification) {
    Optional<Payment> found =
        payments.findByMerchantAndCardPaymentId(merchantId, provider, notification.paymentId());
    if (found.isEmpty()) {
      log.info("{} notification for unknown PaymentId {}", provider, notification.paymentId());
      return false;
    }

    Payment payment = found.get();
    switch (notification.kind()) {
      case STATUS_CHANGED -> statusSync.sync(payment, EventSource.PROVIDER_WEBHOOK);
      case PARTIAL_REFUND -> partialRefund(payment, notification);
      case VOID_DENIED ->
          unitOfWork.run(
              () ->
                  divergences.open(
                      payment, "VOID_DENIED", "ChangeType 5 for " + notification.paymentId()));
      case FRAUD_ALERT ->
          unitOfWork.run(
              () ->
                  divergences.open(
                      payment, "FRAUD_ALERT", "ChangeType 8 for " + notification.paymentId()));
      case IGNORED -> {
        return recordIgnored(payment.id(), notification);
      }
    }

    return true;
  }

  /**
   * A partial refund leaves the sale PAID at the Cielo and CardAuthorization carries no voided
   * amount, so the re-read alone sees nothing: the notification is the only evidence, and a human
   * compares it with the gateway's refunds. A full refund already opened REFUNDED_AT_PROVIDER in
   * the sync, which says more; a second divergence for the same fact would be noise.
   */
  private void partialRefund(Payment payment, CardNotification notification) {
    statusSync.sync(payment, EventSource.PROVIDER_WEBHOOK);

    if (explainedByOwnRefund(payment)) {
      recordIgnored(payment.id(), notification);
      return;
    }

    unitOfWork.run(
        () -> {
          if (divergences.isOpen(payment, "REFUNDED_AT_PROVIDER")) {
            return;
          }
          divergences.open(
              payment,
              "PARTIAL_REFUND_AT_PROVIDER",
              "ChangeType " + notification.changeType() + " for " + notification.paymentId());
        });
  }

  /**
   * The gateway's own partial refund also makes the Cielo send ChangeType 25, and the notification
   * carries no amount to tell the two apart: every partial refund done through the API opened a
   * PARTIAL_REFUND_AT_PROVIDER for a human to close. A COMPLETED or PROCESSING refund of this
   * payment in the last 24 h accounts for it; the Cielo notifies within minutes, so 24 h only has
   * to outlast its retries. The cost is a refund done at the Cielo inside that window going
   * unflagged — the reconciliation still compares totals.
   */
  private boolean explainedByOwnRefund(Payment payment) {
    Instant since = clock.instant().minus(OWN_REFUND_WINDOW);
    return refunds.findByPayment(payment.id()).stream()
        .anyMatch(
            refund ->
                (refund.state() == RefundState.COMPLETED
                        || refund.state() == RefundState.PROCESSING)
                    && refund.createdAt().isAfter(since));
  }

  /**
   * recordIgnored refuses a CREATED payment, and an exception here would make the inbox retry until
   * the flow or the sweeper moved it, for a notification that changes nothing anyway. Returns false
   * there, so the entry ends IGNORED rather than PROCESSED.
   */
  private boolean recordIgnored(String paymentId, CardNotification notification) {
    return unitOfWork.inTransaction(
        () -> {
          Payment payment = payments.findById(paymentId).orElseThrow();
          if (payment.status() == PaymentStatus.CREATED) {
            log.info(
                "ChangeType {} for CREATED payment {} ignored",
                notification.changeType(),
                paymentId);
            return false;
          }

          payment
              .recordIgnored(
                  "card notification ChangeType " + notification.changeType(),
                  EventSource.PROVIDER_WEBHOOK)
              .ifPresent(event -> payments.save(payment, List.of(event)));
          return true;
        });
  }
}
