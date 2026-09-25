package com.gateway.payments.idempotency.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;

interface IdempotencyJpaRepository extends JpaRepository<IdempotencyKeyEntity, IdempotencyKeyId> {
  long deleteByCreatedAtBefore(Instant before);
}
