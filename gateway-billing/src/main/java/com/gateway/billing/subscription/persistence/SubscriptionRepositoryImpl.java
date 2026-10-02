package com.gateway.billing.subscription.persistence;

import com.gateway.billing.subscription.BillingPeriod;
import com.gateway.billing.subscription.Subscription;
import com.gateway.billing.subscription.SubscriptionStatus;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SubscriptionRepositoryImpl implements SubscriptionRepository {
  private static final List<String> BILLABLE =
      List.of(SubscriptionStatus.ACTIVE.name(), SubscriptionStatus.PAST_DUE.name());

  private final SubscriptionJpaRepository jpa;

  @PersistenceContext private EntityManager entityManager;

  public SubscriptionRepositoryImpl(SubscriptionJpaRepository jpa) {
    this.jpa = jpa;
  }

  /** persist, not save: the id is assigned (a ULID), same reasoning as OrderRepositoryImpl. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(Subscription subscription) {
    SubscriptionEntity entity = new SubscriptionEntity();
    entity.id = subscription.id();
    entity.merchantId = subscription.merchantId().value();
    entity.environment = subscription.environment().name();
    entity.customerId = subscription.customerId();
    entity.planId = subscription.planId();
    entity.method = subscription.method().name();
    entity.cardId = subscription.cardId();
    entity.status = subscription.status().name();
    entity.anchorDay = (short) subscription.anchorDay();
    entity.currentPeriodStart = periodStart(subscription.currentPeriod());
    entity.currentPeriodEnd = periodEnd(subscription.currentPeriod());
    entity.nextBillingAt = subscription.nextBillingAt();
    entity.lastInvoiceNumber = subscription.lastInvoiceNumber();
    entity.cancelAtPeriodEnd = subscription.cancelAtPeriodEnd();
    entity.canceledAt = subscription.canceledAt();
    entity.endedAt = subscription.endedAt();
    entity.version = subscription.version();
    entity.createdAt = subscription.createdAt();
    entity.updatedAt = subscription.updatedAt();
    entityManager.persist(entity);
  }

  @Override
  @Transactional
  public boolean update(Subscription subscription) {
    int updated =
        jpa.updateIfVersion(
            subscription.id(),
            subscription.version() - 1,
            subscription.method().name(),
            subscription.cardId(),
            subscription.status().name(),
            periodStart(subscription.currentPeriod()),
            periodEnd(subscription.currentPeriod()),
            subscription.nextBillingAt(),
            subscription.lastInvoiceNumber(),
            subscription.cancelAtPeriodEnd(),
            subscription.canceledAt(),
            subscription.endedAt(),
            subscription.version(),
            subscription.updatedAt());

    return updated == 1;
  }

  @Override
  public Optional<Subscription> find(MerchantId merchantId, String id) {
    return jpa.findByIdAndMerchantId(id, merchantId.value()).map(this::toDomain);
  }

  @Override
  public Optional<Subscription> findById(String id) {
    return jpa.findById(id).map(this::toDomain);
  }

  @Override
  public List<Subscription> findByCustomer(MerchantId merchantId, String customerId) {
    return jpa
        .findByMerchantIdAndCustomerIdOrderByCreatedAtDesc(merchantId.value(), customerId)
        .stream()
        .map(this::toDomain)
        .toList();
  }

  @Override
  public boolean existsActiveForCustomer(MerchantId merchantId, String customerId) {
    return jpa.existsByMerchantIdAndCustomerIdAndStatusIn(merchantId.value(), customerId, BILLABLE);
  }

  private Subscription toDomain(SubscriptionEntity entity) {
    BillingPeriod period =
        entity.currentPeriodStart == null
            ? null
            : new BillingPeriod(entity.currentPeriodStart, entity.currentPeriodEnd);

    return Subscription.rehydrate(
        entity.id,
        new MerchantId(entity.merchantId),
        ProviderEnvironment.valueOf(entity.environment),
        entity.customerId,
        entity.planId,
        PaymentMethod.valueOf(entity.method),
        entity.cardId,
        SubscriptionStatus.valueOf(entity.status),
        entity.anchorDay,
        period,
        entity.nextBillingAt,
        entity.lastInvoiceNumber,
        entity.cancelAtPeriodEnd,
        entity.canceledAt,
        entity.endedAt,
        entity.version,
        entity.createdAt,
        entity.updatedAt);
  }

  private static LocalDate periodStart(BillingPeriod period) {
    return period == null ? null : period.start();
  }

  private static LocalDate periodEnd(BillingPeriod period) {
    return period == null ? null : period.end();
  }
}
