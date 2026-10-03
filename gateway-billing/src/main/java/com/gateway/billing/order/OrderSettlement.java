package com.gateway.billing.order;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.outbox.OutboxListener;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.reconciliation.Divergences;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mirrors payment outcomes onto orders (spec §8). Never decides money: a {@code payment.completed}
 * is the bank's word, relayed. Idempotent by message id through {@code billing.processed_events},
 * written in the same transaction as the reaction, because the relay re-delivers on any throw.
 */
public class OrderSettlement implements OutboxListener {
  private static final Logger log = LoggerFactory.getLogger(OrderSettlement.class);
  private static final Set<String> HANDLED =
      Set.of("payment.completed", "payment.failed", "payment.expired", "payment.canceled");

  private final OrderRepository orders;
  private final PaymentQueries payments;
  private final Divergences divergences;
  private final InvoiceSettlementHook invoices;
  private final BillingEvents events;
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public OrderSettlement(
      OrderRepository orders,
      PaymentQueries payments,
      Divergences divergences,
      InvoiceSettlementHook invoices,
      BillingEvents events,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.orders = orders;
    this.payments = payments;
    this.divergences = divergences;
    this.invoices = invoices;
    this.events = events;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  @Override
  public boolean handles(String eventType) {
    return HANDLED.contains(eventType);
  }

  @Override
  public void on(OutboxMessage message) {
    JsonNode payload = json.readTree(message.payload());
    JsonNode orderNode = payload.get("order_id");
    if (orderNode == null || orderNode.isNull()) {
      return;
    }

    String orderId = orderNode.asText();
    String paymentId = payload.get("id").asText();

    unitOfWork.run(
        () -> {
          if (!orders.recordProcessedEvent(message.id(), clock.instant())) {
            return;
          }

          Optional<Order> found = orders.findById(orderId);
          if (found.isEmpty()) {
            log.warn("payment {} names order {} which does not exist", paymentId, orderId);
            return;
          }

          Order order = found.get();
          Instant now = clock.instant();

          if (message.eventType().equals("payment.completed")) {
            completed(order, paymentId, now);
          } else {
            attemptEnded(order, paymentId, message.eventType(), now);
          }
        });
  }

  private void completed(Order order, String paymentId, Instant now) {
    if (!order.isOpen()) {
      closedButPaid(order, paymentId);
      return;
    }

    order.markPaid(paymentId, now);
    requireUpdated(order);
    events.emit(order.merchantId(), "order.paid", order.id(), order.id(), OrderService.json(order));

    if (order.isInvoice()) {
      invoices.invoicePaid(order, now);
    }
  }

  /**
   * Money landed on an order that is no longer open. Never thrown: a throw would hold this row and
   * every later event of the payment in the relay forever, and the bank has already moved the
   * money, so the only honest reaction is a divergence for a human. Nobody refunds by itself.
   */
  private void closedButPaid(Order order, String paymentId) {
    if (paymentId.equals(order.paidPaymentId())) {
      return;
    }

    Payment payment = payments.get(order.merchantId(), paymentId);

    if (order.status() == OrderStatus.PAID) {
      // Two attempts settled (Pix and card landing together); the human sees both ids.
      divergences.open(
          payment,
          "DOUBLE_PAYMENT",
          "order " + order.id() + " already paid by " + order.paidPaymentId());
      return;
    }

    // CANCELED or EXPIRED: the attempt was paid at the bank in the window between the order's
    // close and the bank hearing the cancel (OrderService.cancelLateAttempt documents the race).
    divergences.open(
        payment,
        "PAID_AFTER_CLOSE",
        "order " + order.id() + " is " + order.status() + " but payment " + paymentId + " paid");
  }

  private void attemptEnded(Order order, String paymentId, String eventType, Instant now) {
    if (order.isInvoice() && order.isOpen()) {
      invoices.invoiceAttemptFailed(order, paymentId, eventType, now);
    }
  }

  private void requireUpdated(Order order) {
    if (!orders.update(order)) {
      throw new IllegalStateException("order " + order.id() + " changed concurrently; retrying");
    }
  }
}
