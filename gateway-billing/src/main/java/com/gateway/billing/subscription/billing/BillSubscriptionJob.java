package com.gateway.billing.subscription.billing;

import com.gateway.billing.BillingProperties;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobBackoff;
import com.gateway.payments.jobs.JobHandler;
import com.gateway.payments.jobs.JobType;
import java.time.Instant;
import java.util.Optional;

public class BillSubscriptionJob implements JobHandler {
  private final SubscriptionBilling billing;
  private final SubscriptionRepository subscriptions;
  private final BillingJobRetry retry;

  public BillSubscriptionJob(
      SubscriptionBilling billing,
      SubscriptionRepository subscriptions,
      JobBackoff backoff,
      BillingProperties properties) {
    this.billing = billing;
    this.subscriptions = subscriptions;
    this.retry = new BillingJobRetry(backoff, properties);
  }

  @Override
  public JobType type() {
    return JobType.BILL_SUBSCRIPTION;
  }

  @Override
  public boolean run(String refId, Instant now) {
    return billing.billOne(refId, now);
  }

  @Override
  public Job afterFailure(Job job, Instant now, String error) {
    return retry.afterFailure(job, now, error);
  }

  /**
   * One row per subscription for its whole life: jobs are unique on (type, ref_id) and enqueue is
   * ON CONFLICT DO NOTHING, so the cycle's enqueue of the next billing is swallowed by this very
   * row. Saved as DONE, the subscription would never bill again; so a finished run goes back to
   * PENDING at the subscription's next billing instant, and only one with none left is DONE.
   */
  @Override
  public Job finish(Job job, Job next, Instant now) {
    if (!"DONE".equals(next.status())) {
      return next;
    }

    Optional<Instant> nextBillingAt =
        subscriptions.findById(job.refId()).map(Subscription::nextBillingAt);
    if (nextBillingAt.isEmpty()) {
      return next;
    }

    return new Job(
        job.id(),
        job.type(),
        job.refId(),
        nextBillingAt.get(),
        0,
        "PENDING",
        null,
        null,
        job.createdAt());
  }
}
