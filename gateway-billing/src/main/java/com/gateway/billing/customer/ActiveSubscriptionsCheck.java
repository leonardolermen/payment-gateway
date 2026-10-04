package com.gateway.billing.customer;

import com.gateway.kernel.ids.MerchantId;

public interface ActiveSubscriptionsCheck {
  boolean hasActive(MerchantId merchantId, String customerId);
}
