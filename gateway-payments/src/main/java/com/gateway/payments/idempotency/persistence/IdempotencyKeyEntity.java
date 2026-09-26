package com.gateway.payments.idempotency.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "idempotency_keys", schema = "payments")
@IdClass(IdempotencyKeyId.class)
class IdempotencyKeyEntity {
  @Id
  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Id
  @Column(name = "key", nullable = false, length = 128)
  String key;

  @Column(name = "request_hash", length = 64, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String requestHash;

  @Column(name = "status", nullable = false, length = 20)
  String status;

  @Column(name = "response_code")
  Integer responseCode;

  @Column(name = "response_body")
  String responseBody;

  @Column(name = "resource_id", length = 26)
  @JdbcTypeCode(SqlTypes.CHAR)
  String resourceId;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  protected IdempotencyKeyEntity() {}
}
