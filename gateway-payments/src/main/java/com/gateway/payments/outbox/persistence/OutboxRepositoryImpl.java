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
  public void append(OutboxMessage message) {
    OutboxEntity entity = new OutboxEntity();
    entity.id = message.id();
    entity.merchantId = message.merchantId().value();
    entity.aggregateId = message.aggregateId();
    entity.partitionKey = message.partitionKey();
    entity.eventType = message.eventType();
    entity.payload = message.payload();
    entity.status = message.status();
    entity.claimedAt = message.claimedAt();
    entity.createdAt = message.createdAt();
    jpa.save(entity);
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
      throw new IllegalStateException(
          "claimPending must run inside a transaction: the SKIP LOCKED claim depends on it.");
    }
    Instant now = Instant.now();
    List<OutboxEntity> claimable = jpa.selectClaimable(now.minus(lease), Limit.of(limit));
    claimable.forEach(message -> message.claimedAt = now);
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

  private static OutboxMessage toDomain(OutboxEntity entity) {
    return new OutboxMessage(
        entity.id,
        new com.gateway.kernel.ids.MerchantId(entity.merchantId),
        entity.aggregateId,
        entity.partitionKey,
        entity.eventType,
        entity.payload,
        entity.status,
        entity.claimedAt,
        entity.createdAt);
  }
}
