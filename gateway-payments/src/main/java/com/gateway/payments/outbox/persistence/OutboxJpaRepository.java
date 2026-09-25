package com.gateway.payments.outbox.persistence;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

interface OutboxJpaRepository extends JpaRepository<OutboxEntity, String> {

  /**
   * Pending messages whose lease is free or expired, locked for exclusive claiming. Same pattern
   * as {@code DeliveryJpaRepository.selectClaimable} in webhook-delivery: {@code PESSIMISTIC_WRITE}
   * + {@code jakarta.persistence.lock.timeout = -2} is what Hibernate turns into {@code SKIP
   * LOCKED} — without it, concurrent claimers would queue behind each other instead of getting
   * disjoint sets.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      SELECT o FROM OutboxEntity o
       WHERE o.status = 'PENDING' AND (o.claimedAt IS NULL OR o.claimedAt < :leaseCutoff)
       ORDER BY o.createdAt ASC
      """)
  List<OutboxEntity> selectClaimable(@Param("leaseCutoff") Instant leaseCutoff, Limit limit);

  @Modifying
  @Query("UPDATE OutboxEntity o SET o.status = 'SENT', o.claimedAt = NULL WHERE o.id = :id")
  int markSent(@Param("id") String id);

  @Modifying
  @Query("UPDATE OutboxEntity o SET o.claimedAt = NULL WHERE o.id = :id")
  int release(@Param("id") String id);
}
