package com.gateway.app.api.admin.divergence.dto;

import com.gateway.payments.reconciliation.DivergenceDetail;
import com.gateway.payments.reconciliation.ReconciliationDivergence;
import java.time.Instant;

/** {@code kind} is the stored provider status: what the bank said, or DISPUTE. */
public record DivergenceResponse(
    String id,
    String paymentId,
    String origin,
    String kind,
    String gatewayStatus,
    String detail,
    String reason,
    String merchantNote,
    String status,
    String resolution,
    String resolutionNote,
    String resolvedBy,
    Instant resolvedAt,
    Instant createdAt,
    Instant updatedAt,
    PaymentSummary payment) {
  public static DivergenceResponse from(DivergenceDetail detail) {
    ReconciliationDivergence divergence = detail.divergence();

    return new DivergenceResponse(
        divergence.id(),
        divergence.paymentId(),
        divergence.origin().name(),
        divergence.providerStatus(),
        divergence.gatewayStatus(),
        divergence.detail(),
        divergence.reason(),
        divergence.merchantNote(),
        divergence.status().name(),
        divergence.resolution() == null ? null : divergence.resolution().name(),
        divergence.resolutionNote(),
        divergence.resolvedBy(),
        divergence.resolvedAt(),
        divergence.createdAt(),
        divergence.updatedAt(),
        PaymentSummary.from(detail.payment()));
  }
}
