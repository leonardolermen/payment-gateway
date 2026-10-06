package com.gateway.billing.order.checkout;

import com.gateway.billing.order.AttemptRequest;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderAttemptService;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentCancellation;
import com.gateway.payments.payment.PaymentQueries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The payer's door. Everything here is keyed by the checkout token, never by an id the merchant API
 * also uses: a payer who holds a link can see and pay one order, and nothing else. A malformed
 * token never reaches the database (CheckoutTokens.hashOf), and no error message or log line echoes
 * it.
 */
public class CheckoutService {
  private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);

  private final CheckoutTokens tokens;
  private final OrderRepository orders;
  private final OrderAttemptService attempts;
  private final PaymentQueries payments;
  private final PaymentCancellation cancellation;

  public CheckoutService(
      CheckoutTokens tokens,
      OrderRepository orders,
      OrderAttemptService attempts,
      PaymentQueries payments,
      PaymentCancellation cancellation) {
    this.tokens = tokens;
    this.orders = orders;
    this.attempts = attempts;
    this.payments = payments;
    this.cancellation = cancellation;
  }

  public CheckoutView get(String rawToken) {
    Order order = resolve(rawToken);

    return new CheckoutView(order, payments.listByOrder(order.merchantId(), order.id()));
  }

  public Payment attempt(String rawToken, AttemptRequest request) {
    Order order = resolve(rawToken);
    requireOpen(order);

    Payment payment = attempts.attempt(order, request, EventSource.CHECKOUT);
    log.info("checkout attempt {} on order {}", payment.id(), order.id());

    return payment;
  }

  public Payment payment(String rawToken, String paymentId) {
    Order order = resolve(rawToken);

    return ownAttempt(order, paymentId);
  }

  /** Pix and boleto only: a card is voided by the merchant, with its own audit. */
  public Payment cancelAttempt(String rawToken, String paymentId) {
    Order order = resolve(rawToken);
    requireOpen(order);

    Payment attempt = ownAttempt(order, paymentId);
    if (attempt.method() == PaymentMethod.CARD) {
      throw new DomainException(
          "CHECKOUT_CANNOT_CANCEL_CARD", "a card attempt is canceled by the merchant");
    }

    return cancellation.cancel(order.merchantId(), attempt.id());
  }

  private Order resolve(String rawToken) {
    return tokens
        .hashOf(rawToken)
        .flatMap(orders::findByCheckoutTokenHash)
        .orElseThrow(() -> new NotFoundException("checkout", "token"));
  }

  // Its own code, not ORDER_CLOSED: the public route maps this one to 410 (the link is dead), while
  // the merchant API keeps ORDER_CLOSED as a 409.
  private void requireOpen(Order order) {
    if (!order.isOpen()) {
      // Fixed: the GET already tells the payer the status, and the order id is not theirs to see.
      throw new DomainException("CHECKOUT_ORDER_CLOSED", "this order can no longer be paid");
    }
  }

  // get() throws NotFoundException for an unknown id; a payment of another order gets the same
  // answer, so a payer cannot probe which ids exist.
  private Payment ownAttempt(Order order, String paymentId) {
    Payment payment = payments.get(order.merchantId(), paymentId);
    if (!order.id().equals(payment.orderId())) {
      throw new NotFoundException("payment", paymentId);
    }

    return payment;
  }
}
