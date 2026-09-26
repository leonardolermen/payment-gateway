package com.gateway.payments.inbox.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "webhook_inbox", schema = "payments")
class WebhookInboxEntity {
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "provider", nullable = false, length = 20)
  String provider;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "raw_headers", nullable = false)
  String rawHeaders;

  @Column(name = "raw_body", nullable = false)
  byte[] rawBody;

  @Column(name = "status", nullable = false, length = 10)
  String status;

  @Column(name = "error", length = 500)
  String error;

  @Column(name = "received_at", nullable = false)
  Instant receivedAt;

  protected WebhookInboxEntity() {}
}
