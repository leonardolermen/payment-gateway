package com.gateway.billing.customer.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "customers", schema = "billing")
class CustomerEntity {
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "environment", nullable = false, length = 10)
  String environment;

  @Column(name = "name", nullable = false, length = 120)
  String name;

  @Column(name = "document_ciphertext", nullable = false)
  byte[] documentCiphertext;

  @Column(name = "document_hash", length = 64, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String documentHash;

  @Column(name = "document_kind", nullable = false, length = 4)
  String documentKind;

  @Column(name = "email", length = 254)
  String email;

  @Column(name = "address")
  @JdbcTypeCode(SqlTypes.JSON)
  String address;

  @Column(name = "version", nullable = false)
  long version;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Column(name = "deleted_at")
  Instant deletedAt;
}
