package com.gateway.merchants.user.persistence;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserJpaRepository extends JpaRepository<UserEntity, String> {
  Optional<UserEntity> findByEmailNormalizedAndDeletedAtIsNull(String emailNormalized);

  List<UserEntity> findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtAsc(String merchantId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query(
      "select user from UserEntity user where user.merchantId = :merchantId"
          + " and user.role = 'OWNER' and user.deletedAt is null")
  List<UserEntity> lockActiveOwners(@Param("merchantId") String merchantId);

  long countByMerchantIdAndRoleAndDeletedAtIsNull(String merchantId, String role);
}
