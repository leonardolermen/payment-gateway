package com.gateway.app.api.installment.dto;

import com.gateway.billing.installment.InstallmentSettings;
import java.time.Instant;

/** {@code updated_at} is null while the merchant runs on the default (never saved). */
public record InstallmentSettingsResponse(
    String environment,
    int maxInstallments,
    int interestFreeUpTo,
    int monthlyRateBps,
    Instant updatedAt) {

  public static InstallmentSettingsResponse from(InstallmentSettings settings) {
    return new InstallmentSettingsResponse(
        settings.environment().name(),
        settings.maxInstallments(),
        settings.interestFreeUpTo(),
        settings.monthlyRateBps(),
        settings.updatedAt());
  }
}
