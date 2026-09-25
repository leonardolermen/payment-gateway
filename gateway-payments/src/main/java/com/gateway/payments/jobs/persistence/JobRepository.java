package com.gateway.payments.jobs.persistence;

import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository {
  /**
   * {@code INSERT ... ON CONFLICT (type, ref_id) DO NOTHING} — one job per (type, ref). Returns
   * {@code true} iff this call inserted the row; the RECONCILE job always uses {@code ref_id =
   * "all"}, so a caller needs this to tell "I created the singleton" from "it was already there".
   */
  boolean enqueue(Job j);

  /** {@code FOR UPDATE SKIP LOCKED}; requires an active transaction. */
  default List<Job> claimDue(Instant now, int limit, Duration lease) {
    return claimDue(now, limit, lease, lease);
  }

  /** {@code reconcileLease} applies to the RECONCILE singleton only: a run lasts minutes, not seconds. */
  List<Job> claimDue(Instant now, int limit, Duration lease, Duration reconcileLease);

  void save(Job j);

  Optional<Job> findByTypeAndRef(JobType type, String refId);
}
