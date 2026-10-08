package com.gateway.merchants.usertoken.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "user_tokens", schema = "merchants")
class UserTokenEntity {
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "user_id", length = 26)
  @JdbcTypeCode(SqlTypes.CHAR)
  String userId;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "kind", nullable = false)
  String kind;

  @Column(name = "token_hash", length = 64, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String tokenHash;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "payload", nullable = false)
  Map<String, String> payload;

  @Column(name = "expires_at", nullable = false)
  Instant expiresAt;

  @Column(name = "used_at")
  Instant usedAt;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;
}
