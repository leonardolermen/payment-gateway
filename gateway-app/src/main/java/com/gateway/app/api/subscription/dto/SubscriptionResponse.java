package com.gateway.app.api.subscription.dto;

import com.gateway.app.api.order.dto.OrderResponse;
import com.gateway.billing.subscription.BillingPeriod;
import com.gateway.billing.subscription.DunningAttempt;
import com.gateway.billing.subscription.Subscription;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code current_period} is null until the first cycle opens one; {@code latest_order} is the
 * newest invoice, null before the first.
 */
public record SubscriptionResponse(
    String id,
    String status,
    String customerId,
    String planId,
    String method,
    String cardId,
    Period currentPeriod,
    Instant nextBillingAt,
    boolean cancelAtPeriodEnd,
    OrderResponse latestOrder,
    List<DunningAttemptResponse> dunning,
    Instant createdAt) {

  public record Period(LocalDate start, LocalDate end) {}

  public static SubscriptionResponse from(
      Subscription subscription, OrderResponse latestOrder, List<DunningAttempt> dunning) {
    BillingPeriod period = subscription.currentPeriod();

    return new SubscriptionResponse(
        subscription.id(),
        subscription.status().name(),
        subscription.customerId(),
        subscription.planId(),
        subscription.method().name(),
        subscription.cardId(),
        period == null ? null : new Period(period.start(), period.end()),
        subscription.nextBillingAt(),
        subscription.cancelAtPeriodEnd(),
        latestOrder,
        dunning.stream().map(DunningAttemptResponse::from).toList(),
        subscription.createdAt());
  }
}
