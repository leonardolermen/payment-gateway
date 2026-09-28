package com.gateway.kernel.provider.card;

/**
 * A parsed acquirer notification. Only a hint: the payment moves on what a query of {@code
 * paymentId} answers, never on the body. {@code changeType} is kept raw for the ignored event.
 */
public record CardNotification(String paymentId, CardNotificationKind kind, int changeType) {}
