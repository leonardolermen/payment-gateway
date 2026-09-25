package com.gateway.payments.reconciliation.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "reconciliation_divergences", schema = "payments")
class ReconciliationDivergenceEntity {
  @Id @Column(name = "id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String id;
  @Column(name = "payment_id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String paymentId;
  @Column(name = "gateway_status", nullable = false, length = 20) String gatewayStatus;
  @Column(name = "provider_status", nullable = false, length = 30) String providerStatus;
  @Column(name = "detail", length = 500) String detail;
  @Column(name = "status", nullable = false, length = 10) String status;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected ReconciliationDivergenceEntity() {}
}
