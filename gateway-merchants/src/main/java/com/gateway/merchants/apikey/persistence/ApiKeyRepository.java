package com.gateway.merchants.apikey.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.apikey.ApiKey;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository {
  ApiKey save(ApiKey apiKey);

  List<ApiKey> findByPrefix(String prefix);

  List<ApiKey> findActiveByMerchantAndEnvironment(
      MerchantId merchantId, ApiKeyEnvironment environment);

  List<ApiKey> findByMerchant(MerchantId merchantId);

  Optional<ApiKey> findById(String id);
}
