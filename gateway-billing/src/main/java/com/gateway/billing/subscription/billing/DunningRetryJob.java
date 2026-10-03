package com.gateway.billing.subscription.billing;

import com.gateway.billing.BillingProperties;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobBackoff;
import com.gateway.payments.jobs.JobHandler;
import com.gateway.payments.jobs.JobType;
import java.time.Instant;

public class DunningRetryJob implements JobHandler {
  private final Dunning dunning;
  private final BillingJobRetry retry;
  private final BillingProperties properties;

  public DunningRetryJob(Dunning dunning, JobBackoff backoff, BillingProperties properties) {
    this.dunning = dunning;
    this.retry = new BillingJobRetry(backoff, properties);
    this.properties = properties;
  }

  @Override
  public JobType type() {
    return JobType.DUNNING_RETRY;
  }

  @Override
  public boolean run(String refId, Instant now) {
    return dunning.retryOne(refId, now);
  }

  @Override
  public Job afterFailure(Job job, Instant now, String error) {
    return retry.afterFailure(job, now, error);
  }

  /**
   * An attempt still live on the invoice (a boleto keeps a week of grace past its deadline) is not
   * a failure: through the backoff it would spend the retries and go DEAD, and the invoice would
   * never be retried again. It waits the same fixed recheck as an order's expiry, attempts
   * untouched.
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
