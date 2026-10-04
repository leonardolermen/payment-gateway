package com.gateway.billing.subscription.billing;

import com.gateway.billing.order.Order;
import com.gateway.billing.subscription.Subscription;
import java.time.Instant;

/**
 * Where a cycle that could not be charged hands over to dunning (spec §7). A port so the cycle
 * lands before the retry schedule does: plan E task 10 replaces the no-op bean.
 */
public interface DunningStarter {
  /** Same transaction as the PAST_DUE write. {@code paymentId} is null when nothing was tried. */
  void firstFailure(Subscription subscription, Order invoice, String paymentId, Instant now);
}
