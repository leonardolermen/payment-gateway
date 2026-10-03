package com.gateway.app.api.order;

import com.gateway.app.api.order.dto.CreateOrderRequest;
import com.gateway.app.api.order.dto.OrderAttemptRequest;
import com.gateway.app.api.order.dto.OrderResponse;
import com.gateway.app.api.payment.dto.PaymentResponse;
import com.gateway.app.api.support.Environments;
import com.gateway.app.api.support.IdempotencyFilter;
import com.gateway.app.security.MerchantContext;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderAttemptService;
import com.gateway.billing.order.OrderService;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import java.time.Clock;
import java.util.List;
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
  private final Clock clock;

  public OrdersController(OrderService orders, OrderAttemptService attempts, Clock clock) {
    this.orders = orders;
    this.attempts = attempts;
    this.clock = clock;
  }

  @PostMapping
  public ResponseEntity<OrderResponse> create(@RequestBody CreateOrderRequest request) {
    MerchantContext.Current caller = MerchantContext.current();

    Order order =
        request.toOrder(caller.merchantId(), Environments.toProvider(caller.environment()), clock);
    Order created = orders.create(order);

    return withResource(HttpStatus.CREATED, created.id(), OrderResponse.from(created, List.of()));
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

  @GetMapping
  public List<OrderResponse> listByReference(
      @RequestParam String reference, @RequestParam(defaultValue = "20") int limit) {
    if (limit <= 0 || limit > MAX_PAGE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
    }

    MerchantId merchantId = MerchantContext.current().merchantId();

    return orders.listByReference(merchantId, reference, limit).stream()
        .map(order -> response(merchantId, order))
        .toList();
  }

  @GetMapping("/{id}/payments")
  public List<PaymentResponse> payments(@PathVariable String id) {
    return orders.attemptsOf(MerchantContext.current().merchantId(), id).stream()
        .map(PaymentResponse::from)
        .toList();
  }

  private OrderResponse response(MerchantId merchantId, Order order) {
    return OrderResponse.from(order, orders.attemptsOf(merchantId, order.id()));
  }

  /** The header is read and stripped by IdempotencyFilter; clients never see it. */
  private static <T> ResponseEntity<T> withResource(HttpStatus status, String resourceId, T body) {
    return ResponseEntity.status(status)
        .header(IdempotencyFilter.RESOURCE_ID_HEADER, resourceId)
        .body(body);
  }
}
