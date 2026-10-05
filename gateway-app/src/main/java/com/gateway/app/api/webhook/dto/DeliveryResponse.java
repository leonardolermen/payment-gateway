package com.gateway.app.api.webhook.dto;

import com.barrier.webhookdelivery.domain.Delivery;
import com.barrier.webhookdelivery.domain.DeliveryStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.Instant;

/**
 * One webhook delivery as the merchant sees it. {@code payload} is left out of lists on purpose: a
 * page of 100 bodies is the bulk of the response and nobody reads it there — {@code GET /{id}}
 * returns it. It is the only field that is omitted when null; every other null stays as {@code
 * null} so the shape of the object does not depend on its state.
 */
public record DeliveryResponse(
    String id,
    String eventId,
    String eventType,
    String aggregateId,
    String endpointId,
    String targetUrl,
    DeliveryStatus status,
    int attempts,
    String lastError,
    String lastErrorBeforeRedelivery,
    Instant nextAttemptAt,
    Instant createdAt,
    Instant deliveredAt,
    Instant redeliveredAt,
    // Raw: the payload is the exact JSON the endpoint received, and re-encoding it as a string
    // would make the merchant parse twice to compare it with what their server logged.
    @JsonInclude(JsonInclude.Include.NON_NULL) @JsonRawValue String payload) {

  public static DeliveryResponse summary(Delivery delivery) {
    return of(delivery, null);
  }

  public static DeliveryResponse full(Delivery delivery) {
    return of(delivery, delivery.payload());
  }

  private static DeliveryResponse of(Delivery delivery, String payload) {
    return new DeliveryResponse(
        delivery.id().toString(),
        delivery.eventId().toString(),
        delivery.eventType(),
        delivery.aggregateId(),
        delivery.endpointId().toString(),
        delivery.targetUrl(),
        delivery.status(),
        delivery.attempts(),
        delivery.lastError(),
        delivery.lastErrorBeforeRedelivery(),
        delivery.nextAttemptAt(),
        delivery.createdAt(),
        delivery.deliveredAt(),
        delivery.redeliveredAt(),
        payload);
  }
}
