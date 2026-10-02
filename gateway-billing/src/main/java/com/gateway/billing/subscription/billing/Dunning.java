package com.gateway.billing.subscription.billing;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.order.InvoiceSettlementHook;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.billing.subscription.DunningAttempt;
import com.gateway.billing.subscription.DunningOutcome;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionService;
import com.gateway.billing.subscription.SubscriptionStatus;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentQueries;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Chasing a failed invoice (spec §7). The single owner of PAST_DUE: the cycle and the settlement of
 * an expired Pix or boleto both hand over here, so "mark, announce, schedule" is written once.
 *
 * <p>Dunning never cancels a subscription. Exhaustion is announced and the subscription stays
 * PAST_DUE: what to do with a customer who does not pay is the merchant's decision, and a cancel
 * the merchant did not ask for cannot be undone.
 */
public class Dunning implements DunningStarter, InvoiceSettlementHook {
  private final DunningLedger ledger;
  private final SubscriptionRepository subscriptions;
  private final OrderRepository orders;
  private final PaymentQueries payments;
  private final InvoiceIssuer issuer;
  private final BillingEvents events;
  private final UnitOfWork unitOfWork;

  public Dunning(
      DunningLedger ledger,
      SubscriptionRepository subscriptions,
      OrderRepository orders,
      PaymentQueries payments,
      InvoiceIssuer issuer,
      BillingEvents events,
      UnitOfWork unitOfWork) {
    this.ledger = ledger;
    this.subscriptions = subscriptions;
    this.orders = orders;
    this.payments = payments;
    this.issuer = issuer;
    this.events = events;
    this.unitOfWork = unitOfWork;
  }

  /**
   * Requires the caller's transaction. PAST_DUE keys on the subscription; the schedule keys on the
   * invoice: a subscription already PAST_DUE for an older invoice still gets this one chased, with
   * no second subscription.past_due. Re-reads the subscription: the caller's copy may predate a
   * cancel or a method change, and update() accepts exactly one version bump since the read.
   */
  @Override
  public void firstFailure(
      Subscription subscription, Order invoice, String paymentId, Instant now) {
    Subscription current = reload(subscription.id());

    if (current.status() == SubscriptionStatus.ACTIVE) {
      current.markPastDue(now);
      update(current);
      emit(current, "subscription.past_due", null);
    }

    // A rerun of the cycle whose booking was lost finds attempt 1 already scheduled.
    if (!ledger.attemptsFor(invoice).isEmpty()) {
      return;
    }

    if (!ledger.start(current, invoice, now)) {
      emit(current, "subscription.dunning_exhausted", invoice);
    }
  }

  /** Same transaction as order.paid. */
  @Override
  public void invoicePaid(Order invoice, Instant at) {
    Subscription subscription = reload(invoice.subscriptionId());

    if (subscription.status() == SubscriptionStatus.PAST_DUE) {
      subscription.recover(at);
      update(subscription);
      emit(subscription, "subscription.recovered", null);
    }

    ledger
        .pendingFor(invoice.id())
        .ifPresent(pending -> ledger.close(pending, DunningOutcome.SKIPPED, null, at));
  }

  /**
   * Same transaction as the settlement. Three cases, by what the ledger already holds for the
   * invoice: nothing (the cycle's Pix or boleto expired unpaid: dunning starts here); a pending
   * retry (an issued attempt expired, or the relay of a decline already booked: the retry is
   * already on its way); or only closed attempts, the last of which issued this very payment (the
   * final retry's Pix or boleto expired: now, and only now, the invoice is exhausted). Keyed on
   * "any attempt", not "a pending one": after the final retry nothing is pending, and starting over
   * at attempt 1 would chase the invoice forever.
   */
  @Override
  public void invoiceAttemptFailed(Order invoice, String paymentId, String eventType, Instant at) {
    List<DunningAttempt> attempts = ledger.attemptsFor(invoice);
    if (attempts.isEmpty()) {
      firstFailure(reload(invoice.subscriptionId()), invoice, paymentId, at);
      return;
    }

    boolean hasPending = attempts.stream().anyMatch(attempt -> attempt.outcome() == null);
    DunningAttempt last = attempts.get(attempts.size() - 1);
    boolean lastIssuedThisPayment =
        last.outcome() == DunningOutcome.ISSUED && paymentId.equals(last.paymentId());
    if (!hasPending && lastIssuedThisPayment) {
      emit(reload(invoice.subscriptionId()), "subscription.dunning_exhausted", invoice);
    }
  }

  /**
   * One retry. Outside any transaction: the bank is called here. True when the attempt is settled
   * either way; false while an earlier attempt on the invoice is still live (a boleto someone may
   * still pay is not replaced, and never two active attempts).
   */
  public boolean retryOne(String attemptId, Instant now) {
    Optional<RetryContext> loaded = unitOfWork.inTransaction(() -> load(attemptId));
    if (loaded.isEmpty() || loaded.get().attempt().outcome() != null) {
      return true;
    }

    RetryContext context = loaded.get();
    if (!context.invoice().isOpen() || !context.subscription().isBillable()) {
      unitOfWork.run(() -> ledger.close(context.attempt(), DunningOutcome.SKIPPED, null, now));
      return true;
    }

    if (payments.activeAttempt(context.invoice().id()).isPresent()) {
      return false;
    }

    // A reissued Pix or boleto lives until the next retry, so the two never overlap; the last one
    // lives as long as the invoice.
    Instant until = ledger.retryAfter(context.attempt(), now).orElse(context.invoice().expiresAt());

    IssuedInvoice issued;
    try {
      issued = issuer.issue(context.subscription(), context.invoice(), now, until);
    } catch (DomainException refused) {
      // E.g. CUSTOMER_ADDRESS_REQUIRED after a switch to boleto: the merchant may fix the
      // customer before the next day, so this attempt is skipped, not the whole schedule.
      unitOfWork.run(
          () -> {
            ledger.close(context.attempt(), DunningOutcome.SKIPPED, null, now);
            scheduleOrExhaust(context, now);
          });
      return true;
    }

    unitOfWork.run(() -> record(context, issued, now));

    return true;
  }

  /**
   * The one dispatch over what an attempt came to. PAID books the attempt only: the order becomes
   * PAID, and the subscription ACTIVE, when payment.completed relays through OrderSettlement.
   */
  private void record(RetryContext context, IssuedInvoice issued, Instant now) {
    DunningAttempt attempt = context.attempt();

    if (issued.charged()) {
      ledger.close(attempt, DunningOutcome.PAID, issued.paymentId(), now);
      return;
    }

    if (issued.declined()) {
      ledger.close(attempt, DunningOutcome.DECLINED, issued.paymentId(), now);
      scheduleOrExhaust(context, now);
      return;
    }

    // Issued and waiting for the payer. The next retry is scheduled now, so the expiry of this
    // one finds it pending and does nothing; with none left, that expiry is the exhaustion.
    ledger.close(attempt, DunningOutcome.ISSUED, issued.paymentId(), now);
    events.emit(
        context.invoice().merchantId(),
        "invoice.updated",
        context.invoice().id(),
        context.subscription().id(),
        invoiceUpdated(context, issued.payment()));
    ledger.scheduleNext(context.subscription(), context.invoice(), attempt.attempt(), now);
  }

  private void scheduleOrExhaust(RetryContext context, Instant now) {
    int attemptsSoFar = context.attempt().attempt();
    boolean scheduled =
        ledger.scheduleNext(context.subscription(), context.invoice(), attemptsSoFar, now);

    if (!scheduled) {
      emit(context.subscription(), "subscription.dunning_exhausted", context.invoice());
    }
  }

  private Optional<RetryContext> load(String attemptId) {
    return ledger.find(attemptId).map(this::contextOf);
  }

  private RetryContext contextOf(DunningAttempt attempt) {
    Order invoice =
        orders
            .findById(attempt.orderId())
            .orElseThrow(() -> new IllegalStateException("order " + attempt.orderId() + " gone"));

    return new RetryContext(attempt, invoice, reload(attempt.subscriptionId()));
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

  /** {@code invoice} is null for an event about the subscription alone. */
  private void emit(Subscription subscription, String type, Order invoice) {
    Map<String, Object> body = new LinkedHashMap<>(SubscriptionService.json(subscription));
    if (invoice != null) {
      body.put("invoice_id", invoice.id());
    }

    events.emit(subscription.merchantId(), type, subscription.id(), subscription.id(), body);
  }

  private static Map<String, Object> invoiceUpdated(RetryContext context, Payment payment) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("invoice_id", context.invoice().id());
    body.put("subscription_id", context.subscription().id());
    body.put("attempt", context.attempt().attempt());
    body.put("payment_id", payment.id());
    body.put("method", context.subscription().method().name());
    body.put("pix", InvoicePayloads.pixOf(payment));
    body.put("boleto", InvoicePayloads.boletoOf(payment));

    return body;
  }

  private record RetryContext(DunningAttempt attempt, Order invoice, Subscription subscription) {}
}
