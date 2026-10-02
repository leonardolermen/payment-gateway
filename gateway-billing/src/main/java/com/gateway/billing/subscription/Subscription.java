package com.gateway.billing.subscription;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Instant;
import java.time.LocalDate;

/** A class, not a record: it mutates through {@link SubscriptionTransitions} like {@code Order}. */
public final class Subscription {
  private final String id;
  private final MerchantId merchantId;
  private final ProviderEnvironment environment;
  private final String customerId;
  private final String planId;
  private final int anchorDay;
  private final Instant createdAt;

  /**
   * The first billing day, known only between the factory and the first insert: there is no column
   * for it, because after the first cycle {@code currentPeriod} and {@code anchorDay} say it all.
   * Null on a rehydrated subscription.
   */
  private final LocalDate startDay;

  private PaymentMethod method;
  private String cardId;
  private SubscriptionStatus status;
  private BillingPeriod currentPeriod;
  private Instant nextBillingAt;
  private int lastInvoiceNumber;
  private boolean cancelAtPeriodEnd;
  private Instant canceledAt;
  private Instant endedAt;
  private long version;
  private Instant updatedAt;

  Subscription(
      String id,
      MerchantId merchantId,
      ProviderEnvironment environment,
      String customerId,
      String planId,
      PaymentMethod method,
      String cardId,
      int anchorDay,
      LocalDate startDay,
      Instant createdAt) {
    this.id = id;
    this.merchantId = merchantId;
    this.environment = environment;
    this.customerId = customerId;
    this.planId = planId;
    this.method = method;
    this.cardId = cardId;
    this.anchorDay = anchorDay;
    this.startDay = startDay;
    this.createdAt = createdAt;
    this.status = SubscriptionStatus.ACTIVE;
    this.lastInvoiceNumber = 0;
    this.version = 1;
    this.updatedAt = createdAt;
  }

  /**
   * Before the first insert only, so no version bump: the row is born with it. The first cycle is a
   * job at this instant; creating a subscription moves no money.
   */
  public void scheduleFirstBilling(Instant firstBillingAt, Instant at) {
    if (lastInvoiceNumber != 0 || currentPeriod != null) {
      throw new IllegalStateException("subscription " + id + " already started billing");
    }

    this.nextBillingAt = firstBillingAt;
    this.updatedAt = at;
  }

  /**
   * Opens the next cycle: the invoice number this period gets, and when the following one bills.
   */
  public int openPeriod(BillingPeriod period, Instant nextBillingAt, Instant at) {
    requireBillable();

    this.currentPeriod = period;
    this.nextBillingAt = nextBillingAt;
    this.lastInvoiceNumber++;
    touch(at);

    return lastInvoiceNumber;
  }

  public void markPastDue(Instant at) {
    transition(SubscriptionStatus.PAST_DUE, at);
  }

  /** A no-op when already ACTIVE: a late payment of an older invoice must not fail the reaction. */
  public void recover(Instant at) {
    if (status == SubscriptionStatus.PAST_DUE) {
      transition(SubscriptionStatus.ACTIVE, at);
    }
  }

  public void requestCancelAtPeriodEnd(Instant at) {
    requireBillable();

    this.cancelAtPeriodEnd = true;
    this.canceledAt = at;
    touch(at);
  }

  public void cancelNow(Instant at) {
    transition(SubscriptionStatus.CANCELED, at);

    this.canceledAt = at;
    this.nextBillingAt = null;
  }

  public void end(Instant at) {
    transition(SubscriptionStatus.ENDED, at);

    this.endedAt = at;
    this.nextBillingAt = null;
  }

  public void changeMethod(PaymentMethod newMethod, String newCardId, Instant at) {
    requireBillable();

    this.method = newMethod;
    this.cardId = newCardId;
    touch(at);
  }

  public boolean isBillable() {
    return status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.PAST_DUE;
  }

  private void requireBillable() {
    if (!isBillable()) {
      throw new IllegalStateException("subscription " + id + " is " + status);
    }
  }

  private void transition(SubscriptionStatus to, Instant at) {
    if (!SubscriptionTransitions.allowed(status, to)) {
      throw new IllegalStateException(
          "subscription " + id + " is " + status + ", cannot become " + to);
    }

    this.status = to;
    touch(at);
  }

  private void touch(Instant at) {
    this.version++;
    this.updatedAt = at;
  }

  public String id() {
    return id;
  }

  public MerchantId merchantId() {
    return merchantId;
  }

  public ProviderEnvironment environment() {
    return environment;
  }

  public String customerId() {
    return customerId;
  }

  public String planId() {
    return planId;
  }

  public PaymentMethod method() {
    return method;
  }

  public String cardId() {
    return cardId;
  }

  public SubscriptionStatus status() {
    return status;
  }

  public int anchorDay() {
    return anchorDay;
  }

  public LocalDate startDay() {
    return startDay;
  }

  public BillingPeriod currentPeriod() {
    return currentPeriod;
  }

  public Instant nextBillingAt() {
    return nextBillingAt;
  }

  public int lastInvoiceNumber() {
    return lastInvoiceNumber;
  }

  public boolean cancelAtPeriodEnd() {
    return cancelAtPeriodEnd;
  }

  public Instant canceledAt() {
    return canceledAt;
  }

  public Instant endedAt() {
    return endedAt;
  }

  public long version() {
    return version;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public static Subscription rehydrate(
      String id,
      MerchantId merchantId,
      ProviderEnvironment environment,
      String customerId,
      String planId,
      PaymentMethod method,
      String cardId,
      SubscriptionStatus status,
      int anchorDay,
      BillingPeriod currentPeriod,
      Instant nextBillingAt,
      int lastInvoiceNumber,
      boolean cancelAtPeriodEnd,
      Instant canceledAt,
      Instant endedAt,
      long version,
      Instant createdAt,
      Instant updatedAt) {
    Subscription subscription =
        new Subscription(
            id,
            merchantId,
            environment,
            customerId,
            planId,
            method,
            cardId,
            anchorDay,
            null,
            createdAt);

    subscription.status = status;
    subscription.currentPeriod = currentPeriod;
    subscription.nextBillingAt = nextBillingAt;
    subscription.lastInvoiceNumber = lastInvoiceNumber;
    subscription.cancelAtPeriodEnd = cancelAtPeriodEnd;
    subscription.canceledAt = canceledAt;
    subscription.endedAt = endedAt;
    subscription.version = version;
    subscription.updatedAt = updatedAt;

    return subscription;
  }
}
