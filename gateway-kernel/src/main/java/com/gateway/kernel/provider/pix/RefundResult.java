package com.gateway.kernel.provider.pix;

import com.gateway.kernel.money.Money;
import java.time.Instant;

/** {@code reason} carries the bank's {@code motivo} when a refund fails; null otherwise. */
public record RefundResult(String refundId, RefundStatus status, Money amount, String reason, Instant requestedAt, Instant settledAt) {}
