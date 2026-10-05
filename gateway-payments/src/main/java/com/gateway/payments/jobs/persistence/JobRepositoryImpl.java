package com.gateway.payments.jobs.persistence;

import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobQuery;
import com.gateway.payments.jobs.JobType;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class JobRepositoryImpl implements JobRepository {
  private final JobJpaRepository jpa;

  @PersistenceContext private EntityManager entityManager;

  public JobRepositoryImpl(JobJpaRepository jpa) {
    this.jpa = jpa;
  }

  /**
   * {@code uq_jobs_type_ref} backs this: one job per (type, ref), same {@code ON CONFLICT DO
   * NOTHING} reasoning as {@code IdempotencyRepositoryImpl}.
   */
  @Override
  @Transactional
  public boolean enqueue(Job job) {
    int inserted =
        entityManager
            .createNativeQuery(
                """
                INSERT INTO payments.jobs (id, type, ref_id, next_run_at, attempts, status, claimed_at, last_error, created_at)
                VALUES (:id, :type, :refId, :nextRunAt, :attempts, :status, :claimedAt, :lastError, :createdAt)
                ON CONFLICT (type, ref_id) DO NOTHING
                """)
            .setParameter("id", job.id())
            .setParameter("type", job.type().name())
            .setParameter("refId", job.refId())
            .setParameter("nextRunAt", job.nextRunAt())
            .setParameter("attempts", job.attempts())
            .setParameter("status", job.status())
            .setParameter("claimedAt", job.claimedAt())
            .setParameter("lastError", job.lastError())
            .setParameter("createdAt", job.createdAt())
            .executeUpdate();
    return inserted > 0;
  }

  /**
   * {@code PESSIMISTIC_WRITE} + {@code SKIP LOCKED} (see {@code JobJpaRepository.selectDue}) keeps
   * two workers off the same row; it says nothing about a caller with no transaction at all, where
   * the lock would be released the instant it is taken. Copied from {@code
   * DeliveryRepositoryImpl.claimDue} in webhook-delivery, including this guard.
   */
  @Override
  public List<Job> claimDue(Instant now, int limit, Duration lease, Duration reconcileLease) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException(
          "claimDue must run inside a transaction: the SKIP LOCKED claim depends on it.");
    }
    List<JobEntity> due =
        jpa.selectDue(now, now.minus(lease), now.minus(reconcileLease), Limit.of(limit));
    due.forEach(job -> job.claimedAt = now);
    return due.stream().map(JobRepositoryImpl::toDomain).toList();
  }

  /**
   * No fencing token: a worker whose lease expired mid-run and gets reclaimed by another worker can
   * still land this write after the reclaimer's. This plan accepts that because every job handler
   * (expire, process-webhook, poll-refund, reconcile) is idempotent — replaying or double-applying
   * one changes nothing — so a late, superseded write is a no-op rather than corruption.
   */
  @Override
  @Transactional
  public void save(Job job) {
    JobEntity entity = jpa.findById(job.id()).orElseGet(JobEntity::new);
    entity.id = job.id();
    entity.type = job.type().name();
    entity.refId = job.refId();
    entity.nextRunAt = job.nextRunAt();
    entity.attempts = job.attempts();
    entity.status = job.status();
    entity.claimedAt = job.claimedAt();
    entity.lastError = job.lastError();
    entity.createdAt = job.createdAt();
    jpa.save(entity);
  }

  @Override
  public Optional<Job> findByTypeAndRef(JobType type, String refId) {
    return jpa.findByTypeAndRefId(type.name(), refId).map(JobRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Job> findById(String id) {
    return jpa.findById(id).map(JobRepositoryImpl::toDomain);
  }

  @Override
  public List<Job> find(JobQuery query) {
    StringBuilder jpql = new StringBuilder("SELECT j FROM JobEntity j");
    Map<String, Object> parameters = new LinkedHashMap<>();
    List<String> predicates = new ArrayList<>();

    if (query.status() != null) {
      predicates.add("j.status = :status");
      parameters.put("status", query.status());
    }
    if (query.type() != null) {
      predicates.add("j.type = :type");
      parameters.put("type", query.type().name());
    }

    if (!predicates.isEmpty()) {
      jpql.append(" WHERE ").append(String.join(" AND ", predicates));
    }
    jpql.append(
        " ORDER BY CASE j.status WHEN 'DEAD' THEN 0 ELSE 1 END, j.attempts DESC, j.nextRunAt ASC,"
            + " j.id");

    TypedQuery<JobEntity> typed = entityManager.createQuery(jpql.toString(), JobEntity.class);
    parameters.forEach(typed::setParameter);

    return typed.setMaxResults(query.limit()).getResultList().stream()
        .map(JobRepositoryImpl::toDomain)
        .toList();
  }

  /**
   * The transaction is on the JPA method, not here: the interface's three-argument default calls
   * this one on the target, past any proxy, and a {@code @Transactional} here would not apply.
   */
  @Override
  public boolean forceDue(String id, Instant now, Duration lease, Duration reconcileLease) {
    return jpa.forceDue(id, now, now.minus(lease), now.minus(reconcileLease)) == 1;
  }

  @Override
  public boolean giveUp(
      String id, String note, Instant now, Duration lease, Duration reconcileLease) {
    return jpa.giveUp(id, note, now.minus(lease), now.minus(reconcileLease)) == 1;
  }

  @Override
  public long countByStatus(String status) {
    return jpa.countByStatus(status);
  }

  @Override
  public long countOverdue(Instant before) {
    return jpa.countByStatusAndNextRunAtBefore("PENDING", before);
  }

  private static Job toDomain(JobEntity entity) {
    return new Job(
        entity.id,
        JobType.valueOf(entity.type),
        entity.refId,
        entity.nextRunAt,
        entity.attempts,
        entity.status,
        entity.claimedAt,
        entity.lastError,
        entity.createdAt);
  }
}
