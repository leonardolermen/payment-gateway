package com.gateway.billing.order.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {
  Optional<OrderEntity> findByCheckoutTokenHash(String checkoutTokenHash);

  Optional<OrderEntity> findByIdAndMerchantId(String id, String merchantId);

  List<OrderEntity> findByMerchantIdAndReferenceOrderByCreatedAtDesc(
      String merchantId, String reference, Pageable page);

  /** Ids are ULIDs, so {@code id DESC} is creation order and the last id is the cursor. */
  @Query(
      "SELECT o FROM OrderEntity o WHERE o.merchantId = :merchantId"
          + " AND o.environment = :environment AND (:status IS NULL OR o.status = :status)"
          + " AND (:cursorId IS NULL OR o.id < :cursorId) ORDER BY o.id DESC")
  List<OrderEntity> findPage(
      @Param("merchantId") String merchantId,
      @Param("environment") String environment,
      @Param("status") String status,
      @Param("cursorId") String cursorId,
      Limit limit);

  /** The customer's history is cross-environment by nature: a customer lives in one environment. */
  @Query(
      "SELECT o FROM OrderEntity o WHERE o.merchantId = :merchantId"
          + " AND o.customerId = :customerId AND (:status IS NULL OR o.status = :status)"
          + " AND (:cursorId IS NULL OR o.id < :cursorId) ORDER BY o.id DESC")
  List<OrderEntity> findPageByCustomer(
      @Param("merchantId") String merchantId,
      @Param("customerId") String customerId,
      @Param("status") String status,
      @Param("cursorId") String cursorId,
      Limit limit);

  List<OrderEntity> findBySubscriptionIdOrderByInvoiceNumberDesc(
      String subscriptionId, Pageable page);

  Optional<OrderEntity> findBySubscriptionIdAndInvoiceNumber(
      String subscriptionId, Integer invoiceNumber);

  List<OrderEntity> findByStatusAndExpiresAtBefore(String status, Instant before, Pageable page);

  /**
   * clearAutomatically for the same reason as the payments update: a bulk UPDATE bypasses the
   * persistence context, and a later find in the same transaction would read the stale version.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update OrderEntity o set o.status = :status, o.paidPaymentId = :paidPaymentId, "
          + "o.paidAt = :paidAt, o.checkoutTokenHash = :checkoutTokenHash, o.version = :version, o.updatedAt = :updatedAt "
          + "where o.id = :id and o.version = :expectedVersion")
  int updateIfVersion(
      String id,
      long expectedVersion,
      String status,
      String paidPaymentId,
      Instant paidAt,
      String checkoutTokenHash,
      long version,
      Instant updatedAt);
}
