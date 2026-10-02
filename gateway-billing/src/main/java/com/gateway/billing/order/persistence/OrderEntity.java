package com.gateway.billing.order.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "orders", schema = "billing")
class OrderEntity {
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "environment", nullable = false, length = 10)
  String environment;

  @Column(name = "customer_id", length = 26)
  @JdbcTypeCode(SqlTypes.CHAR)
  String customerId;

  @Column(name = "payer")
  @JdbcTypeCode(SqlTypes.JSON)
  String payer;

  @Column(name = "amount", nullable = false)
  long amount;

  @Column(name = "currency", length = 3, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String currency;

  @Column(name = "reference", length = 100)
  String reference;

  @Column(name = "description", length = 200)
  String description;

  @Column(name = "status", nullable = false, length = 10)
  String status;

  @Column(name = "paid_payment_id", length = 26)
  @JdbcTypeCode(SqlTypes.CHAR)
  String paidPaymentId;

  @Column(name = "paid_at")
  Instant paidAt;

  @Column(name = "expires_at")
  Instant expiresAt;

  @Column(name = "subscription_id", length = 26)
  @JdbcTypeCode(SqlTypes.CHAR)
  String subscriptionId;

  @Column(name = "invoice_number")
  Integer invoiceNumber;

  @Column(name = "period_start")
  LocalDate periodStart;

  @Column(name = "period_end")
  LocalDate periodEnd;

  @Column(name = "version", nullable = false)
  long version;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;
}
