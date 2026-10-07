package com.gateway.billing.subscription;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.BillingProperties;
import com.gateway.billing.customer.Customer;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.card.SavedCard;
import com.gateway.payments.card.SavedCards;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.persistence.JobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The writes. Customer and plan lookups stay in the API layer, which passes them in, and the reads
 * live in {@link SubscriptionQueries}: together they would be nine dependencies.
 *
 * <p>Eight, one above the limit: {@link OpenInvoiceCancellation} is the collaborator that keeps the
 * immediate cancel's bank call out of this class, and it cannot be folded into another one.
 */
public class SubscriptionService {
  private final SubscriptionRepository subscriptions;
  private final SavedCards savedCards;
  private final JobRepository jobs;
  private final BillingEvents events;
  private final BillingProperties properties;
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final OpenInvoiceCancellation openInvoice;

  public SubscriptionService(
      SubscriptionRepository subscriptions,
      SavedCards savedCards,
      JobRepository jobs,
      BillingEvents events,
      BillingProperties properties,
      UnitOfWork unitOfWork,
      Clock clock,
      OpenInvoiceCancellation openInvoice) {
    this.subscriptions = subscriptions;
    this.savedCards = savedCards;
    this.jobs = jobs;
    this.events = events;
    this.properties = properties;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
    this.openInvoice = openInvoice;
  }

  /**
   * Moves no money: the first cycle is a BILL_SUBSCRIPTION job at {@code nextBillingAt}. ACTIVE
   * subscriptions only: an INCOMPLETE one opens its first invoice instead of a job, which is {@code
   * SubscriptionCreation}'s.
   */
  public Subscription create(Subscription subscription, Customer customer) {
    if (subscription.isIncomplete()) {
      throw new IllegalStateException(
          "subscription " + subscription.id() + " is INCOMPLETE; SubscriptionCreation opens it");
    }
    requireCardOwnedBy(subscription.merchantId(), subscription.cardId(), customer);

    Instant now = clock.instant();

    // A start TODAY bills now, whatever the hour: spec section 5 promises the first cycle at once.
    // Anchoring it to the billing hour sent a subscription created at 01:11 São Paulo to 03:00,
    // and BillingApiIntegrationTest.customersPlansOrdersAndACardSubscription timed out waiting
    // for PAID. A future start day still waits for that day's billing hour; max(.., now) covers
    // a past one.
    Instant firstBilling =
        subscription.startDay().equals(BillingCalendar.today(now))
            ? now
            : BillingCalendar.billingInstant(subscription.startDay(), properties.billingHour());
    Instant nextBillingAt = firstBilling.isBefore(now) ? now : firstBilling;
    subscription.scheduleFirstBilling(nextBillingAt, now);

    return unitOfWork.inTransaction(
        () -> {
          subscriptions.insert(subscription);
          jobs.enqueue(Job.billSubscription(subscription.id(), nextBillingAt, clock));
          emit(subscription, "subscription.created");

          return subscription;
        });
  }

  /**
   * At period end only flags it, with no event: the next BILL_SUBSCRIPTION sees the flag and ends
   * it, and {@code subscription.ended} is the merchant's notice (spec §7). Immediate is {@code
   * subscription.canceled}, after the open invoice is canceled at the bank.
   *
   * <p>An INCOMPLETE subscription is always canceled now: it has no paid period to finish. Its row
   * is written first and its invoice canceled after, the reverse of the billable order: the closed
   * invoice would otherwise reach the settlement hook while the subscription is still INCOMPLETE
   * and end it INCOMPLETE_EXPIRED, which says "the payer did not pay", not "the merchant gave up".
   */
  public Subscription cancel(MerchantId merchantId, String id, boolean atPeriodEnd) {
    Subscription subscription = get(merchantId, id);
    Instant now = clock.instant();

    if (subscription.isIncomplete()) {
      subscription.cancelNow(now);
      Subscription canceled = save(subscription, "subscription.canceled");
      openInvoice.cancelOpenInvoice(canceled);

      return canceled;
    }

    requireBillable(subscription);

    if (atPeriodEnd) {
      subscription.requestCancelAtPeriodEnd(now);

      return save(subscription);
    }

    // Before the subscription's transaction: the bank call never runs inside one.
    openInvoice.cancelOpenInvoice(subscription);
    subscription.cancelNow(now);

    return save(subscription, "subscription.canceled");
  }

  /** The customer comes from the API layer, which loaded it; the same rules as on create apply. */
  public Subscription changeMethod(
      MerchantId merchantId, String id, PaymentMethod method, String cardId, Customer customer) {
    Subscription subscription = get(merchantId, id);
    requireBillable(subscription);
    SubscriptionFactory.requireMethodData(customer, method, cardId);
    requireCardOwnedBy(merchantId, cardId, customer);

    subscription.changeMethod(method, cardId, clock.instant());

    // No event: spec §9 lists none for a method change, and an event type is webhook contract.
    return save(subscription);
  }

  private Subscription get(MerchantId merchantId, String id) {
    return subscriptions
        .find(merchantId, id)
        .orElseThrow(() -> new NotFoundException("subscription", id));
  }

  private Subscription save(Subscription subscription) {
    return unitOfWork.inTransaction(
        () -> {
          update(subscription);

          return subscription;
        });
  }

  private Subscription save(Subscription subscription, String eventType) {
    return unitOfWork.inTransaction(
        () -> {
          update(subscription);
          emit(subscription, eventType);

          return subscription;
        });
  }

  private void update(Subscription subscription) {
    if (!subscriptions.update(subscription)) {
      throw new DomainException(
          "CONFLICT", "subscription " + subscription.id() + " changed concurrently");
    }
  }

  private void requireCardOwnedBy(MerchantId merchantId, String cardId, Customer customer) {
    if (cardId == null) {
      return;
    }

    SavedCard card = savedCards.get(merchantId, cardId);
    if (!customer.id().equals(card.customerId())) {
      throw new DomainException(
          "CARD_NOT_OWNED_BY_CUSTOMER",
          "card_id " + card.id() + " does not belong to customer_id " + customer.id());
    }
  }

  private static void requireBillable(Subscription subscription) {
    if (!subscription.isBillable()) {
      throw new DomainException(
          "SUBSCRIPTION_NOT_ACTIVE",
          "subscription " + subscription.id() + " is " + subscription.status());
    }
  }

  private void emit(Subscription subscription, String eventType) {
    events.emit(
        subscription.merchantId(),
        eventType,
        subscription.id(),
        subscription.id(),
        json(subscription));
  }

  public static Map<String, Object> json(Subscription subscription) {
    BillingPeriod period = subscription.currentPeriod();
    Map<String, Object> currentPeriod = null;
    if (period != null) {
      currentPeriod = new LinkedHashMap<>();
      currentPeriod.put("start", period.start().toString());
      currentPeriod.put("end", period.end().toString());
    }
    Instant nextBillingAt = subscription.nextBillingAt();

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", subscription.id());
    body.put("status", subscription.status().name());
    body.put("customer_id", subscription.customerId());
    body.put("plan_id", subscription.planId());
    body.put("method", subscription.method().name());
    body.put("card_id", subscription.cardId());
    body.put("current_period", currentPeriod);
    body.put("next_billing_at", nextBillingAt == null ? null : nextBillingAt.toString());
    body.put("cancel_at_period_end", subscription.cancelAtPeriodEnd());
    body.put("created_at", subscription.createdAt().toString());

    return body;
  }
}
