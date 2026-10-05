package com.gateway.payments.reconciliation.persistence;

import com.gateway.payments.reconciliation.DivergenceCount;
import com.gateway.payments.reconciliation.DivergenceOrigin;
import com.gateway.payments.reconciliation.DivergenceQuery;
import com.gateway.payments.reconciliation.DivergenceResolution;
import com.gateway.payments.reconciliation.DivergenceStatus;
import com.gateway.payments.reconciliation.ReconciliationDivergence;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.TypedQuery;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReconciliationDivergenceRepositoryImpl implements ReconciliationDivergenceRepository {
  private static final List<String> DISPUTE_OPEN_STATUSES =
      List.of(DivergenceStatus.OPEN.name(), DivergenceStatus.UNDER_REVIEW.name());

  private final ReconciliationDivergenceJpaRepository jpa;

  @PersistenceContext private EntityManager entityManager;

  public ReconciliationDivergenceRepositoryImpl(ReconciliationDivergenceJpaRepository jpa) {
    this.jpa = jpa;
  }

  /**
   * ON CONFLICT DO NOTHING against the partial unique indexes instead of "load every OPEN row and
   * look": that scan grew with the table and still let two concurrent writers miss each other. No
   * conflict target on purpose: it covers the SYSTEM index and the dispute index alike, and a
   * conflict never raises, so the caller's transaction is never poisoned. REQUIRED, not MANDATORY:
   * reconciliation calls this outside any other transaction.
   */
  @Override
  @Transactional
  public boolean openIfAbsent(ReconciliationDivergence divergence) {
    int inserted =
        entityManager
            .createNativeQuery(
                """
                INSERT INTO payments.reconciliation_divergences
                    (id, payment_id, origin, gateway_status, provider_status, detail, reason,
                     merchant_note, status, created_at, updated_at)
                VALUES (:id, :paymentId, :origin, :gatewayStatus, :providerStatus, :detail, :reason,
                        :merchantNote, 'OPEN', :createdAt, :updatedAt)
                ON CONFLICT DO NOTHING
                """)
            .setParameter("id", divergence.id())
            .setParameter("paymentId", divergence.paymentId())
            .setParameter("origin", divergence.origin().name())
            .setParameter("gatewayStatus", divergence.gatewayStatus())
            .setParameter("providerStatus", divergence.providerStatus())
            .setParameter("detail", divergence.detail())
            .setParameter("reason", divergence.reason())
            .setParameter("merchantNote", divergence.merchantNote())
            .setParameter("createdAt", Timestamp.from(divergence.createdAt()))
            .setParameter("updatedAt", Timestamp.from(divergence.updatedAt()))
            .executeUpdate();
    return inserted == 1;
  }

  @Override
  public boolean hasOpen(String paymentId, String providerStatus) {
    return jpa.existsByPaymentIdAndProviderStatusAndStatusAndOrigin(
        paymentId, providerStatus, DivergenceStatus.OPEN.name(), DivergenceOrigin.SYSTEM.name());
  }

  @Override
  public List<ReconciliationDivergence> open() {
    return jpa.findByStatus(DivergenceStatus.OPEN.name()).stream()
        .map(ReconciliationDivergenceRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public Optional<ReconciliationDivergence> findById(String id) {
    return jpa.findById(id).map(ReconciliationDivergenceRepositoryImpl::toDomain);
  }

  /**
   * Built per call rather than one {@code @Query} with {@code (:x IS NULL OR …)}: Postgres cannot
   * infer the type of a parameter that is only ever compared with NULL, and a predicate that is
   * absent from the SQL is one the planner can use the listing index for.
   */
  @Override
  public List<ReconciliationDivergence> find(DivergenceQuery query) {
    StringBuilder jpql = new StringBuilder("SELECT d FROM ReconciliationDivergenceEntity d");
    Map<String, Object> parameters = new LinkedHashMap<>();
    List<String> predicates = new ArrayList<>();

    if (query.merchantId() != null) {
      jpql.append(" JOIN PaymentEntity p ON p.id = d.paymentId");
      predicates.add("p.merchantId = :merchantId");
      parameters.put("merchantId", query.merchantId());
    }
    if (query.status() != null) {
      predicates.add("d.status = :status");
      parameters.put("status", query.status().name());
    }
    if (query.origin() != null) {
      predicates.add("d.origin = :origin");
      parameters.put("origin", query.origin().name());
    }
    if (query.kind() != null) {
      predicates.add("d.providerStatus = :kind");
      parameters.put("kind", query.kind());
    }
    if (query.since() != null) {
      predicates.add("d.createdAt >= :since");
      parameters.put("since", query.since());
    }
    if (query.afterId() != null) {
      predicates.add("d.id < :afterId");
      parameters.put("afterId", query.afterId());
    }

    if (!predicates.isEmpty()) {
      jpql.append(" WHERE ").append(String.join(" AND ", predicates));
    }
    jpql.append(" ORDER BY d.createdAt DESC, d.id DESC");

    TypedQuery<ReconciliationDivergenceEntity> typed =
        entityManager.createQuery(jpql.toString(), ReconciliationDivergenceEntity.class);
    parameters.forEach(typed::setParameter);

    return typed.setMaxResults(query.limit()).getResultList().stream()
        .map(ReconciliationDivergenceRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  @Transactional
  public boolean update(ReconciliationDivergence divergence) {
    DivergenceResolution resolution = divergence.resolution();

    int updated =
        jpa.updateIfUnchanged(
            divergence.id(),
            divergence.status().name(),
            resolution == null ? null : resolution.name(),
            divergence.resolutionNote(),
            divergence.resolvedBy(),
            divergence.resolvedAt(),
            divergence.updatedAt(),
            divergence.loadedUpdatedAt());

    return updated == 1;
  }

  @Override
  public Optional<ReconciliationDivergence> findOpenDispute(String paymentId) {
    return jpa.findFirstByPaymentIdAndOriginAndStatusIn(
            paymentId, DivergenceOrigin.MERCHANT.name(), DISPUTE_OPEN_STATUSES)
        .map(ReconciliationDivergenceRepositoryImpl::toDomain);
  }

  @Override
  public List<ReconciliationDivergence> findByPayment(String paymentId) {
    return jpa.findByPaymentIdOrderByCreatedAtDescIdDesc(paymentId).stream()
        .map(ReconciliationDivergenceRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public List<DivergenceCount> countOpenByOriginAndKind() {
    return jpa.countOpenByOriginAndKind().stream()
        .map(
            row ->
                new DivergenceCount(
                    DivergenceOrigin.valueOf((String) row[0]),
                    (String) row[1],
                    ((Number) row[2]).longValue()))
        .toList();
  }

  private static ReconciliationDivergence toDomain(ReconciliationDivergenceEntity entity) {
    return ReconciliationDivergence.rehydrate(
        entity.id,
        entity.paymentId,
        DivergenceOrigin.valueOf(entity.origin),
        entity.gatewayStatus,
        entity.providerStatus,
        entity.detail,
        entity.reason,
        entity.merchantNote,
        DivergenceStatus.valueOf(entity.status),
        entity.resolution == null ? null : DivergenceResolution.valueOf(entity.resolution),
        entity.resolutionNote,
        entity.resolvedBy,
        entity.resolvedAt,
        entity.createdAt,
        entity.updatedAt);
  }
}
