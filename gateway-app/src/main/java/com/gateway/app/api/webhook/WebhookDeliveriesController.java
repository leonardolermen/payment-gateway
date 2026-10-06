package com.gateway.app.api.webhook;

import com.barrier.webhookdelivery.domain.Delivery;
import com.barrier.webhookdelivery.domain.DeliveryCursor;
import com.barrier.webhookdelivery.domain.DeliveryQuery;
import com.barrier.webhookdelivery.domain.DeliveryStatus;
import com.barrier.webhookdelivery.repository.DeliveryRepository;
import com.barrier.webhookdelivery.service.WebhookDeliveryService;
import com.gateway.app.api.webhook.dto.DeliveryCursorCodec;
import com.gateway.app.api.webhook.dto.DeliveryResponse;
import com.gateway.app.api.webhook.dto.RedeliverDeadRequest;
import com.gateway.app.security.MerchantContext;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The merchant's view of what was sent to their endpoints, and the way to send it again. Tenant is
 * always the calling merchant, as in {@link WebhookEndpointsController}: another merchant's
 * delivery id is a 404, because the lib's queries are scoped by tenant and never return it.
 */
@RestController
@RequestMapping("/v1/webhooks/deliveries")
public class WebhookDeliveriesController {
  public static final String NEXT_CURSOR_HEADER = "X-Next-Cursor";

  /**
   * Bounds the bulk redelivery: the lib caps one call at 1000 rows, and a merchant replaying months
   * of dead events would flood their own endpoint with stale state they have since reconciled.
   */
  private static final int MAX_REDELIVER_WINDOW_DAYS = 30;

  private final DeliveryRepository deliveries;
  private final WebhookDeliveryService service;
  private final Clock clock;

  public WebhookDeliveriesController(
      DeliveryRepository deliveries, WebhookDeliveryService service, Clock clock) {
    this.deliveries = deliveries;
    this.service = service;
    this.clock = clock;
  }

  @GetMapping
  public ResponseEntity<List<DeliveryResponse>> list(
      @RequestParam(required = false) DeliveryStatus status,
      @RequestParam(name = "event_type", required = false) String eventType,
      @RequestParam(name = "aggregate_id", required = false) String aggregateId,
      @RequestParam(required = false) Instant since,
      @RequestParam(required = false) String after,
      @RequestParam(defaultValue = "20") int limit) {
    DeliveryCursor cursor = after == null ? null : DeliveryCursorCodec.decode(after);
    DeliveryQuery query = new DeliveryQuery(status, eventType, aggregateId, since, cursor, limit);

    List<Delivery> page = deliveries.findByTenant(tenant(), query);

    // A full page only means there MAY be more: the next call can come back empty. Asking for
    // limit + 1 would avoid that, at the cost of a query the lib does not offer.
    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (page.size() == query.limit()) {
      Delivery last = page.get(page.size() - 1);
      response.header(
          NEXT_CURSOR_HEADER,
          DeliveryCursorCodec.encode(new DeliveryCursor(last.createdAt(), last.id())));
    }

    return response.body(page.stream().map(DeliveryResponse::summary).toList());
  }

  @GetMapping("/{id}")
  public DeliveryResponse get(@PathVariable UUID id) {
    return DeliveryResponse.full(mine(id));
  }

  @PostMapping("/{id}/redeliver")
  public ResponseEntity<Map<String, String>> redeliver(@PathVariable UUID id) {
    return switch (service.redeliver(tenant(), id)) {
      case SCHEDULED -> ResponseEntity.accepted().body(Map.of("status", "PENDING"));
      case NOT_FOUND -> throw new NotFoundException("delivery", id.toString());
      case NOT_REDELIVERABLE ->
          throw new DomainException(
              "DELIVERY_NOT_REDELIVERABLE",
              "delivery " + id + " is not DEAD or FAILED, or its endpoint is inactive");
    };
  }

  @PostMapping("/redeliver-dead")
  public ResponseEntity<Map<String, Integer>> redeliverDead(
      @RequestBody RedeliverDeadRequest body) {
    Instant since = body.since();
    if (since == null) {
      throw new IllegalArgumentException("since is required");
    }
    if (since.isBefore(clock.instant().minus(Duration.ofDays(MAX_REDELIVER_WINDOW_DAYS)))) {
      throw new DomainException(
          "WINDOW_TOO_WIDE",
          "since must be within the last " + MAX_REDELIVER_WINDOW_DAYS + " days");
    }

    return ResponseEntity.accepted()
        .body(Map.of("scheduled", service.redeliverDead(tenant(), since)));
  }

  private Delivery mine(UUID id) {
    return deliveries
        .findByTenantAndId(tenant(), id)
        .orElseThrow(() -> new NotFoundException("delivery", id.toString()));
  }

  private String tenant() {
    return MerchantContext.current().merchantId().value();
  }
}
