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
 * emit through here from the outbox relay — never from the request thread, because delivery is
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
    String json = mapper.writeValueAsString(payload);
    return intake.accept(new DeliveryRequest(
        merchantId.value(), eventType, UUID.randomUUID(), aggregateId, partitionKey, json, MDC.get("correlationId")));
  }
}
