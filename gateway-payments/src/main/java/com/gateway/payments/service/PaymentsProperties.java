package com.gateway.payments.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables of the payments module. Every field has a default so the module runs with no
 * {@code gateway.payments.*} keys at all — a missing key must not turn into a zero-second expiry or
 * a zero-length lease.
 */
@ConfigurationProperties("gateway.payments")
public record PaymentsProperties(
    int defaultExpiresInSeconds,
    Duration expirationGrace,
    Duration reconciliationLookback,
    Duration reconciliationMinAge,
    Duration idempotencyTtl,
    int jobMaxAttempts,
    Duration jobLease,
    Duration outboxLease,
    Duration stuckCreatedAfter,
    int refundPollMaxAttempts,
    Duration refundNotFoundGrace,
    Duration reconcileLease) {

  public PaymentsProperties {
    if (defaultExpiresInSeconds <= 0) defaultExpiresInSeconds = 3600;
    if (expirationGrace == null) expirationGrace = Duration.ofMinutes(5);
    if (reconciliationLookback == null) reconciliationLookback = Duration.ofHours(48);
    if (reconciliationMinAge == null) reconciliationMinAge = Duration.ofMinutes(10);
    if (idempotencyTtl == null) idempotencyTtl = Duration.ofHours(24);
    if (jobMaxAttempts <= 0) jobMaxAttempts = 8;
    if (jobLease == null) jobLease = Duration.ofMinutes(2);
    if (outboxLease == null) outboxLease = Duration.ofMinutes(1);
    // Well above the bank's recommended 30 s client timeout: a CREATED younger than this may still
    // have its createCharge call in flight.
    if (stuckCreatedAfter == null) stuckCreatedAfter = Duration.ofMinutes(10);
    // 288 polls x 5 min = 24 h, the bank's own horizon for settling a devolucao.
    if (refundPollMaxAttempts <= 0) refundPollMaxAttempts = 288;
    // A refund PUT that timed out or got a 503 may or may not have landed. The bank answers GET
    // /devolucao within seconds of accepting one, so 30 min of "not found" means it never landed.
    if (refundNotFoundGrace == null) refundNotFoundGrace = Duration.ofMinutes(30);
    // RECONCILE walks up to 1000 payments with a bank call per merchant, each with a 30 s timeout;
    // the 2 min jobLease let a second worker reclaim a run still in progress.
    if (reconcileLease == null) reconcileLease = Duration.ofMinutes(10);
  }

  public static PaymentsProperties defaults() {
    return new PaymentsProperties(0, null, null, null, null, 0, null, null, null, 0, null, null);
  }
}
