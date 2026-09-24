package com.gateway.payments.service;

import com.gateway.payments.domain.Job;
import com.gateway.payments.domain.JobType;
import com.gateway.payments.repository.JobRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs due jobs. The claim is a short transaction ({@code SKIP LOCKED} + lease); the work runs
 * OUTSIDE it, because most of it calls a bank, and the lease — not a held row lock — is what keeps
 * a second worker off a job that is still running.
 */
public class JobRunner {
  private static final Logger log = LoggerFactory.getLogger(JobRunner.class);
  private static final int BATCH = 20;
  /** RECONCILE is a singleton row that never finishes; this is its period. */
  static final Duration RECONCILE_EVERY = Duration.ofMinutes(15);
  private static final Duration MAX_BACKOFF = Duration.ofHours(24);

  private final JobRepository jobs;
  private final WebhookInboxService inbox;
  private final ExpirationService expiration;
  private final RefundPollingService polling;
  private final ReconciliationService reconciliation;
  private final PaymentsProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public JobRunner(
      JobRepository jobs,
      WebhookInboxService inbox,
      ExpirationService expiration,
      RefundPollingService polling,
      ReconciliationService reconciliation,
      PaymentsProperties props,
      TransactionTemplate tx,
      Clock clock) {
    this.jobs = jobs;
    this.inbox = inbox;
    this.expiration = expiration;
    this.polling = polling;
    this.reconciliation = reconciliation;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  /** Creates the RECONCILE singleton if it is not there yet; safe to call on every boot. */
  public void scheduleReconciliation() {
    if (!Boolean.TRUE.equals(tx.execute(s -> jobs.enqueue(Job.reconcile(clock))))) {
      log.debug("reconcile job already scheduled");
    }
  }

  /** Returns how many jobs were claimed (not how many succeeded) — callers loop until 0. */
  public int runDue(Instant now) {
    List<Job> claimed = tx.execute(s -> jobs.claimDue(now, BATCH, props.jobLease()));
    if (claimed == null) return 0;
    for (Job job : claimed) {
      Job next;
      try {
        boolean done = run(job, now);
        next = done ? job.done() : job.reschedule(now.plus(backoff(job.attempts())), "not settled yet", props.jobMaxAttempts());
      } catch (RuntimeException e) {
        log.warn("job {} {} for {} failed (attempt {})", job.type(), job.id(), job.refId(), job.attempts() + 1, e);
        next = job.reschedule(now.plus(backoff(job.attempts())), truncate(e.getClass().getSimpleName() + ": " + e.getMessage()), props.jobMaxAttempts());
      }
      if (job.type() == JobType.RECONCILE) {
        // A periodic singleton: ON CONFLICT DO NOTHING means a DONE or DEAD row would stop
        // reconciliation forever, since nothing could enqueue it again. Always back to PENDING,
        // attempts untouched; a failure is only logged (and kept in last_error).
        String error = next.lastError();
        next = new Job(job.id(), job.type(), job.refId(), now.plus(RECONCILE_EVERY), 0, "PENDING", null, error, job.createdAt());
      }
      Job toSave = next;
      tx.executeWithoutResult(s -> jobs.save(toSave));
    }
    return claimed.size();
  }

  private boolean run(Job job, Instant now) {
    return switch (job.type()) {
      case PROCESS_WEBHOOK -> {
        inbox.process(job.refId());
        yield true;
      }
      case EXPIRE_PAYMENT -> {
        expiration.expireOne(job.refId(), now);
        yield true;
      }
      case POLL_REFUND -> polling.poll(job.refId());
      case RECONCILE -> {
        reconciliation.reconcileAll(now);
        yield true;
      }
    };
  }

  /** 1 min, 2 min, 4 min, ... capped at 24 h. */
  static Duration backoff(int attempts) {
    if (attempts >= 11) return MAX_BACKOFF; // 2^11 min > 24 h, and avoids shifting into overflow
    Duration d = Duration.ofMinutes(1L << attempts);
    return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
  }

  private static String truncate(String s) {
    return s == null || s.length() <= 500 ? s : s.substring(0, 500);
  }
}
