package com.gateway.payments.dispute;

import com.gateway.payments.reconciliation.DivergenceResolution;
import com.gateway.payments.reconciliation.DivergenceStatus;
import com.gateway.payments.reconciliation.ReconciliationDivergence;
import java.time.Instant;

/**
 * What the merchant sees of a MERCHANT divergence. A view, not the divergence itself: gateway
 * status, detail and who resolved it are the operator's, and a field that is not here cannot leak
 * through a response someone writes later.
 */
public record Dispute(
    String id,
    String paymentId,
    DisputeReason reason,
    String note,
    DivergenceStatus status,
    DivergenceResolution resolution,
    String resolutionNote,
    Instant createdAt,
    Instant resolvedAt) {

  public static Dispute from(ReconciliationDivergence divergence) {
    return new Dispute(
        divergence.id(),
        divergence.paymentId(),
        DisputeReason.valueOf(divergence.reason()),
        divergence.merchantNote(),
        divergence.status(),
        divergence.resolution(),
        divergence.resolutionNote(),
        divergence.createdAt(),
        divergence.resolvedAt());
  }
}
