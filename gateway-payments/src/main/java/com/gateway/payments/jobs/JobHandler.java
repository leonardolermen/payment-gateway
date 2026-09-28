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

  /** How this job comes back after a failure ({@code failed}) or a "not yet". */
  Job reschedule(Job job, Instant now, String error, boolean failed);

  /**
   * The row about to be saved, after {@code next} was decided either way. Most types save it as it
   * is; a type that reacts to its own outcome (a DEAD refund poll, the periodic reconcile)
   * overrides this.
   */
  default Job finish(Job job, Job next, Instant now) {
    return next;
  }
}
