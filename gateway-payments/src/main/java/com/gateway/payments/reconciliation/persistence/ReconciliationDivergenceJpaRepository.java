package com.gateway.payments.reconciliation.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface ReconciliationDivergenceJpaRepository
    extends JpaRepository<ReconciliationDivergenceEntity, String> {
  List<ReconciliationDivergenceEntity> findByStatus(String status);

  boolean existsByPaymentIdAndProviderStatusAndStatusAndOrigin(
      String paymentId, String providerStatus, String status, String origin);

  Optional<ReconciliationDivergenceEntity> findFirstByPaymentIdAndOriginAndStatusIn(
      String paymentId, String origin, Collection<String> statuses);

  List<ReconciliationDivergenceEntity> findByPaymentIdOrderByCreatedAtDescIdDesc(String paymentId);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE ReconciliationDivergenceEntity d
         SET d.status = :status, d.resolution = :resolution, d.resolutionNote = :resolutionNote,
             d.resolvedBy = :resolvedBy, d.resolvedAt = :resolvedAt, d.updatedAt = :updatedAt
       WHERE d.id = :id AND d.updatedAt = :loadedUpdatedAt
      """)
  int updateIfUnchanged(
      @Param("id") String id,
      @Param("status") String status,
      @Param("resolution") String resolution,
      @Param("resolutionNote") String resolutionNote,
      @Param("resolvedBy") String resolvedBy,
      @Param("resolvedAt") Instant resolvedAt,
      @Param("updatedAt") Instant updatedAt,
      @Param("loadedUpdatedAt") Instant loadedUpdatedAt);

  @Query(
      nativeQuery = true,
      value =
          """
          SELECT origin, provider_status, count(*)
            FROM payments.reconciliation_divergences
           WHERE status IN ('OPEN', 'UNDER_REVIEW')
           GROUP BY 1, 2
          """)
  List<Object[]> countOpenByOriginAndKind();
}
