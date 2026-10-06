package com.gateway.app.api.admin.payment.dto;

import java.util.List;

public record StuckPaymentsResponse(
    List<StuckPaymentResponse> createdTooLong, List<StuckPaymentResponse> pendingPastExpiry) {}
