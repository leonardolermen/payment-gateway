package com.gateway.merchants.user;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import java.time.Clock;
import java.time.Instant;

public record User(
    String id,
    MerchantId merchantId,
    String name,
    EmailAddress email,
    Role role,
    String passwordHash,
    Instant emailVerifiedAt,
    Instant lastLoginAt,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public static User create(
      MerchantId merchantId,
      String name,
      EmailAddress email,
      Role role,
      String passwordHash,
      Clock clock) {
    Instant now = clock.instant();
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty() || trimmed.length() > 120) {
      throw new IllegalArgumentException("name is required (up to 120 characters)");
    }

    return new User(
        Ulid.next(), merchantId, trimmed, email, role, passwordHash, null, null, now, now, null);
  }

  public boolean isActive() {
    return deletedAt == null;
  }

  public boolean isEmailVerified() {
    return emailVerifiedAt != null;
  }

  public User verified(Instant at) {
    return new User(
        id, merchantId, name, email, role, passwordHash, at, lastLoginAt, createdAt, at, deletedAt);
  }

  public User withRole(Role newRole, Instant at) {
    return new User(
        id,
        merchantId,
        name,
        email,
        newRole,
        passwordHash,
        emailVerifiedAt,
        lastLoginAt,
        createdAt,
        at,
        deletedAt);
  }

  public User withPasswordHash(String hash, Instant at) {
    return new User(
        id,
        merchantId,
        name,
        email,
        role,
        hash,
        emailVerifiedAt,
        lastLoginAt,
        createdAt,
        at,
        deletedAt);
  }

  public User withName(String newName, Instant at) {
    return new User(
        id,
        merchantId,
        newName.trim(),
        email,
        role,
        passwordHash,
        emailVerifiedAt,
        lastLoginAt,
        createdAt,
        at,
        deletedAt);
  }

  public User loggedInAt(Instant at) {
    return new User(
        id,
        merchantId,
        name,
        email,
        role,
        passwordHash,
        emailVerifiedAt,
        at,
        createdAt,
        updatedAt,
        deletedAt);
  }

  public User deleted(Instant at) {
    return new User(
        id,
        merchantId,
        name,
        email,
        role,
        passwordHash,
        emailVerifiedAt,
        lastLoginAt,
        createdAt,
        at,
        at);
  }
}
