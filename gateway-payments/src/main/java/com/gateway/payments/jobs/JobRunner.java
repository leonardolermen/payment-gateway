package com.gateway.payments.jobs;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.inbox.WebhookInboxService;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.ExpirationService;
import com.gateway.payments.payment.boleto.BoletoPollingService;
import com.gateway.payments.reconciliation.ReconciliationService;
import com.gateway.payments.refund.RefundPollingService;
import com.gateway.payments.refund.RefundService;
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
  private final BoletoPollingService boletoPolling;
  private final RefundService refunds;
  private final ReconciliationService reconciliation;
  private final PaymentsProperties props;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public JobRunner(
      JobRepository jobs,
      WebhookInboxService inbox,
      ExpirationService expiration,
      RefundPollingService polling,
      BoletoPollingService boletoPolling,
      RefundService refunds,
      ReconciliationService reconciliation,
      PaymentsProperties props,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.jobs = jobs;
    this.inbox = inbox;
    this.expiration = expiration;
    this.polling = polling;
    this.boletoPolling = boletoPolling;
    this.refunds = refunds;
    this.reconciliation = reconciliation;
    this.props = props;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
  }

  /** Creates the RECONCILE singleton if it is not there yet; safe to call on every boot. */
  public void scheduleReconciliation() {
    if (!Boolean.TRUE.equals(
        transactionTemplate.execute(transaction -> jobs.enqueue(Job.reconcile(clock))))) {
      log.debug("reconcile job already scheduled");
    }
  }

  /** Returns how many jobs were claimed (not how many succeeded) — callers loop until 0. */
  public int runDue(Instant now) {
    List<Job> claimed =
        transactionTemplate.execute(
            transaction -> jobs.claimDue(now, BATCH, props.jobLease(), props.reconcileLease()));
    if (claimed == null) {
      return 0;
    }
    for (Job job : claimed) {
      Job next;
      try {
        boolean done = run(job, now);
        next = done ? job.done() : retry(job, now, "not settled yet", false);
      } catch (RuntimeException e) {
        log.warn(
            "job {} {} for {} failed (attempt {})",
            job.type(),
            job.id(),
            job.refId(),
            job.attempts() + 1,
            e);
        next = retry(job, now, truncate(describe(e)), true);
      }
      if (job.type() == JobType.POLL_REFUND && "DEAD".equals(next.status())) {
        try {
          refunds.giveUp(job.refId());
        } catch (RuntimeException e) {
          // Stays DEAD with the error; the refund is still PROCESSING and visible to support.
          log.error("could not give up on refund {}", job.refId(), e);
        }
      }
      if (job.type() == JobType.RECONCILE) {
        // A periodic singleton: ON CONFLICT DO NOTHING means a DONE or DEAD row would stop
        // reconciliation forever, since nothing could enqueue it again. Always back to PENDING,
        // attempts untouched; a failure is only logged (and kept in last_error).
        String error = next.lastError();
        next =
            new Job(
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
      Job toSave = next;
      transactionTemplate.executeWithoutResult(transaction -> jobs.save(toSave));
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
        expiration.sweepStuckCreated(now);
        reconciliation.reconcileAll(now);
        yield true;
      }
      case POLL_BOLETO -> boletoPolling.check(job.refId(), EventSource.PROVIDER_POLL);
    };
  }

  /**
   * POLL_REFUND polls every 5 minutes for {@code refundPollMaxAttempts} (288 = 24 h): exponential
   * backoff with 8 attempts gave up after about 4 h, while the bank may take a day to settle a
   * devolucao. POLL_BOLETO looks again every {@code boletoPollEvery} (6 h) while the bank says
   * open; a failure (bank unreachable) backs off like any job but never waits longer than the poll
   * period itself. Everything else backs off exponentially up to {@code jobMaxAttempts}.
   */
  private Job retry(Job job, Instant now, String error, boolean failed) {
    if (job.type() == JobType.POLL_REFUND) {
      return job.reschedule(
          now.plus(RefundService.POLL_EVERY), error, props.refundPollMaxAttempts());
    }
    if (job.type() == JobType.POLL_BOLETO) {
      Duration wait =
          failed && backoff(job.attempts()).compareTo(props.boletoPollEvery()) < 0
              ? backoff(job.attempts())
              : props.boletoPollEvery();
      return job.reschedule(now.plus(wait), error, props.boletoPollMaxAttempts());
    }
    return job.reschedule(now.plus(backoff(job.attempts())), error, props.jobMaxAttempts());
  }

  /**
   * 1 min, 2 min, 4 min, ... capped at 24 h. {@code attempts} is the count BEFORE this failure
   * ({@code claimDue} does not increment it; {@code reschedule} does), so the first retry waits 1
   * min.
   */
  public static Duration backoff(int attempts) {
    if (attempts >= 11) return MAX_BACKOFF; // 2^11 min > 24 h, and avoids shifting into overflow
    Duration d = Duration.ofMinutes(1L << attempts);
    return d.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : d;
  }

  /**
   * A provider's message alone ("down", "Bad Gateway") does not say whether support should wait or
   * call the bank; the code (UNAVAILABLE, AUTH, ...) is what they triage last_error by.
   */
  private static String describe(RuntimeException e) {
    if (e instanceof ProviderException pe) {
      return "ProviderException " + pe.code() + ": " + pe.getMessage();
    }
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }

  private static String truncate(String s) {
    return s == null || s.length() <= 500 ? s : s.substring(0, 500);
  }
}
