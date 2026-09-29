package com.gateway.payments.card.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cards", schema = "payments")
class SavedCardEntity {
  // CHAR(n) columns carry @JdbcTypeCode(CHAR): see PaymentEntity for why validation needs it.
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "provider", nullable = false, length = 20)
  String provider;

  @Column(name = "environment", nullable = false, length = 10)
  String environment;

  @Column(name = "token_ciphertext", nullable = false)
  byte[] tokenCiphertext;

  @Column(name = "brand", nullable = false, length = 10)
  String brand;

  @Column(name = "last4", length = 4, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String last4;

  @Column(name = "expiry_month", nullable = false)
  short expiryMonth;

  @Column(name = "expiry_year", nullable = false)
  short expiryYear;

  @Column(name = "holder", nullable = false, length = 25)
  String holder;

  @Column(name = "customer_document_hash", length = 64)
  @JdbcTypeCode(SqlTypes.CHAR)
  String customerDocumentHash;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "deleted_at")
  Instant deletedAt;

  protected SavedCardEntity() {}
}
