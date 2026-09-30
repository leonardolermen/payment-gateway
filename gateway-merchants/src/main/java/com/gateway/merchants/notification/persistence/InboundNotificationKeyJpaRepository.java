package com.gateway.merchants.notification.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface InboundNotificationKeyJpaRepository
    extends JpaRepository<InboundNotificationKeyEntity, String> {
  Optional<InboundNotificationKeyEntity> findByMerchantIdAndProvider(
      String merchantId, String provider);
}
