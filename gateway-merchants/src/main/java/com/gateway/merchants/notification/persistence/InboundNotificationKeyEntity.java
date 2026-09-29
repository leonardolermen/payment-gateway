package com.gateway.merchants.notification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inbound_notification_keys", schema = "merchants")
class InboundNotificationKeyEntity {
  // CHAR(n) needs @JdbcTypeCode(CHAR) for schema validation (see ProviderCredentialEntity).
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "provider", nullable = false, length = 20)
  String provider;

  @Column(name = "key_hash", length = 64, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String keyHash;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  protected InboundNotificationKeyEntity() {}
}
