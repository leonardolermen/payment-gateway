package com.gateway.merchants.session.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface SessionJpaRepository extends JpaRepository<SessionEntity, String> {
  Optional<SessionEntity> findByAccessHash(String accessHash);

  Optional<SessionEntity> findByRefreshHash(String refreshHash);

  Optional<SessionEntity> findByPreviousRefreshHash(String previousRefreshHash);

  List<SessionEntity> findByUserIdAndRevokedAtIsNullAndRefreshExpiresAtAfter(
      String userId, Instant now);
}
