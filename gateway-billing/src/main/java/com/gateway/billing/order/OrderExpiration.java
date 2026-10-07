package com.gateway.billing.order;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.PaymentQueries;
import java.time.Instant;

/**
 * Expires an OPEN order on its limit (spec §4.2). An active attempt wins: the Pix or boleto has its
 * own expiry at the bank, and the order expires on the pass after that attempt ends, never under a
 * charge someone may still be paying.
 */
public class OrderExpiration {
  private final OrderRepository orders;
  private final PaymentQueries payments;
  private final BillingEvents events;
  private final UnitOfWork unitOfWork;
  private final InvoiceSettlementHook invoices;

  public OrderExpiration(
      OrderRepository orders,
      PaymentQueries payments,
      BillingEvents events,
      UnitOfWork unitOfWork,
      InvoiceSettlementHook invoices) {
    this.orders = orders;
    this.payments = payments;
    this.events = events;
    this.unitOfWork = unitOfWork;
    this.invoices = invoices;
  }

  /** true = nothing more to do; false = an attempt is active, ask again later. */
  public boolean expireOne(String orderId, Instant now) {
    return unitOfWork.inTransaction(
        () -> {
          Order order = orders.findById(orderId).orElse(null);
          if (order == null || !order.isOpen()) {
            return true;
          }
          if (order.expiresAt() == null || order.expiresAt().isAfter(now)) {
            return true;
          }
          if (payments.activeAttempt(orderId).isPresent()) {
            return false;
          }

          order.markExpired(now);
          if (!orders.update(order)) {
            throw new IllegalStateException("order " + orderId + " changed concurrently");
          }

          events.emit(
              order.merchantId(), "order.expired", orderId, orderId, OrderService.json(order));
          if (order.isInvoice()) {
            invoices.invoiceClosed(order, now);
          }

          return true;
        });
  }
}
