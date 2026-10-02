package com.gateway.billing.subscription;

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
    String paymentId) {}
