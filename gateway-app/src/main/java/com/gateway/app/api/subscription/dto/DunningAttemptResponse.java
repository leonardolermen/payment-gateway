package com.gateway.app.api.subscription.dto;

import com.gateway.billing.subscription.DunningAttempt;
import java.time.Instant;

/** {@code outcome} and {@code ran_at} are null while the retry is still scheduled. */
public record DunningAttemptResponse(
    int attempt, Instant scheduledAt, Instant ranAt, String outcome, String paymentId) {

  public static DunningAttemptResponse from(DunningAttempt dunning) {
    return new DunningAttemptResponse(
        dunning.attempt(),
        dunning.scheduledAt(),
        dunning.ranAt(),
        dunning.outcome() == null ? null : dunning.outcome().name(),
        dunning.paymentId());
  }
}
