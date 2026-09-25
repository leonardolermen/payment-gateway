package com.gateway.merchants.credential.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface ProviderCredentialJpaRepository extends JpaRepository<ProviderCredentialEntity, String> {
  Optional<ProviderCredentialEntity> findByMerchantIdAndProviderAndEnvironment(String merchantId, String provider, String environment);
  List<ProviderCredentialEntity> findByMerchantId(String merchantId);
}
