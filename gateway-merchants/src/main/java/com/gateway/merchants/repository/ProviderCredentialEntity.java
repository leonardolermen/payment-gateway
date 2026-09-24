package com.gateway.merchants.repository;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "provider_credentials", schema = "merchants")
class ProviderCredentialEntity {
  // @JdbcTypeCode(CHAR) matches the migration's CHAR(26): a plain @Column(length=...) maps a String
  // to VARCHAR, and Hibernate's schema validator compares that against Postgres' bpchar and fails —
  // columnDefinition alone only steers CREATE DDL, not validation.
  @Id @Column(name = "id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String id;
  @Column(name = "merchant_id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String merchantId;
  @Column(name = "provider", nullable = false, length = 20) String provider;
  @Column(name = "environment", nullable = false, length = 10) String environment;
  @Column(name = "nonce", nullable = false) byte[] nonce;
  @Column(name = "ciphertext", nullable = false) byte[] ciphertext;
  @Column(name = "encrypted_dek", nullable = false) byte[] encryptedDek;
  @Column(name = "dek_nonce", nullable = false) byte[] dekNonce;
  @Column(name = "active", nullable = false) boolean active;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  @Column(name = "updated_at", nullable = false) Instant updatedAt;
  protected ProviderCredentialEntity() {}
}
