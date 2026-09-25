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

  java.util.List<PaymentEntity> findByMerchantIdAndReferenceOrderByIdDesc(String merchantId, String reference, Limit limit);

  @Query("SELECT p FROM PaymentEntity p WHERE p.status = 'PENDING' AND p.expiresAt < :before ORDER BY p.expiresAt ASC")
  java.util.List<PaymentEntity> findPendingOlderThan(@Param("before") Instant before, Limit limit);

  /** Row lock for read-modify-writes that the version check alone cannot serialize (refund sums). */
  @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM PaymentEntity p WHERE p.id = :id")
  Optional<PaymentEntity> findByIdForUpdate(@Param("id") String id);

  @Query("SELECT p FROM PaymentEntity p WHERE p.status = :status AND p.createdAt < :before ORDER BY p.createdAt ASC")
  java.util.List<PaymentEntity> findByStatusAndCreatedAtBefore(@Param("status") String status, @Param("before") Instant before, Limit limit);

  @Query("SELECT p FROM PaymentEntity p WHERE p.status IN :statuses AND p.createdAt > :after ORDER BY p.createdAt ASC")
  java.util.List<PaymentEntity> findByStatusInAndCreatedAtAfter(
      @Param("statuses") Collection<String> statuses, @Param("after") Instant after, Limit limit);

  @Query("SELECT p FROM PaymentEntity p WHERE p.merchantId = :merchantId AND (:cursorId IS NULL OR p.id < :cursorId) ORDER BY p.id DESC")
  java.util.List<PaymentEntity> findByMerchant(@Param("merchantId") String merchantId, @Param("cursorId") String cursorId, Limit limit);

  /**
   * The optimistic-lock write itself: {@code WHERE version = :expectedVersion}, {@code expected}
   * being the version the aggregate was loaded at (see {@code PaymentEntity}'s comment on why that
   * column is not {@code @Version}-managed). 0 rows affected means someone else saved first; the
   * caller turns that into {@code ObjectOptimisticLockingFailureException}.
   *
   * <p>{@code clearAutomatically = true} matters within a single transaction: a bulk JPQL
   * {@code UPDATE} bypasses the persistence context, so without it a later {@code findById} in the
   * same transaction would return the stale cached entity from before this write, with the old
   * {@code version} — the next {@code save} would then compute a stale {@code expectedVersion} and
   * fail with a false optimistic-lock error even though nobody else touched the row.
   * {@code flushAutomatically = true} makes sure any pending changes are flushed before this bulk
   * update runs, so it never overwrites something not yet written.
   */
  @Modifying(clearAutomatically = true, flushAutomatically = true)
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
