package com.gateway.payments.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

interface PaymentJpaRepository extends JpaRepository<PaymentEntity, String> {

  Optional<PaymentEntity> findByMerchantIdAndId(String merchantId, String id);

  @Query("SELECT p FROM PaymentEntity p WHERE p.status = 'PENDING' AND p.expiresAt < :before ORDER BY p.expiresAt ASC")
  java.util.List<PaymentEntity> findPendingOlderThan(@Param("before") Instant before, Limit limit);

  @Query("SELECT p FROM PaymentEntity p WHERE p.status IN :statuses AND p.createdAt > :after ORDER BY p.createdAt ASC")
  java.util.List<PaymentEntity> findByStatusInAndCreatedAtAfter(@Param("statuses") Collection<String> statuses, @Param("after") Instant after);

  @Query("SELECT p FROM PaymentEntity p WHERE p.merchantId = :merchantId AND (:cursorId IS NULL OR p.id < :cursorId) ORDER BY p.id DESC")
  java.util.List<PaymentEntity> findByMerchant(@Param("merchantId") String merchantId, @Param("cursorId") String cursorId, Limit limit);

  /**
   * The optimistic-lock write itself: {@code WHERE version = :expectedVersion}, {@code expected}
   * being the version the aggregate was loaded at (not whatever this row's {@code @Version} field
   * auto-manages via the persistence context — see {@code PaymentEntity}'s comment). 0 rows
   * affected means someone else saved first; the caller turns that into
   * {@code ObjectOptimisticLockingFailureException}.
   */
  @Modifying
  @Transactional(propagation = Propagation.MANDATORY)
  @Query(
      """
      UPDATE PaymentEntity p SET
        p.status = :status,
        p.details = :details,
        p.expiresAt = :expiresAt,
        p.paidAt = :paidAt,
        p.paidAmount = :paidAmount,
        p.refundedAmount = :refundedAmount,
        p.version = :newVersion,
        p.updatedAt = :updatedAt
      WHERE p.id = :id AND p.version = :expectedVersion
      """)
  int updateIfVersionMatches(
      @Param("id") String id,
      @Param("status") String status,
      @Param("details") String details,
      @Param("expiresAt") Instant expiresAt,
      @Param("paidAt") Instant paidAt,
      @Param("paidAmount") Long paidAmount,
      @Param("refundedAmount") long refundedAmount,
      @Param("newVersion") long newVersion,
      @Param("updatedAt") Instant updatedAt,
      @Param("expectedVersion") long expectedVersion);
}
