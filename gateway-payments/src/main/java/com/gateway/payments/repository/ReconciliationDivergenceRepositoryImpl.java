package com.gateway.payments.repository;

import com.gateway.payments.domain.ReconciliationDivergence;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationDivergenceRepositoryImpl implements ReconciliationDivergenceRepository {
  private final ReconciliationDivergenceJpaRepository jpa;

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

  @Override
  public List<ReconciliationDivergence> open() {
    return jpa.findByStatus("OPEN").stream().map(ReconciliationDivergenceRepositoryImpl::toDomain).toList();
  }

  private static ReconciliationDivergence toDomain(ReconciliationDivergenceEntity e) {
    return new ReconciliationDivergence(e.id, e.paymentId, e.gatewayStatus, e.providerStatus, e.detail, e.status, e.createdAt);
  }
}
