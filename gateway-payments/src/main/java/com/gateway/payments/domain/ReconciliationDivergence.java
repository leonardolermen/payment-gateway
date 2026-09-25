package com.gateway.payments.domain;

import java.time.Instant;

/** A mismatch between our recorded status and the provider's, found by the reconciliation job. */
public record ReconciliationDivergence(
    String id, String paymentId, String gatewayStatus, String providerStatus, String detail, String status, Instant createdAt) {}
