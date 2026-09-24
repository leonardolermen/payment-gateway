package com.gateway.payments.repository;

import com.gateway.payments.domain.ReconciliationDivergence;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class ReconciliationDivergenceRepositoryImpl implements ReconciliationDivergenceRepository {
  private final ReconciliationDivergenceJpaRepository jpa;

  @PersistenceContext private EntityManager em;

  public ReconciliationDivergenceRepositoryImpl(ReconciliationDivergenceJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public void save(ReconciliationDivergence d) {
    ReconciliationDivergenceEntity e = jpa.findById(d.id()).orElseGet(ReconciliationDivergenceEntity::new);
    e.id = d.id();
    e.paymentId = d.paymentId();
    e.gatewayStatus = d.gatewayStatus();
    e.providerStatus = d.providerStatus();
    e.detail = d.detail();
    e.status = d.status();
    e.createdAt = d.createdAt();
    jpa.save(e);
  }

  /**
   * ON CONFLICT DO NOTHING against the partial unique index instead of "load every OPEN row and
   * look": that scan grew with the table and still let two concurrent writers miss each other.
   * REQUIRED, not MANDATORY: reconciliation calls this outside any other transaction.
   */
  @Override
  @Transactional
  public boolean openIfAbsent(ReconciliationDivergence d) {
    int inserted =
        em.createNativeQuery(
                """
                INSERT INTO payments.reconciliation_divergences (id, payment_id, gateway_status, provider_status, detail, status, created_at)
                VALUES (:id, :paymentId, :gatewayStatus, :providerStatus, :detail, 'OPEN', :createdAt)
                ON CONFLICT DO NOTHING
                """)
            .setParameter("id", d.id())
            .setParameter("paymentId", d.paymentId())
            .setParameter("gatewayStatus", d.gatewayStatus())
            .setParameter("providerStatus", d.providerStatus())
            .setParameter("detail", d.detail())
            .setParameter("createdAt", Timestamp.from(d.createdAt()))
            .executeUpdate();
    return inserted == 1;
  }

  @Override
  public List<ReconciliationDivergence> open() {
    return jpa.findByStatus("OPEN").stream().map(ReconciliationDivergenceRepositoryImpl::toDomain).toList();
  }

  private static ReconciliationDivergence toDomain(ReconciliationDivergenceEntity e) {
    return new ReconciliationDivergence(e.id, e.paymentId, e.gatewayStatus, e.providerStatus, e.detail, e.status, e.createdAt);
  }
}
