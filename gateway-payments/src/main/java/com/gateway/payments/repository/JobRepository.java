package com.gateway.payments.repository;

import com.gateway.payments.domain.Job;
import com.gateway.payments.domain.JobType;
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
  List<Job> claimDue(Instant now, int limit, Duration lease);

  void save(Job j);

  Optional<Job> findByTypeAndRef(JobType type, String refId);
}
