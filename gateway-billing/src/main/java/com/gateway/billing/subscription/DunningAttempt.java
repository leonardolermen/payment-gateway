package com.gateway.billing.subscription;

import com.gateway.kernel.ids.Ulid;
import java.time.Instant;

/** {@code outcome} is null while the attempt is pending: scheduled, not yet run. */
public record DunningAttempt(
    String id,
    String subscriptionId,
    String orderId,
    int attempt,
    Instant scheduledAt,
    Instant ranAt,
    DunningOutcome outcome,
    String paymentId) {

  /** Pending: not run yet, no outcome. */
  public static DunningAttempt scheduled(
      String subscriptionId, String orderId, int attempt, Instant scheduledAt) {
    return new DunningAttempt(
        Ulid.next(), subscriptionId, orderId, attempt, scheduledAt, null, null, null);
  }

  public DunningAttempt closed(DunningOutcome outcome, String paymentId, Instant ranAt) {
    return new DunningAttempt(
        id, subscriptionId, orderId, attempt, scheduledAt, ranAt, outcome, paymentId);
  }
}
