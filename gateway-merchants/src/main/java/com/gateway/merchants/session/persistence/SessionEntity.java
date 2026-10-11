package com.gateway.merchants.session.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sessions", schema = "merchants")
class SessionEntity {
  // @JdbcTypeCode(CHAR) matches CHAR(n) in the migration, as every entity in this module does.
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "user_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String userId;

  @Column(name = "access_hash", length = 64, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String accessHash;

  @Column(name = "refresh_hash", length = 64, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String refreshHash;

  @Column(name = "previous_refresh_hash", length = 64)
  @JdbcTypeCode(SqlTypes.CHAR)
  String previousRefreshHash;

  @Column(name = "access_expires_at", nullable = false)
  Instant accessExpiresAt;

  @Column(name = "refresh_expires_at", nullable = false)
  Instant refreshExpiresAt;

  @Column(name = "ip", length = 45)
  String ip;

  @Column(name = "user_agent", length = 200)
  String userAgent;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "last_used_at", nullable = false)
  Instant lastUsedAt;

  @Column(name = "revoked_at")
  Instant revokedAt;
}
