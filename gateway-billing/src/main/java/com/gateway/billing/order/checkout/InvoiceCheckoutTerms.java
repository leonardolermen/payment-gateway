package com.gateway.billing.order.checkout;

import com.gateway.billing.order.Order;
import java.util.Optional;

/**
 * Port: the checkout asks, the subscription package answers, so {@code order/} never reads a
 * subscription. Like {@code InvoiceSettlementHook}, the other way an order hears about its
 * subscription.
 */
public interface InvoiceCheckoutTerms {
  /** Empty for a standalone order. */
  Optional<InvoiceTerms> of(Order order);
}
