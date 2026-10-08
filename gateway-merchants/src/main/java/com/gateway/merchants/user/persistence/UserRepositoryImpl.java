package com.gateway.merchants.user.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class UserRepositoryImpl implements UserRepository {
  private final UserJpaRepository jpa;

  @PersistenceContext private EntityManager entityManager;

  public UserRepositoryImpl(UserJpaRepository jpa) {
    this.jpa = jpa;
  }

  /** persist + flush: the unique index must answer now, inside the caller's transaction. */
  @Override
  @Transactional
  public void insert(User user) {
    entityManager.persist(toEntity(user, new UserEntity()));
    entityManager.flush();
  }

  @Override
  @Transactional
  public User save(User user) {
    UserEntity entity = jpa.findById(user.id()).orElseGet(UserEntity::new);
    return toDomain(jpa.save(toEntity(user, entity)));
  }

  @Override
  public Optional<User> findById(String id) {
    return jpa.findById(id).map(UserRepositoryImpl::toDomain);
  }

  @Override
  public Optional<User> findActiveByEmail(String normalized) {
    return jpa.findByEmailNormalizedAndDeletedAtIsNull(normalized)
        .map(UserRepositoryImpl::toDomain);
  }

  @Override
  public List<User> findActiveByMerchant(MerchantId merchantId) {
    return jpa.findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtAsc(merchantId.value()).stream()
        .map(UserRepositoryImpl::toDomain)
        .toList();
  }

  @Override
  public void lockActiveOwners(MerchantId merchantId) {
    jpa.lockActiveOwners(merchantId.value());
  }

  @Override
  public long countActiveByMerchantAndRole(MerchantId merchantId, Role role) {
    return jpa.countByMerchantIdAndRoleAndDeletedAtIsNull(merchantId.value(), role.name());
  }

  private static UserEntity toEntity(User user, UserEntity entity) {
    entity.id = user.id();
    entity.merchantId = user.merchantId().value();
    entity.name = user.name();
    entity.email = user.email().value();
    entity.emailNormalized = user.email().normalized();
    entity.passwordHash = user.passwordHash();
    entity.role = user.role().name();
    entity.emailVerifiedAt = user.emailVerifiedAt();
    entity.lastLoginAt = user.lastLoginAt();
    entity.createdAt = user.createdAt();
    entity.updatedAt = user.updatedAt();
    entity.deletedAt = user.deletedAt();
    return entity;
  }

  private static User toDomain(UserEntity entity) {
    return new User(
        entity.id,
        new MerchantId(entity.merchantId),
        entity.name,
        new EmailAddress(entity.email),
        Role.valueOf(entity.role),
        entity.passwordHash,
        entity.emailVerifiedAt,
        entity.lastLoginAt,
        entity.createdAt,
        entity.updatedAt,
        entity.deletedAt);
  }
}
