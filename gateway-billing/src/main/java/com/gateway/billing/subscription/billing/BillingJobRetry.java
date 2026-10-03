package com.gateway.billing.subscription.billing;

import com.gateway.billing.BillingProperties;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobBackoff;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The retry of a billing job, which never goes DEAD. A BILL_SUBSCRIPTION row is the subscription's
 * only scheduler (one row per subscription for its whole life) and a DUNNING_RETRY row is the only
 * thing that chases its invoice: a DEAD row stops billing or chasing silently, which is money lost
 * with no error anywhere. So once the backoff would kill the job, it is re-pended at {@link
 * BillingProperties#orderExpiryRecheck()} with the attempts left as they are, and an ERROR log asks
 * for an operator instead.
 */
class BillingJobRetry {
  private static final Logger log = LoggerFactory.getLogger(BillingJobRetry.class);

  private final JobBackoff backoff;
  private final BillingProperties properties;

  BillingJobRetry(JobBackoff backoff, BillingProperties properties) {
    this.backoff = backoff;
    this.properties = properties;
  }

  Job afterFailure(Job job, Instant now, String error) {
    Job retried = backoff.retry(job, now, error);
    if (!"DEAD".equals(retried.status())) {
      return retried;
    }

    log.error(
        "billing job {} for {} keeps retrying hourly after {} attempts; an operator must look",
        job.type(),
        job.refId(),
        job.attempts());

    return new Job(
        job.id(),
        job.type(),
        job.refId(),
        now.plus(properties.orderExpiryRecheck()),
        job.attempts(),
        "PENDING",
        null,
        error,
        job.createdAt());
  }
}
