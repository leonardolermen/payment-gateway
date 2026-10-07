package com.gateway.payments.jobs;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.LogContext;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.jobs.persistence.JobRepository;
import java.time.Clock;
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

  private final JobRepository jobs;
  private final JobHandlers handlers;
  private final PaymentsProperties properties;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public JobRunner(
      JobRepository jobs,
      JobHandlers handlers,
      PaymentsProperties properties,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.jobs = jobs;
    this.handlers = handlers;
    this.properties = properties;
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
            transaction ->
                jobs.claimDue(now, BATCH, properties.jobLease(), properties.reconcileLease()));
    if (claimed == null) {
      return 0;
    }
    for (Job job : claimed) {
      try (LogContext context = LogContext.with("job", job.type().name()).and("jobId", job.id())) {
        JobHandler handler = handlers.forType(job.type());
        Job next;
        try {
          boolean done = handler.run(job.refId(), now);
          next = done ? job.done() : handler.notYet(job, now);
        } catch (RuntimeException e) {
          log.warn(
              "job {} {} for {} failed (attempt {})",
              job.type(),
              job.id(),
              job.refId(),
              job.attempts() + 1,
              e);
          next = handler.afterFailure(job, now, truncate(describe(e)));
        }
        Job toSave = handler.finish(job, next, now);
        transactionTemplate.executeWithoutResult(transaction -> jobs.save(toSave));
      }
    }
    return claimed.size();
  }

  /**
   * A provider's message alone ("down", "Bad Gateway") does not say whether support should wait or
   * call the bank; the code (UNAVAILABLE, AUTH, ...) is what they triage last_error by.
   */
  private static String describe(RuntimeException e) {
    if (e instanceof ProviderException providerException) {
      return "ProviderException "
          + providerException.code()
          + ": "
          + providerException.getMessage();
    }
    return e.getClass().getSimpleName() + ": " + e.getMessage();
  }

  private static String truncate(String message) {
    return message == null || message.length() <= 500 ? message : message.substring(0, 500);
  }
}
