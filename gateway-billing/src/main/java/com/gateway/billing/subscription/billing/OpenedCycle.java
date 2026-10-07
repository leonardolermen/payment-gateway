package com.gateway.billing.subscription.billing;

import com.gateway.billing.order.Order;
import com.gateway.billing.order.checkout.CheckoutToken;
import com.gateway.billing.subscription.Subscription;

/**
 * The subscription as transaction 1 left it, and the invoice that cycle has to charge.
 *
 * @param resumed true when no period was opened: the current invoice came back because an earlier
 *     run committed transaction 1 and then failed, so what it left undone has to be finished
 * @param token the plain checkout token of an invoice created by this run, for its event; null when
 *     the invoice was not created here (resumed, or found already inserted), since only its hash
 *     was ever stored
 */
public record OpenedCycle(
    Subscription subscription, Order invoice, boolean resumed, CheckoutToken token) {

  public static OpenedCycle opened(Subscription subscription, Order invoice, CheckoutToken token) {
    return new OpenedCycle(subscription, invoice, false, token);
  }

  public static OpenedCycle resumed(Subscription subscription, Order invoice) {
    return new OpenedCycle(subscription, invoice, true, null);
  }
}
