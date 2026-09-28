package com.gateway.payments.inbox;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.card.CardNotification;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.card.CardStatusSync;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.Divergences;
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

  private final PaymentRepository payments;
  private final CardStatusSync statusSync;
  private final Divergences divergences;
  private final UnitOfWork unitOfWork;

  public CardNotifications(
      PaymentRepository payments,
      CardStatusSync statusSync,
      Divergences divergences,
      UnitOfWork unitOfWork) {
    this.payments = payments;
    this.statusSync = statusSync;
    this.divergences = divergences;
    this.unitOfWork = unitOfWork;
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
      case STATUS_CHANGED, PARTIAL_REFUND -> statusSync.sync(payment, EventSource.PROVIDER_WEBHOOK);
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
      case IGNORED -> recordIgnored(payment.id(), notification);
    }

    return true;
  }

  private void recordIgnored(String paymentId, CardNotification notification) {
    unitOfWork.run(
        () -> {
          Payment payment = payments.findById(paymentId).orElseThrow();
          payments.save(
              payment,
              List.of(
                  payment
                      .recordIgnored(
                          "card notification ChangeType " + notification.changeType(),
                          EventSource.PROVIDER_WEBHOOK)
                      .orElseThrow()));
        });
  }
}
