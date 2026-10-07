package com.gateway.billing.subscription;

import com.gateway.billing.order.Order;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.billing.subscription.persistence.DunningAttemptRepository;
import com.gateway.billing.subscription.persistence.SubscriptionRepository;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.util.List;

/** The reads, ports only: invoices and dunning are always scoped through the merchant's own get. */
public class SubscriptionQueries {
  /** A subscription bills at most daily; this many invoices is years of history. */
  private static final int INVOICE_LIMIT = 100;

  private final SubscriptionRepository subscriptions;
  private final OrderRepository orders;
  private final DunningAttemptRepository dunning;

  public SubscriptionQueries(
      SubscriptionRepository subscriptions,
      OrderRepository orders,
      DunningAttemptRepository dunning) {
    this.subscriptions = subscriptions;
    this.orders = orders;
    this.dunning = dunning;
  }

  public Subscription get(MerchantId merchantId, String id) {
    return subscriptions
        .find(merchantId, id)
        .orElseThrow(() -> new NotFoundException("subscription", id));
  }

  public List<Subscription> listByCustomer(MerchantId merchantId, String customerId) {
    return subscriptions.findByCustomer(merchantId, customerId);
  }

  /** The panel's list: the key's environment, newest first, by cursor (the last id seen). */
  public List<Subscription> list(
      MerchantId merchantId,
      ProviderEnvironment environment,
      SubscriptionStatus status,
      String cursor,
      int limit) {
    return subscriptions.list(merchantId, environment, status, cursor, limit);
  }

  /** Newest invoice first. */
  public List<Order> invoicesOf(MerchantId merchantId, String id) {
    get(merchantId, id);

    return orders.findBySubscription(id, INVOICE_LIMIT);
  }

  public List<DunningAttempt> dunningOf(MerchantId merchantId, String id) {
    get(merchantId, id);

    return dunning.findBySubscription(id);
  }
}
