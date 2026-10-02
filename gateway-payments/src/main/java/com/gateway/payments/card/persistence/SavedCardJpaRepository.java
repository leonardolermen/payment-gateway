package com.gateway.payments.card.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

interface SavedCardJpaRepository extends JpaRepository<SavedCardEntity, String> {
  Optional<SavedCardEntity> findByIdAndMerchantIdAndDeletedAtIsNull(String id, String merchantId);

  List<SavedCardEntity> findByMerchantIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAt(
      String merchantId, String customerId);

  @Modifying
  @Query(
      "update SavedCardEntity c set c.customerId = :customerId where c.merchantId = :merchantId "
          + "and c.environment = :environment and c.customerDocumentHash = :hash "
          + "and c.customerId is null and c.deletedAt is null")
  int adopt(String merchantId, String environment, String hash, String customerId);
}
