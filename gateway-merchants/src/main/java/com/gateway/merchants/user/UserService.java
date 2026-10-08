package com.gateway.merchants.user;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.user.persistence.UserRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

public class UserService {
  private final UserRepository users;
  private final PasswordService passwords;
  private final Clock clock;

  public UserService(UserRepository users, PasswordService passwords, Clock clock) {
    this.users = users;
    this.passwords = passwords;
    this.clock = clock;
  }

  @Transactional
  public User register(
      MerchantId merchantId, String name, EmailAddress email, Role role, String password) {
    passwords.requireStrong(password);
    User user = User.create(merchantId, name, email, role, passwords.hash(password), clock);

    try {
      users.insert(user);
    } catch (DataIntegrityViolationException duplicate) {
      throw new DomainException("EMAIL_TAKEN", "email is already in use");
    }

    return user;
  }

  /** Same answer and same Argon2 cost whether the e-mail exists or the password is wrong. */
  @Transactional
  public Optional<User> authenticate(EmailAddress email, String password) {
    Optional<User> found = users.findActiveByEmail(email.normalized());
    if (found.isEmpty()) {
      passwords.burnTime(password);
      return Optional.empty();
    }

    User user = found.get();
    if (!passwords.matches(password, user.passwordHash())) {
      return Optional.empty();
    }

    return Optional.of(users.save(user.loggedInAt(clock.instant())));
  }

  @Transactional(readOnly = true)
  public User get(String id) {
    return findActive(id).orElseThrow(() -> new NotFoundException("user", id));
  }

  /** For callers that cannot throw, such as a servlet filter: a removed user is simply absent. */
  @Transactional(readOnly = true)
  public Optional<User> findActive(String id) {
    return users.findById(id).filter(User::isActive);
  }

  @Transactional(readOnly = true)
  public List<User> listByMerchant(MerchantId merchantId) {
    return users.findActiveByMerchant(merchantId);
  }

  @Transactional
  public User changePassword(String userId, String current, String next) {
    User user = get(userId);
    if (!passwords.matches(current, user.passwordHash())) {
      throw new DomainException("INVALID_CREDENTIALS", "current password does not match");
    }

    return resetPassword(userId, next);
  }

  @Transactional
  public User resetPassword(String userId, String next) {
    passwords.requireStrong(next);

    return users.save(get(userId).withPasswordHash(passwords.hash(next), clock.instant()));
  }

  @Transactional
  public User markEmailVerified(String userId) {
    return users.save(get(userId).verified(clock.instant()));
  }

  @Transactional
  public User rename(String userId, String name) {
    return users.save(get(userId).withName(name, clock.instant()));
  }

  @Transactional
  public User changeRole(MerchantId merchantId, String userId, Role role) {
    User user = ofMerchant(merchantId, userId);
    if (user.role() == Role.OWNER && role != Role.OWNER) {
      requireAnotherOwner(merchantId);
    }

    return users.save(user.withRole(role, clock.instant()));
  }

  @Transactional
  public User remove(MerchantId merchantId, String userId) {
    User user = ofMerchant(merchantId, userId);
    if (user.role() == Role.OWNER) {
      requireAnotherOwner(merchantId);
    }

    return users.save(user.deleted(clock.instant()));
  }

  private User ofMerchant(MerchantId merchantId, String userId) {
    User user = get(userId);
    if (!user.merchantId().equals(merchantId)) {
      throw new NotFoundException("user", userId);
    }

    return user;
  }

  // A store with no owner has nobody who can invite one: the last owner stays until another exists.
  private void requireAnotherOwner(MerchantId merchantId) {
    if (users.countActiveByMerchantAndRole(merchantId, Role.OWNER) <= 1) {
      throw new DomainException("LAST_OWNER", "the last owner cannot be demoted or removed");
    }
  }
}
