package com.gateway.payments.jobs.persistence;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

interface JobJpaRepository extends JpaRepository<JobEntity, String> {

  Optional<JobEntity> findByTypeAndRefId(String type, String refId);

  /**
   * Same {@code PESSIMISTIC_WRITE} + {@code lock.timeout = -2} pattern as {@code
   * OutboxJpaRepository}.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
  @Query(
      """
      SELECT j FROM JobEntity j
       WHERE j.status = 'PENDING' AND j.nextRunAt <= :now
         AND (j.claimedAt IS NULL
              OR (j.type <> 'RECONCILE' AND j.claimedAt < :leaseCutoff)
              OR (j.type = 'RECONCILE' AND j.claimedAt < :reconcileCutoff))
       ORDER BY j.nextRunAt ASC
      """)
  List<JobEntity> selectDue(
      @Param("now") Instant now,
      @Param("leaseCutoff") Instant leaseCutoff,
      @Param("reconcileCutoff") Instant reconcileCutoff,
      Limit limit);

  /**
   * The lease condition is {@link #selectDue}'s: free here means a worker could claim it too. DONE
   * is never re-queued: not every handler is idempotent, and a second run of a finished job could
   * act twice on the same payment.
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE JobEntity j SET j.status = 'PENDING', j.nextRunAt = :now, j.claimedAt = NULL
       WHERE j.id = :id AND j.status IN ('PENDING', 'DEAD')
         AND (j.claimedAt IS NULL
              OR (j.type <> 'RECONCILE' AND j.claimedAt < :leaseCutoff)
              OR (j.type = 'RECONCILE' AND j.claimedAt < :reconcileCutoff))
      """)
  int forceDue(
      @Param("id") String id,
      @Param("now") Instant now,
      @Param("leaseCutoff") Instant leaseCutoff,
      @Param("reconcileCutoff") Instant reconcileCutoff);

  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      """
      UPDATE JobEntity j SET j.status = 'DEAD', j.lastError = :note
       WHERE j.id = :id AND j.status = 'PENDING'
         AND (j.claimedAt IS NULL
              OR (j.type <> 'RECONCILE' AND j.claimedAt < :leaseCutoff)
              OR (j.type = 'RECONCILE' AND j.claimedAt < :reconcileCutoff))
      """)
  int giveUp(
      @Param("id") String id,
      @Param("note") String note,
      @Param("leaseCutoff") Instant leaseCutoff,
      @Param("reconcileCutoff") Instant reconcileCutoff);

  long countByStatus(String status);

  // DONE rows are kept as history and only grow; the gauge is about the queue, not the archive.
  @Query(
      nativeQuery = true,
      value =
          """
          SELECT status, type, count(*) FROM payments.jobs
           WHERE status IN ('PENDING', 'DEAD')
           GROUP BY status, type
          """)
  List<Object[]> countByStatusAndType();

  long countByStatusAndNextRunAtBefore(String status, Instant before);
}
