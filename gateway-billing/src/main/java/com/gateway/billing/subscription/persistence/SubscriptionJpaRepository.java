package com.gateway.billing.subscription.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SubscriptionJpaRepository extends JpaRepository<SubscriptionEntity, String> {
  Optional<SubscriptionEntity> findByIdAndMerchantId(String id, String merchantId);

  List<SubscriptionEntity> findByMerchantIdAndCustomerIdOrderByCreatedAtDesc(
      String merchantId, String customerId);

  /** Ids are ULIDs, so {@code id DESC} is creation order and the last id is the cursor. */
  @Query(
      "SELECT s FROM SubscriptionEntity s WHERE s.merchantId = :merchantId"
          + " AND s.environment = :environment AND (:status IS NULL OR s.status = :status)"
          + " AND (:cursorId IS NULL OR s.id < :cursorId) ORDER BY s.id DESC")
  List<SubscriptionEntity> findPage(
      @Param("merchantId") String merchantId,
      @Param("environment") String environment,
      @Param("status") String status,
      @Param("cursorId") String cursorId,
      Limit limit);

  boolean existsByMerchantIdAndCustomerIdAndStatusIn(
      String merchantId, String customerId, Collection<String> statuses);

  /** clearAutomatically for the same reason as the order update: a later find reads fresh. */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update SubscriptionEntity s set s.method = :method, s.cardId = :cardId, "
          + "s.status = :status, s.currentPeriodStart = :currentPeriodStart, "
          + "s.currentPeriodEnd = :currentPeriodEnd, s.nextBillingAt = :nextBillingAt, "
          + "s.lastInvoiceNumber = :lastInvoiceNumber, s.cancelAtPeriodEnd = :cancelAtPeriodEnd, "
          + "s.canceledAt = :canceledAt, s.endedAt = :endedAt, s.version = :version, "
          + "s.updatedAt = :updatedAt where s.id = :id and s.version = :expectedVersion")
  int updateIfVersion(
      String id,
      long expectedVersion,
      String method,
      String cardId,
      String status,
      LocalDate currentPeriodStart,
      LocalDate currentPeriodEnd,
      Instant nextBillingAt,
      int lastInvoiceNumber,
      boolean cancelAtPeriodEnd,
      Instant canceledAt,
      Instant endedAt,
      long version,
      Instant updatedAt);
}
