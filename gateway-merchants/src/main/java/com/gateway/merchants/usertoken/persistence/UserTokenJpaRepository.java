package com.gateway.merchants.usertoken.persistence;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface UserTokenJpaRepository extends JpaRepository<UserTokenEntity, String> {
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update UserTokenEntity t set t.usedAt = :now where t.tokenHash = :hash and t.kind = :kind"
          + " and t.usedAt is null and t.expiresAt > :now")
  int consume(@Param("hash") String hash, @Param("kind") String kind, @Param("now") Instant now);

  Optional<UserTokenEntity> findByTokenHash(String tokenHash);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update UserTokenEntity t set t.usedAt = :now where t.kind = :kind"
          + " and t.userId = :userId and t.usedAt is null")
  int markUsedOpenOf(
      @Param("kind") String kind, @Param("userId") String userId, @Param("now") Instant now);
}
