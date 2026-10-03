package com.gateway.billing.order;

import com.gateway.billing.BillingProperties;
import com.gateway.billing.order.persistence.OrderRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One attempt in flight per order, even past a job lease. The partial unique index covers only
 * active payment statuses: once a card payment is COMPLETED and the relay has not yet marked the
 * order PAID, two cycle or dunning runs could both read "not paid" and charge twice. The marker
 * expires after {@code attemptLock}, so a process that dies mid-call never locks the order.
 *
 * <p>Its own class rather than two more constructor arguments on OrderAttemptService, which would
 * push it past the dependency limit for what is one concern.
 */
public class AttemptSlot {
  private static final Logger log = LoggerFactory.getLogger(AttemptSlot.class);

  private final OrderRepository orders;
  private final BillingProperties properties;
  private final Clock clock;

  public AttemptSlot(OrderRepository orders, BillingProperties properties, Clock clock) {
    this.orders = orders;
    this.properties = properties;
    this.clock = clock;
  }

  /** The instant written, which the release needs; empty when another caller holds the slot. */
  public Optional<Instant> claim(String orderId) {
    // Micros: Postgres stores no more, and the release compares this instant for equality.
    Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
    if (!orders.claimAttempt(orderId, now, properties.attemptLock())) {
      return Optional.empty();
    }

    return Optional.of(now);
  }

  /**
   * Never throws: it runs in a finally, and a database blip here must not replace the attempt's own
   * outcome or exception. A marker left behind expires by itself after the lock.
   */
  public void release(String orderId, Instant claimedAt) {
    try {
      orders.releaseAttempt(orderId, claimedAt);
    } catch (RuntimeException e) {
      // The class only: a message may carry SQL.
      log.warn(
          "attempt marker of order {} not released ({}); it expires by itself",
          orderId,
          e.getClass().getSimpleName());
    }
  }
}
