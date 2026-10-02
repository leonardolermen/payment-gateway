package com.gateway.payments.payment.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentDetailsJson;
import com.gateway.payments.payment.PaymentEvent;
import com.gateway.payments.payment.PaymentStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PaymentRepositoryImpl implements PaymentRepository {
  /** Must match the WHERE of {@code uq_payments_order_active}, or the 409 names the wrong row. */
  private static final List<String> ACTIVE_ATTEMPT_STATUSES =
      List.of(
          PaymentStatus.CREATED.name(),
          PaymentStatus.PENDING.name(),
          PaymentStatus.AUTHORIZED.name());

  private final PaymentJpaRepository jpa;
  private final PaymentEventJpaRepository eventsJpa;

  @PersistenceContext private EntityManager entityManager;

  public PaymentRepositoryImpl(PaymentJpaRepository jpa, PaymentEventJpaRepository eventsJpa) {
    this.jpa = jpa;
    this.eventsJpa = eventsJpa;
  }

  /**
   * {@code expected} is {@code payment.version()} minus {@code newEvents.size()}: the version the
   * aggregate had before the transitions that produced {@code newEvents} were applied in memory —
   * i.e. the version it was loaded (or created) at. {@code expected == 0} means the aggregate has
   * never been persisted (see {@link Payment#create}, which starts at version 1), so this is an
   * insert; any other value is an update guarded by that expected version.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Payment save(Payment payment, List<PaymentEvent> newEvents) {
    long newVersion = payment.version();
    long expectedVersion = newVersion - newEvents.size();
    String details = PaymentDetailsJson.write(payment.pix(), payment.boleto(), payment.card());

    if (expectedVersion == 0) {
      PaymentEntity entity = new PaymentEntity();
      entity.id = payment.id();
      entity.merchantId = payment.merchantId().value();
      entity.environment = payment.environment().name();
      entity.provider = payment.provider();
      entity.method = payment.method().name();
      entity.status = payment.status().name();
      entity.amount = payment.amount().cents();
      entity.currency = payment.amount().currency();
      entity.reference = payment.reference();
      entity.orderId = payment.orderId();
      entity.description = payment.description();
      entity.customerDocumentHash = payment.customerDocumentHash();
      entity.details = details;
      entity.expiresAt = payment.expiresAt();
      entity.paidAt = payment.paidAt();
      entity.paidAmount = payment.paidAmount() == null ? null : payment.paidAmount().cents();
      entity.refundedAmount = payment.refundedAmount().cents();
      entity.version = newVersion;
      entity.createdAt = payment.createdAt();
      entity.updatedAt = payment.updatedAt();
      // persist, not jpa.save: the id is already assigned (a ULID), so save() would go through
      // Hibernate's merge path (a SELECT to check whether the row exists, then an INSERT) — an
      // unnecessary round trip for a row we know is brand new. persist() inserts directly.
      entityManager.persist(entity);
    } else {
      int updated =
          jpa.updateIfVersionMatches(
              payment.id(),
              payment.status().name(),
              details,
              payment.expiresAt(),
              payment.paidAt(),
              payment.paidAmount() == null ? null : payment.paidAmount().cents(),
              payment.refundedAmount().cents(),
              newVersion,
              payment.updatedAt(),
              expectedVersion);
      if (updated == 0) {
        throw new ObjectOptimisticLockingFailureException(PaymentEntity.class, payment.id());
      }
    }

    for (PaymentEvent event : newEvents) {
      // Same reasoning as the payment row above: every event is a brand-new row with an assigned
      // id, so persist() (direct INSERT) instead of save() (SELECT-then-INSERT/UPDATE merge).
      entityManager.persist(toEventEntity(event));
    }
    return payment;
  }

  @Override
  public Optional<Payment> findById(String id) {
    return jpa.findById(id).map(PaymentRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Payment> findActiveByOrder(String orderId) {
    return jpa.findFirstByOrderIdAndStatusIn(orderId, ACTIVE_ATTEMPT_STATUSES)
        .map(PaymentRepositoryImpl::toDomain);
  }

  @Override
  public List<Payment> listByMerchantAndOrder(MerchantId merchantId, String orderId) {
    return jpa.findByMerchantIdAndOrderIdOrderByCreatedAt(merchantId.value(), orderId).stream()
        .map(PaymentRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public Optional<Payment> findByMerchantAndId(MerchantId merchantId, String id) {
    return jpa.findByMerchantIdAndId(merchantId.value(), id).map(PaymentRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Payment> findByMerchantAndTxid(
      MerchantId merchantId, String provider, String txid) {
    return jpa.findByProviderAndTxid(provider, txid)
        .filter(entity -> entity.merchantId.equals(merchantId.value()))
        .map(PaymentRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Payment> findByMerchantAndCardPaymentId(
      MerchantId merchantId, String provider, String cardPaymentId) {
    return jpa.findByMerchantAndCardPaymentId(merchantId.value(), provider, cardPaymentId)
        .map(PaymentRepositoryImpl::toDomain);
  }

  @Override
  public List<Payment> listByMerchant(MerchantId merchantId, int limit, String cursorId) {
    return jpa.findByMerchant(merchantId.value(), cursorId, Limit.of(limit)).stream()
        .map(PaymentRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public List<Payment> listByMerchantAndReference(
      MerchantId merchantId, String reference, int limit) {
    return jpa
        .findByMerchantIdAndReferenceOrderByIdDesc(merchantId.value(), reference, Limit.of(limit))
        .stream()
        .map(PaymentRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public List<Payment> findPendingOlderThan(Instant expiresBefore, int limit) {
    return jpa.findPendingOlderThan(expiresBefore, Limit.of(limit)).stream()
        .map(PaymentRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public List<Payment> findByMethodAndStatusIn(
      com.gateway.kernel.payment.PaymentMethod method,
      Set<PaymentStatus> statuses,
      Instant createdAfter,
      int limit) {
    Set<String> names = statuses.stream().map(Enum::name).collect(Collectors.toSet());
    return jpa
        .findByMethodAndStatusInAndCreatedAtAfter(
            method.name(), names, createdAfter, Limit.of(limit))
        .stream()
        .map(PaymentRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public List<Payment> findNewestByMethodAndStatusIn(
      com.gateway.kernel.payment.PaymentMethod method,
      Set<PaymentStatus> statuses,
      Instant createdAfter,
      int limit) {
    Set<String> names = statuses.stream().map(Enum::name).collect(Collectors.toSet());
    return jpa
        .findByMethodAndStatusInAndCreatedAtAfterNewestFirst(
            method.name(), names, createdAfter, Limit.of(limit))
        .stream()
        .map(PaymentRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public List<Payment> findByStatusCreatedBeforeWithoutOpenDivergence(
      PaymentStatus status, Instant createdBefore, String kind, int limit) {
    return jpa
        .findByStatusCreatedBeforeWithoutOpenDivergence(status.name(), createdBefore, kind, limit)
        .stream()
        .map(PaymentRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public List<Payment> findByStatusIn(
      Set<PaymentStatus> statuses, Instant createdAfter, int limit) {
    Set<String> names = statuses.stream().map(Enum::name).collect(Collectors.toSet());
    return jpa.findByStatusInAndCreatedAtAfter(names, createdAfter, Limit.of(limit)).stream()
        .map(PaymentRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public Optional<Payment> findByIdForUpdate(String id) {
    // Same guard as JobRepositoryImpl.claimDue: outside a transaction the lock is released the
    // instant it is taken, and the caller would believe it holds it.
    if (!org.springframework.transaction.support.TransactionSynchronizationManager
        .isActualTransactionActive()) {
      throw new IllegalStateException(
          "findByIdForUpdate must run inside a transaction: the row lock depends on it.");
    }
    return jpa.findByIdForUpdate(id).map(PaymentRepositoryImpl::toDomain);
  }

  @Override
  public List<Payment> findByStatusCreatedBefore(
      PaymentStatus status, Instant createdBefore, int limit) {
    return jpa
        .findByStatusAndCreatedAtBefore(status.name(), createdBefore, Limit.of(limit))
        .stream()
        .map(PaymentRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public List<PaymentEvent> events(String paymentId) {
    return eventsJpa.findByPaymentIdOrderBySequenceAsc(paymentId).stream()
        .map(PaymentRepositoryImpl::toEventDomain)
        .toList();
  }

  private static PaymentEventEntity toEventEntity(PaymentEvent event) {
    PaymentEventEntity entity = new PaymentEventEntity();
    entity.id = event.id();
    entity.paymentId = event.paymentId();
    entity.sequence = event.sequence();
    entity.type = event.type();
    entity.source = event.source().name();
    entity.payload = event.payload();
    entity.createdAt = event.at();
    return entity;
  }

  private static PaymentEvent toEventDomain(PaymentEventEntity entity) {
    return new PaymentEvent(
        entity.id,
        entity.paymentId,
        entity.sequence,
        entity.type,
        EventSource.valueOf(entity.source),
        entity.payload,
        entity.createdAt);
  }

  private static Payment toDomain(PaymentEntity entity) {
    return Payment.rehydrate(
        entity.id,
        new MerchantId(entity.merchantId),
        ProviderEnvironment.valueOf(entity.environment),
        entity.provider,
        PaymentMethod.valueOf(entity.method),
        PaymentStatus.valueOf(entity.status),
        new Money(entity.amount, entity.currency),
        entity.reference,
        entity.description,
        entity.customerDocumentHash,
        PaymentDetailsJson.readPix(entity.details),
        PaymentDetailsJson.readBoleto(entity.details),
        PaymentDetailsJson.readCard(entity.details),
        entity.expiresAt,
        entity.paidAt,
        entity.paidAmount == null ? null : new Money(entity.paidAmount, entity.currency),
        new Money(entity.refundedAmount, entity.currency),
        entity.version,
        entity.createdAt,
        entity.updatedAt,
        entity.orderId,
        Clock.systemUTC());
  }
}
