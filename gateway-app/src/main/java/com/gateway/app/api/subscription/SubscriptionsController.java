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
import com.gateway.billing.subscription.SubscriptionStatus;
import com.gateway.billing.subscription.billing.SubscriptionCreation;
import com.gateway.kernel.ids.MerchantId;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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
 * next_billing_at}, which is "now" for a start today, so the first invoice appears moments later. A
 * card subscription without {@code card_id} is the exception (spec 2026-10-07 §2): it is born
 * INCOMPLETE with its first invoice already open, and the response carries that invoice's link.
 */
@RestController
@RequestMapping("/v1/subscriptions")
public class SubscriptionsController {
  private static final int MAX_PAGE = 100;

  private final SubscriptionCreation creation;
  private final SubscriptionService subscriptions;
  private final SubscriptionQueries queries;
  private final CustomerService customers;
  private final PlanService plans;
  private final OrderService orders;
  private final Clock clock;

  public SubscriptionsController(
      SubscriptionCreation creation,
      SubscriptionService subscriptions,
      SubscriptionQueries queries,
      CustomerService customers,
      PlanService plans,
      OrderService orders,
      Clock clock) {
    this.creation = creation;
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

    SubscriptionCreation.Created created = creation.create(subscription, customer);

    // The header is read and stripped by IdempotencyFilter; clients never see it.
    return ResponseEntity.status(HttpStatus.CREATED)
        .header(IdempotencyFilter.RESOURCE_ID_HEADER, created.subscription().id())
        .body(
            response(
                caller.merchantId(),
                created.subscription(),
                SubscriptionResponse.FirstInvoice.from(created.firstInvoice())));
  }

  @GetMapping("/{id}")
  public SubscriptionResponse get(@PathVariable String id) {
    MerchantId merchantId = MerchantContext.current().merchantId();

    return response(merchantId, queries.get(merchantId, id), null);
  }

  /**
   * The panel's list (spec 2026-10-07 §5): newest first in the key's environment, by cursor (the
   * last id of the previous page), optionally one status. {@code customer_id} is the older lookup
   * of one customer's subscriptions; it pages nothing, so it takes neither cursor nor status.
   */
  @GetMapping
  public List<SubscriptionResponse> list(
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) String cursor,
      @RequestParam(required = false) String status,
      @RequestParam(name = "customer_id", required = false) String customerId) {
    if (limit <= 0 || limit > MAX_PAGE) {
      throw new IllegalArgumentException("limit must be between 1 and " + MAX_PAGE);
    }

    MerchantContext.Current caller = MerchantContext.current();
    MerchantId merchantId = caller.merchantId();

    List<Subscription> page;
    if (customerId != null) {
      if (cursor != null || status != null) {
        throw new IllegalArgumentException("customer_id cannot be combined with cursor or status");
      }
      page = queries.listByCustomer(merchantId, customerId);
    } else {
      page =
          queries.list(
              merchantId,
              Environments.toProvider(caller.environment()),
              statusFilter(status),
              cursor,
              limit);
    }

    return responses(merchantId, page);
  }

  @PostMapping("/{id}/cancel")
  public ResponseEntity<SubscriptionResponse> cancel(
      @PathVariable String id, @RequestBody(required = false) SubscriptionCancelRequest request) {
    MerchantId merchantId = MerchantContext.current().merchantId();
    boolean atPeriodEnd = request == null || request.atPeriodEndOrDefault();

    Subscription canceled = subscriptions.cancel(merchantId, id, atPeriodEnd);

    return ResponseEntity.ok()
        .header(IdempotencyFilter.RESOURCE_ID_HEADER, canceled.id())
        .body(response(merchantId, canceled, null));
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

    return response(merchantId, changed, null);
  }

  /** The invoices, newest first. */
  @GetMapping("/{id}/orders")
  public List<OrderResponse> invoices(@PathVariable String id) {
    MerchantId merchantId = MerchantContext.current().merchantId();

    return queries.invoicesOf(merchantId, id).stream()
        .map(order -> orderResponse(merchantId, order))
        .toList();
  }

  private SubscriptionResponse response(
      MerchantId merchantId,
      Subscription subscription,
      SubscriptionResponse.FirstInvoice firstInvoice) {
    return responses(merchantId, List.of(subscription), firstInvoice).getFirst();
  }

  private List<SubscriptionResponse> responses(MerchantId merchantId, List<Subscription> page) {
    return responses(merchantId, page, null);
  }

  /**
   * Customer names and plans in one query each for the whole page; the latest invoice and the
   * dunning log are still read per subscription, as before the list existed.
   */
  private List<SubscriptionResponse> responses(
      MerchantId merchantId,
      List<Subscription> page,
      SubscriptionResponse.FirstInvoice firstInvoice) {
    Set<String> customerIds =
        page.stream().map(Subscription::customerId).collect(Collectors.toSet());
    Set<String> planIds = page.stream().map(Subscription::planId).collect(Collectors.toSet());
    Map<String, String> names = customers.namesOf(merchantId, customerIds);
    Map<String, Plan> plansById = plans.byIds(merchantId, planIds);

    return page.stream()
        .map(
            subscription -> {
              OrderResponse latestOrder =
                  queries.invoicesOf(merchantId, subscription.id()).stream()
                      .findFirst()
                      .map(order -> orderResponse(merchantId, order))
                      .orElse(null);

              return SubscriptionResponse.from(
                  subscription,
                  names.get(subscription.customerId()),
                  plansById.get(subscription.planId()),
                  latestOrder,
                  queries.dunningOf(merchantId, subscription.id()),
                  firstInvoice);
            })
        .toList();
  }

  private static SubscriptionStatus statusFilter(String status) {
    if (status == null) {
      return null;
    }

    try {
      return SubscriptionStatus.valueOf(status);
    } catch (IllegalArgumentException unknown) {
      throw new IllegalArgumentException(
          "status must be one of " + Arrays.toString(SubscriptionStatus.values()));
    }
  }

  private OrderResponse orderResponse(MerchantId merchantId, Order order) {
    // An invoice always has the subscription's customer; the guard is for List.of, which takes no
    // null.
    String customerName =
        order.customerId() == null
            ? null
            : customers.namesOf(merchantId, List.of(order.customerId())).get(order.customerId());

    return OrderResponse.from(order, orders.attemptsOf(merchantId, order.id()), customerName);
  }
}
