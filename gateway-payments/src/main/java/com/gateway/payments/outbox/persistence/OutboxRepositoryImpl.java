package com.gateway.payments.outbox.persistence;

import com.gateway.payments.outbox.OutboxMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Repository
public class OutboxRepositoryImpl implements OutboxRepository {
  private final OutboxJpaRepository jpa;

  public OutboxRepositoryImpl(OutboxJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(OutboxMessage m) {
    OutboxEntity e = new OutboxEntity();
    e.id = m.id();
    e.merchantId = m.merchantId().value();
    e.aggregateId = m.aggregateId();
    e.partitionKey = m.partitionKey();
    e.eventType = m.eventType();
    e.payload = m.payload();
    e.status = m.status();
    e.claimedAt = m.claimedAt();
    e.createdAt = m.createdAt();
    jpa.save(e);
  }

  /**
   * Same shape as {@code JobRepositoryImpl.claimDue}: {@code PESSIMISTIC_WRITE} + {@code SKIP
   * LOCKED} only stops two claimers landing on the same row, not a claim running with no
   * transaction at all — under autocommit, the lock is released the instant it is taken, and
   * "protected" would be a lie. Refusing loudly beats silently claiming unprotected.
   */
  @Override
  public List<OutboxMessage> claimPending(int limit, Duration lease) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("claimPending must run inside a transaction: the SKIP LOCKED claim depends on it.");
    }
    Instant now = Instant.now();
    List<OutboxEntity> claimable = jpa.selectClaimable(now.minus(lease), Limit.of(limit));
    claimable.forEach(e -> e.claimedAt = now);
    return claimable.stream().map(OutboxRepositoryImpl::toDomain).toList();
  }

  @Override
  @Transactional
  public void markSent(String id) {
    jpa.markSent(id);
  }

  @Override
  @Transactional
  public void release(String id) {
    jpa.release(id);
  }

  private static OutboxMessage toDomain(OutboxEntity e) {
    return new OutboxMessage(
        e.id,
        new com.gateway.kernel.ids.MerchantId(e.merchantId),
        e.aggregateId,
        e.partitionKey,
        e.eventType,
        e.payload,
        e.status,
        e.claimedAt,
        e.createdAt);
  }
}
