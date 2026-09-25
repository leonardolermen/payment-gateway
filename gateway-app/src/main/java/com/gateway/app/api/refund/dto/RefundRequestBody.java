package com.gateway.app.api.refund.dto;

/** {@code amount} absent (or the body absent) refunds everything not yet refunded or in flight. */
public record RefundRequestBody(Long amount) {}
