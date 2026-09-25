package com.gateway.payments.domain;

import com.gateway.kernel.ids.Ulid;
import java.time.Clock;
import java.time.Instant;

/** A unit of background work polled off the {@code jobs} table by a worker. */
public record Job(
    String id,
    JobType type,
    String refId,
    Instant nextRunAt,
    int attempts,
    String status,
    Instant claimedAt,
    String lastError,
    Instant createdAt) {

  public static Job expireAt(String paymentId, Instant when, Clock clock) {
    return new Job(Ulid.next(), JobType.EXPIRE_PAYMENT, paymentId, when, 0, "PENDING", null, null, clock.instant());
  }

  public static Job processWebhook(String inboxId, Clock clock) {
    Instant now = clock.instant();
    return new Job(Ulid.next(), JobType.PROCESS_WEBHOOK, inboxId, now, 0, "PENDING", null, null, now);
  }

  public static Job pollRefund(String refundId, Instant firstAt, Clock clock) {
    return new Job(Ulid.next(), JobType.POLL_REFUND, refundId, firstAt, 0, "PENDING", null, null, clock.instant());
  }

  public static Job reconcile(Clock clock) {
    Instant now = clock.instant();
    return new Job(Ulid.next(), JobType.RECONCILE, "all", now, 0, "PENDING", null, null, now);
  }

  /** Bumps {@code attempts}; the job goes {@code DEAD} once it reaches {@code maxAttempts} instead of retrying forever. */
  public Job reschedule(Instant next, String error, int maxAttempts) {
    int newAttempts = attempts + 1;
    String newStatus = newAttempts >= maxAttempts ? "DEAD" : "PENDING";
    return new Job(id, type, refId, next, newAttempts, newStatus, null, error, createdAt);
  }

  public Job done() {
    return new Job(id, type, refId, nextRunAt, attempts, "DONE", claimedAt, lastError, createdAt);
  }
}
