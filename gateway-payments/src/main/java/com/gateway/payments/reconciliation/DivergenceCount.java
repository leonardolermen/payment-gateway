package com.gateway.payments.reconciliation;

/**
 * How many divergences of one (origin, kind) wait in the operator's queue: OPEN or UNDER_REVIEW.
 */
public record DivergenceCount(DivergenceOrigin origin, String kind, long count) {}
