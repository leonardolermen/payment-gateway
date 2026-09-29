package com.gateway.app.api.payment.dto;

/** POST /v1/payments/{id}/capture. No amount captures everything authorized. */
public record CaptureRequestBody(Long amount) {}
