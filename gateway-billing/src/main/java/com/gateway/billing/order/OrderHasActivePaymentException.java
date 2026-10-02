package com.gateway.billing.order;

import com.gateway.kernel.errors.DomainException;

public class OrderHasActivePaymentException extends DomainException {
  private final String paymentId;

  public OrderHasActivePaymentException(String orderId, String paymentId) {
    super(
        "ORDER_HAS_ACTIVE_PAYMENT",
        "order " + orderId + " already has an active payment: " + paymentId);
    this.paymentId = paymentId;
  }

  public String paymentId() {
    return paymentId;
  }
}
