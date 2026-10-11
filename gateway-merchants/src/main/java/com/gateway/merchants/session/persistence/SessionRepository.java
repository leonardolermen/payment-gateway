package com.gateway.merchants.session.persistence;

import com.gateway.merchants.session.Session;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SessionRepository {
  void insert(Session session);

  /** Moves last_used_at forward; never touches revoked_at. False when the session is revoked. */
  boolean touch(String id, Instant now);

  /** Revocation is final: the first revoked_at wins. False when it was already revoked. */
  boolean revoke(String id, Instant now);

  /**
   * Compare-and-set on the refresh hash: of two callers presenting the same refresh token, only one
   * gets true.
   */
  boolean rotate(
      String id,
      String oldRefreshHash,
      String accessHash,
      String refreshHash,
      Instant accessExpiresAt,
      Instant refreshExpiresAt,
      Instant now);

  Optional<Session> findById(String id);

  Optional<Session> findByAccessHash(String accessHash);

  Optional<Session> findByRefreshHash(String refreshHash);

  Optional<Session> findByPreviousRefreshHash(String previousRefreshHash);

  List<Session> findLiveByUser(String userId, Instant now);
}
