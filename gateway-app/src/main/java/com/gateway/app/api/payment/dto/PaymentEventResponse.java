package com.gateway.app.api.payment.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.gateway.payments.payment.PaymentEvent;
import java.time.Instant;

/**
 * {@code payload} is already JSON (the domain writes it by hand); raw, so it is not re-quoted as a
 * string.
 */
public record PaymentEventResponse(
    String id,
    long sequence,
    String type,
    String source,
    @JsonRawValue String payload,
    Instant at) {

  public static PaymentEventResponse from(PaymentEvent e) {
    return new PaymentEventResponse(
        e.id(), e.sequence(), e.type(), e.source().name(), e.payload(), e.at());
  }
}
