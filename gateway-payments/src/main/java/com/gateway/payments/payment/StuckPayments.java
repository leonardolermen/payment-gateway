package com.gateway.payments.payment;

import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.persistence.PaymentRepository;
import java.time.Instant;
import java.util.List;

/**
 * What the sweeps should have moved and did not, for the operator to look at. The thresholds are
 * the sweeps' own ({@code stuckCreatedAfter}, {@code expirationGrace}): a row the sweep has not
 * reached yet is not stuck, it is merely early.
 */
public class StuckPayments {
  private static final int LIMIT = 100;

  private final PaymentRepository payments;
  private final PaymentsProperties properties;

  public StuckPayments(PaymentRepository payments, PaymentsProperties properties) {
    this.payments = payments;
    this.properties = properties;
  }

  /** CREATED past {@code stuckCreatedAfter}, oldest first: the bank's answer never arrived. */
  public List<Payment> createdTooLong(Instant now) {
    return payments.findByStatusCreatedBefore(PaymentStatus.CREATED, createdCutoff(now), LIMIT);
  }

  /** PENDING past its expiry plus the grace, oldest expiry first: the expire job did not run. */
  public List<Payment> pendingPastExpiry(Instant now) {
    return payments.findPendingOlderThan(expiryCutoff(now), LIMIT);
  }

  public StuckCounts counts(Instant now) {
    long createdTooLong =
        payments.countByStatusCreatedBefore(PaymentStatus.CREATED, createdCutoff(now));
    long pendingPastExpiry = payments.countPendingOlderThan(expiryCutoff(now));

    return new StuckCounts(createdTooLong, pendingPastExpiry);
  }

  private Instant createdCutoff(Instant now) {
    return now.minus(properties.stuckCreatedAfter());
  }

  private Instant expiryCutoff(Instant now) {
    return now.minus(properties.expirationGrace());
  }

  public record StuckCounts(long createdTooLong, long pendingPastExpiry) {}
}
