package com.gateway.merchants.user.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users", schema = "merchants")
class UserEntity {
  // @JdbcTypeCode(CHAR) matches CHAR(n) in the migration, as every entity in this module does.
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "name", nullable = false, length = 120)
  String name;

  @Column(name = "email", nullable = false, length = 254)
  String email;

  @Column(name = "email_normalized", nullable = false, length = 254)
  String emailNormalized;

  @Column(name = "password_hash", nullable = false, length = 200)
  String passwordHash;

  @Column(name = "role", nullable = false, length = 10)
  String role;

  @Column(name = "email_verified_at")
  Instant emailVerifiedAt;

  @Column(name = "last_login_at")
  Instant lastLoginAt;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;

  @Column(name = "deleted_at")
  Instant deletedAt;
}
