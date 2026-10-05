package com.gateway.payments.jobs;

import java.util.Set;

/**
 * The operator's view of the queue: every filter optional (null = any), DEAD first, then the jobs
 * that failed most, then the next to run. No cursor on purpose: that order has no stable key to
 * continue from, and an operator looks at the top of the queue, not at page 7 of it.
 */
public record JobQuery(String status, JobType type, int limit) {
  public static final int DEFAULT_LIMIT = 50;
  public static final int MAX_LIMIT = 200;

  private static final Set<String> STATUSES = Set.of("PENDING", "DONE", "DEAD");

  public JobQuery {
    if (status != null && !STATUSES.contains(status)) {
      throw new IllegalArgumentException("status must be one of PENDING, DONE, DEAD");
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
    }
  }
}
