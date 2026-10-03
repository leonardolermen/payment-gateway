package com.gateway.billing.subscription.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "subscriptions", schema = "billing")
class SubscriptionEntity {
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "environment", nullable = false, length = 10)
  String environment;

  @Column(name = "customer_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String customerId;

  @Column(name = "plan_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String planId;

  @Column(name = "method", nullable = false, length = 10)
  String method;

  @Column(name = "card_id", length = 26)
  @JdbcTypeCode(SqlTypes.CHAR)
  String cardId;

  @Column(name = "status", nullable = false, length = 10)
  String status;

  @Column(name = "anchor_day", nullable = false)
  short anchorDay;

  @Column(name = "current_period_start")
  LocalDate currentPeriodStart;

  @Column(name = "current_period_end")
  LocalDate currentPeriodEnd;

  @Column(name = "next_billing_at")
  Instant nextBillingAt;

  @Column(name = "last_invoice_number", nullable = false)
  int lastInvoiceNumber;

  @Column(name = "cancel_at_period_end", nullable = false)
  boolean cancelAtPeriodEnd;

  @Column(name = "canceled_at")
  Instant canceledAt;

  @Column(name = "ended_at")
  Instant endedAt;

  @Column(name = "version", nullable = false)
  long version;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;
}
