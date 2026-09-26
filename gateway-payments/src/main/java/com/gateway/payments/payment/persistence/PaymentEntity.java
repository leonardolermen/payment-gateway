package com.gateway.payments.payment.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payments", schema = "payments")
class PaymentEntity {
  // @JdbcTypeCode(CHAR) matches the migration's CHAR(n): a plain @Column(length=...) maps a String
  // to VARCHAR, and Hibernate's schema validator compares that against Postgres' bpchar and fails —
  // columnDefinition alone only steers CREATE DDL, not validation.
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "environment", nullable = false, length = 10)
  String environment;

  @Column(name = "provider", nullable = false, length = 20)
  String provider;

  @Column(name = "method", nullable = false, length = 10)
  String method;

  @Column(name = "status", nullable = false, length = 20)
  String status;

  @Column(name = "amount", nullable = false)
  long amount;

  @Column(name = "currency", length = 3, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String currency;

  @Column(name = "reference", length = 140)
  String reference;

  @Column(name = "description", length = 140)
  String description;

  @Column(name = "customer_document_hash", length = 64)
  @JdbcTypeCode(SqlTypes.CHAR)
  String customerDocumentHash;

  // details is {"pix":{txid,pixCopiaECola,location,endToEndId},"boleto":{nossoNumero,...}|null}
  // (V203); the
  // domain has no Jackson dependency (see Payment's javadoc), so PaymentDetailsJson builds/parses
  // this by hand.
  @Column(name = "details", nullable = false)
  @JdbcTypeCode(SqlTypes.JSON)
  String details;

  @Column(name = "expires_at")
  Instant expiresAt;

  @Column(name = "paid_at")
  Instant paidAt;

  @Column(name = "paid_amount")
  Long paidAmount;

  @Column(name = "refunded_amount", nullable = false)
  long refundedAmount;

  // Plain column, not @Version: the optimistic lock is the explicit WHERE version = expected bulk
  // update in PaymentJpaRepository.updateIfVersionMatches, using the version the aggregate was
  // loaded at. A Hibernate-managed @Version here would fight that — within one transaction, a
  // second findById after a save would come back with a persistence-context-cached (stale) entity
  // whose @Version the bulk UPDATE's clearAutomatically didn't exist to invalidate, so this field
  // must never carry that annotation.
  @Column(name = "version", nullable = false)
  long version;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  protected PaymentEntity() {}
}
