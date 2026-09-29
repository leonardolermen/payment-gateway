package com.gateway.payments.jobs;

import com.gateway.payments.payment.StuckCreatedSweep;
import com.gateway.payments.reconciliation.CardReconciliation;
import com.gateway.payments.reconciliation.ReconciliationService;
import java.time.Duration;
import java.time.Instant;

/**
 * The periodic pass: the stuck-CREATED sweep, then reconciliation against the bank and against the
 * acquirer.
 */
public class ReconcileJob implements JobHandler {
  /** RECONCILE is a singleton row that never finishes; this is its period. */
  static final Duration RECONCILE_EVERY = Duration.ofMinutes(15);

  private final StuckCreatedSweep sweep;
  private final ReconciliationService reconciliation;
  private final CardReconciliation cardReconciliation;
  private final JobBackoff backoff;

  public ReconcileJob(
      StuckCreatedSweep sweep,
      ReconciliationService reconciliation,
      CardReconciliation cardReconciliation,
      JobBackoff backoff) {
    this.sweep = sweep;
    this.reconciliation = reconciliation;
    this.cardReconciliation = cardReconciliation;
    this.backoff = backoff;
  }

  @Override
  public JobType type() {
    return JobType.RECONCILE;
  }

  @Override
  public boolean run(String refId, Instant now) {
    sweep.sweepStuckCreated(now);
    reconciliation.reconcileAll(now);
    cardReconciliation.reconcile(now);
    return true;
  }

  @Override
  public Job afterFailure(Job job, Instant now, String error) {
    return backoff.retry(job, now, error);
  }

  /**
   * Here and not in {@link #afterFailure} or {@link #notYet}: it applies to a run that finished
   * too, not only to one that failed. The error the backoff recorded is the one thing kept from
   * {@code next}.
   */
  @Override
  public Job finish(Job job, Job next, Instant now) {
    // A periodic singleton: ON CONFLICT DO NOTHING means a DONE or DEAD row would stop
    // reconciliation forever, since nothing could enqueue it again. Always back to PENDING,
    // attempts untouched; a failure is only logged (and kept in last_error).
    String error = next.lastError();
    return new Job(
        job.id(),
        job.type(),
        job.refId(),
        now.plus(RECONCILE_EVERY),
        0,
        "PENDING",
        null,
        error,
        job.createdAt());
  }
}
