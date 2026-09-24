package com.gateway.payments.repository;

import java.io.Serializable;
import java.util.Objects;

/** Composite id for {@link IdempotencyKeyEntity}: the primary key is {@code (merchant_id, key)}. */
class IdempotencyKeyId implements Serializable {
  String merchantId;
  String key;

  IdempotencyKeyId() {}

  IdempotencyKeyId(String merchantId, String key) {
    this.merchantId = merchantId;
    this.key = key;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof IdempotencyKeyId that)) return false;
    return Objects.equals(merchantId, that.merchantId) && Objects.equals(key, that.key);
  }

  @Override
  public int hashCode() {
    return Objects.hash(merchantId, key);
  }
}
