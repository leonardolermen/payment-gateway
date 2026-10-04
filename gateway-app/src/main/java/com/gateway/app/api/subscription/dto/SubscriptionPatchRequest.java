package com.gateway.app.api.subscription.dto;

import com.gateway.kernel.payment.PaymentMethod;

/** PATCH /v1/subscriptions/{id}: from the next invoice on; the open one keeps its method. */
public record SubscriptionPatchRequest(PaymentMethod method, String cardId) {

  public void validate() {
    if (method == null) {
      throw new IllegalArgumentException("method is required");
    }
  }
}
