package com.gateway.billing.subscription.billing;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderFactory;
import com.gateway.billing.order.OrderService;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.billing.plan.Plan;
import com.gateway.billing.plan.PlanService;
import com.gateway.billing.subscription.BillingCalendar;
import com.gateway.billing.subscription.BillingPeriod;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionService;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.persistence.JobRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Transaction 1 of the cycle (spec §6 step 1): under the subscription's row lock, open the next
 * period and create its invoice order, or end the subscription when it asked to stop at period end.
 * Requires the caller's transaction: the lock means nothing without one.
 */
public class CycleOpener {
  private final SubscriptionRepository subscriptions;
  private final OrderRepository orders;
  private final PlanService plans;
  private final JobRepository jobs;
  private final BillingEvents events;
  private final int billingHour;
  private final Clock clock;

  public CycleOpener(
      SubscriptionRepository subscriptions,
      OrderRepository orders,
      PlanService plans,
      JobRepository jobs,
      BillingEvents events,
      int billingHour,
      Clock clock) {
    this.subscriptions = subscriptions;
    this.orders = orders;
    this.plans = plans;
    this.jobs = jobs;
    this.events = events;
    this.billingHour = billingHour;
    this.clock = clock;
  }

  /**
   * Empty when there is nothing to charge: not billable, no cycle yet, or just ended. When the
   * subscription is not due, the current invoice comes back as resumed: a run that committed
   * transaction 1 and then failed (the bank call, transaction 2) moved nextBillingAt a period
   * ahead, and its retry must still finish that invoice instead of finding nothing due.
   */
  public Optional<OpenedCycle> open(String subscriptionId, Instant now) {
    Optional<Subscription> locked = subscriptions.lock(subscriptionId);
    if (locked.isEmpty() || !locked.get().isBillable()) {
      return Optional.empty();
    }

    Subscription subscription = locked.get();
    boolean isNewCycleDue = isDue(subscription, now) && !isCurrentCycleAgain(subscription, now);
    if (!isNewCycleDue) {
      return currentInvoice(subscription)
          .map(invoice -> OpenedCycle.resumed(subscription, invoice));
    }

    if (subscription.cancelAtPeriodEnd()) {
      // Only end(): SubscriptionRepository.update expects exactly one version bump since the read.
      subscription.end(now);
      update(subscription);
      events.emit(
          subscription.merchantId(),
          "subscription.ended",
          subscription.id(),
          subscription.id(),
          SubscriptionService.json(subscription));

      return Optional.empty();
    }

    return Optional.of(openNext(subscription, now));
  }

  private OpenedCycle openNext(Subscription subscription, Instant now) {
    Plan plan = plans.get(subscription.merchantId(), subscription.planId());
    BillingPeriod period = nextPeriod(subscription, plan);

    Instant nextBillingAt = BillingCalendar.billingInstant(period.end(), billingHour);
    int invoiceNumber = subscription.openPeriod(period, nextBillingAt, now);

    // The order lives until the last day of its period: the next cycle's invoice takes over then.
    Instant expiresAt = BillingCalendar.endOfDay(period.end().minusDays(1));
    Order draft =
        OrderFactory.invoice(
            subscription.merchantId(),
            subscription.environment(),
            subscription.customerId(),
            plan.amount(),
            subscription.id(),
            invoiceNumber,
            period.start(),
            period.end(),
            expiresAt,
            null,
            clock);
    Optional<Order> existing = orders.insertInvoiceIfAbsent(draft);

    update(subscription);
    // A no-op while the job row that is running this cycle exists (unique on type and ref_id);
    // BillSubscriptionJob.finish moves that row to nextBillingAt. This covers a cycle run by hand.
    jobs.enqueue(Job.billSubscription(subscription.id(), nextBillingAt, clock));

    if (existing.isPresent()) {
      return OpenedCycle.opened(subscription, existing.get());
    }

    // Not OrderService.create: an invoice is born here, inside this transaction, yet it must
    // behave like any order, so the same event and the same expiry job.
    events.emit(
        draft.merchantId(), "order.created", draft.id(), draft.id(), OrderService.json(draft));
    jobs.enqueue(Job.expireOrder(draft.id(), expiresAt, clock));

    return OpenedCycle.opened(subscription, draft);
  }

  private static boolean isDue(Subscription subscription, Instant now) {
    return subscription.isBillable()
        && subscription.nextBillingAt() != null
        && !subscription.nextBillingAt().isAfter(now);
  }

  /**
   * Due, yet São Paulo has not reached the current period's last day: the row was billed for this
   * period and next_billing_at was rewound, so opening another period would bill one month twice.
   * Compared by day, not against billingInstant(end, billingHour): with the hour raised between
   * cycles, the recomputed instant lies after the stored next_billing_at, every run on that day
   * would call it a rerun, and the job would spin on its own past next_billing_at until the hour.
   */
  private static boolean isCurrentCycleAgain(Subscription subscription, Instant now) {
    BillingPeriod current = subscription.currentPeriod();

    return current != null && BillingCalendar.today(now).isBefore(current.end());
  }

  private Optional<Order> currentInvoice(Subscription subscription) {
    return orders.findBySubscription(subscription.id(), 1).stream()
        .filter(invoice -> invoice.invoiceNumber() == subscription.lastInvoiceNumber())
        .findFirst();
  }

  private static BillingPeriod nextPeriod(Subscription subscription, Plan plan) {
    BillingPeriod current = subscription.currentPeriod();
    if (current != null) {
      return BillingCalendar.next(
          current, plan.interval(), plan.intervalCount(), subscription.anchorDay());
    }

    // startDay() is null on a row read back from the database. The first billing instant was
    // computed from it (or is the creation instant, the same São Paulo day), so its day is the
    // start.
    LocalDate startDay = BillingCalendar.today(subscription.nextBillingAt());

    return BillingCalendar.firstPeriod(
        startDay, plan.interval(), plan.intervalCount(), subscription.anchorDay());
  }

  private void update(Subscription subscription) {
    if (!subscriptions.update(subscription)) {
      throw new DomainException(
          "CONFLICT", "subscription " + subscription.id() + " changed concurrently");
    }
  }
}
