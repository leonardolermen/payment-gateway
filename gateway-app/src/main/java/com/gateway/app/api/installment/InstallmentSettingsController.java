package com.gateway.app.api.installment;

import com.gateway.app.api.installment.dto.InstallmentSettingsRequest;
import com.gateway.app.api.installment.dto.InstallmentSettingsResponse;
import com.gateway.app.api.support.Environments;
import com.gateway.app.security.MerchantContext;
import com.gateway.billing.installment.InstallmentSettingsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How the merchant prices card installments (spec 2026-10-07 §2), in the API key's environment: a
 * TEST key reads and writes the TEST settings only. The PUT replaces the whole settings, so it is
 * idempotent by state and stays out of IdempotencyFilter.
 */
@RestController
@RequestMapping("/v1/installment-settings")
public class InstallmentSettingsController {
  private final InstallmentSettingsService installments;

  public InstallmentSettingsController(InstallmentSettingsService installments) {
    this.installments = installments;
  }

  @GetMapping
  public InstallmentSettingsResponse get() {
    MerchantContext.Current caller = MerchantContext.current();

    return InstallmentSettingsResponse.from(
        installments.get(caller.merchantId(), Environments.toProvider(caller.environment())));
  }

  @PutMapping
  public InstallmentSettingsResponse put(@RequestBody InstallmentSettingsRequest request) {
    request.validate();
    MerchantContext.Current caller = MerchantContext.current();

    return InstallmentSettingsResponse.from(
        installments.update(
            caller.merchantId(),
            Environments.toProvider(caller.environment()),
            request.maxInstallments(),
            request.interestFreeUpTo(),
            request.monthlyRateBps(),
            caller.apiKeyId()));
  }
}
