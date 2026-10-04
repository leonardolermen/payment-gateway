package com.gateway.billing.plan.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "plans", schema = "billing")
class PlanEntity {
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "name", nullable = false, length = 80)
  String name;

  @Column(name = "amount", nullable = false)
  long amount;

  @Column(name = "currency", length = 3, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String currency;

  @Column(name = "interval", nullable = false, length = 5)
  String interval;

  @Column(name = "interval_count", nullable = false)
  short intervalCount;

  @Column(name = "trial_days", nullable = false)
  short trialDays;

  @Column(name = "active", nullable = false)
  boolean active;

  @Column(name = "version", nullable = false)
  long version;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;
}
