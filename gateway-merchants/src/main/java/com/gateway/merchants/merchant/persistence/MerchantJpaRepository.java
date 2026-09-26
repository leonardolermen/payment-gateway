package com.gateway.merchants.merchant.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface MerchantJpaRepository extends JpaRepository<MerchantEntity, String> {
  Optional<MerchantEntity> findByInboundWebhookToken(String inboundWebhookToken);
}
