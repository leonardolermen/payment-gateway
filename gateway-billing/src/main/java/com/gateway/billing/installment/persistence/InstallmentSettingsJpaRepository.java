package com.gateway.billing.installment.persistence;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface InstallmentSettingsJpaRepository
    extends JpaRepository<InstallmentSettingsEntity, InstallmentSettingsKey> {

  /** One statement: two first writes racing for the same key both succeed, the last one wins. */
  @Modifying
  @Query(
      value =
          "INSERT INTO billing.installment_settings (merchant_id, environment, max_installments,"
              + " interest_free_up_to, monthly_rate_bps, updated_at)"
              + " VALUES (:merchantId, :environment, :maxInstallments, :interestFreeUpTo,"
              + " :monthlyRateBps, :updatedAt)"
              + " ON CONFLICT (merchant_id, environment) DO UPDATE SET"
              + " max_installments = EXCLUDED.max_installments,"
              + " interest_free_up_to = EXCLUDED.interest_free_up_to,"
              + " monthly_rate_bps = EXCLUDED.monthly_rate_bps,"
              + " updated_at = EXCLUDED.updated_at",
      nativeQuery = true)
  int upsert(
      @Param("merchantId") String merchantId,
      @Param("environment") String environment,
      @Param("maxInstallments") int maxInstallments,
      @Param("interestFreeUpTo") int interestFreeUpTo,
      @Param("monthlyRateBps") int monthlyRateBps,
      @Param("updatedAt") Instant updatedAt);
}
