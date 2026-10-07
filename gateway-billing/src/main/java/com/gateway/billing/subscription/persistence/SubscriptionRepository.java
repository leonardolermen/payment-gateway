package com.gateway.billing.subscription.persistence;

import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionStatus;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
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

  /**
   * ACTIVE, PAST_DUE or INCOMPLETE: a subscription that can still bill the customer, or whose first
   * invoice may still be paid and turn it ACTIVE.
   */
  boolean existsActiveForCustomer(MerchantId merchantId, String customerId);

  /**
   * One page of the merchant's subscriptions in one environment, newest first: ids are ULIDs, so
   * {@code id DESC} is creation order and the last id of a page is the next page's cursor. {@code
   * status} and {@code cursorId} are optional (null).
   */
  List<Subscription> list(
      MerchantId merchantId,
      ProviderEnvironment environment,
      SubscriptionStatus status,
      String cursorId,
      int limit);
}
