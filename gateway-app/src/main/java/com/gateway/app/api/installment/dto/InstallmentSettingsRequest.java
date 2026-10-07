package com.gateway.app.api.installment.dto;

/**
 * The whole settings, every field required: a PUT replaces them, so a missing field would otherwise
 * silently mean a default. No environment: it is the API key's. Ranges are checked by {@code
 * InstallmentSettings} in billing; both are a 400.
 */
public record InstallmentSettingsRequest(
    Integer maxInstallments, Integer interestFreeUpTo, Integer monthlyRateBps) {

  public void validate() {
    required(maxInstallments, "max_installments");
    required(interestFreeUpTo, "interest_free_up_to");
    required(monthlyRateBps, "monthly_rate_bps");
  }

  private static void required(Integer value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
  }
}
