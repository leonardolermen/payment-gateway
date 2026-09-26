package com.gateway.app.api.refund.dto;

import com.gateway.payments.refund.Refund;
import java.time.Instant;

public record RefundResponse(
    String id,
    String paymentId,
    long amount,
    String state,
    String reason,
    Instant requestedAt,
    Instant settledAt) {

  public static RefundResponse from(Refund r) {
    return new RefundResponse(
        r.id(),
        r.paymentId(),
        r.amount().cents(),
        r.state().name(),
        r.failureReason(),
        r.createdAt(),
        r.settledAt());
  }
}
