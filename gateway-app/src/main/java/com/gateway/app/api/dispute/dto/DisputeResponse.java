package com.gateway.app.api.dispute.dto;

import com.gateway.payments.dispute.Dispute;
import java.time.Instant;

public record DisputeResponse(
    String id,
    String paymentId,
    String reason,
    String note,
    String status,
    String resolution,
    String resolutionNote,
    Instant createdAt,
    Instant resolvedAt) {
  public static DisputeResponse from(Dispute dispute) {
    return new DisputeResponse(
        dispute.id(),
        dispute.paymentId(),
        dispute.reason().name(),
        dispute.note(),
        dispute.status().name(),
        dispute.resolution() == null ? null : dispute.resolution().name(),
        dispute.resolutionNote(),
        dispute.createdAt(),
        dispute.resolvedAt());
  }
}
