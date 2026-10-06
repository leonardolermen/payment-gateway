package com.gateway.app.api.checkout.dto;

import com.gateway.billing.order.Order;
import com.gateway.billing.order.checkout.CheckoutView;
import java.time.Instant;
import java.util.List;

/**
 * The payer's view of an order. Never the merchant id, the customer id, the payer's own data, the
 * merchant's reference or the paid payment id: whoever holds the link sees what to pay and how, and
 * a leaked link must not leak a person.
 */
public record CheckoutResponse(
    String orderId,
    String merchantName,
    long amount,
    String currency,
    String description,
    String status,
    Instant expiresAt,
    List<String> methods,
    CheckoutPaymentResponse activePayment) {

  public static CheckoutResponse from(
      CheckoutView view, String merchantName, List<String> methods) {
    Order order = view.order();

    return new CheckoutResponse(
        order.id(),
        merchantName,
        order.amount().cents(),
        order.amount().currency(),
        order.description(),
        order.status().name(),
        order.expiresAt(),
        methods,
        view.activeAttempt().map(CheckoutPaymentResponse::from).orElse(null));
  }
}
