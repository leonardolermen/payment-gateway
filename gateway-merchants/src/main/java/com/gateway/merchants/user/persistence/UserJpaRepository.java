package com.gateway.merchants.user.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserJpaRepository extends JpaRepository<UserEntity, String> {
  Optional<UserEntity> findByEmailNormalizedAndDeletedAtIsNull(String emailNormalized);

  List<UserEntity> findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtAsc(String merchantId);

  long countByMerchantIdAndRoleAndDeletedAtIsNull(String merchantId, String role);
}
