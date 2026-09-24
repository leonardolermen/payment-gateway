package com.gateway.merchants.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

interface ApiKeyJpaRepository extends JpaRepository<ApiKeyEntity, String> {
  List<ApiKeyEntity> findByPrefix(String prefix);
  List<ApiKeyEntity> findByMerchantIdAndEnvironmentAndActiveTrue(String merchantId, String environment);
  List<ApiKeyEntity> findByMerchantIdOrderByCreatedAtAsc(String merchantId);
}
