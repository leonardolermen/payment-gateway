package com.gateway.billing.subscription.billing;

import com.gateway.billing.order.Order;
import com.gateway.billing.subscription.Subscription;

/** The subscription as transaction 1 left it, and the invoice that cycle has to charge. */
public record OpenedCycle(Subscription subscription, Order invoice) {}
