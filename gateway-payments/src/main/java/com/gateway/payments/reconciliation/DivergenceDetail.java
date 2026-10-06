package com.gateway.payments.reconciliation;

import com.gateway.payments.payment.Payment;

public record DivergenceDetail(ReconciliationDivergence divergence, Payment payment) {}
