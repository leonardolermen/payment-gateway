package com.gateway.merchants.repository;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.domain.ApiKey;
import com.gateway.merchants.domain.ApiKeyEnvironment;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository {
  ApiKey save(ApiKey apiKey);
  List<ApiKey> findByPrefix(String prefix);
  List<ApiKey> findActiveByMerchantAndEnvironment(MerchantId merchantId, ApiKeyEnvironment environment);
  List<ApiKey> findByMerchant(MerchantId merchantId);
  Optional<ApiKey> findById(String id);
}
