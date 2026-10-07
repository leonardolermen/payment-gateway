package com.gateway.billing.subscription.billing;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.BillingProperties;
import com.gateway.billing.order.Order;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionStatus;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.PaymentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The cycle (spec §6). Transaction 1 opens the period and creates the invoice order; the bank is
 * called outside; transaction 2 records what happened.
 *
 * <p>A rerun is safe without any idempotency key: uq_orders_invoice allows one order per
 * subscription and invoice number, {@link CycleOpener} hands back the current invoice when the
 * current period runs again, and an invoice that already has an attempt (active or completed) is
 * not attempted again. That is the idempotency, not a header; spec §6's "synthetic key" is amended
 * to this.
 *
 * <p>Seven dependencies: {@link CycleOpener} took transaction 1's collaborators, and {@link
 * Dunning} took the PAST_DUE write that transaction 2 used to make itself.
 */
public class SubscriptionBilling {
  static final String CARD_RECURRING_UNSUPPORTED = "CARD_RECURRING_UNSUPPORTED";

  private final CycleOpener opener;
  private final InvoiceIssuer issuer;
  private final PaymentQueries payments;
  private final DunningStarter dunning;
  private final BillingEvents events;
  private final BillingProperties properties;
  private final UnitOfWork unitOfWork;

  public SubscriptionBilling(
      CycleOpener opener,
      InvoiceIssuer issuer,
      PaymentQueries payments,
      DunningStarter dunning,
      BillingEvents events,
      BillingProperties properties,
      UnitOfWork unitOfWork) {
    this.opener = opener;
    this.issuer = issuer;
    this.payments = payments;
    this.dunning = dunning;
    this.events = events;
    this.properties = properties;
    this.unitOfWork = unitOfWork;
  }

  /** Always true when it returns: "nothing to bill" is done too. A throw retries the job. */
  public boolean billOne(String subscriptionId, Instant now) {
    Optional<OpenedCycle> opened = unitOfWork.inTransaction(() -> opener.open(subscriptionId, now));
    if (opened.isEmpty()) {
      return true;
    }

    OpenedCycle cycle = opened.get();
    Subscription subscription = cycle.subscription();
    Order invoice = cycle.invoice();
    List<Payment> attempts = payments.listByOrder(invoice.merchantId(), invoice.id());

    // Never a second active attempt, never a second charge, nothing on a closed invoice.
    if (!invoice.isOpen() || hasLiveOrPaidAttempt(attempts)) {
      return true;
    }

    // A resumed cycle whose failure was already booked has nothing left: PAST_DUE is only
    // written by that booking, and dunning owns the invoice from there.
    boolean failureAlreadyBooked =
        cycle.resumed() && subscription.status() != SubscriptionStatus.ACTIVE;

    Optional<Payment> declined = attempts.stream().filter(InvoiceIssuer::isDecline).findFirst();
    if (declined.isPresent()) {
      // The card was declined and transaction 2 never committed: book it, do not charge again.
      if (!failureAlreadyBooked) {
        IssuedInvoice issued = InvoiceIssuer.declinedBy(declined.get());
        unitOfWork.run(() -> record(cycle, issued, null, now));
      }

      return true;
    }

    if (subscription.method() == PaymentMethod.CARD && !properties.cardRecurringEnabled()) {
      if (!failureAlreadyBooked) {
        IssuedInvoice nothing = IssuedInvoice.notAttempted();
        unitOfWork.run(() -> record(cycle, nothing, CARD_RECURRING_UNSUPPORTED, now));
      }

      return true;
    }

    // No attempt yet, or only ones that failed without an answer (the bank unreachable): the
    // exception went to the job, and this retry is the attempt that run never got to make.
    IssuedInvoice issued = issuer.issue(subscription, invoice, now, invoice.expiresAt());

    unitOfWork.run(() -> record(cycle, issued, null, now));

    return true;
  }

  private static boolean hasLiveOrPaidAttempt(List<Payment> attempts) {
    return attempts.stream()
        .map(Payment::status)
        .anyMatch(status -> status == PaymentStatus.COMPLETED || status.isActive());
  }

  /**
   * Transaction 2. A Pix or boleto issued, a card charged, or a payment in doubt is only announced:
   * doubt leaves the order OPEN with its slot taken and books nothing. A decline, or a card that
   * may not be charged, makes the subscription PAST_DUE and starts dunning. The event carries the
   * invoice's checkout link, the one time the merchant sees it (spec 2026-10-07 §3).
   */
  private void record(OpenedCycle cycle, IssuedInvoice issued, String reason, Instant now) {
    Subscription opened = cycle.subscription();
    Order invoice = cycle.invoice();
    String checkoutUrl = opener.checkoutUrl(cycle, now);

    events.emit(
        invoice.merchantId(),
        "invoice.created",
        invoice.id(),
        opened.id(),
        InvoicePayloads.created(opened, invoice, issued, reason, checkoutUrl));

    boolean mustBeChased = issued.declined() || reason != null;
    if (!mustBeChased) {
      return;
    }

    // Dunning owns PAST_DUE, its event and the schedule, so an expired Pix reaching it through
    // the settlement books exactly what a declined card booked here.
    dunning.firstFailure(opened, invoice, issued.paymentId(), now);
  }
}
