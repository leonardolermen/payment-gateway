package com.gateway.billing.customer.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface CustomerJpaRepository extends JpaRepository<CustomerEntity, String> {
  Optional<CustomerEntity> findByIdAndMerchantIdAndDeletedAtIsNull(String id, String merchantId);

  Optional<CustomerEntity> findByMerchantIdAndEnvironmentAndDocumentHashAndDeletedAtIsNull(
      String merchantId, String environment, String documentHash);

  @Query(
      "SELECT c FROM CustomerEntity c WHERE c.merchantId = :merchantId AND c.environment = :environment "
          + "AND c.deletedAt IS NULL AND (:cursorId IS NULL OR c.id < :cursorId) ORDER BY c.id DESC")
  List<CustomerEntity> listActive(
      @Param("merchantId") String merchantId,
      @Param("environment") String environment,
      @Param("cursorId") String cursorId,
      Limit limit);

  @Modifying
  @Query(
      "update CustomerEntity c set c.name = :name, c.email = :email, c.address = :address, "
          + "c.version = :version, c.updatedAt = :updatedAt, c.deletedAt = :deletedAt "
          + "where c.id = :id and c.version = :expectedVersion")
  int updateIfVersion(
      String id,
      long expectedVersion,
      String name,
      String email,
      String address,
      long version,
      Instant updatedAt,
      Instant deletedAt);
}
