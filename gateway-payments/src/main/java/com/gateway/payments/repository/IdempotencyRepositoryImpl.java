package com.gateway.payments.repository;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.domain.IdempotencyKey;
import com.gateway.payments.domain.IdempotencyStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IdempotencyRepositoryImpl implements IdempotencyRepository {
  private final IdempotencyJpaRepository jpa;

  @PersistenceContext private EntityManager em;

  public IdempotencyRepositoryImpl(IdempotencyJpaRepository jpa) {
    this.jpa = jpa;
  }

  /**
   * {@code ON CONFLICT DO NOTHING} and not {@code save} + catch the duplicate-key exception: the
   * catch only works when each save commits on its own. Inside a caller's transaction the INSERT
   * would wait for flush, the violation would surface outside the try, and Postgres would abort
   * the whole surrounding transaction for what should have been a no-op — same reasoning as
   * {@code DeliveryRepositoryImpl.saveIfAbsent} in webhook-delivery.
   *
   * <p>Native SQL is safe here, unlike {@code OutboxJpaRepository.selectClaimable}: the table is
   * qualified with its fixed schema ({@code payments}), so the connection's {@code search_path}
   * cannot redirect it.
   */
  @Override
  @Transactional
  public boolean insertIfAbsent(IdempotencyKey k) {
    int inserted =
        em.createNativeQuery(
                """
                INSERT INTO payments.idempotency_keys
                  (merchant_id, key, request_hash, status, response_code, response_body, resource_id, created_at)
                VALUES
                  (:merchantId, :key, :requestHash, :status, :responseCode, :responseBody, :resourceId, :createdAt)
                ON CONFLICT (merchant_id, key) DO NOTHING
                """)
            .setParameter("merchantId", k.merchantId().value())
            .setParameter("key", k.key())
            .setParameter("requestHash", k.requestHash())
            .setParameter("status", k.status().name())
            .setParameter("responseCode", k.responseCode())
            .setParameter("responseBody", k.responseBody())
            .setParameter("resourceId", k.resourceId())
            .setParameter("createdAt", k.createdAt())
            .executeUpdate();
    return inserted > 0;
  }

  @Override
  public Optional<IdempotencyKey> find(MerchantId merchantId, String key) {
    return jpa.findById(new IdempotencyKeyId(merchantId.value(), key)).map(IdempotencyRepositoryImpl::toDomain);
  }

  @Override
  @Transactional
  public void finish(IdempotencyKey k) {
    IdempotencyKeyEntity e =
        jpa.findById(new IdempotencyKeyId(k.merchantId().value(), k.key()))
            .orElseThrow(() -> new IllegalStateException("no idempotency key row for " + k.merchantId().value() + "/" + k.key()));
    e.status = k.status().name();
    e.responseCode = k.responseCode();
    e.responseBody = k.responseBody();
    e.resourceId = k.resourceId();
    jpa.save(e);
  }

  @Override
  @Transactional
  public int deleteOlderThan(Instant before) {
    return (int) jpa.deleteByCreatedAtBefore(before);
  }

  private static IdempotencyKey toDomain(IdempotencyKeyEntity e) {
    return new IdempotencyKey(
        new MerchantId(e.merchantId), e.key, e.requestHash, IdempotencyStatus.valueOf(e.status), e.responseCode, e.responseBody, e.resourceId, e.createdAt);
  }
}
