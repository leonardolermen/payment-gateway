package com.gateway.billing.order.persistence;

import com.gateway.billing.order.Order;
import com.gateway.kernel.ids.MerchantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
  /** Requires a transaction. */
  void insert(Order order);

  /** Optimistic: writes only when the stored version is {@code order.version() - 1}. */
  boolean update(Order order);

  Optional<Order> find(MerchantId merchantId, String id);

  /** Without the merchant: the outbox consumer knows only the order id. */
  Optional<Order> findById(String id);

  List<Order> findByReference(MerchantId merchantId, String reference, int limit);

  List<Order> findBySubscription(String subscriptionId, int limit);

  List<Order> findOpenExpiredBefore(Instant now, int limit);

  /** Returns whether the row was inserted (false = already processed). Same transaction. */
  boolean recordProcessedEvent(String eventId, Instant at);
}
