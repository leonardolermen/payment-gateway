package com.gateway.merchants.credential.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.credential.Provider;
import com.gateway.merchants.credential.ProviderCredential;
import java.util.List;
import java.util.Optional;

public interface ProviderCredentialRepository {
  ProviderCredential save(ProviderCredential credential);
  Optional<ProviderCredential> find(MerchantId merchantId, Provider provider, ApiKeyEnvironment environment);
  List<ProviderCredential> findByMerchant(MerchantId merchantId);
}
