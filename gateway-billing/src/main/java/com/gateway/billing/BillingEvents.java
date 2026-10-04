package com.gateway.billing;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.outbox.persistence.OutboxRepository;
import java.time.Clock;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Billing writes to the same outbox as payments: one relay, one ordering per partition, one webhook
 * pipeline. Payloads are built by hand as maps, never by serialising the aggregate (same rule as
 * PaymentEvents).
 */
public class BillingEvents {
  private final OutboxRepository outbox;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public BillingEvents(OutboxRepository outbox, Clock clock) {
    this.outbox = outbox;
    this.clock = clock;
  }

  /** Same transaction as the caller. */
  public void emit(
      MerchantId merchantId,
      String type,
      String aggregateId,
      String partitionKey,
      Map<String, Object> body) {
    outbox.append(
        new OutboxMessage(
            Ulid.next(),
            merchantId,
            aggregateId,
            partitionKey,
            type,
            json.writeValueAsString(body),
            "PENDING",
            null,
            clock.instant()));
  }
}
