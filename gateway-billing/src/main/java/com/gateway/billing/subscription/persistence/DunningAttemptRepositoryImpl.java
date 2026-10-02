package com.gateway.billing.subscription.persistence;

import com.gateway.billing.subscription.DunningAttempt;
import com.gateway.billing.subscription.DunningOutcome;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DunningAttemptRepositoryImpl implements DunningAttemptRepository {
  private final DunningAttemptJpaRepository jpa;

  @PersistenceContext private EntityManager entityManager;

  public DunningAttemptRepositoryImpl(DunningAttemptJpaRepository jpa) {
    this.jpa = jpa;
  }

  /** persist, not save: the id is assigned, so save would merge and SELECT first. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(DunningAttempt attempt) {
    entityManager.persist(toEntity(attempt));
  }

  /**
   * Flushed at once: closing an attempt and scheduling the next share a transaction, and Hibernate
   * flushes inserts before updates, so the new pending row would meet the old one still pending and
   * break uq_dunning_pending_order.
   */
  @Override
  @Transactional
  public void update(DunningAttempt attempt) {
    jpa.saveAndFlush(toEntity(attempt));
  }

  @Override
  public Optional<DunningAttempt> findById(String id) {
    return jpa.findById(id).map(DunningAttemptRepositoryImpl::toDomain);
  }

  @Override
  public List<DunningAttempt> findBySubscription(String subscriptionId) {
    return jpa.findBySubscriptionIdOrderByAttemptAsc(subscriptionId).stream()
        .map(DunningAttemptRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public Optional<DunningAttempt> findPendingByOrder(String orderId) {
    return jpa.findByOrderIdAndOutcomeIsNull(orderId).map(DunningAttemptRepositoryImpl::toDomain);
  }

  private static DunningAttemptEntity toEntity(DunningAttempt attempt) {
    DunningAttemptEntity entity = new DunningAttemptEntity();
    entity.id = attempt.id();
    entity.subscriptionId = attempt.subscriptionId();
    entity.orderId = attempt.orderId();
    entity.attempt = (short) attempt.attempt();
    entity.scheduledAt = attempt.scheduledAt();
    entity.ranAt = attempt.ranAt();
    entity.outcome = attempt.outcome() == null ? null : attempt.outcome().name();
    entity.paymentId = attempt.paymentId();

    return entity;
  }

  private static DunningAttempt toDomain(DunningAttemptEntity entity) {
    return new DunningAttempt(
        entity.id,
        entity.subscriptionId,
        entity.orderId,
        entity.attempt,
        entity.scheduledAt,
        entity.ranAt,
        entity.outcome == null ? null : DunningOutcome.valueOf(entity.outcome),
        entity.paymentId);
  }
}
