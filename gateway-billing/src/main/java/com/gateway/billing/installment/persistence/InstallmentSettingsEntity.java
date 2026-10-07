package com.gateway.billing.installment.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "installment_settings", schema = "billing")
class InstallmentSettingsEntity {
  @EmbeddedId InstallmentSettingsKey key;

  @Column(name = "max_installments", nullable = false)
  short maxInstallments;

  @Column(name = "interest_free_up_to", nullable = false)
  short interestFreeUpTo;

  @Column(name = "monthly_rate_bps", nullable = false)
  int monthlyRateBps;

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt;
}
