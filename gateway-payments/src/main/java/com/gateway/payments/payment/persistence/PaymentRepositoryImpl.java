package com.gateway.payments.payment.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentDetailsJson;
import com.gateway.payments.payment.PaymentEvent;
import com.gateway.kernel.payment.PaymentMethod;
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
  private final PaymentJpaRepository jpa;
  private final PaymentEventJpaRepository eventsJpa;

  @PersistenceContext private EntityManager em;

  public PaymentRepositoryImpl(PaymentJpaRepository jpa, PaymentEventJpaRepository eventsJpa) {
    this.jpa = jpa;
    this.eventsJpa = eventsJpa;
  }

  /**
   * {@code expected} is {@code p.version()} minus {@code newEvents.size()}: the version the
   * aggregate had before the transitions that produced {@code newEvents} were applied in memory —
   * i.e. the version it was loaded (or created) at. {@code expected == 0} means the aggregate has
   * never been persisted (see {@link Payment#create}, which starts at version 1), so this is an
   * insert; any other value is an update guarded by that expected version.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public Payment save(Payment p, List<PaymentEvent> newEvents) {
    long newVersion = p.version();
    long expectedVersion = newVersion - newEvents.size();
    String details = PaymentDetailsJson.write(p.pix(), p.boleto());

    if (expectedVersion == 0) {
      PaymentEntity e = new PaymentEntity();
      e.id = p.id();
      e.merchantId = p.merchantId().value();
      e.environment = p.environment().name();
      e.provider = p.provider();
      e.method = p.method().name();
      e.status = p.status().name();
      e.amount = p.amount().cents();
      e.currency = p.amount().currency();
      e.reference = p.reference();
      e.description = p.description();
      e.customerDocumentHash = p.customerDocumentHash();
      e.details = details;
      e.expiresAt = p.expiresAt();
      e.paidAt = p.paidAt();
      e.paidAmount = p.paidAmount() == null ? null : p.paidAmount().cents();
      e.refundedAmount = p.refundedAmount().cents();
      e.version = newVersion;
      e.createdAt = p.createdAt();
      e.updatedAt = p.updatedAt();
      // persist, not jpa.save: the id is already assigned (a ULID), so save() would go through
      // Hibernate's merge path (a SELECT to check whether the row exists, then an INSERT) — an
      // unnecessary round trip for a row we know is brand new. persist() inserts directly.
      em.persist(e);
    } else {
      int updated =
          jpa.updateIfVersionMatches(
              p.id(),
              p.status().name(),
              details,
              p.expiresAt(),
              p.paidAt(),
              p.paidAmount() == null ? null : p.paidAmount().cents(),
              p.refundedAmount().cents(),
              newVersion,
              p.updatedAt(),
              expectedVersion);
      if (updated == 0) {
        throw new ObjectOptimisticLockingFailureException(PaymentEntity.class, p.id());
      }
    }

    for (PaymentEvent event : newEvents) {
      // Same reasoning as the payment row above: every event is a brand-new row with an assigned
      // id, so persist() (direct INSERT) instead of save() (SELECT-then-INSERT/UPDATE merge).
      em.persist(toEventEntity(event));
    }
    return p;
  }

  @Override
  public Optional<Payment> findById(String id) {
    return jpa.findById(id).map(PaymentRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Payment> findByMerchantAndId(MerchantId merchantId, String id) {
    return jpa.findByMerchantIdAndId(merchantId.value(), id).map(PaymentRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Payment> findByMerchantAndTxid(MerchantId merchantId, String provider, String txid) {
    return jpa.findByProviderAndTxid(provider, txid).filter(e -> e.merchantId.equals(merchantId.value())).map(PaymentRepositoryImpl::toDomain);
  }

  @Override
  public List<Payment> listByMerchant(MerchantId merchantId, int limit, String cursorId) {
    return jpa.findByMerchant(merchantId.value(), cursorId, Limit.of(limit)).stream().map(PaymentRepositoryImpl::toDomain).toList();
  }

  @Override
  public List<Payment> listByMerchantAndReference(MerchantId merchantId, String reference, int limit) {
    return jpa.findByMerchantIdAndReferenceOrderByIdDesc(merchantId.value(), reference, Limit.of(limit)).stream().map(PaymentRepositoryImpl::toDomain).toList();
  }

  @Override
  public List<Payment> findPendingOlderThan(Instant expiresBefore, int limit) {
    return jpa.findPendingOlderThan(expiresBefore, Limit.of(limit)).stream().map(PaymentRepositoryImpl::toDomain).toList();
  }

  @Override
  public List<Payment> findByMethodAndStatusIn(com.gateway.kernel.payment.PaymentMethod method, Set<PaymentStatus> statuses, Instant createdAfter, int limit) {
    Set<String> names = statuses.stream().map(Enum::name).collect(Collectors.toSet());
    return jpa.findByMethodAndStatusInAndCreatedAtAfter(method.name(), names, createdAfter, Limit.of(limit)).stream().map(PaymentRepositoryImpl::toDomain).toList();
  }

  @Override
  public List<Payment> findByStatusIn(Set<PaymentStatus> statuses, Instant createdAfter, int limit) {
    Set<String> names = statuses.stream().map(Enum::name).collect(Collectors.toSet());
    return jpa.findByStatusInAndCreatedAtAfter(names, createdAfter, Limit.of(limit)).stream().map(PaymentRepositoryImpl::toDomain).toList();
  }

  @Override
  public Optional<Payment> findByIdForUpdate(String id) {
    // Same guard as JobRepositoryImpl.claimDue: outside a transaction the lock is released the
    // instant it is taken, and the caller would believe it holds it.
    if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("findByIdForUpdate must run inside a transaction: the row lock depends on it.");
    }
    return jpa.findByIdForUpdate(id).map(PaymentRepositoryImpl::toDomain);
  }

  @Override
  public List<Payment> findByStatusCreatedBefore(PaymentStatus status, Instant createdBefore, int limit) {
    return jpa.findByStatusAndCreatedAtBefore(status.name(), createdBefore, Limit.of(limit)).stream().map(PaymentRepositoryImpl::toDomain).toList();
  }

  @Override
  public List<PaymentEvent> events(String paymentId) {
    return eventsJpa.findByPaymentIdOrderBySequenceAsc(paymentId).stream().map(PaymentRepositoryImpl::toEventDomain).toList();
  }

  private static PaymentEventEntity toEventEntity(PaymentEvent event) {
    PaymentEventEntity e = new PaymentEventEntity();
    e.id = event.id();
    e.paymentId = event.paymentId();
    e.sequence = event.sequence();
    e.type = event.type();
    e.source = event.source().name();
    e.payload = event.payload();
    e.createdAt = event.at();
    return e;
  }

  private static PaymentEvent toEventDomain(PaymentEventEntity e) {
    return new PaymentEvent(e.id, e.paymentId, e.sequence, e.type, EventSource.valueOf(e.source), e.payload, e.createdAt);
  }

  private static Payment toDomain(PaymentEntity e) {
    return Payment.rehydrate(
        e.id,
        new MerchantId(e.merchantId),
        ProviderEnvironment.valueOf(e.environment),
        e.provider,
        PaymentMethod.valueOf(e.method),
        PaymentStatus.valueOf(e.status),
        new Money(e.amount, e.currency),
        e.reference,
        e.description,
        e.customerDocumentHash,
        PaymentDetailsJson.readPix(e.details),
        PaymentDetailsJson.readBoleto(e.details),
        e.expiresAt,
        e.paidAt,
        e.paidAmount == null ? null : new Money(e.paidAmount, e.currency),
        new Money(e.refundedAmount, e.currency),
        e.version,
        e.createdAt,
        e.updatedAt,
        Clock.systemUTC());
  }
}
