package com.gateway.payments.domain;

import java.time.Instant;

/**
 * One recorded fact about a payment. {@code sequence} is the version the payment reached when
 * this event was applied — the optimistic-lock value doubles as the event's ordering key.
 */
public record PaymentEvent(String id, String paymentId, long sequence, String type, EventSource source, String payload, Instant at) {}
