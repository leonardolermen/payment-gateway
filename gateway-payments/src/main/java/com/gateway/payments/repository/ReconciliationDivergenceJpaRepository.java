package com.gateway.payments.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ReconciliationDivergenceJpaRepository extends JpaRepository<ReconciliationDivergenceEntity, String> {
  List<ReconciliationDivergenceEntity> findByStatus(String status);
}
