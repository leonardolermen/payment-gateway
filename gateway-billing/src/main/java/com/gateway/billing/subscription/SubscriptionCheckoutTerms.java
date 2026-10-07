package com.gateway.billing.subscription;

import com.gateway.billing.order.Order;
import com.gateway.billing.order.checkout.InvoiceCheckoutTerms;
import com.gateway.billing.order.checkout.InvoiceTerms;
import com.gateway.billing.plan.PlanService;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import java.util.Optional;

/** An invoice of an INCOMPLETE subscription is the one that saves the card (spec 2026-10-07 §2). */
public class SubscriptionCheckoutTerms implements InvoiceCheckoutTerms {
  private final SubscriptionRepository subscriptions;
  private final PlanService plans;

  public SubscriptionCheckoutTerms(SubscriptionRepository subscriptions, PlanService plans) {
    this.subscriptions = subscriptions;
    this.plans = plans;
  }

  @Override
  public Optional<InvoiceTerms> of(Order order) {
    if (!order.isInvoice()) {
      return Optional.empty();
    }

    return subscriptions
        .find(order.merchantId(), order.subscriptionId())
        .map(
            subscription ->
                new InvoiceTerms(
                    plans.get(subscription.merchantId(), subscription.planId()).name(),
                    subscription.isIncomplete()));
  }
}
