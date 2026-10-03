package com.gateway.app.api.subscription;

import com.gateway.app.api.order.dto.OrderResponse;
import com.gateway.app.api.subscription.dto.SubscriptionCancelRequest;
import com.gateway.app.api.subscription.dto.SubscriptionPatchRequest;
import com.gateway.app.api.subscription.dto.SubscriptionRequest;
import com.gateway.app.api.subscription.dto.SubscriptionResponse;
import com.gateway.app.api.support.Environments;
import com.gateway.app.api.support.IdempotencyFilter;
import com.gateway.app.security.MerchantContext;
import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderService;
import com.gateway.billing.plan.Plan;
import com.gateway.billing.plan.PlanService;
import com.gateway.billing.subscription.BillingCalendar;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionFactory;
import com.gateway.billing.subscription.SubscriptionQueries;
import com.gateway.billing.subscription.SubscriptionService;
import com.gateway.kernel.ids.MerchantId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Subscriptions. Creating one moves no money here: the first cycle is a job at {@code
 * next_billing_at}, which is "now" for a start today, so the first invoice appears moments later.
 */
@RestController
@RequestMapping("/v1/subscriptions")
public class SubscriptionsController {
  private final SubscriptionService subscriptions;
  private final SubscriptionQueries queries;
  private final CustomerService customers;
  private final PlanService plans;
  private final OrderService orders;
  private final Clock clock;

  public SubscriptionsController(
      SubscriptionService subscriptions,
      SubscriptionQueries queries,
      CustomerService customers,
      PlanService plans,
      OrderService orders,
      Clock clock) {
    this.subscriptions = subscriptions;
    this.queries = queries;
    this.customers = customers;
    this.plans = plans;
    this.orders = orders;
    this.clock = clock;
  }

  @PostMapping
  public ResponseEntity<SubscriptionResponse> create(@RequestBody SubscriptionRequest request) {
    request.validate();

    MerchantContext.Current caller = MerchantContext.current();
    Customer customer = customers.get(caller.merchantId(), request.customerId());
    Plan plan = plans.get(caller.merchantId(), request.planId());

    // Today in São Paulo, not in UTC: after 21:00 there, UTC is already tomorrow.
    LocalDate startDay =
        request.startAt() == null ? BillingCalendar.today(clock.instant()) : request.startAt();
    Subscription subscription =
        SubscriptionFactory.fromRequest(
            caller.merchantId(),
            Environments.toProvider(caller.environment()),
            customer,
            plan,
            request.method(),
            request.cardId(),
            startDay,
            clock);

    Subscription created = subscriptions.create(subscription, customer);

    // The header is read and stripped by IdempotencyFilter; clients never see it.
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(IdempotencyFilter.RESOURCE_ID_HEADER, created.id())
        .body(response(caller.merchantId(), created));
  }

  @GetMapping("/{id}")
  public SubscriptionResponse get(@PathVariable String id) {
    MerchantId merchantId = MerchantContext.current().merchantId();

    return response(merchantId, queries.get(merchantId, id));
  }

  @GetMapping
  public List<SubscriptionResponse> listByCustomer(@RequestParam("customer_id") String customerId) {
    MerchantId merchantId = MerchantContext.current().merchantId();

    return queries.listByCustomer(merchantId, customerId).stream()
        .map(subscription -> response(merchantId, subscription))
        .toList();
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<SubscriptionResponse> cancel(
      @PathVariable String id, @RequestBody(required = false) SubscriptionCancelRequest request) {
    MerchantId merchantId = MerchantContext.current().merchantId();
    boolean atPeriodEnd = request == null || request.atPeriodEndOrDefault();

    Subscription canceled = subscriptions.cancel(merchantId, id, atPeriodEnd);

    return ResponseEntity.ok()
        .header(IdempotencyFilter.RESOURCE_ID_HEADER, canceled.id())
        .body(response(merchantId, canceled));
  }

  /** The customer is loaded for the method rules: a BOLECODE needs his address, a CARD his card. */
  @PatchMapping("/{id}")
  public SubscriptionResponse changeMethod(
      @PathVariable String id, @RequestBody SubscriptionPatchRequest request) {
    request.validate();

    MerchantId merchantId = MerchantContext.current().merchantId();
    Subscription current = queries.get(merchantId, id);
    Customer customer = customers.get(merchantId, current.customerId());

    Subscription changed =
        subscriptions.changeMethod(merchantId, id, request.method(), request.cardId(), customer);

    return response(merchantId, changed);
  }

  /** The invoices, newest first. */
  @GetMapping("/{id}/orders")
  public List<OrderResponse> invoices(@PathVariable String id) {
    MerchantId merchantId = MerchantContext.current().merchantId();

    return queries.invoicesOf(merchantId, id).stream()
        .map(order -> orderResponse(merchantId, order))
        .toList();
  }

  private SubscriptionResponse response(MerchantId merchantId, Subscription subscription) {
    OrderResponse latestOrder =
        queries.invoicesOf(merchantId, subscription.id()).stream()
            .findFirst()
            .map(order -> orderResponse(merchantId, order))
            .orElse(null);

    return SubscriptionResponse.from(
        subscription, latestOrder, queries.dunningOf(merchantId, subscription.id()));
  }

  private OrderResponse orderResponse(MerchantId merchantId, Order order) {
    return OrderResponse.from(order, orders.attemptsOf(merchantId, order.id()));
  }
}
