package com.gateway.payments.jobs;

import java.time.Instant;

/**
 * What one {@link JobType} does and how it comes back. {@link JobRunner} claims, leases and saves;
 * everything that differs by type lives here, so the runner never switches on it.
 */
public interface JobHandler {
  JobType type();

  /** true = done, false = the job asks to run again. */
  boolean run(String refId, Instant now);

  /** The last_error a "not yet" leaves on the row; it is not a failure, only a reason to wait. */
  String NOT_YET = "not settled yet";

  /** How this job comes back after {@link #run} threw. */
  Job afterFailure(Job job, Instant now, String error);

  /**
   * How this job comes back after {@link #run} answered false. Most types wait as for a failure.
   */
  default Job notYet(Job job, Instant now) {
    return afterFailure(job, now, NOT_YET);
  }

  /**
   * The row about to be saved, after {@code next} was decided either way. Most types save it as it
   * is; a type that reacts to its own outcome (a DEAD refund poll, the periodic reconcile)
   * overrides this.
   */
  default Job finish(Job job, Job next, Instant now) {
    return next;
  }
}
