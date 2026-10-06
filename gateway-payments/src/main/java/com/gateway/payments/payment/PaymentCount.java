package com.gateway.payments.payment;

/** How many payments share one (status, method, provider, environment): one metric series each. */
public record PaymentCount(
    String status, String method, String provider, String environment, long count) {}
