package com.gateway.merchants.mail.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbound_emails", schema = "merchants")
class OutboundEmailEntity {
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "recipient", length = 254, nullable = false)
  String recipient;

  @Column(name = "subject", length = 200, nullable = false)
  String subject;

  @Column(name = "text_body", nullable = false)
  String textBody;

  @Column(name = "html_body", nullable = false)
  String htmlBody;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;
}
