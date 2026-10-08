package com.gateway.merchants.usertoken.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.usertoken.UserToken;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserTokenRepositoryImpl implements UserTokenRepository {
  private final UserTokenJpaRepository jpa;

  @PersistenceContext private EntityManager entityManager;

  public UserTokenRepositoryImpl(UserTokenJpaRepository jpa) {
    this.jpa = jpa;
  }

  /** persist + flush: a constraint violation must surface inside the caller's transaction. */
  @Override
  @Transactional
  public void insert(UserToken token) {
    entityManager.persist(toEntity(token));
    entityManager.flush();
  }

  @Override
  public Optional<UserToken> findById(String id) {
    return jpa.findById(id).map(UserTokenRepositoryImpl::toDomain);
  }

  @Override
  @Transactional
  public Optional<UserToken> consume(String tokenHash, UserToken.Kind kind, Instant now) {
    if (jpa.consume(tokenHash, kind.name(), now) != 1) {
      return Optional.empty();
    }

    return jpa.findByTokenHash(tokenHash).map(UserTokenRepositoryImpl::toDomain);
  }

  @Override
  @Transactional
  public void markUsedOpenOf(UserToken.Kind kind, String userId, Instant now) {
    jpa.markUsedOpenOf(kind.name(), userId, now);
  }

  private static UserTokenEntity toEntity(UserToken token) {
    UserTokenEntity entity = new UserTokenEntity();
    entity.id = token.id();
    entity.userId = token.userId();
    entity.merchantId = token.merchantId().value();
    entity.kind = token.kind().name();
    entity.tokenHash = token.tokenHash();
    entity.payload = Map.copyOf(token.payload());
    entity.expiresAt = token.expiresAt();
    entity.usedAt = token.usedAt();
    entity.createdAt = token.createdAt();
    return entity;
  }

  private static UserToken toDomain(UserTokenEntity entity) {
    return new UserToken(
        entity.id,
        entity.userId,
        new MerchantId(entity.merchantId),
        UserToken.Kind.valueOf(entity.kind),
        entity.tokenHash,
        Map.copyOf(entity.payload),
        entity.expiresAt,
        entity.usedAt,
        entity.createdAt);
  }
}
