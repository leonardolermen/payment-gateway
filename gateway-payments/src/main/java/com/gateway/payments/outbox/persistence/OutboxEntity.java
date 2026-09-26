package com.gateway.payments.outbox.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox", schema = "payments")
class OutboxEntity {
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "aggregate_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String aggregateId;

  @Column(name = "partition_key", length = 64)
  String partitionKey;

  @Column(name = "event_type", nullable = false, length = 60)
  String eventType;

  @Column(name = "payload", nullable = false)
  String payload;

  @Column(name = "status", nullable = false, length = 10)
  String status;

  @Column(name = "claimed_at")
  Instant claimedAt;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  protected OutboxEntity() {}
}
