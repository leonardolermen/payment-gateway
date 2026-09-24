package com.gateway.merchants.repository;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.domain.ApiKeyEnvironment;
import com.gateway.merchants.domain.Provider;
import com.gateway.merchants.domain.ProviderCredential;
import java.util.List;
import java.util.Optional;

public interface ProviderCredentialRepository {
  ProviderCredential save(ProviderCredential credential);
  Optional<ProviderCredential> find(MerchantId merchantId, Provider provider, ApiKeyEnvironment environment);
  List<ProviderCredential> findByMerchant(MerchantId merchantId);
}
