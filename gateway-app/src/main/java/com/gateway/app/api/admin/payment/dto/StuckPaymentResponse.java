package com.gateway.app.api.admin.payment.dto;

import com.gateway.payments.payment.Payment;
import java.time.Instant;

public record StuckPaymentResponse(
    String id,
    String merchantId,
    String method,
    String provider,
    String status,
    long amount,
    String currency,
    Instant createdAt,
    Instant expiresAt) {
  public static StuckPaymentResponse from(Payment payment) {
    return new StuckPaymentResponse(
        payment.id(),
        payment.merchantId().value(),
        payment.method().name(),
        payment.provider(),
        payment.status().name(),
        payment.amount().cents(),
        payment.amount().currency(),
        payment.createdAt(),
        payment.expiresAt());
  }
}
