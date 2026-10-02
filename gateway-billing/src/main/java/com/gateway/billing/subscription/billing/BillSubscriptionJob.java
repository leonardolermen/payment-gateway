package com.gateway.billing.subscription.billing;

import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobBackoff;
import com.gateway.payments.jobs.JobHandler;
import com.gateway.payments.jobs.JobType;
import java.time.Instant;

/**
 * Subscription billing lands in plan E task 9. Until then this owns BILL_SUBSCRIPTION so the job
 * registry starts, and a job that fires fails loudly and retries through the backoff instead of
 * reporting a cycle as billed when nothing was charged.
 */
public class BillSubscriptionJob implements JobHandler {
  private final JobBackoff backoff;

  public BillSubscriptionJob(JobBackoff backoff) {
    this.backoff = backoff;
  }

  @Override
  public JobType type() {
    return JobType.BILL_SUBSCRIPTION;
  }

  @Override
  public boolean run(String refId, Instant now) {
    throw new UnsupportedOperationException("subscription billing lands in plan E task 9");
  }

  @Override
  public Job afterFailure(Job job, Instant now, String error) {
    return backoff.retry(job, now, error);
  }
}
