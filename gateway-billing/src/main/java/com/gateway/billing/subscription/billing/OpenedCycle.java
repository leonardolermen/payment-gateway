package com.gateway.billing.subscription.billing;

import com.gateway.billing.order.Order;
import com.gateway.billing.subscription.Subscription;

/**
 * The subscription as transaction 1 left it, and the invoice that cycle has to charge.
 *
 * @param resumed true when no period was opened: the current invoice came back because an earlier
 *     run committed transaction 1 and then failed, so what it left undone has to be finished
 */
public record OpenedCycle(Subscription subscription, Order invoice, boolean resumed) {

  public static OpenedCycle opened(Subscription subscription, Order invoice) {
    return new OpenedCycle(subscription, invoice, false);
  }

  public static OpenedCycle resumed(Subscription subscription, Order invoice) {
    return new OpenedCycle(subscription, invoice, true);
  }
}
