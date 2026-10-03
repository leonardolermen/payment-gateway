package com.gateway.app.api.subscription.dto;

import com.gateway.kernel.payment.PaymentMethod;
import java.time.LocalDate;

/**
 * POST /v1/subscriptions. {@code start_at} defaults to today in São Paulo, decided by the
 * controller, which holds the clock.
 */
public record SubscriptionRequest(
    String customerId, String planId, PaymentMethod method, String cardId, LocalDate startAt) {

  public void validate() {
    if (customerId == null || planId == null || method == null) {
      throw new IllegalArgumentException("customer_id, plan_id and method are required");
    }
  }
}
