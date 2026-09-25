package com.gateway.payments.payment.boleto.persistence;

import com.gateway.kernel.ids.MerchantId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BoletoNumberRepositoryImpl implements BoletoNumberRepository {
  /** 8 digits: the issue OpenAPI says "máximo 08 caracteres" and the query schema pins minLength = maxLength = 8. */
  private static final long MAX = 99_999_999L;

  @PersistenceContext private EntityManager em;

  /**
   * One statement: the upsert takes the row lock, increments and returns, so two concurrent
   * callers are serialized by Postgres and neither reads a stale value (a SELECT-then-UPDATE
   * would). The first call inserts 1; the excluded row's value is what ON CONFLICT adds to.
   *
   * Hibernate accepts RETURNING on a native INSERT via createNativeQuery(sql, Class).getSingleResult()
   * without the doReturningWork fallback the brief anticipated for a dialect that treats it as an
   * update count. The Class arg only hints the result-set mapping though — createNativeQuery(String,
   * Class) still returns the untyped Query (not TypedQuery), so getSingleResult() is Object/Number,
   * not Long: javac rejected a direct Long assignment ("incompatible types: Object cannot be
   * converted to Long") until this went through Number.longValue().
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public String next(MerchantId merchantId) {
    Number value =
        (Number)
            em.createNativeQuery(
                    """
                    INSERT INTO payments.boleto_numbers (merchant_id, next_value) VALUES (:merchantId, 1)
                    ON CONFLICT (merchant_id) DO UPDATE SET next_value = payments.boleto_numbers.next_value + 1
                    RETURNING next_value
                    """,
                    Long.class)
                .setParameter("merchantId", merchantId.value())
                .getSingleResult();
    long n = value.longValue();
    if (n > MAX) throw new IllegalStateException("nosso numero exhausted for merchant " + merchantId.value());
    return String.format("%08d", n);
  }
}
