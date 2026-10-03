package com.gateway.billing.subscription.billing;

import com.gateway.billing.order.Order;
import com.gateway.billing.subscription.DunningAttempt;
import com.gateway.billing.subscription.DunningOutcome;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.persistence.DunningAttemptRepository;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.persistence.JobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The attempt rows of dunning and the jobs that run them. A row and its job are written together,
 * in the caller's transaction: a row without a job would never run, a job without a row would run
 * nothing.
 */
public class DunningLedger {
  private final DunningAttemptRepository attempts;
  private final JobRepository jobs;
  private final DunningSchedule schedule;
  private final Clock clock;

  public DunningLedger(
      DunningAttemptRepository attempts,
      JobRepository jobs,
      DunningSchedule schedule,
      Clock clock) {
    this.attempts = attempts;
    this.jobs = jobs;
    this.schedule = schedule;
    this.clock = clock;
  }

  public Optional<DunningAttempt> find(String attemptId) {
    return attempts.findById(attemptId);
  }

  public Optional<DunningAttempt> pendingFor(String orderId) {
    return attempts.findPendingByOrder(orderId);
  }

  /** Oldest first. */
  public List<DunningAttempt> attemptsFor(Order invoice) {
    return attempts.findBySubscription(invoice.subscriptionId()).stream()
        .filter(attempt -> attempt.orderId().equals(invoice.id()))
        .toList();
  }

  /** Whether a retry is still scheduled for another invoice of the same subscription. */
  public boolean isChasingAnotherInvoice(Order invoice) {
    return attempts.hasPendingForOtherOrder(invoice.subscriptionId(), invoice.id());
  }

  /** Attempt 1; false when the schedule has no retry day at all. Requires a transaction. */
  public boolean start(Subscription subscription, Order invoice, Instant now) {
    return scheduleNext(subscription, invoice, 0, now);
  }

  /** False when every retry day is used: the caller decides what exhaustion means. */
  public boolean scheduleNext(
      Subscription subscription, Order invoice, int attemptsSoFar, Instant now) {
    Optional<Instant> next = schedule.nextAfter(attemptsSoFar, now);
    if (next.isEmpty()) {
      return false;
    }

    DunningAttempt attempt =
        DunningAttempt.scheduled(subscription.id(), invoice.id(), attemptsSoFar + 1, next.get());
    attempts.insert(attempt);
    jobs.enqueue(Job.dunningRetry(attempt.id(), next.get(), clock));

    return true;
  }

  /** When the retry after this one would run, if there is one. */
  public Optional<Instant> retryAfter(DunningAttempt attempt, Instant now) {
    return schedule.nextAfter(attempt.attempt(), now);
  }

  public void close(DunningAttempt attempt, DunningOutcome outcome, String paymentId, Instant now) {
    attempts.update(attempt.closed(outcome, paymentId, now));
  }
}
