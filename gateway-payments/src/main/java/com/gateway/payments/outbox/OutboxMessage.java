package com.gateway.payments.outbox;

import com.gateway.kernel.ids.MerchantId;
import java.time.Instant;

/**
 * A transactional-outbox row: written in the same transaction as the domain change, published
 * later.
 */
public record OutboxMessage(
    String id,
    MerchantId merchantId,
    String aggregateId,
    String partitionKey,
    String eventType,
    String payload,
    String status,
    Instant claimedAt,
    Instant createdAt) {}
