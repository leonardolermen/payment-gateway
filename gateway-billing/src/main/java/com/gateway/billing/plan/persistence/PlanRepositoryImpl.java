package com.gateway.billing.plan.persistence;

import com.gateway.billing.plan.Plan;
import com.gateway.billing.plan.PlanInterval;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PlanRepositoryImpl implements PlanRepository {
  private final PlanJpaRepository jpa;

  @PersistenceContext private EntityManager entityManager;

  public PlanRepositoryImpl(PlanJpaRepository jpa) {
    this.jpa = jpa;
  }

  /** persist, not save: the id is assigned (a ULID), same reasoning as orders. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(Plan plan) {
    PlanEntity entity = new PlanEntity();
    entity.id = plan.id();
    entity.merchantId = plan.merchantId().value();
    entity.name = plan.name();
    entity.amount = plan.amount().cents();
    entity.currency = plan.amount().currency();
    entity.interval = plan.interval().name();
    entity.intervalCount = (short) plan.intervalCount();
    entity.trialDays = (short) plan.trialDays();
    entity.active = plan.active();
    entity.version = plan.version();
    entity.createdAt = plan.createdAt();
    entity.updatedAt = plan.updatedAt();
    entityManager.persist(entity);
  }

  @Override
  @Transactional
  public boolean update(Plan plan) {
    int updated =
        jpa.updateIfVersion(
            plan.id(),
            plan.version() - 1,
            plan.name(),
            plan.active(),
            plan.version(),
            plan.updatedAt());

    return updated == 1;
  }

  @Override
  public Optional<Plan> find(MerchantId merchantId, String id) {
    return jpa.findByIdAndMerchantId(id, merchantId.value()).map(this::toDomain);
  }

  @Override
  public List<Plan> list(MerchantId merchantId, Boolean active) {
    List<PlanEntity> entities =
        active == null
            ? jpa.findByMerchantIdOrderByCreatedAtDesc(merchantId.value())
            : jpa.findByMerchantIdAndActiveOrderByCreatedAtDesc(merchantId.value(), active);

    return entities.stream().map(this::toDomain).toList();
  }

  private Plan toDomain(PlanEntity entity) {
    return new Plan(
        entity.id,
        new MerchantId(entity.merchantId),
        entity.name,
        new Money(entity.amount, entity.currency),
        PlanInterval.valueOf(entity.interval),
        entity.intervalCount,
        entity.trialDays,
        entity.active,
        entity.version,
        entity.createdAt,
        entity.updatedAt);
  }
}
