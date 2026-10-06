package com.gateway.billing.order.checkout;

import com.gateway.billing.order.Order;
import com.gateway.payments.payment.Payment;
import java.util.List;
import java.util.Optional;

/** What the payer may see: the order and its attempts; the app decides which fields leave. */
public record CheckoutView(Order order, List<Payment> attempts) {
  public Optional<Payment> activeAttempt() {
    return attempts.stream().filter(attempt -> attempt.status().isActive()).findFirst();
  }
}
