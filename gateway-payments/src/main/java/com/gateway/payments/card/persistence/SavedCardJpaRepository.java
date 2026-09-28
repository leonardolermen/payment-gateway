package com.gateway.payments.card.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface SavedCardJpaRepository extends JpaRepository<SavedCardEntity, String> {
  Optional<SavedCardEntity> findByIdAndMerchantIdAndDeletedAtIsNull(String id, String merchantId);
}
