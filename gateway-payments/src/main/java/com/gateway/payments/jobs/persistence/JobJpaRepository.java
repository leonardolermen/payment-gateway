package com.gateway.payments.jobs.persistence;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

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
}
