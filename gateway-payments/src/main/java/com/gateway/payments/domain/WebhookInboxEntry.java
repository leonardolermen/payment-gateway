package com.gateway.payments.domain;

import com.gateway.kernel.ids.MerchantId;
import java.time.Instant;

/** A raw provider webhook as received, before it is parsed into a domain event — kept for replay and audit. */
public record WebhookInboxEntry(
    String id,
    String provider,
    MerchantId merchantId,
    String rawHeaders,
    byte[] rawBody,
    String status,
    String error,
    Instant receivedAt) {}
