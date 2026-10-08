package com.gateway.merchants.session.persistence;

import com.gateway.merchants.session.Session;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SessionRepository {
  void insert(Session session);

  Session save(Session session);

  Optional<Session> findById(String id);

  Optional<Session> findByAccessHash(String accessHash);

  Optional<Session> findByRefreshHash(String refreshHash);

  Optional<Session> findByPreviousRefreshHash(String previousRefreshHash);

  List<Session> findLiveByUser(String userId, Instant now);
}
