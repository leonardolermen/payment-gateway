package com.gateway.billing.order;

import java.time.Instant;

/** What a subscription wants to know about its invoice; implemented in the subscription package. */
public interface InvoiceSettlementHook {
  void invoicePaid(Order order, Instant at);

  void invoiceAttemptFailed(Order order, String paymentId, String eventType, Instant at);
}
