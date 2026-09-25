package com.gateway.payments.jobs.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "jobs", schema = "payments")
class JobEntity {
  @Id @Column(name = "id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String id;
  @Column(name = "type", nullable = false, length = 30) String type;
  @Column(name = "ref_id", nullable = false, length = 64) String refId;
  @Column(name = "next_run_at", nullable = false) Instant nextRunAt;
  @Column(name = "attempts", nullable = false) int attempts;
  @Column(name = "status", nullable = false, length = 10) String status;
  @Column(name = "claimed_at") Instant claimedAt;
  @Column(name = "last_error", length = 500) String lastError;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  protected JobEntity() {}
}
