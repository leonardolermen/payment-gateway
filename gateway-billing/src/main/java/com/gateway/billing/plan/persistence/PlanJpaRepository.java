package com.gateway.billing.plan.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface PlanJpaRepository extends JpaRepository<PlanEntity, String> {
  Optional<PlanEntity> findByIdAndMerchantId(String id, String merchantId);

  List<PlanEntity> findByMerchantIdOrderByCreatedAtDesc(String merchantId);

  List<PlanEntity> findByMerchantIdAndActiveOrderByCreatedAtDesc(String merchantId, boolean active);

  /** clearAutomatically: a bulk UPDATE bypasses the persistence context; same as orders. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update PlanEntity p set p.name = :name, p.active = :active, p.version = :version, "
          + "p.updatedAt = :updatedAt where p.id = :id and p.version = :expectedVersion")
  int updateIfVersion(
      String id,
      long expectedVersion,
      String name,
      boolean active,
      long version,
      Instant updatedAt);
}
