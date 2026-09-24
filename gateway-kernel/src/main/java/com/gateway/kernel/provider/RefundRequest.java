package com.gateway.kernel.provider;

import com.gateway.kernel.money.Money;

/** {@code refundId} is ours, generated before the call so a retry after a timeout is idempotent. */
public record RefundRequest(String endToEndId, String refundId, Money amount) {}
