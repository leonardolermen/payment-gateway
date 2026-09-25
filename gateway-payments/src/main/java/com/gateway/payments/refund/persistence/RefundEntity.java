package com.gateway.payments.refund.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "refunds", schema = "payments")
class RefundEntity {
  @Id @Column(name = "id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String id;
  @Column(name = "payment_id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String paymentId;
  @Column(name = "merchant_id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String merchantId;
  @Column(name = "amount", nullable = false) long amount;
  @Column(name = "state", nullable = false, length = 20) String state;
  @Column(name = "provider_refund_id", length = 64) String providerRefundId;
  @Column(name = "reason", length = 140) String reason;
  @Column(name = "requested_at", nullable = false) Instant requestedAt;
  @Column(name = "settled_at") Instant settledAt;
  @Column(name = "updated_at", nullable = false) Instant updatedAt;
  protected RefundEntity() {}
}
