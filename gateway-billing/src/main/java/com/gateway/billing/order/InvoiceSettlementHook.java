package com.gateway.billing.order;

import java.time.Instant;

/**
 * What a subscription wants to know about its invoice; implemented in the subscription package.
 * Every method runs in the transaction that changed the order.
 */
public interface InvoiceSettlementHook {
  void invoicePaid(Order order, Instant at);

  void invoiceAttemptFailed(Order order, String paymentId, String eventType, Instant at);

  /** The invoice expired or was canceled unpaid. */
  void invoiceClosed(Order order, Instant at);
}
