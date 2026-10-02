package com.gateway.billing.subscription.billing;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.BillingProperties;
import com.gateway.billing.order.Order;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionService;
import com.gateway.billing.subscription.SubscriptionStatus;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.PaymentStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
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
 * <p>Eight dependencies, one over the house limit: {@link CycleOpener} already took transaction 1's
 * collaborators, and what is left (the attempt, the attempt check, transaction 2's write, its event
 * and dunning hand-off, the recurring switch, the transaction) is one sequence that splitting would
 * only scatter.
 */
public class SubscriptionBilling {
  static final String CARD_RECURRING_UNSUPPORTED = "CARD_RECURRING_UNSUPPORTED";

  private final CycleOpener opener;
  private final InvoiceIssuer issuer;
  private final PaymentQueries payments;
  private final SubscriptionRepository subscriptions;
  private final DunningStarter dunning;
  private final BillingEvents events;
  private final BillingProperties properties;
  private final UnitOfWork unitOfWork;

  public SubscriptionBilling(
      CycleOpener opener,
      InvoiceIssuer issuer,
      PaymentQueries payments,
      SubscriptionRepository subscriptions,
      DunningStarter dunning,
      BillingEvents events,
      BillingProperties properties,
      UnitOfWork unitOfWork) {
    this.opener = opener;
    this.issuer = issuer;
    this.payments = payments;
    this.subscriptions = subscriptions;
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

    Subscription subscription = opened.get().subscription();
    Order invoice = opened.get().invoice();

    if (alreadyAttempted(invoice)) {
      return true;
    }

    if (subscription.method() == PaymentMethod.CARD && !properties.cardRecurringEnabled()) {
      IssuedInvoice nothing = IssuedInvoice.notAttempted();
      unitOfWork.run(() -> record(subscription, invoice, nothing, CARD_RECURRING_UNSUPPORTED, now));

      return true;
    }

    IssuedInvoice issued = issuer.issue(subscription, invoice, now, invoice.expiresAt());

    unitOfWork.run(() -> record(subscription, invoice, issued, null, now));

    return true;
  }

  private boolean alreadyAttempted(Order invoice) {
    return payments.listByOrder(invoice.merchantId(), invoice.id()).stream()
        .map(Payment::status)
        .anyMatch(status -> status == PaymentStatus.COMPLETED || status.isActive());
  }

  /**
   * Transaction 2. A Pix or boleto issued, a card charged, or a payment in doubt is only announced:
   * doubt leaves the order OPEN with its slot taken and books nothing. A decline, or a card that
   * may not be charged, makes the subscription PAST_DUE and starts dunning.
   */
  private void record(
      Subscription opened, Order invoice, IssuedInvoice issued, String reason, Instant now) {
    events.emit(
        invoice.merchantId(),
        "invoice.created",
        invoice.id(),
        opened.id(),
        invoiceCreated(opened, invoice, issued, reason));

    boolean mustBeChased = issued.declined() || reason != null;
    if (!mustBeChased) {
      return;
    }

    // Re-read, not transaction 1's copy: a cancel or a method change may have landed while the bank
    // was answering, and update() accepts exactly one version bump since the row was read.
    Subscription current =
        subscriptions
            .findById(opened.id())
            .orElseThrow(() -> new IllegalStateException("subscription " + opened.id() + " gone"));
    if (current.status() == SubscriptionStatus.ACTIVE) {
      current.markPastDue(now);
      if (!subscriptions.update(current)) {
        throw new DomainException(
            "CONFLICT", "subscription " + current.id() + " changed concurrently");
      }
      events.emit(
          current.merchantId(),
          "subscription.past_due",
          current.id(),
          current.id(),
          SubscriptionService.json(current));
    }

    dunning.firstFailure(current, invoice, issued.paymentId(), now);
  }

  private static Map<String, Object> invoiceCreated(
      Subscription subscription, Order invoice, IssuedInvoice issued, String reason) {
    Map<String, Object> period = new LinkedHashMap<>();
    period.put("start", invoice.periodStart().toString());
    period.put("end", invoice.periodEnd().toString());

    Payment payment = issued.payment();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("invoice_id", invoice.id());
    body.put("subscription_id", subscription.id());
    body.put("invoice_number", invoice.invoiceNumber());
    body.put("amount", invoice.amount().cents());
    body.put("currency", invoice.amount().currency());
    body.put("method", subscription.method().name());
    body.put("period", period);
    body.put("payment_id", issued.paymentId());
    body.put("charged", issued.charged());
    body.put("decline_code", issued.declineCode());
    body.put("reason", reason);
    body.put("pix", pixOf(payment));
    body.put("boleto", boletoOf(payment));

    return body;
  }

  private static Map<String, Object> pixOf(Payment payment) {
    if (payment == null || payment.pix() == null) {
      return null;
    }

    Map<String, Object> pix = new LinkedHashMap<>();
    pix.put("copia_e_cola", payment.pix().pixCopiaECola());

    return pix;
  }

  private static Map<String, Object> boletoOf(Payment payment) {
    if (payment == null || payment.boleto() == null) {
      return null;
    }

    Map<String, Object> boleto = new LinkedHashMap<>();
    boleto.put("linha_digitavel", payment.boleto().linhaDigitavel());
    boleto.put("due_date", String.valueOf(payment.boleto().dueDate()));

    return boleto;
  }
}
