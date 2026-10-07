package com.gateway.billing.installment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** The table's primary key: one row per merchant and environment. */
@Embeddable
class InstallmentSettingsKey implements Serializable {
  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "environment", nullable = false, length = 10)
  String environment;

  InstallmentSettingsKey() {}

  InstallmentSettingsKey(String merchantId, String environment) {
    this.merchantId = merchantId;
    this.environment = environment;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof InstallmentSettingsKey key
        && Objects.equals(merchantId, key.merchantId)
        && Objects.equals(environment, key.environment);
  }

  @Override
  public int hashCode() {
    return Objects.hash(merchantId, environment);
  }
}
