package com.gateway.billing.subscription.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dunning_attempts", schema = "billing")
class DunningAttemptEntity {
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "subscription_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String subscriptionId;

  @Column(name = "order_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String orderId;

  @Column(name = "attempt", nullable = false)
  short attempt;

  @Column(name = "scheduled_at", nullable = false)
  Instant scheduledAt;

  @Column(name = "ran_at")
  Instant ranAt;

  @Column(name = "outcome", length = 20)
  String outcome;

  @Column(name = "payment_id", length = 26)
  @JdbcTypeCode(SqlTypes.CHAR)
  String paymentId;
}
