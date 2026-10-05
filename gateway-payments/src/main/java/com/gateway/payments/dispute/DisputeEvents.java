package com.gateway.payments.dispute;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.outbox.persistence.OutboxRepository;
import com.gateway.payments.reconciliation.ReconciliationDivergence;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells the merchant its dispute moved. The payload is built by hand, never by serialising the
 * divergence (same rule as PaymentEvents): the SYSTEM-only columns (detail, gateway status) are
 * ours, not the merchant's. Partitioned by payment, so it orders with that payment's own events.
 */
public class DisputeEvents {
  public static final String UPDATED = "dispute.updated";

  private final OutboxRepository outbox;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public DisputeEvents(OutboxRepository outbox, Clock clock) {
    this.outbox = outbox;
    this.clock = clock;
  }

  /** Same transaction as the caller. */
  public void updated(MerchantId merchantId, ReconciliationDivergence dispute) {
    outbox.append(
        new OutboxMessage(
            Ulid.next(),
            merchantId,
            dispute.id(),
            dispute.paymentId(),
            UPDATED,
            json.writeValueAsString(json(dispute)),
            "PENDING",
            null,
            clock.instant()));
  }

  public static Map<String, Object> json(ReconciliationDivergence dispute) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", dispute.id());
    body.put("payment_id", dispute.paymentId());
    body.put("reason", dispute.reason());
    body.put("status", dispute.status().name());
    body.put("resolution", dispute.resolution() == null ? null : dispute.resolution().name());
    body.put("resolution_note", dispute.resolutionNote());
    body.put("updated_at", dispute.updatedAt().toString());
    return body;
  }
}
