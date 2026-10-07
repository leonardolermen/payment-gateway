package com.gateway.billing.installment.persistence;

import com.gateway.billing.installment.InstallmentSettings;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.util.Optional;

public interface InstallmentSettingsRepository {
  /** Empty when the merchant never saved settings in this environment. */
  Optional<InstallmentSettings> find(MerchantId merchantId, ProviderEnvironment environment);

  /**
   * Requires a transaction. Inserts or replaces the row of the settings' merchant and environment.
   */
  void save(InstallmentSettings settings);
}
