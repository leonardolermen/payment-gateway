package com.gateway.app.jobs;

import com.gateway.payments.service.ExpirationService;
import com.gateway.payments.service.IdempotencyService;
import com.gateway.payments.service.JobRunner;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The clock that drives the payments module. The module has the work ({@link JobRunner} and the
 * services) but no scheduler of its own: when to run is a deployment decision, and a
 * {@code @Scheduled} inside a library module would start firing in every test context that imports it.
 *
 * <p>Deliberately NOT here: {@code ExpirationService.sweepStuckCreated}, which the RECONCILE job
 * already runs every 15 minutes (see {@code JobRunner.run}); scheduling it twice would only race
 * against itself.
 */
@Component
public class JobScheduler {
  private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

  private final JobRunner jobs;
  private final IdempotencyService idempotency;
  private final ExpirationService expiration;
  private final Clock clock;

  public JobScheduler(JobRunner jobs, IdempotencyService idempotency, ExpirationService expiration, Clock clock) {
    this.jobs = jobs;
    this.idempotency = idempotency;
    this.expiration = expiration;
    this.clock = clock;
  }

  /** Batches per tick: 20 x 20 jobs. A backlog beyond that waits one poll interval instead of pinning this thread. */
  private static final int MAX_BATCHES_PER_TICK = 20;

  /** Drains due jobs in batches: {@code runDue} returns how many it claimed, 0 means caught up. */
  @Scheduled(fixedDelayString = "${gateway.payments.jobs-poll-ms:2000}")
  public void runDueJobs() {
    for (int i = 0; i < MAX_BATCHES_PER_TICK && jobs.runDue(clock.instant()) > 0; i++) {
      // each batch is its own short claim transaction
    }
  }

  /** Idempotent (ON CONFLICT DO NOTHING); the cron only re-creates the singleton if it ever vanished. */
  @Scheduled(cron = "0 */15 * * * *")
  public void scheduleReconciliation() {
    jobs.scheduleReconciliation();
  }

  @Scheduled(cron = "0 7 * * * *")
  public void purgeIdempotencyKeys() {
    int purged = idempotency.purgeExpired(clock.instant());
    if (purged > 0) log.info("purged {} expired idempotency keys", purged);
  }

  /**
   * The safety net under the per-payment EXPIRE_PAYMENT job: that job is the normal path, this sweep
   * catches a PENDING charge whose job went DEAD or was never enqueued.
   */
  @Scheduled(fixedDelayString = "${gateway.payments.stuck-sweep-ms:60000}")
  public void expireOverdue() {
    expiration.expireDue(clock.instant());
  }
}
