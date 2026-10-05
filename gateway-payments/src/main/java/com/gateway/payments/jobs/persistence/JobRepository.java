package com.gateway.payments.jobs.persistence;

import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobQuery;
import com.gateway.payments.jobs.JobType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository {
  /**
   * {@code INSERT ... ON CONFLICT (type, ref_id) DO NOTHING} â€” one job per (type, ref). Returns
   * {@code true} iff this call inserted the row; the RECONCILE job always uses {@code ref_id =
   * "all"}, so a caller needs this to tell "I created the singleton" from "it was already there".
   */
  boolean enqueue(Job job);

  /** {@code FOR UPDATE SKIP LOCKED}; requires an active transaction. */
  default List<Job> claimDue(Instant now, int limit, Duration lease) {
    return claimDue(now, limit, lease, lease);
  }

  /**
   * {@code reconcileLease} applies to the RECONCILE singleton only: a run lasts minutes, not
   * seconds.
   */
  List<Job> claimDue(Instant now, int limit, Duration lease, Duration reconcileLease);

  void save(Job job);

  Optional<Job> findByTypeAndRef(JobType type, String refId);

  Optional<Job> findById(String id);

  /** {@link JobQuery}'s order: DEAD first, then attempts descending, then next_run_at. */
  List<Job> find(JobQuery query);

  /**
   * Back to PENDING and due {@code now}, {@code attempts} and {@code last_error} kept, so the
   * operator still sees what failed and how often. Refused (false) when a worker holds the job
   * inside its lease — {@code reconcileLease} for the RECONCILE singleton, as in {@link
   * #claimDue(Instant, int, Duration, Duration)} — or the id does not exist.
   */
  boolean forceDue(String id, Instant now, Duration lease, Duration reconcileLease);

  default boolean forceDue(String id, Instant now, Duration lease) {
    return forceDue(id, now, lease, lease);
  }

  /**
   * PENDING to DEAD with the operator's note as {@code last_error}. Refused (false) for a job that
   * is not PENDING, is held inside its lease, or does not exist.
   */
  boolean giveUp(String id, String note, Instant now, Duration lease, Duration reconcileLease);

  default boolean giveUp(String id, String note, Instant now, Duration lease) {
    return giveUp(id, note, now, lease, lease);
  }

  long countByStatus(String status);

  /** PENDING jobs whose {@code next_run_at} is before {@code before}: the runner is behind. */
  long countOverdue(Instant before);
}
