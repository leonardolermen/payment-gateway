package com.gateway.app.api.order.dto;

import com.gateway.billing.order.Order;
import com.gateway.payments.payment.Payment;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * An order with its attempts summarised; the full payment is GET /v1/payments/{id}. {@code
 * subscription_id}, {@code invoice_number} and {@code period} are null on a standalone order.
 *
 * <p>{@code checkoutUrl} is non-null only on create and rotate: the token is not stored (only its
 * hash), so GET has nothing to rebuild the url from and always returns null there.
 */
public record OrderResponse(
    String id,
    String status,
    long amount,
    String currency,
    String reference,
    String description,
    String customerId,
    String paidPaymentId,
    Instant paidAt,
    Instant expiresAt,
    String subscriptionId,
    Integer invoiceNumber,
    Period period,
    List<Attempt> payments,
    Instant createdAt,
    String checkoutUrl) {

  public record Period(LocalDate start, LocalDate end) {}

  public record Attempt(String id, String method, String status, Instant createdAt) {

    static Attempt from(Payment payment) {
      return new Attempt(
          payment.id(), payment.method().name(), payment.status().name(), payment.createdAt());
    }
  }

  public static OrderResponse from(Order order, List<Payment> attempts) {
    return from(order, attempts, null);
  }

  /** {@code checkoutUrl} is non-null only where the plain token is in hand: creation, rotation. */
  public static OrderResponse from(Order order, List<Payment> attempts, String checkoutUrl) {
    Period period =
        order.periodStart() == null ? null : new Period(order.periodStart(), order.periodEnd());

    return new OrderResponse(
        order.id(),
        order.status().name(),
        order.amount().cents(),
        order.amount().currency(),
        order.reference(),
        order.description(),
        order.customerId(),
        order.paidPaymentId(),
        order.paidAt(),
        order.expiresAt(),
        order.subscriptionId(),
        order.invoiceNumber(),
        period,
        attempts.stream().map(Attempt::from).toList(),
        order.createdAt(),
        checkoutUrl);
  }
}
