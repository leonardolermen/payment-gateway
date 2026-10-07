package com.gateway.app.api.subscription.dto;

import com.gateway.app.api.order.dto.OrderResponse;
import com.gateway.billing.plan.Plan;
import com.gateway.billing.subscription.BillingPeriod;
import com.gateway.billing.subscription.DunningAttempt;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.billing.SubscriptionCreation;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code current_period} is null until the first cycle opens one; {@code latest_order} is the
 * newest invoice, null before the first.
 *
 * <p>{@code customer_name} is null when the customer was deleted; {@code plan_name}, {@code
 * amount}, {@code interval} and {@code interval_count} are the plan's (spec 2026-10-07 §5). {@code
 * first_invoice} is set only in the response of the create that opened it (a card subscription
 * without {@code card_id}), with the payer's link; null everywhere else, since only the link's hash
 * is stored.
 */
public record SubscriptionResponse(
    String id,
    String status,
    String customerId,
    String customerName,
    String planId,
    String planName,
    long amount,
    String interval,
    int intervalCount,
    String method,
    String cardId,
    Period currentPeriod,
    Instant nextBillingAt,
    boolean cancelAtPeriodEnd,
    OrderResponse latestOrder,
    List<DunningAttemptResponse> dunning,
    FirstInvoice firstInvoice,
    Instant createdAt) {

  public record Period(LocalDate start, LocalDate end) {}

  public record FirstInvoice(String orderId, String checkoutUrl) {
    public static FirstInvoice from(SubscriptionCreation.FirstInvoice invoice) {
      return invoice == null ? null : new FirstInvoice(invoice.orderId(), invoice.checkoutUrl());
    }
  }

  public static SubscriptionResponse from(
      Subscription subscription,
      String customerName,
      Plan plan,
      OrderResponse latestOrder,
      List<DunningAttempt> dunning,
      FirstInvoice firstInvoice) {
    BillingPeriod period = subscription.currentPeriod();

    return new SubscriptionResponse(
        subscription.id(),
        subscription.status().name(),
        subscription.customerId(),
        customerName,
        subscription.planId(),
        plan.name(),
        plan.amount().cents(),
        plan.interval().name(),
        plan.intervalCount(),
        subscription.method().name(),
        subscription.cardId(),
        period == null ? null : new Period(period.start(), period.end()),
        subscription.nextBillingAt(),
        subscription.cancelAtPeriodEnd(),
        latestOrder,
        dunning.stream().map(DunningAttemptResponse::from).toList(),
        firstInvoice,
        subscription.createdAt());
  }
}
