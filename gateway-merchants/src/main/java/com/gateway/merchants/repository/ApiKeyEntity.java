package com.gateway.merchants.repository;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "api_keys", schema = "merchants")
class ApiKeyEntity {
  // @JdbcTypeCode(CHAR) matches the migration's CHAR(n): a plain @Column(length=...) maps a String
  // to VARCHAR, and Hibernate's schema validator compares that against Postgres' bpchar and fails —
  // columnDefinition alone only steers CREATE DDL, not validation.
  @Id @Column(name = "id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String id;
  @Column(name = "merchant_id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String merchantId;
  @Column(name = "environment", nullable = false, length = 10) String environment;
  @Column(name = "prefix", nullable = false, length = 12) String prefix;
  @Column(name = "hash", length = 64, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String hash;
  @Column(name = "active", nullable = false) boolean active;
  @Column(name = "expires_at") Instant expiresAt;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected ApiKeyEntity() {}
}
