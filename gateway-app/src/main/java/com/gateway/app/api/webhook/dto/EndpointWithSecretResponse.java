package com.gateway.app.api.webhook.dto;

import com.barrier.webhookdelivery.domain.WebhookEndpoint;
import java.time.Instant;
import java.util.List;

/**
 * Same fields as {@link EndpointResponse} plus the secret: only registration and rotation return
 * this — every other route hands back {@link EndpointResponse}, which never carries it.
 */
public record EndpointWithSecretResponse(
    String id,
    String url,
    List<String> events,
    boolean active,
    String secret,
    String warning,
    Instant previousSecretUntil,
    Instant createdAt,
    Instant updatedAt) {

  private static final String WARNING = "store this secret now — it will not be shown again";

  public static EndpointWithSecretResponse from(WebhookEndpoint e) {
    return new EndpointWithSecretResponse(
        e.id().toString(),
        e.targetUrl(),
        e.events(),
        e.active(),
        e.secret(),
        WARNING,
        e.previousSecretUntil(),
        e.createdAt(),
        e.updatedAt());
  }
}
