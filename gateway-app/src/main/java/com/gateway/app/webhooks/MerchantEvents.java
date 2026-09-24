package com.gateway.app.webhooks;

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

  public IntakeResult emit(MerchantId merchantId, String eventType, String aggregateId, String partitionKey, Object payload) {
    return emitRaw(merchantId, eventType, aggregateId, partitionKey, mapper.writeValueAsString(payload));
  }

  /**
   * For payloads that are already JSON: the payments outbox stores the exact public body its module
   * chose for merchants. Passed through untouched, because a round-trip through a {@code Map} and
   * this app's mapper (global SNAKE_CASE) could rename keys the payments module already wrote.
   */
  public IntakeResult emitRaw(MerchantId merchantId, String eventType, String aggregateId, String partitionKey, String rawJson) {
    return intake.accept(new DeliveryRequest(
        merchantId.value(), eventType, UUID.randomUUID(), aggregateId, partitionKey, rawJson, MDC.get("correlationId")));
  }
}
