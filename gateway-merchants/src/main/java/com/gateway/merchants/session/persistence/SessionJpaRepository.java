package com.gateway.merchants.session.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface SessionJpaRepository extends JpaRepository<SessionEntity, String> {
  Optional<SessionEntity> findByAccessHash(String accessHash);

  Optional<SessionEntity> findByRefreshHash(String refreshHash);

  Optional<SessionEntity> findByPreviousRefreshHash(String previousRefreshHash);

  List<SessionEntity> findByUserIdAndRevokedAtIsNullAndRefreshExpiresAtAfter(
      String userId, Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update SessionEntity s set s.lastUsedAt = :now where s.id = :id and s.revokedAt is null")
  int touch(@Param("id") String id, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update SessionEntity s set s.revokedAt = :now where s.id = :id and s.revokedAt is null")
  int revoke(@Param("id") String id, @Param("now") Instant now);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query(
      "update SessionEntity s set s.accessHash = :accessHash, s.refreshHash = :refreshHash, "
          + "s.previousRefreshHash = :oldRefreshHash, s.accessExpiresAt = :accessExpiresAt, "
          + "s.refreshExpiresAt = :refreshExpiresAt, s.lastUsedAt = :now "
          + "where s.id = :id and s.refreshHash = :oldRefreshHash and s.revokedAt is null")
  int rotate(
      @Param("id") String id,
      @Param("oldRefreshHash") String oldRefreshHash,
      @Param("accessHash") String accessHash,
      @Param("refreshHash") String refreshHash,
      @Param("accessExpiresAt") Instant accessExpiresAt,
      @Param("refreshExpiresAt") Instant refreshExpiresAt,
      @Param("now") Instant now);
}
