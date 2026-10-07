package com.gateway.app.api.order;

import com.gateway.app.api.checkout.CheckoutProperties;
import com.gateway.app.api.order.dto.CreateOrderRequest;
import com.gateway.app.api.order.dto.OrderAttemptRequest;
import com.gateway.app.api.order.dto.OrderResponse;
import com.gateway.app.api.payment.dto.PaymentResponse;
import com.gateway.app.api.support.Environments;
import com.gateway.app.api.support.IdempotencyFilter;
import com.gateway.app.security.MerchantContext;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderAttemptService;
import com.gateway.billing.order.OrderService;
import com.gateway.billing.order.OrderStatus;
import com.gateway.billing.order.checkout.CheckoutTokens;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Orders: what is owed, paid by one or more attempts. Only one attempt may be active at a time, so
 * a second one is 409 ORDER_HAS_ACTIVE_PAYMENT with the id of the first.
 */
@RestController
@RequestMapping("/v1/orders")
public class OrdersController {
  private static final int MAX_PAGE = 100;

  private final OrderService orders;
  private final OrderAttemptService attempts;
  private final CustomerService customers;
  private final CheckoutTokens checkoutTokens;
  private final CheckoutProperties checkout;
  private final Clock clock;

  public OrdersController(
      OrderService orders,
      OrderAttemptService attempts,
      CustomerService customers,
      CheckoutTokens checkoutTokens,
      CheckoutProperties checkout,
      Clock clock) {
    this.orders = orders;
    this.attempts = attempts;
    this.customers = customers;
    this.checkoutTokens = checkoutTokens;
    this.checkout = checkout;
    this.clock = clock;
  }

  @PostMapping
  public ResponseEntity<OrderResponse> create(@RequestBody CreateOrderRequest request) {
    MerchantContext.Current caller = MerchantContext.current();

    // Only the hash goes into the order; the plain token never touches the service, which logs and
    // emits. The url built from it is returned once, here and on rotation.
    CheckoutTokens.Issued issued = checkoutTokens.issue();
    Order order =
        request.toOrder(
            caller.merchantId(),
            Environments.toProvider(caller.environment()),
            issued.hash(),
            clock);
    Order created = orders.create(order);

    return withResource(
        HttpStatus.CREATED,
        created.id(),
        OrderResponse.from(
            created,
            List.of(),
            customerName(caller.merchantId(), created),
            checkout.urlFor(issued.token().value())));
  }

  /** The old link dies here; the new one is in the body, once. */
  @PostMapping("/{id}/checkout-token/rotate")
  public ResponseEntity<OrderResponse> rotateCheckoutToken(@PathVariable String id) {
    MerchantId merchantId = MerchantContext.current().merchantId();
    CheckoutTokens.Issued issued = checkoutTokens.issue();

    Order rotated = orders.rotateCheckoutToken(merchantId, id, issued.hash());

    return withResource(
        HttpStatus.OK,
        rotated.id(),
        OrderResponse.from(
            rotated,
            orders.attemptsOf(merchantId, id),
            customerName(merchantId, rotated),
            checkout.urlFor(issued.token().value())));
  }

  @PostMapping("/{id}/payments")
  public ResponseEntity<PaymentResponse> attempt(
      @PathVariable String id, @RequestBody OrderAttemptRequest request) {
    MerchantId merchantId = MerchantContext.current().merchantId();

    Order order = orders.get(merchantId, id);
    Payment payment = attempts.attempt(order, request.toAttempt(), EventSource.API);

    return withResource(HttpStatus.CREATED, payment.id(), PaymentResponse.from(payment));
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<OrderResponse> cancel(@PathVariable String id) {
    MerchantId merchantId = MerchantContext.current().merchantId();

    Order canceled = orders.cancel(merchantId, id);

    return withResource(HttpStatus.OK, canceled.id(), response(merchantId, canceled));
  }

  @GetMapping("/{id}")
  public OrderResponse get(@PathVariable String id) {
    MerchantId merchantId = MerchantContext.current().merchantId();

    return response(merchantId, orders.get(merchantId, id));
  }

  /**
   * The panel's list: newest first in the key's environment, by cursor (the last id of the previous
   * page), optionally one status. {@code reference} is the older lookup a client uses to find an
   * order it may have created; it pages nothing, so it takes neither cursor nor status.
   */
  @GetMapping
  public List<OrderResponse> list(
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String reference) {
    if (limit <= 0 || limit > MAX_PAGE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
    }

    MerchantContext.Current caller = MerchantContext.current();
    MerchantId merchantId = caller.merchantId();

    List<Order> page;
    if (reference != null) {
      if (cursor != null || status != null) {
        throw new IllegalArgumentException("reference cannot be combined with cursor or status");
      }
      page = orders.listByReference(merchantId, reference, limit);
    } else {
      page =
          orders.list(
              merchantId,
              Environments.toProvider(caller.environment()),
              statusFilter(status),
              cursor,
              limit);
    }

    return responses(merchantId, page);
  }

  @GetMapping("/{id}/payments")
  public List<PaymentResponse> payments(@PathVariable String id) {
    return orders.attemptsOf(MerchantContext.current().merchantId(), id).stream()
        .map(PaymentResponse::from)
        .toList();
  }

  private OrderResponse response(MerchantId merchantId, Order order) {
    return OrderResponse.from(
        order, orders.attemptsOf(merchantId, order.id()), customerName(merchantId, order));
  }

  /** One query for the names and one for the attempts, whatever the page size. */
  private List<OrderResponse> responses(MerchantId merchantId, List<Order> page) {
    Set<String> customerIds =
        page.stream().map(Order::customerId).filter(Objects::nonNull).collect(Collectors.toSet());
    Map<String, String> names = customers.namesOf(merchantId, customerIds);
    Map<String, List<Payment>> attemptsByOrder = orders.attemptsOf(merchantId, page);

    return page.stream()
        .map(
            order ->
                OrderResponse.from(
                    order,
                    attemptsByOrder.getOrDefault(order.id(), List.of()),
                    order.customerId() == null ? payerName(order) : names.get(order.customerId())))
        .toList();
  }

  private String customerName(MerchantId merchantId, Order order) {
    if (order.customerId() == null) {
      return payerName(order);
    }

    return customers.namesOf(merchantId, List.of(order.customerId())).get(order.customerId());
  }

  /**
   * An order with the payer inline has no customer, but it has a name: the one the merchant typed.
   * Showing "no customer" there would hide who owes the charge.
   */
  private static String payerName(Order order) {
    return order.payer() == null ? null : order.payer().name().value();
  }

  private static OrderStatus statusFilter(String status) {
    if (status == null) {
      return null;
    }

    try {
      return OrderStatus.valueOf(status);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException(
          "status must be one of " + Arrays.toString(OrderStatus.values()));
    }
  }

  /** The header is read and stripped by IdempotencyFilter; clients never see it. */
  private static <T> ResponseEntity<T> withResource(HttpStatus status, String resourceId, T body) {
    return ResponseEntity.status(status)
        .header(IdempotencyFilter.RESOURCE_ID_HEADER, resourceId)
        .body(body);
  }
}
