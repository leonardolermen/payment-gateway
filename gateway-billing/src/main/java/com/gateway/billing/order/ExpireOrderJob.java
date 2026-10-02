package com.gateway.billing.order;

import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobBackoff;
import com.gateway.payments.jobs.JobHandler;
import com.gateway.payments.jobs.JobType;
import java.time.Instant;

public class ExpireOrderJob implements JobHandler {
  private final OrderExpiration expiration;
  private final JobBackoff backoff;

  public ExpireOrderJob(OrderExpiration expiration, JobBackoff backoff) {
    this.expiration = expiration;
    this.backoff = backoff;
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
}
