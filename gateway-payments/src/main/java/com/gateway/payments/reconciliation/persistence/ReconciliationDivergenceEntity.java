package com.gateway.payments.reconciliation.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reconciliation_divergences", schema = "payments")
class ReconciliationDivergenceEntity {
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "payment_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String paymentId;

  @Column(name = "gateway_status", nullable = false, length = 20)
  String gatewayStatus;

  @Column(name = "provider_status", nullable = false, length = 30)
  String providerStatus;

  @Column(name = "detail", length = 500)
  String detail;

  @Column(name = "status", nullable = false, length = 12)
  String status;

  @Column(name = "origin", nullable = false, length = 10)
  String origin;

  @Column(name = "reason", length = 20)
  String reason;

  @Column(name = "merchant_note", length = 500)
  String merchantNote;

  @Column(name = "resolution", length = 16)
  String resolution;

  @Column(name = "resolution_note", length = 500)
  String resolutionNote;

  @Column(name = "resolved_by", length = 80)
  String resolvedBy;

  @Column(name = "resolved_at")
  Instant resolvedAt;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  protected ReconciliationDivergenceEntity() {}
}
