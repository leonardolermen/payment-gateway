package com.gateway.app.api.admin.divergence.dto;

import com.gateway.payments.payment.Payment;
import java.time.Instant;

/** Enough of the payment to judge the divergence without a second call. */
public record PaymentSummary(
    String id,
    String merchantId,
    String status,
    String method,
    String provider,
    long amount,
    String currency,
    Instant paidAt) {
  public static PaymentSummary from(Payment payment) {
    return new PaymentSummary(
        payment.id(),
        payment.merchantId().value(),
        payment.status().name(),
        payment.method().name(),
        payment.provider(),
        payment.amount().cents(),
        payment.amount().currency(),
        payment.paidAt());
  }
}
