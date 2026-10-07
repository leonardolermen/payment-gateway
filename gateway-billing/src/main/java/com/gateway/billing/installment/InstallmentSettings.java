package com.gateway.billing.installment;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Instant;

/**
 * How a merchant prices card installments in one environment (spec 2026-10-07 §2): up to {@code
 * maxInstallments}, the first {@code interestFreeUpTo} without interest, the rest at {@code
 * monthlyRateBps} a month (2,99% = 299). {@code updatedAt} is null for the default, which no row
 * holds: 12, all interest-free, the behavior before the settings existed.
 */
public record InstallmentSettings(
    MerchantId merchantId,
    ProviderEnvironment environment,
    int maxInstallments,
    int interestFreeUpTo,
    int monthlyRateBps,
    Instant updatedAt) {

  public static final int CEILING = 12;
  public static final int MAX_RATE_BPS = 1000;

  public InstallmentSettings {
    if (maxInstallments < 1 || maxInstallments > CEILING) {
      throw new IllegalArgumentException("max_installments must be between 1 and " + CEILING);
    }
    if (interestFreeUpTo < 1 || interestFreeUpTo > maxInstallments) {
      throw new IllegalArgumentException(
          "interest_free_up_to must be between 1 and max_installments (" + maxInstallments + ")");
    }
    if (monthlyRateBps < 0 || monthlyRateBps > MAX_RATE_BPS) {
      throw new IllegalArgumentException("monthly_rate_bps must be between 0 and " + MAX_RATE_BPS);
    }
  }

  public static InstallmentSettings defaults(
      MerchantId merchantId, ProviderEnvironment environment) {
    return new InstallmentSettings(merchantId, environment, CEILING, CEILING, 0, null);
  }
}
