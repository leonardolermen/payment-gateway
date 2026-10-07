package com.gateway.billing.installment.persistence;

import com.gateway.billing.installment.InstallmentSettings;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InstallmentSettingsRepositoryImpl implements InstallmentSettingsRepository {
  private final InstallmentSettingsJpaRepository jpa;

  public InstallmentSettingsRepositoryImpl(InstallmentSettingsJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public Optional<InstallmentSettings> find(
      MerchantId merchantId, ProviderEnvironment environment) {
    return jpa.findById(new InstallmentSettingsKey(merchantId.value(), environment.name()))
        .map(InstallmentSettingsRepositoryImpl::toDomain);
  }

  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void save(InstallmentSettings settings) {
    jpa.upsert(
        settings.merchantId().value(),
        settings.environment().name(),
        settings.maxInstallments(),
        settings.interestFreeUpTo(),
        settings.monthlyRateBps(),
        settings.updatedAt());
  }

  private static InstallmentSettings toDomain(InstallmentSettingsEntity entity) {
    return new InstallmentSettings(
        new MerchantId(entity.key.merchantId),
        ProviderEnvironment.valueOf(entity.key.environment),
        entity.maxInstallments,
        entity.interestFreeUpTo,
        entity.monthlyRateBps,
        entity.updatedAt);
  }
}
