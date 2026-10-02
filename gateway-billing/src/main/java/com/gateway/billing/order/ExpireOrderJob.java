package com.gateway.billing.order;

import com.gateway.billing.BillingProperties;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobBackoff;
import com.gateway.payments.jobs.JobHandler;
import com.gateway.payments.jobs.JobType;
import java.time.Instant;

public class ExpireOrderJob implements JobHandler {
  private final OrderExpiration expiration;
  private final JobBackoff backoff;
  private final BillingProperties properties;

  public ExpireOrderJob(
      OrderExpiration expiration, JobBackoff backoff, BillingProperties properties) {
    this.expiration = expiration;
    this.backoff = backoff;
    this.properties = properties;
  }

  @Override
  public JobType type() {
    return JobType.EXPIRE_ORDER;
  }

  @Override
  public boolean run(String refId, Instant now) {
    return expiration.expireOne(refId, now);
  }

  @Override
  public Job afterFailure(Job job, Instant now, String error) {
    return backoff.retry(job, now, error);
  }

  /**
   * An active attempt is not a failure: it can outlive the order's expiry by days (its own payment
   * limit). Going through the backoff would spend retries and kill the job, leaving the order OPEN
   * forever with no order.expired; so it waits a fixed recheck, attempts untouched, never DEAD.
   */
  @Override
  public Job notYet(Job job, Instant now) {
    return new Job(
        job.id(),
        job.type(),
        job.refId(),
        now.plus(properties.orderExpiryRecheck()),
        job.attempts(),
        "PENDING",
        null,
        NOT_YET,
        job.createdAt());
  }
}
