package com.gateway.app.api.subscription.dto;

/** {@code at_period_end} defaults to true: what the customer already paid for is delivered. */
public record SubscriptionCancelRequest(Boolean atPeriodEnd) {

  public boolean atPeriodEndOrDefault() {
    return atPeriodEnd == null || atPeriodEnd;
  }
}
