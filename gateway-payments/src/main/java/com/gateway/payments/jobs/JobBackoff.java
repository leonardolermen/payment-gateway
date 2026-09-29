package com.gateway.payments.jobs;

import com.gateway.payments.PaymentsProperties;
import java.time.Duration;
import java.time.Instant;

/** The generic retry: exponential backoff up to {@code jobMaxAttempts}. */
public class JobBackoff {
  private static final Duration MAX_BACKOFF = Duration.ofHours(24);

  private final PaymentsProperties properties;

  public JobBackoff(PaymentsProperties properties) {
    this.properties = properties;
  }

  public Job retry(Job job, Instant now, String error) {
    return job.reschedule(now.plus(backoff(job.attempts())), error, properties.jobMaxAttempts());
  }

  /**
   * 1 min, 2 min, 4 min, ... capped at 24 h. {@code attempts} is the count BEFORE this failure
   * ({@code claimDue} does not increment it; {@code reschedule} does), so the first retry waits 1
   * min.
   */
  public static Duration backoff(int attempts) {
    if (attempts >= 11) {
      return MAX_BACKOFF; // 2^11 min > 24 h, and avoids shifting into overflow
    }
    Duration wait = Duration.ofMinutes(1L << attempts);
    return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
  }
}
