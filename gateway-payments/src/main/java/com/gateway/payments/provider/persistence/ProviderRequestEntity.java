package com.gateway.payments.provider.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "provider_requests", schema = "payments")
class ProviderRequestEntity {
  @Id @Column(name = "id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String id;
  @Column(name = "payment_id", length = 26) @JdbcTypeCode(SqlTypes.CHAR) String paymentId;
  @Column(name = "provider", nullable = false, length = 20) String provider;
  @Column(name = "operation", nullable = false, length = 40) String operation;
  @Column(name = "request") String request;
  @Column(name = "response") String response;
  @Column(name = "status", nullable = false) int status;
  @Column(name = "latency_ms", nullable = false) long latencyMs;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected ProviderRequestEntity() {}
}
