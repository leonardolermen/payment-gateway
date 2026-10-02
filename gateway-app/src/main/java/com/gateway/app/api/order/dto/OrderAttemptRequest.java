package com.gateway.app.api.order.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.gateway.billing.order.AttemptRequest;

/**
 * POST /v1/orders/{id}/payments: the body of POST /v1/payments without amount, currency and
 * customer, which the order already fixed (spec §5). Polymorphic on {@code method} like {@code
 * CreatePaymentRequest}, so a field of another method, or one of the order's own, is refused by the
 * deserialiser instead of being silently ignored.
 */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "method")
@JsonSubTypes({
  @JsonSubTypes.Type(value = PixAttemptBody.class, name = "PIX"),
  @JsonSubTypes.Type(value = BolecodeAttemptBody.class, name = "BOLECODE"),
  @JsonSubTypes.Type(value = CardAttemptBody.class, name = "CARD")
})
public sealed interface OrderAttemptRequest
    permits PixAttemptBody, BolecodeAttemptBody, CardAttemptBody {

  /** Checks this body's own shape and turns it into what the order flow takes. */
  AttemptRequest toAttempt();
}
