package com.gateway.billing.subscription.billing;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.order.InvoiceSettlementHook;
import com.gateway.billing.order.Order;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionService;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentQueries;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What happens to a subscription when its invoice settles. The first invoice of an INCOMPLETE
 * subscription is decided here (spec 2026-10-07 §2); every other invoice goes to {@link Dunning},
 * exactly as before this class existed.
 *
 * <ul>
 *   <li>Paid: the card saved by that payment becomes the subscription's, it turns ACTIVE, its first
 *       BILL_SUBSCRIPTION is enqueued at the end of the paid period, and {@code
 *       subscription.activated} goes out.
 *   <li>Expired or canceled: INCOMPLETE_EXPIRED, final, no dunning: the payer never agreed to have
 *       a card charged.
 *   <li>An attempt failed: nothing. The invoice is still open and the payer may try another card;
 *       dunning would chase a card nobody saved.
 * </ul>
 *
 * Every method runs in the caller's transaction, and re-reads the subscription: update() accepts
 * exactly one version bump since the read.
 */
public class SubscriptionInvoices implements InvoiceSettlementHook {
  private static final Logger log = LoggerFactory.getLogger(SubscriptionInvoices.class);

  private final Dunning dunning;
  private final SubscriptionRepository subscriptions;
  private final PaymentQueries payments;
  private final JobRepository jobs;
  private final BillingEvents events;
  private final Clock clock;

  public SubscriptionInvoices(
      Dunning dunning,
      SubscriptionRepository subscriptions,
      PaymentQueries payments,
      JobRepository jobs,
      BillingEvents events,
      Clock clock) {
    this.dunning = dunning;
    this.subscriptions = subscriptions;
    this.payments = payments;
    this.jobs = jobs;
    this.events = events;
    this.clock = clock;
  }

  @Override
  public void invoicePaid(Order invoice, Instant at) {
    Subscription subscription = reload(invoice.subscriptionId());
    if (!subscription.isIncomplete()) {
      dunning.invoicePaid(invoice, at);
      return;
    }

    Payment paid = payments.get(invoice.merchantId(), invoice.paidPaymentId());
    String cardId = paid.card() == null ? null : paid.card().cardId();
    if (cardId == null) {
      // The acquirer charged but returned no token (the Cielo sandbox never does): nothing to bill
      // the next cycle with. The payer's period is paid; the merchant sees an INCOMPLETE
      // subscription with a PAID invoice and decides (cancel, or a new subscription with a card).
      log.warn(
          "first invoice {} of subscription {} paid by payment {} without a saved card;"
              + " the subscription stays INCOMPLETE",
          invoice.id(),
          subscription.id(),
          paid.id());
      return;
    }

    subscription.activate(cardId, at);
    update(subscription);
    // The first cycle's row: none exists while INCOMPLETE (CycleOpener.openFirst), and from here
    // BillSubscriptionJob keeps it, period after period, like any subscription's.
    jobs.enqueue(Job.billSubscription(subscription.id(), subscription.nextBillingAt(), clock));
    events.emit(
        subscription.merchantId(),
        "subscription.activated",
        subscription.id(),
        subscription.id(),
        SubscriptionService.json(subscription));
  }

  @Override
  public void invoiceAttemptFailed(Order invoice, String paymentId, String eventType, Instant at) {
    if (reload(invoice.subscriptionId()).isIncomplete()) {
      return;
    }

    dunning.invoiceAttemptFailed(invoice, paymentId, eventType, at);
  }

  /**
   * Only an INCOMPLETE subscription reacts: any other invoice that closes unpaid is dunning's
   * business already (its retry finds the invoice closed and skips), and a subscription the
   * merchant canceled is no longer INCOMPLETE by the time its invoice is canceled.
   */
  @Override
  public void invoiceClosed(Order invoice, Instant at) {
    Subscription subscription = reload(invoice.subscriptionId());
    if (!subscription.isIncomplete()) {
      return;
    }

    subscription.expireIncomplete(at);
    update(subscription);
  }

  private Subscription reload(String subscriptionId) {
    return subscriptions
        .findById(subscriptionId)
        .orElseThrow(() -> new IllegalStateException("subscription " + subscriptionId + " gone"));
  }

  private void update(Subscription subscription) {
    if (!subscriptions.update(subscription)) {
      throw new DomainException(
          "CONFLICT", "subscription " + subscription.id() + " changed concurrently");
    }
  }
}
