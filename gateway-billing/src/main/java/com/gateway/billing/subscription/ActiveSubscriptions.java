package com.gateway.billing.subscription;

import com.gateway.billing.customer.ActiveSubscriptionsCheck;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import com.gateway.kernel.ids.MerchantId;

/**
 * PAST_DUE counts as active: dunning may still charge the customer being deleted. So does
 * INCOMPLETE: its first invoice may still be paid and turn it ACTIVE.
 */
public class ActiveSubscriptions implements ActiveSubscriptionsCheck {
  private final SubscriptionRepository subscriptions;

  public ActiveSubscriptions(SubscriptionRepository subscriptions) {
    this.subscriptions = subscriptions;
  }

  @Override
  public boolean hasActive(MerchantId merchantId, String customerId) {
    return subscriptions.existsActiveForCustomer(merchantId, customerId);
  }
}
