package com.gateway.app.api.checkout.dto;

import com.gateway.billing.installment.InstallmentOption;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.checkout.CheckoutView;
import java.time.Instant;
import java.util.List;

/**
 * The payer's view of an order. Never the merchant id, the customer id, the payer's own data, the
 * merchant's reference or the paid payment id: whoever holds the link sees what to pay and how, and
 * a leaked link must not leak a person.
 *
 * <p>{@code installmentOptions} are the card's, priced by the gateway from the merchant's settings
 * (spec 2026-10-07 §4): empty when the card is not among {@code methods}, so the page never offers
 * what it cannot charge.
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
    List<InstallmentOptionResponse> installmentOptions,
    CheckoutPaymentResponse activePayment) {

  public record InstallmentOptionResponse(
      int count, long installmentAmount, long total, boolean interestFree) {
    static InstallmentOptionResponse from(InstallmentOption option) {
      return new InstallmentOptionResponse(
          option.count(), option.installmentAmount(), option.total(), option.interestFree());
    }
  }

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
        methods.contains("CARD")
            ? view.installmentOptions().stream().map(InstallmentOptionResponse::from).toList()
            : List.of(),
        view.activeAttempt().map(CheckoutPaymentResponse::from).orElse(null));
  }
}
