package com.gateway.billing.order.persistence;

import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderStatus;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
  /** Requires a transaction. */
  void insert(Order order);

  /**
   * Inserts a subscription's invoice unless that subscription already has one with the same number,
   * and then returns the existing one instead. Requires a transaction holding the subscription's
   * row lock: under it the read cannot race, and uq_orders_invoice stays the backstop.
   */
  Optional<Order> insertInvoiceIfAbsent(Order invoice);

  /** Optimistic: writes only when the stored version is {@code order.version() - 1}. */
  boolean update(Order order);

  Optional<Order> find(MerchantId merchantId, String id);

  /** Without the merchant: the outbox consumer knows only the order id. */
  Optional<Order> findById(String id);

  /** The public checkout lookup: a peppered SHA-256, never the token itself. */
  Optional<Order> findByCheckoutTokenHash(String hash);

  List<Order> findByReference(MerchantId merchantId, String reference, int limit);

  /** Newest first, in the given environment; {@code cursorIdOrNull} is exclusive. */
  List<Order> listByMerchant(
      MerchantId merchantId,
      ProviderEnvironment environment,
      OrderStatus statusOrNull,
      String cursorIdOrNull,
      int limit);

  List<Order> findBySubscription(String subscriptionId, int limit);

  List<Order> findOpenExpiredBefore(Instant now, int limit);

  /**
   * Marks an OPEN order as having an attempt in flight unless a marker younger than {@code lock}
   * holds it. Own transaction, committed before the bank call. Returns whether this caller holds
   * it.
   */
  boolean claimAttempt(String orderId, Instant now, Duration lock);

  /**
   * Clears the marker only if it is still the one written at {@code claimedAt}. Own transaction.
   */
  void releaseAttempt(String orderId, Instant claimedAt);

  /** Returns whether the row was inserted (false = already processed). Same transaction. */
  boolean recordProcessedEvent(String eventId, Instant at);
}
