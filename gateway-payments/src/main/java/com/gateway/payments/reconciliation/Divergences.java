package com.gateway.payments.reconciliation;

import com.gateway.kernel.ids.Ulid;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.reconciliation.persistence.ReconciliationDivergenceRepository;
import java.time.Clock;

/**
 * Opening a divergence: the record that the gateway and the bank disagree about a payment, for a
 * human to settle. Idempotent by payment — a second open while one is still OPEN is not a second
 * problem.
 */
public class Divergences {
  private static final int MAX_DETAIL = 500;

  private final ReconciliationDivergenceRepository divergences;
  private final Clock clock;

  public Divergences(ReconciliationDivergenceRepository divergences, Clock clock) {
    this.divergences = divergences;
    this.clock = clock;
  }

  /** Returns whether this call was the one that opened it. */
  public boolean open(Payment payment, String providerStatus, String detail) {
    String trimmed = detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);

    return divergences.openIfAbsent(
        new ReconciliationDivergence(
            Ulid.next(),
            payment.id(),
            payment.status().name(),
            providerStatus,
            trimmed,
            "OPEN",
            clock.instant()));
  }
}
