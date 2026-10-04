package com.gateway.billing.subscription.persistence;

import com.gateway.billing.subscription.Subscription;
import com.gateway.kernel.ids.MerchantId;
import java.util.List;
import java.util.Optional;

public interface SubscriptionRepository {
  /** Requires a transaction. */
  void insert(Subscription subscription);

  /** Optimistic: writes only when the stored version is {@code subscription.version() - 1}. */
  boolean update(Subscription subscription);

  Optional<Subscription> find(MerchantId merchantId, String id);

  /**
   * {@code SELECT ... FOR UPDATE}, requires a transaction: two billing runs for the same
   * subscription would otherwise both open a period from the same row.
   */
  Optional<Subscription> lock(String id);

  /** Without the merchant: a job knows only the subscription id. */
  Optional<Subscription> findById(String id);

  /** Newest first. */
  List<Subscription> findByCustomer(MerchantId merchantId, String customerId);

  /** ACTIVE or PAST_DUE: a subscription that can still bill the customer. */
  boolean existsActiveForCustomer(MerchantId merchantId, String customerId);
}
