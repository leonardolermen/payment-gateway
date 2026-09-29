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

  public static RefundResponse from(Refund refund) {
    return new RefundResponse(
        refund.id(),
        refund.paymentId(),
        refund.amount().cents(),
        refund.state().name(),
        refund.failureReason(),
        refund.createdAt(),
        refund.settledAt());
  }
}
