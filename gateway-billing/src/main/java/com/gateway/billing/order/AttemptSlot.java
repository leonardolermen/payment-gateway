package com.gateway.billing.order;

import com.gateway.billing.BillingProperties;
import com.gateway.billing.order.persistence.OrderRepository;
import java.time.Clock;

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
  private final OrderRepository orders;
  private final BillingProperties properties;
  private final Clock clock;

  public AttemptSlot(OrderRepository orders, BillingProperties properties, Clock clock) {
    this.orders = orders;
    this.properties = properties;
    this.clock = clock;
  }

  public boolean claim(String orderId) {
    return orders.claimAttempt(orderId, clock.instant(), properties.attemptLock());
  }

  public void release(String orderId) {
    orders.releaseAttempt(orderId);
  }
}
