package com.gateway.merchants.repository;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.domain.ApiKey;
import com.gateway.merchants.domain.ApiKeyEnvironment;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class ApiKeyRepositoryImpl implements ApiKeyRepository {
  private final ApiKeyJpaRepository jpa;

  public ApiKeyRepositoryImpl(ApiKeyJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  public ApiKey save(ApiKey apiKey) {
    ApiKeyEntity entity = jpa.findById(apiKey.id()).orElseGet(ApiKeyEntity::new);
    entity.id = apiKey.id();
    entity.merchantId = apiKey.merchantId().value();
    entity.environment = apiKey.environment().name();
    entity.prefix = apiKey.prefix();
    entity.hash = apiKey.hash();
    entity.active = apiKey.active();
    entity.expiresAt = apiKey.expiresAt();
    entity.createdAt = apiKey.createdAt();
    return toDomain(jpa.save(entity));
  }

  @Override public List<ApiKey> findByPrefix(String prefix) {
    return jpa.findByPrefix(prefix).stream().map(ApiKeyRepositoryImpl::toDomain).toList();
  }

  @Override
  public List<ApiKey> findActiveByMerchantAndEnvironment(MerchantId merchantId, ApiKeyEnvironment environment) {
    return jpa.findByMerchantIdAndEnvironmentAndActiveTrue(merchantId.value(), environment.name()).stream()
        .map(ApiKeyRepositoryImpl::toDomain).toList();
  }

  @Override public List<ApiKey> findByMerchant(MerchantId merchantId) {
    return jpa.findByMerchantIdOrderByCreatedAtAsc(merchantId.value()).stream().map(ApiKeyRepositoryImpl::toDomain).toList();
  }
  @Override public Optional<ApiKey> findById(String id) {
    return jpa.findById(id).map(ApiKeyRepositoryImpl::toDomain);
  }

  private static ApiKey toDomain(ApiKeyEntity e) {
    return new ApiKey(e.id, new MerchantId(e.merchantId), ApiKeyEnvironment.valueOf(e.environment), e.prefix, e.hash, e.active, e.expiresAt, e.createdAt);
  }
}
