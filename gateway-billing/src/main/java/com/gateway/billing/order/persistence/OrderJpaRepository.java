package com.gateway.billing.order.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface OrderJpaRepository extends JpaRepository<OrderEntity, String> {
  Optional<OrderEntity> findByIdAndMerchantId(String id, String merchantId);

  List<OrderEntity> findByMerchantIdAndReferenceOrderByCreatedAtDesc(
      String merchantId, String reference, Pageable page);

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
          + "o.paidAt = :paidAt, o.version = :version, o.updatedAt = :updatedAt "
          + "where o.id = :id and o.version = :expectedVersion")
  int updateIfVersion(
      String id,
      long expectedVersion,
      String status,
      String paidPaymentId,
      Instant paidAt,
      long version,
      Instant updatedAt);
}
