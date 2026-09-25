package com.gateway.payments.payment.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "payment_events", schema = "payments")
class PaymentEventEntity {
  @Id @Column(name = "id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String id;
  @Column(name = "payment_id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String paymentId;
  @Column(name = "sequence", nullable = false) long sequence;
  @Column(name = "type", nullable = false, length = 30) String type;
  @Column(name = "source", nullable = false, length = 20) String source;
  @Column(name = "payload", nullable = false) @JdbcTypeCode(SqlTypes.JSON) String payload;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected PaymentEventEntity() {}
}
