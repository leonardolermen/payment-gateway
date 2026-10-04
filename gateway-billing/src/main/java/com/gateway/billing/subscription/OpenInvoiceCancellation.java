package com.gateway.billing.subscription;

import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderService;
import com.gateway.billing.order.OrderStatus;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.kernel.errors.DomainException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Spec §7, immediate cancel: the subscription's open invoice is canceled at the bank and closed.
 * Its own collaborator because SubscriptionService already had seven dependencies.
 *
 * <p>Never fails the subscription cancel: the merchant asked to stop billing, and a bank that is
 * down must not keep a subscription alive. An invoice left OPEN here expires by itself at the end
 * of its period (OrderExpiration), so the cost of swallowing is a charge that stays payable until
 * then.
 */
public class OpenInvoiceCancellation {
  private static final Logger log = LoggerFactory.getLogger(OpenInvoiceCancellation.class);

  private final OrderRepository orders;
  private final OrderService orderService;

  public OpenInvoiceCancellation(OrderRepository orders, OrderService orderService) {
    this.orders = orders;
    this.orderService = orderService;
  }

  public void cancelOpenInvoice(Subscription subscription) {
    List<Order> latest = orders.findBySubscription(subscription.id(), 1);
    if (latest.isEmpty() || latest.get(0).status() != OrderStatus.OPEN) {
      return;
    }

    Order invoice = latest.get(0);
    try {
      orderService.cancel(subscription.merchantId(), invoice.id());
    } catch (DomainException e) {
      if ("ALREADY_PAID".equals(e.code())) {
        log.info(
            "invoice {} paid while canceling subscription {}; the subscription is canceled anyway",
            invoice.id(),
            subscription.id());
        return;
      }
      // The code only: a message may carry provider detail.
      log.warn(
          "open invoice {} of subscription {} not canceled ({}); it expires by itself",
          invoice.id(),
          subscription.id(),
          e.code());
    } catch (RuntimeException e) {
      log.warn(
          "open invoice {} of subscription {} not canceled ({}); it expires by itself",
          invoice.id(),
          subscription.id(),
          e.getClass().getSimpleName());
    }
  }
}
