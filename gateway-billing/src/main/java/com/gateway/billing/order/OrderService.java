package com.gateway.billing.order;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentCancellation;
import com.gateway.payments.payment.PaymentQueries;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrderService {
  private static final Logger log = LoggerFactory.getLogger(OrderService.class);

  private final OrderRepository orders;
  private final PaymentQueries payments;
  private final PaymentCancellation cancellation;
  private final BillingEvents events;
  private final JobRepository jobs;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public OrderService(
      OrderRepository orders,
      PaymentQueries payments,
      PaymentCancellation cancellation,
      BillingEvents events,
      JobRepository jobs,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.orders = orders;
    this.payments = payments;
    this.cancellation = cancellation;
    this.events = events;
    this.jobs = jobs;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public Order create(Order order) {
    return unitOfWork.inTransaction(
        () -> {
          orders.insert(order);
          events.emit(order.merchantId(), "order.created", order.id(), order.id(), json(order));

          if (order.expiresAt() != null) {
            jobs.enqueue(Job.expireOrder(order.id(), order.expiresAt(), clock));
          }

          return order;
        });
  }

  public Order get(MerchantId merchantId, String id) {
    return orders.find(merchantId, id).orElseThrow(() -> new NotFoundException("order", id));
  }

  public List<Order> listByReference(MerchantId merchantId, String reference, int limit) {
    return orders.findByReference(merchantId, reference, limit);
  }

  public List<Payment> attemptsOf(MerchantId merchantId, String id) {
    get(merchantId, id);

    return payments.listByOrder(merchantId, id);
  }

  /**
   * Cancels the active attempt at the bank first, outside any transaction (the bank decides), then
   * the order. If the bank says the attempt already paid, {@link PaymentCancellation} throws
   * ALREADY_PAID itself (it settles the boleto and refuses), so it propagates untouched and the
   * order stays OPEN for the settlement listener to mark PAID. A cancel that returns always returns
   * a CANCELED payment: its final write refuses anything but PENDING.
   */
  public Order cancel(MerchantId merchantId, String id) {
    Order order = get(merchantId, id);
    requireOpen(order);

    Optional<Payment> active = payments.activeAttempt(order.id());
    active.ifPresent(payment -> cancellation.cancel(merchantId, payment.id()));

    Order canceled =
        unitOfWork.inTransaction(
            () -> {
              Order current = orders.find(merchantId, id).orElseThrow();
              requireOpen(current);

              current.markCanceled(clock.instant());
              if (!orders.update(current)) {
                throw new DomainException("CONFLICT", "order " + id + " changed concurrently");
              }

              events.emit(merchantId, "order.canceled", id, id, json(current));

              return current;
            });

    cancelLateAttempt(merchantId, id);

    return canceled;
  }

  /**
   * An attempt that re-read the order just before the close above can still have opened a charge:
   * the attempt checks OPEN, then the bank call runs outside any lock. Asked once more, after the
   * close, so no payable charge outlives a CANCELED order. Never thrown back: the order is already
   * CANCELED and committed, so an error would tell the merchant the opposite of what happened. A
   * late ALREADY_PAID is money on a canceled order; the settlement listener books it as a
   * divergence, and anything else left PENDING dies with the payment's own expiration.
   */
  private void cancelLateAttempt(MerchantId merchantId, String orderId) {
    Optional<Payment> late = payments.activeAttempt(orderId);
    if (late.isEmpty()) {
      return;
    }

    log.warn(
        "attempt {} started while order {} was being canceled; cancelling it at the bank",
        late.get().id(),
        orderId);

    try {
      cancellation.cancel(merchantId, late.get().id());
    } catch (DomainException e) {
      log.warn(
          "late cancel of attempt {} on canceled order {} refused: {}",
          late.get().id(),
          orderId,
          e.code());
    }
  }

  private static void requireOpen(Order order) {
    if (!order.isOpen()) {
      throw new DomainException("ORDER_CLOSED", "order " + order.id() + " is " + order.status());
    }
  }

  public static Map<String, Object> json(Order order) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", order.id());
    body.put("status", order.status().name());
    body.put("amount", order.amount().cents());
    body.put("currency", order.amount().currency());
    body.put("reference", order.reference());
    body.put("customer_id", order.customerId());
    body.put("paid_payment_id", order.paidPaymentId());
    body.put("paid_at", order.paidAt() == null ? null : order.paidAt().toString());
    body.put("expires_at", order.expiresAt() == null ? null : order.expiresAt().toString());
    body.put("subscription_id", order.subscriptionId());
    body.put("invoice_number", order.invoiceNumber());
    body.put("created_at", order.createdAt().toString());

    return body;
  }
}
