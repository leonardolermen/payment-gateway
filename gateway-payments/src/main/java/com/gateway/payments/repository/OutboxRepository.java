package com.gateway.payments.repository;

import com.gateway.payments.domain.OutboxMessage;
import java.time.Duration;
import java.util.List;

public interface OutboxRepository {
  /** Same transaction as the caller — the outbox row and the domain change commit or roll back together. */
  void append(OutboxMessage m);

  /** {@code FOR UPDATE SKIP LOCKED}; requires an active transaction. */
  List<OutboxMessage> claimPending(int limit, Duration lease);

  void markSent(String id);

  void release(String id);
}
