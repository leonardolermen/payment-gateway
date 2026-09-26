package com.gateway.payments.idempotency.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.idempotency.IdempotencyKey;
import java.time.Instant;
import java.util.Optional;

public interface IdempotencyRepository {
  /**
   * {@code INSERT ... ON CONFLICT (merchant_id, key) DO NOTHING}; {@code true} iff this call
   * inserted the row.
   */
  boolean insertIfAbsent(IdempotencyKey k);

  Optional<IdempotencyKey> find(MerchantId merchantId, String key);

  void finish(IdempotencyKey k);

  int deleteOlderThan(Instant before);
}
