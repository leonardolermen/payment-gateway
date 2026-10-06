package com.gateway.billing.customer.persistence;

import java.time.Instant;
import java.util.Collection;
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

  /** Ids are ULIDs, so {@code id DESC} is creation order and the last id is the cursor. */
  @Query(
      "SELECT c FROM CustomerEntity c WHERE c.merchantId = :merchantId"
          + " AND c.environment = :environment AND c.deletedAt IS NULL"
          + " AND (:cursorId IS NULL OR c.id < :cursorId) ORDER BY c.id DESC")
  List<CustomerEntity> findActivePage(
      @Param("merchantId") String merchantId,
      @Param("environment") String environment,
      @Param("cursorId") String cursorId,
      Limit limit);

  /** Only the two columns: a name never needs the sealed document opened. */
  @Query(
      "SELECT c.id AS id, c.name AS name FROM CustomerEntity c WHERE c.merchantId = :merchantId"
          + " AND c.id IN :ids AND c.deletedAt IS NULL")
  List<CustomerName> findActiveNames(
      @Param("merchantId") String merchantId, @Param("ids") Collection<String> ids);

  interface CustomerName {
    String getId();

    String getName();
  }

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
