package com.gateway.payments.outbox;

/**
 * An in-process consumer of the outbox, called by the app's relay before the merchant's webhook
 * (spec §8). A listener that throws keeps the row unsent, so the next pass calls it again: every
 * implementation must be idempotent by {@link OutboxMessage#id()}.
 */
public interface OutboxListener {
  boolean handles(String eventType);

  void on(OutboxMessage message);
}
