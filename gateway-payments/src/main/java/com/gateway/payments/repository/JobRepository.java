package com.gateway.payments.repository;

import com.gateway.payments.domain.Job;
import com.gateway.payments.domain.JobType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface JobRepository {
  /** {@code INSERT ... ON CONFLICT (type, ref_id) DO NOTHING} — one job per (type, ref). */
  void enqueue(Job j);

  /** {@code FOR UPDATE SKIP LOCKED}; requires an active transaction. */
  List<Job> claimDue(Instant now, int limit, Duration lease);

  void save(Job j);

  Optional<Job> findByTypeAndRef(JobType type, String refId);
}
