package com.gateway.payments.reconciliation.persistence;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReconciliationDivergenceJpaRepository extends JpaRepository<ReconciliationDivergenceEntity, String> {
  List<ReconciliationDivergenceEntity> findByStatus(String status);
}
