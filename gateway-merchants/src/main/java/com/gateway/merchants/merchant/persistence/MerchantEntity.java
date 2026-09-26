package com.gateway.merchants.merchant.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "merchants", schema = "merchants")
class MerchantEntity {
  // @JdbcTypeCode(CHAR) matches the migration's CHAR(26): a plain @Column(length=...) maps a String
  // to VARCHAR, and Hibernate's schema validator compares that against Postgres' bpchar and fails —
  // columnDefinition alone only steers CREATE DDL, not validation.
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "name", nullable = false, length = 200)
  String name;

  @Column(name = "status", nullable = false, length = 20)
  String status;

  // No unique=true: it only steers generated DDL, which ddl-auto=validate never runs; V101 holds
  // the constraint.
  @Column(name = "inbound_webhook_token", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String inboundWebhookToken;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  protected MerchantEntity() {}
}
