package com.gateway.merchants.session.persistence;

import com.gateway.merchants.session.Session;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SessionRepositoryImpl implements SessionRepository {
  private final SessionJpaRepository jpa;

  @PersistenceContext private EntityManager entityManager;

  public SessionRepositoryImpl(SessionJpaRepository jpa) {
    this.jpa = jpa;
  }

  /** persist + flush: a constraint violation must surface inside the caller's transaction. */
  @Override
  @Transactional
  public void insert(Session session) {
    entityManager.persist(toEntity(session, new SessionEntity()));
    entityManager.flush();
  }

  @Override
  @Transactional
  public boolean touch(String id, Instant now) {
    return jpa.touch(id, now) == 1;
  }

  @Override
  @Transactional
  public boolean revoke(String id, Instant now) {
    return jpa.revoke(id, now) == 1;
  }

  @Override
  @Transactional
  public boolean rotate(
      String id,
      String oldRefreshHash,
      String accessHash,
      String refreshHash,
      Instant accessExpiresAt,
      Instant refreshExpiresAt,
      Instant now) {
    return jpa.rotate(
            id, oldRefreshHash, accessHash, refreshHash, accessExpiresAt, refreshExpiresAt, now)
        == 1;
  }

  @Override
  public Optional<Session> findById(String id) {
    return jpa.findById(id).map(SessionRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Session> findByAccessHash(String accessHash) {
    return jpa.findByAccessHash(accessHash).map(SessionRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Session> findByRefreshHash(String refreshHash) {
    return jpa.findByRefreshHash(refreshHash).map(SessionRepositoryImpl::toDomain);
  }

  @Override
  public Optional<Session> findByPreviousRefreshHash(String previousRefreshHash) {
    return jpa.findByPreviousRefreshHash(previousRefreshHash).map(SessionRepositoryImpl::toDomain);
  }

  @Override
  public List<Session> findLiveByUser(String userId, Instant now) {
    return jpa.findByUserIdAndRevokedAtIsNullAndRefreshExpiresAtAfter(userId, now).stream()
        .map(SessionRepositoryImpl::toDomain)
        .toList();
  }

  private static SessionEntity toEntity(Session session, SessionEntity entity) {
    entity.id = session.id();
    entity.userId = session.userId();
    entity.accessHash = session.accessHash();
    entity.refreshHash = session.refreshHash();
    entity.previousRefreshHash = session.previousRefreshHash();
    entity.accessExpiresAt = session.accessExpiresAt();
    entity.refreshExpiresAt = session.refreshExpiresAt();
    entity.ip = session.ip();
    entity.userAgent = session.userAgent();
    entity.createdAt = session.createdAt();
    entity.lastUsedAt = session.lastUsedAt();
    entity.revokedAt = session.revokedAt();
    return entity;
  }

  private static Session toDomain(SessionEntity entity) {
    return new Session(
        entity.id,
        entity.userId,
        entity.accessHash,
        entity.refreshHash,
        entity.previousRefreshHash,
        entity.accessExpiresAt,
        entity.refreshExpiresAt,
        entity.ip,
        entity.userAgent,
        entity.createdAt,
        entity.lastUsedAt,
        entity.revokedAt);
  }
}
