package com.gateway.app.outbound;

import com.barrier.webhookdelivery.intake.DeliveryIntake;
import com.barrier.webhookdelivery.intake.DeliveryRequest;
import com.barrier.webhookdelivery.intake.IntakeResult;
import com.gateway.kernel.ids.MerchantId;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * The only point in the gateway that talks to webhook-delivery. Business modules (plans B and C)
 * cannot call this: they cannot import {@code com.gateway.app} (ArchUnit {@code nobodyImportsApp}).
 * The outbox relay, which lives in {@code app}, is what calls {@link #emit}, reading each module's
 * outbox through that module's own interface. Never from the request thread, because delivery is
 * asynchronous by construction and the library does not deliver on accept either.
 */
@Component
public class MerchantEvents {
  private final DeliveryIntake intake;
  private final ObjectMapper mapper;

  public MerchantEvents(DeliveryIntake intake, ObjectMapper mapper) {
    this.intake = intake;
    this.mapper = mapper;
  }

  public IntakeResult emit(
      MerchantId merchantId,
      String eventType,
      String aggregateId,
      String partitionKey,
      Object payload) {
    return emitRaw(
        merchantId,
        eventType,
        aggregateId,
        partitionKey,
        mapper.writeValueAsString(payload),
        UUID.randomUUID());
  }

  /**
   * For payloads that are already JSON: the payments outbox stores the exact public body its module
   * chose for merchants. Passed through untouched, because a round-trip through a {@code Map} and
   * this app's mapper (global SNAKE_CASE) could rename keys the payments module already wrote.
   *
   * <p>{@code eventId} is the caller's: the relay derives it from the outbox row id, so a row
   * emitted twice (crash between emit and markSent) reaches the merchant with the same {@code
   * X-Gateway-Event-Id} both times and the merchant's dedup works. A random id per call made every
   * redelivery look like a new event.
   */
  public IntakeResult emitRaw(
      MerchantId merchantId,
      String eventType,
      String aggregateId,
      String partitionKey,
      String rawJson,
      UUID eventId) {
    return intake.accept(
        new DeliveryRequest(
            merchantId.value(),
            eventType,
            eventId,
            aggregateId,
            partitionKey,
            rawJson,
            MDC.get("correlationId")));
  }
}
