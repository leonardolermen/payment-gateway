package com.gateway.billing.subscription.billing;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.customer.Customer;
import com.gateway.billing.order.Order;
import com.gateway.billing.subscription.BillingCalendar;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionService;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import com.gateway.payments.UnitOfWork;
import java.time.Clock;
import java.time.Instant;

/**
 * The one door of {@code POST /v1/subscriptions}. A subscription born ACTIVE is {@link
 * SubscriptionService#create}'s, as before: the first cycle is a job. One born INCOMPLETE (a card
 * subscription without a card, spec 2026-10-07 §2) has nobody to charge yet, so its first invoice
 * is opened here, in the transaction that inserts it, by the cycle's own {@link
 * CycleOpener#openFirst}; the payer pays it by the link this returns.
 *
 * <p>Here and not in SubscriptionService: opening an invoice is the cycle's code, and the
 * subscription package does not reach into its billing sub-package.
 */
public class SubscriptionCreation {
  /**
   * The invoice a creation opened, with the payer's link: the url is here once, like {@code POST
   * /v1/orders}'s, and only its hash is stored.
   */
  public record FirstInvoice(String orderId, String checkoutUrl) {}

  /** {@code firstInvoice} is null for a subscription born ACTIVE. */
  public record Created(Subscription subscription, FirstInvoice firstInvoice) {}

  private final SubscriptionService service;
  private final SubscriptionRepository subscriptions;
  private final CycleOpener opener;
  private final BillingEvents events;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public SubscriptionCreation(
      SubscriptionService service,
      SubscriptionRepository subscriptions,
      CycleOpener opener,
      BillingEvents events,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.service = service;
    this.subscriptions = subscriptions;
    this.opener = opener;
    this.events = events;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public Created create(Subscription subscription, Customer customer) {
    if (!subscription.isIncomplete()) {
      return new Created(service.create(subscription, customer), null);
    }

    Instant now = clock.instant();
    // The first period starts on the start day (after any trial), or today if that is past: the
    // cycle reads that day off nextBillingAt, and openPeriod then moves nextBillingAt to the end
    // of the period. Noon only names the day; no job runs at this instant.
    Instant startDay = BillingCalendar.billingInstant(subscription.startDay(), 12);
    subscription.scheduleFirstBilling(startDay.isBefore(now) ? now : startDay, now);

    return unitOfWork.inTransaction(
        () -> {
          subscriptions.insert(subscription);
          events.emit(
              subscription.merchantId(),
              "subscription.created",
              subscription.id(),
              subscription.id(),
              SubscriptionService.json(subscription));

          OpenedCycle cycle = opener.openFirst(subscription, now);
          Order invoice = cycle.invoice();
          String checkoutUrl = opener.checkoutUrl(cycle, now);
          events.emit(
              invoice.merchantId(),
              "invoice.created",
              invoice.id(),
              subscription.id(),
              InvoicePayloads.created(
                  cycle.subscription(), invoice, IssuedInvoice.notAttempted(), null, checkoutUrl));

          return new Created(cycle.subscription(), new FirstInvoice(invoice.id(), checkoutUrl));
        });
  }
}
