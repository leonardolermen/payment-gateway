package com.gateway.app.api.webhook.dto;

import com.barrier.webhookdelivery.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.List;

/** No secret: the merchant's list/get view. */
public record EndpointResponse(
    String id, String url, List<String> events, boolean active, Instant previousSecretUntil, Instant createdAt, Instant updatedAt) {

  public static EndpointResponse from(WebhookEndpoint e) {
    return new EndpointResponse(
        e.id().toString(), e.targetUrl(), e.events(), e.active(), e.previousSecretUntil(), e.createdAt(), e.updatedAt());
  }
}
