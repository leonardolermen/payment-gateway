# Users and Sessions (B3) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Merchants log into the panel with e-mail and password; a store has several users with a role; invites, e-mail verification and password reset go out by e-mail; the API key keeps working untouched.

**Architecture:** New concepts in `gateway-merchants` (`user/`, `session/`, `usertoken/`, `mail/`), each with its own `persistence/`, wired by `MerchantsConfiguration`. Sessions are opaque tokens hashed in Postgres (no JWT). `gateway-app` gets `UserSessionFilter` (before `ApiKeyAuthFilter`), a declarative `RoleRoutes` table, `AuthController`/`MeController`/`TeamController`, and a `SendEmailJob` on the existing jobs table. `MerchantContext.Current` carries a sealed `Actor` instead of an `apiKeyId`.

**Tech Stack:** Java 25, Spring Boot 4, JPA/Hibernate, Flyway, `spring-security-crypto` (Argon2id only), `spring-boot-starter-mail`, Bucket4j (existing), Testcontainers + GreenMail (tests), ArchUnit.

**Spec:** `docs/superpowers/specs/2026-10-08-usuarios-e-sessao-design.md`

## Global Constraints

- Code, comments, commits in English; 100 columns; `./mvnw spotless:apply` before each commit; `./mvnw verify` green before the PR (Docker needed). `JAVA_HOME` must be a JDK 25 (`~/.jdks/corretto-25.0.4.1`).
- ArchUnit (`gateway-app/src/test/.../architecture/ArchitectureTest.java`): `merchants` imports neither `payments` nor `billing` nor `app`; `@Entity` package-private and only under `..persistence..`; outside `..persistence..`/`..support..`, only classes whose name ends in `Service|Runner|Gateway|Relay|Properties|Configuration|Events` may import `org.springframework..`. Hence `PasswordService` (Argon2 is `org.springframework.security.crypto`), `SmtpMailGateway`/`LoggingMailGateway` (JavaMailSender), and the job handler lives in `gateway-app`.
- Folders are concepts (`user/`, `session/`, `usertoken/`, `mail/`), never roles. Value objects validate in the canonical constructor. No `if` chains for validation.
- Secrets: password hashes, access/refresh/token hashes only — never the plaintext in a row, a log, an error message or `provider_requests`. Token hash = `ApiKey.hashOf(plain, properties.apiKeyPepper())` (SHA-256 + pepper). Passwords = Argon2id, m = 65536 KB, t = 3, p = 1 (`Argon2PasswordEncoder(16, 32, 1, 65536, 3)`).
- Error codes are contract: `EMAIL_TAKEN` 409, `WEAK_PASSWORD` 422, `INVALID_CREDENTIALS` 401, `SESSION_EXPIRED` 401, `TOKEN_EXPIRED` 410, `LAST_OWNER` 409, `FORBIDDEN_FOR_ROLE` 403 (+`required_role`), `EMAIL_NOT_VERIFIED` 403, `USER_SESSION_REQUIRED` 403, `RESEND_TOO_SOON` 429.
- Existing tests do not change, except two stubs that must learn the new `JobType` (Task 5) and the two call sites of `MerchantContext.Current.apiKeyId()` (Task 6). `JobHandlers` refuses to start with a type that has no handler, so `SEND_EMAIL` needs a stub wherever `BillingJobOwnersStub` is used.
- Environment of a user session comes from `X-Environment` (`TEST` default); never from a body.
- Properties: `gateway.mail.host|port|username|password|from` (empty host = `LoggingMailGateway`), `gateway.panel.base-url` (default `http://localhost:5173`), `gateway.auth.rate-limit-per-minute` (default 10), `gateway.auth.access-ttl` (PT15M), `gateway.auth.refresh-ttl` (P30D).

## Review Focus

1. A refresh token presented twice (the first use rotated it) must revoke the whole session, and the second caller gets `401 SESSION_EXPIRED` — pinned in Task 3 (`SessionServiceIntegrationTest.aReusedRefreshRevokesTheSession`).
2. Login with an e-mail that does not exist must take the same code path and answer as a wrong password, and must still run Argon2 — pinned in Task 2 (`UserServiceIntegrationTest.unknownEmailAndWrongPasswordAreTheSameEmptyAnswer`).
3. A verify/reset/invite token consumed twice, or after `expires_at`, must be refused with `TOKEN_EXPIRED`, and consumption must be atomic (two concurrent consumers: one wins) — pinned in Task 4 (`UserTokenServiceIntegrationTest.aTokenIsConsumedOnceAndNeverAfterExpiry`).
4. The last active `OWNER` cannot be demoted or removed, even by itself — pinned in Task 8 (`TeamApiIntegrationTest.theLastOwnerStays`).
5. A `gk_` API key must still authenticate every existing route exactly as before, with no role check — pinned in Task 6 (`UserSessionFilterIntegrationTest.anApiKeyIsUntouchedByRoles`) and by the untouched existing suites.

---

### Task 1: Dependencies, migration V103, `User` and its persistence

**Files:**
- Modify: `pom.xml` (versions), `gateway-merchants/pom.xml`, `gateway-app/pom.xml`
- Create: `gateway-merchants/src/main/resources/db/migration/merchants/V103__users_sessions_tokens.sql`
- Create: `gateway-merchants/src/main/java/com/gateway/merchants/user/{Role,EmailAddress,User}.java`
- Create: `gateway-merchants/src/main/java/com/gateway/merchants/user/persistence/{UserEntity,UserJpaRepository,UserRepository,UserRepositoryImpl}.java`
- Modify: `gateway-merchants/src/main/java/com/gateway/merchants/MerchantsConfiguration.java` (`@EntityScan`, `@EnableJpaRepositories`, `@Import`)
- Test: `gateway-merchants/src/test/java/com/gateway/merchants/user/UserRepositoryIntegrationTest.java`, `gateway-merchants/src/test/java/com/gateway/merchants/user/EmailAddressTest.java`

**Interfaces:**
- Produces:
  - `enum Role { OWNER, FINANCE, READONLY; boolean atLeast(Role minimum) }` (OWNER > FINANCE > READONLY).
  - `record EmailAddress(String value)` — canonical constructor trims, requires `@` and a dot after it, max 254; `String normalized()` = lower-case.
  - `record User(String id, MerchantId merchantId, String name, EmailAddress email, Role role, String passwordHash, Instant emailVerifiedAt, Instant lastLoginAt, Instant createdAt, Instant updatedAt, Instant deletedAt)` with `static User create(MerchantId, String name, EmailAddress, Role, String passwordHash, Clock)`, `User verified(Instant)`, `User withRole(Role, Instant)`, `User withPasswordHash(String, Instant)`, `User loggedInAt(Instant)`, `User deleted(Instant)`, `boolean isActive()`, `boolean isEmailVerified()`.
  - `interface UserRepository { void insert(User); User save(User); Optional<User> findById(String); Optional<User> findActiveByEmail(String normalized); List<User> findActiveByMerchant(MerchantId); long countActiveByMerchantAndRole(MerchantId, Role); }` — `insert` throws `DataIntegrityViolationException` on a duplicate `email_normalized`.

- [ ] **Step 1: Add the dependencies**

Root `pom.xml`, in `<properties>`: `<greenmail.version>2.1.3</greenmail.version>`. In `<dependencyManagement>`: 
```xml
<dependency><groupId>com.icegreen</groupId><artifactId>greenmail-junit5</artifactId><version>${greenmail.version}</version><scope>test</scope></dependency>
```
`gateway-merchants/pom.xml`:
```xml
<dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-crypto</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-mail</artifactId></dependency>
```
`gateway-app/pom.xml`: `<dependency><groupId>com.icegreen</groupId><artifactId>greenmail-junit5</artifactId><scope>test</scope></dependency>`.
Run: `./mvnw -q -pl gateway-merchants,gateway-app -am dependency:resolve` → Expected: no error (Boot's BOM manages both Spring artifacts).

- [ ] **Step 2: Write the migration**

```sql
-- gateway-merchants/src/main/resources/db/migration/merchants/V103__users_sessions_tokens.sql
-- The panel's own login (spec 2026-10-08-usuarios-e-sessao). One e-mail is one user in one store:
-- email_normalized is unique across the system, not per merchant. Every secret here is a hash:
-- Argon2id for the password, peppered SHA-256 for session and one-time tokens.
CREATE TABLE merchants.users (
    id                CHAR(26)     PRIMARY KEY,
    merchant_id       CHAR(26)     NOT NULL REFERENCES merchants.merchants (id),
    name              VARCHAR(120) NOT NULL,
    email             VARCHAR(254) NOT NULL,
    email_normalized  VARCHAR(254) NOT NULL,
    password_hash     VARCHAR(200) NOT NULL,
    role              VARCHAR(10)  NOT NULL CHECK (role IN ('OWNER', 'FINANCE', 'READONLY')),
    email_verified_at TIMESTAMPTZ,
    last_login_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL,
    updated_at        TIMESTAMPTZ  NOT NULL,
    deleted_at        TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_users_email ON merchants.users (email_normalized) WHERE deleted_at IS NULL;
CREATE INDEX idx_users_merchant ON merchants.users (merchant_id) WHERE deleted_at IS NULL;

-- previous_refresh_hash: a refresh presented after it was rotated is the sign of a stolen cookie;
-- keeping the one just replaced is what lets the service recognise it and revoke the session.
CREATE TABLE merchants.sessions (
    id                    CHAR(26)     PRIMARY KEY,
    user_id               CHAR(26)     NOT NULL REFERENCES merchants.users (id),
    access_hash           CHAR(64)     NOT NULL,
    refresh_hash          CHAR(64)     NOT NULL,
    previous_refresh_hash CHAR(64),
    access_expires_at     TIMESTAMPTZ  NOT NULL,
    refresh_expires_at    TIMESTAMPTZ  NOT NULL,
    ip                    VARCHAR(45),
    user_agent            VARCHAR(200),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_used_at          TIMESTAMPTZ  NOT NULL,
    revoked_at            TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_sessions_access ON merchants.sessions (access_hash);
CREATE UNIQUE INDEX uq_sessions_refresh ON merchants.sessions (refresh_hash);
CREATE INDEX idx_sessions_previous_refresh ON merchants.sessions (previous_refresh_hash)
    WHERE previous_refresh_hash IS NOT NULL;
CREATE INDEX idx_sessions_user_live ON merchants.sessions (user_id) WHERE revoked_at IS NULL;

CREATE TABLE merchants.user_tokens (
    id          CHAR(26)    PRIMARY KEY,
    user_id     CHAR(26)    REFERENCES merchants.users (id),
    merchant_id CHAR(26)    NOT NULL REFERENCES merchants.merchants (id),
    kind        VARCHAR(16) NOT NULL CHECK (kind IN ('VERIFY_EMAIL', 'RESET_PASSWORD', 'INVITE')),
    token_hash  CHAR(64)    NOT NULL,
    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    expires_at  TIMESTAMPTZ NOT NULL,
    used_at     TIMESTAMPTZ,
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE UNIQUE INDEX uq_user_tokens_hash ON merchants.user_tokens (token_hash);

-- The rendered message waits here for SEND_EMAIL and is deleted once sent: the link inside carries
-- a one-time token, so the row must not outlive the send.
CREATE TABLE merchants.outbound_emails (
    id         CHAR(26)     PRIMARY KEY,
    recipient  VARCHAR(254) NOT NULL,
    subject    VARCHAR(200) NOT NULL,
    text_body  TEXT         NOT NULL,
    html_body  TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL
);
```

- [ ] **Step 3: Failing unit test for `EmailAddress`**

```java
// gateway-merchants/src/test/java/com/gateway/merchants/user/EmailAddressTest.java
package com.gateway.merchants.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailAddressTest {
  @Test
  void trimsAndNormalizesToLowerCase() {
    EmailAddress email = new EmailAddress("  Ana.Silva@Loja.COM ");

    assertThat(email.value()).isEqualTo("Ana.Silva@Loja.COM");
    assertThat(email.normalized()).isEqualTo("ana.silva@loja.com");
  }

  @Test
  void refusesWhatIsNotAnAddress() {
    assertThatThrownBy(() -> new EmailAddress("ana")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailAddress("ana@loja")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailAddress("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailAddress("a".repeat(250) + "@x.com"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```
Run: `./mvnw -q -pl gateway-merchants test -Dtest=EmailAddressTest` → Expected: compilation FAIL (no `EmailAddress`).

- [ ] **Step 4: Write `Role`, `EmailAddress`, `User`**

```java
// user/Role.java
package com.gateway.merchants.user;

/** OWNER > FINANCE > READONLY; the route table in the app asks "at least". */
public enum Role {
  READONLY,
  FINANCE,
  OWNER;

  public boolean atLeast(Role minimum) {
    return ordinal() >= minimum.ordinal();
  }
}
```
```java
// user/EmailAddress.java
package com.gateway.merchants.user;

import java.util.Locale;
import java.util.regex.Pattern;

/** Trimmed on the way in; compared lower-cased. Shape only: deliverability is the mail's problem. */
public record EmailAddress(String value) {
  private static final Pattern SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
  private static final int MAX = 254;

  public EmailAddress {
    if (value == null) {
      throw new IllegalArgumentException("email is required");
    }
    value = value.trim();
    if (value.length() > MAX || !SHAPE.matcher(value).matches()) {
      throw new IllegalArgumentException("email is not a valid address");
    }
  }

  public String normalized() {
    return value.toLowerCase(Locale.ROOT);
  }
}
```
```java
// user/User.java
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
      MerchantId merchantId, String name, EmailAddress email, Role role, String passwordHash, Clock clock) {
    Instant now = clock.instant();
    String trimmed = name == null ? "" : name.trim();
    if (trimmed.isEmpty() || trimmed.length() > 120) {
      throw new IllegalArgumentException("name is required (up to 120 characters)");
    }

    return new User(Ulid.next(), merchantId, trimmed, email, role, passwordHash, null, null, now, now, null);
  }

  public boolean isActive() {
    return deletedAt == null;
  }

  public boolean isEmailVerified() {
    return emailVerifiedAt != null;
  }

  public User verified(Instant at) {
    return new User(id, merchantId, name, email, role, passwordHash, at, lastLoginAt, createdAt, at, deletedAt);
  }

  public User withRole(Role newRole, Instant at) {
    return new User(id, merchantId, name, email, newRole, passwordHash, emailVerifiedAt, lastLoginAt, createdAt, at, deletedAt);
  }

  public User withPasswordHash(String hash, Instant at) {
    return new User(id, merchantId, name, email, role, hash, emailVerifiedAt, lastLoginAt, createdAt, at, deletedAt);
  }

  public User withName(String newName, Instant at) {
    return new User(id, merchantId, newName.trim(), email, role, passwordHash, emailVerifiedAt, lastLoginAt, createdAt, at, deletedAt);
  }

  public User loggedInAt(Instant at) {
    return new User(id, merchantId, name, email, role, passwordHash, emailVerifiedAt, at, createdAt, updatedAt, deletedAt);
  }

  public User deleted(Instant at) {
    return new User(id, merchantId, name, email, role, passwordHash, emailVerifiedAt, lastLoginAt, createdAt, at, at);
  }
}
```
Run: `./mvnw -q -pl gateway-merchants test -Dtest=EmailAddressTest` → Expected: PASS 2/2.

- [ ] **Step 5: Failing repository integration test**

```java
// gateway-merchants/src/test/java/com/gateway/merchants/user/UserRepositoryIntegrationTest.java
package com.gateway.merchants.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.merchants.TestApp;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.user.persistence.UserRepository;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApp.class)
@Testcontainers
class UserRepositoryIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired MerchantService merchants;
  @Autowired UserRepository users;

  @Test
  void insertsFindsByNormalizedEmailAndRefusesADuplicate() {
    Merchant store = merchants.create("Loja");
    User ana = User.create(store.id(), "Ana", new EmailAddress("Ana@Loja.com"), Role.OWNER, "h", Clock.systemUTC());

    users.insert(ana);

    assertThat(users.findActiveByEmail("ana@loja.com")).map(User::id).contains(ana.id());
    assertThat(users.findActiveByMerchant(store.id())).extracting(User::id).containsExactly(ana.id());
    assertThat(users.countActiveByMerchantAndRole(store.id(), Role.OWNER)).isEqualTo(1);

    User again = User.create(store.id(), "Ana 2", new EmailAddress("ANA@loja.com"), Role.FINANCE, "h", Clock.systemUTC());
    assertThatThrownBy(() -> users.insert(again)).isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void aDeletedUserIsNotFoundAndFreesTheEmail() {
    Merchant store = merchants.create("Loja");
    User ana = User.create(store.id(), "Ana", new EmailAddress("ana@loja.com"), Role.OWNER, "h", Clock.systemUTC());
    users.insert(ana);

    users.save(ana.deleted(Clock.systemUTC().instant()));

    assertThat(users.findActiveByEmail("ana@loja.com")).isEmpty();
    assertThat(users.findById(ana.id())).map(User::isActive).contains(false);
    users.insert(User.create(store.id(), "Ana", new EmailAddress("ana@loja.com"), Role.OWNER, "h", Clock.systemUTC()));
  }
}
```
Run: `./mvnw -q -pl gateway-merchants test -Dtest=UserRepositoryIntegrationTest` → Expected: compilation FAIL.

- [ ] **Step 6: Write the persistence trio and wire it**

```java
// user/persistence/UserEntity.java
package com.gateway.merchants.user.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "users", schema = "merchants")
class UserEntity {
  // @JdbcTypeCode(CHAR) matches CHAR(n) in the migration, as every entity in this module does.
  @Id @Column(name = "id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String id;
  @Column(name = "merchant_id", length = 26, nullable = false) @JdbcTypeCode(SqlTypes.CHAR) String merchantId;
  @Column(name = "name", nullable = false, length = 120) String name;
  @Column(name = "email", nullable = false, length = 254) String email;
  @Column(name = "email_normalized", nullable = false, length = 254) String emailNormalized;
  @Column(name = "password_hash", nullable = false, length = 200) String passwordHash;
  @Column(name = "role", nullable = false, length = 10) String role;
  @Column(name = "email_verified_at") Instant emailVerifiedAt;
  @Column(name = "last_login_at") Instant lastLoginAt;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  @Column(name = "updated_at", nullable = false) Instant updatedAt;
  @Column(name = "deleted_at") Instant deletedAt;
}
```
```java
// user/persistence/UserJpaRepository.java
package com.gateway.merchants.user.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface UserJpaRepository extends JpaRepository<UserEntity, String> {
  Optional<UserEntity> findByEmailNormalizedAndDeletedAtIsNull(String emailNormalized);

  List<UserEntity> findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtAsc(String merchantId);

  long countByMerchantIdAndRoleAndDeletedAtIsNull(String merchantId, String role);
}
```
```java
// user/persistence/UserRepository.java
package com.gateway.merchants.user.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import java.util.List;
import java.util.Optional;

public interface UserRepository {
  /** Throws DataIntegrityViolationException when the e-mail already belongs to an active user. */
  void insert(User user);

  User save(User user);

  Optional<User> findById(String id);

  Optional<User> findActiveByEmail(String normalized);

  List<User> findActiveByMerchant(MerchantId merchantId);

  long countActiveByMerchantAndRole(MerchantId merchantId, Role role);
}
```
```java
// user/persistence/UserRepositoryImpl.java
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
    return jpa.findByEmailNormalizedAndDeletedAtIsNull(normalized).map(UserRepositoryImpl::toDomain);
  }

  @Override
  public List<User> findActiveByMerchant(MerchantId merchantId) {
    return jpa.findByMerchantIdAndDeletedAtIsNullOrderByCreatedAtAsc(merchantId.value()).stream()
        .map(UserRepositoryImpl::toDomain)
        .toList();
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
```
In `MerchantsConfiguration`, add `"com.gateway.merchants.user.persistence"` to both `@EntityScan` and `@EnableJpaRepositories`, and `UserRepositoryImpl.class` to `@Import`.

Run: `./mvnw -q -pl gateway-merchants test -Dtest=UserRepositoryIntegrationTest` → Expected: PASS 2/2.

- [ ] **Step 7: Commit**

```bash
./mvnw -q spotless:apply
git add pom.xml gateway-merchants gateway-app/pom.xml
git commit -m "feat(merchants): users — migration V103, the User aggregate and its repository"
```

---

### Task 2: Passwords and `UserService`

**Files:**
- Create: `gateway-merchants/src/main/java/com/gateway/merchants/user/{PasswordService,UserService}.java`
- Modify: `MerchantsConfiguration.java` (beans)
- Test: `gateway-merchants/src/test/java/com/gateway/merchants/user/{PasswordServiceTest,UserServiceIntegrationTest}.java`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  - `PasswordService { String hash(String raw); boolean matches(String raw, String hash); void requireStrong(String raw) }` — `requireStrong` throws `DomainException("WEAK_PASSWORD", "password must have at least 10 characters")`. Holds a `DUMMY_HASH` computed at construction, matched against when the user does not exist.
  - `UserService(UserRepository, PasswordService, Clock)`: `User register(MerchantId, String name, EmailAddress, Role, String password)` (→ `EMAIL_TAKEN`), `Optional<User> authenticate(EmailAddress, String password)` (updates `last_login_at`), `User get(String id)` (NotFound), `List<User> listByMerchant(MerchantId)`, `User changePassword(String userId, String current, String next)` (→ `INVALID_CREDENTIALS` on wrong current), `User resetPassword(String userId, String next)`, `User markEmailVerified(String userId)`, `User rename(String userId, String name)`, `User changeRole(MerchantId, String userId, Role)` and `User remove(MerchantId, String userId)` (both → `LAST_OWNER` when the target is the only active OWNER; NotFound when the user is not the merchant's).

- [ ] **Step 1: Failing tests**

```java
// user/PasswordServiceTest.java
package com.gateway.merchants.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import org.junit.jupiter.api.Test;

class PasswordServiceTest {
  PasswordService passwords = new PasswordService();

  @Test
  void hashesWithArgon2idAndMatchesOnlyTheSamePassword() {
    String hash = passwords.hash("correct horse battery");

    assertThat(hash).startsWith("$argon2id$");
    assertThat(passwords.matches("correct horse battery", hash)).isTrue();
    assertThat(passwords.matches("correct horse batter", hash)).isFalse();
  }

  @Test
  void tenCharactersIsTheOnlyRule() {
    passwords.requireStrong("abcdefghij");
    assertThatThrownBy(() -> passwords.requireStrong("abcdefghi"))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("10");
  }
}
```
```java
// user/UserServiceIntegrationTest.java  (same @SpringBootTest/@Testcontainers header as Task 1's test)
  @Autowired MerchantService merchants;
  @Autowired UserService users;

  @Test
  void registersAndAuthenticates() {
    Merchant store = merchants.create("Loja");
    User ana = users.register(store.id(), "Ana", new EmailAddress("ana@loja.com"), Role.OWNER, "senha-forte-1");

    assertThat(ana.passwordHash()).doesNotContain("senha-forte-1");
    assertThat(users.authenticate(new EmailAddress("ANA@loja.com"), "senha-forte-1")).map(User::id).contains(ana.id());
    assertThat(users.get(ana.id()).lastLoginAt()).isNotNull();
    assertThatThrownBy(() -> users.register(store.id(), "Ana", new EmailAddress("ana@loja.com"), Role.FINANCE, "outra-senha-1"))
        .isInstanceOf(DomainException.class)
        .hasFieldOrPropertyWithValue("code", "EMAIL_TAKEN");
  }

  @Test
  void unknownEmailAndWrongPasswordAreTheSameEmptyAnswer() {
    Merchant store = merchants.create("Loja");
    users.register(store.id(), "Ana", new EmailAddress("ana@loja.com"), Role.OWNER, "senha-forte-1");

    assertThat(users.authenticate(new EmailAddress("ana@loja.com"), "errada-errada")).isEmpty();
    assertThat(users.authenticate(new EmailAddress("ninguem@loja.com"), "senha-forte-1")).isEmpty();
  }

  @Test
  void theLastOwnerCannotBeDemotedOrRemoved() {
    Merchant store = merchants.create("Loja");
    User ana = users.register(store.id(), "Ana", new EmailAddress("ana@loja.com"), Role.OWNER, "senha-forte-1");
    User bia = users.register(store.id(), "Bia", new EmailAddress("bia@loja.com"), Role.FINANCE, "senha-forte-2");

    assertThatThrownBy(() -> users.changeRole(store.id(), ana.id(), Role.FINANCE))
        .hasFieldOrPropertyWithValue("code", "LAST_OWNER");
    assertThatThrownBy(() -> users.remove(store.id(), ana.id()))
        .hasFieldOrPropertyWithValue("code", "LAST_OWNER");

    users.changeRole(store.id(), bia.id(), Role.OWNER);
    users.remove(store.id(), ana.id());
    assertThat(users.listByMerchant(store.id())).extracting(User::id).containsExactly(bia.id());
  }

  @Test
  void changePasswordNeedsTheCurrentOne() {
    Merchant store = merchants.create("Loja");
    User ana = users.register(store.id(), "Ana", new EmailAddress("ana@loja.com"), Role.OWNER, "senha-forte-1");

    assertThatThrownBy(() -> users.changePassword(ana.id(), "errada", "nova-senha-11"))
        .hasFieldOrPropertyWithValue("code", "INVALID_CREDENTIALS");
    users.changePassword(ana.id(), "senha-forte-1", "nova-senha-11");
    assertThat(users.authenticate(new EmailAddress("ana@loja.com"), "nova-senha-11")).isPresent();
  }
```
Run: `./mvnw -q -pl gateway-merchants test -Dtest='PasswordServiceTest,UserServiceIntegrationTest'` → Expected: compilation FAIL.

- [ ] **Step 2: Write the services**

```java
// user/PasswordService.java
package com.gateway.merchants.user;

import com.gateway.kernel.errors.DomainException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Argon2id, 64 MB, 3 passes: ~150 ms on a laptop, the cost a login can pay and a brute force cannot.
 * The dummy hash is what authenticate() compares against when the e-mail is unknown, so that path
 * takes as long as a wrong password.
 */
public class PasswordService {
  private static final int MIN_LENGTH = 10;

  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 65536, 3);
  private final String dummyHash = encoder.encode("not-a-password-anyone-has");

  public String hash(String raw) {
    return encoder.encode(raw);
  }

  public boolean matches(String raw, String hash) {
    return encoder.matches(raw, hash);
  }

  public void burnTime(String raw) {
    encoder.matches(raw, dummyHash);
  }

  public void requireStrong(String raw) {
    if (raw == null || raw.length() < MIN_LENGTH) {
      throw new DomainException("WEAK_PASSWORD", "password must have at least " + MIN_LENGTH + " characters");
    }
  }
}
```
```java
// user/UserService.java
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
  public User register(MerchantId merchantId, String name, EmailAddress email, Role role, String password) {
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
    return users.findById(id).filter(User::isActive).orElseThrow(() -> new NotFoundException("user", id));
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
```
Beans in `MerchantsConfiguration`:
```java
  @Bean
  public PasswordService passwordService() {
    return new PasswordService();
  }

  @Bean
  public UserService userService(UserRepository users, PasswordService passwords, Clock clock) {
    return new UserService(users, passwords, clock);
  }
```
`TestApp` of the merchants module has no `Clock` bean: add `@Bean Clock clock() { return Clock.systemUTC(); }` to `TestApp` (the app already defines one in `AppConfiguration`).

Run: `./mvnw -q -pl gateway-merchants test -Dtest='PasswordServiceTest,UserServiceIntegrationTest'` → Expected: PASS 6/6.

- [ ] **Step 3: Commit**

```bash
./mvnw -q spotless:apply && git add gateway-merchants && git commit -m "feat(merchants): Argon2id passwords and UserService — register, authenticate, roles, last owner"
```

---

### Task 3: Sessions — opaque tokens, rotation, reuse detection

**Files:**
- Create: `gateway-merchants/src/main/java/com/gateway/merchants/session/{Session,SessionService}.java`, `session/persistence/{SessionEntity,SessionJpaRepository,SessionRepository,SessionRepositoryImpl}.java`
- Modify: `MerchantsProperties.java` (`authAccessTtl`, `authRefreshTtl` with defaults PT15M / P30D), `MerchantsConfiguration.java`
- Test: `gateway-merchants/src/test/java/com/gateway/merchants/session/SessionServiceIntegrationTest.java`

**Interfaces:**
- Produces:
  - `record Session(String id, String userId, String accessHash, String refreshHash, String previousRefreshHash, Instant accessExpiresAt, Instant refreshExpiresAt, String ip, String userAgent, Instant createdAt, Instant lastUsedAt, Instant revokedAt)` with `boolean isLive(Instant now)` (not revoked, refresh not expired), `boolean accessValid(Instant now)`.
  - `SessionService.Issued(Session session, Secret accessToken, Secret refreshToken, Duration accessTtl)`.
  - `SessionService(SessionRepository, MerchantsProperties, Clock)`: `Issued open(String userId, String ip, String userAgent)`; `Optional<Session> authenticate(String accessToken)` (touches `last_used_at` at most once a minute); `Optional<Issued> refresh(String refreshToken)` (rotates; a token matching `previous_refresh_hash` revokes the session and returns empty); `void revoke(String sessionId)`; `void revokeOthers(String userId, String keepSessionId)`; `void revokeAll(String userId)`; `List<Session> listLive(String userId)`.
  - Tokens: `gs_` + 43 chars base64url of 32 random bytes (access), `gr_` + same (refresh); hash = `ApiKey.hashOf(plain, pepper)`.
  - `SessionRepository { void insert(Session); Session save(Session); Optional<Session> findByAccessHash(String); Optional<Session> findByRefreshHash(String); Optional<Session> findByPreviousRefreshHash(String); List<Session> findLiveByUser(String userId, Instant now); }`.

- [ ] **Step 1: Failing integration test**

```java
// session/SessionServiceIntegrationTest.java (same test header; autowire MerchantService, UserService, SessionService)
  private User ana() {
    Merchant store = merchants.create("Loja");
    return users.register(store.id(), "Ana", new EmailAddress("ana@loja.com"), Role.OWNER, "senha-forte-1");
  }

  @Test
  void opensAuthenticatesAndRotates() {
    User ana = ana();
    SessionService.Issued first = sessions.open(ana.id(), "203.0.113.9", "Mozilla/5.0");

    assertThat(first.accessToken().reveal()).startsWith("gs_");
    assertThat(first.refreshToken().reveal()).startsWith("gr_");
    assertThat(sessions.authenticate(first.accessToken().reveal())).map(Session::userId).contains(ana.id());
    assertThat(sessions.authenticate("gs_nope")).isEmpty();

    SessionService.Issued second = sessions.refresh(first.refreshToken().reveal()).orElseThrow();
    assertThat(second.session().id()).isEqualTo(first.session().id());
    assertThat(sessions.authenticate(first.accessToken().reveal())).isEmpty();
    assertThat(sessions.authenticate(second.accessToken().reveal())).isPresent();
  }

  @Test
  void aReusedRefreshRevokesTheSession() {
    User ana = ana();
    SessionService.Issued first = sessions.open(ana.id(), null, null);
    SessionService.Issued second = sessions.refresh(first.refreshToken().reveal()).orElseThrow();

    // The old refresh comes back: someone else holds the cookie. Everything on that session dies.
    assertThat(sessions.refresh(first.refreshToken().reveal())).isEmpty();
    assertThat(sessions.refresh(second.refreshToken().reveal())).isEmpty();
    assertThat(sessions.authenticate(second.accessToken().reveal())).isEmpty();
  }

  @Test
  void revokeOthersKeepsOnlyTheCurrentOne() {
    User ana = ana();
    SessionService.Issued laptop = sessions.open(ana.id(), null, null);
    SessionService.Issued phone = sessions.open(ana.id(), null, null);

    sessions.revokeOthers(ana.id(), laptop.session().id());

    assertThat(sessions.authenticate(laptop.accessToken().reveal())).isPresent();
    assertThat(sessions.authenticate(phone.accessToken().reveal())).isEmpty();
    assertThat(sessions.listLive(ana.id())).extracting(Session::id).containsExactly(laptop.session().id());
  }

  @Test
  void nothingPlainIsStored() {
    User ana = ana();
    SessionService.Issued issued = sessions.open(ana.id(), null, null);

    String row = jdbc.queryForObject("SELECT access_hash || refresh_hash FROM merchants.sessions WHERE id = ?", String.class, issued.session().id());
    assertThat(row).doesNotContain(issued.accessToken().reveal()).doesNotContain(issued.refreshToken().reveal());
  }
```
Run: `./mvnw -q -pl gateway-merchants test -Dtest=SessionServiceIntegrationTest` → Expected: compilation FAIL.

- [ ] **Step 2: Write `Session`, the persistence trio, `SessionService`**

```java
// session/Session.java
package com.gateway.merchants.session;

import java.time.Instant;

public record Session(
    String id, String userId, String accessHash, String refreshHash, String previousRefreshHash,
    Instant accessExpiresAt, Instant refreshExpiresAt, String ip, String userAgent,
    Instant createdAt, Instant lastUsedAt, Instant revokedAt) {

  public boolean isLive(Instant now) {
    return revokedAt == null && refreshExpiresAt.isAfter(now);
  }

  public boolean accessValid(Instant now) {
    return isLive(now) && accessExpiresAt.isAfter(now);
  }

  public Session rotated(String newAccessHash, String newRefreshHash, Instant accessExpiresAt, Instant refreshExpiresAt, Instant now) {
    return new Session(id, userId, newAccessHash, newRefreshHash, refreshHash, accessExpiresAt, refreshExpiresAt, ip, userAgent, createdAt, now, revokedAt);
  }

  public Session touched(Instant now) {
    return new Session(id, userId, accessHash, refreshHash, previousRefreshHash, accessExpiresAt, refreshExpiresAt, ip, userAgent, createdAt, now, revokedAt);
  }

  public Session revoked(Instant now) {
    return new Session(id, userId, accessHash, refreshHash, previousRefreshHash, accessExpiresAt, refreshExpiresAt, ip, userAgent, createdAt, lastUsedAt, now);
  }
}
```
Persistence: `SessionEntity` (columns as in V103, `@JdbcTypeCode(SqlTypes.CHAR)` on the five CHAR columns), `SessionJpaRepository` with `findByAccessHash`, `findByRefreshHash`, `findByPreviousRefreshHash`, `findByUserIdAndRevokedAtIsNullAndRefreshExpiresAtAfter(String, Instant)`; `SessionRepositoryImpl` mapping both ways like `UserRepositoryImpl` (insert = `persist`+`flush`, save = find-or-new).

```java
// session/SessionService.java
package com.gateway.merchants.session;

import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.security.Secret;
import com.gateway.merchants.MerchantsProperties;
import com.gateway.merchants.apikey.ApiKey;
import com.gateway.merchants.session.persistence.SessionRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/**
 * Opaque tokens, hashed with the API-key pepper. Access lives 15 minutes in the browser's memory;
 * refresh lives 30 days in an HttpOnly cookie and is rotated on every use. A refresh that comes back
 * after it was rotated is the one sign of a stolen cookie we can see, and it ends the session.
 */
public class SessionService {
  public record Issued(Session session, Secret accessToken, Secret refreshToken, Duration accessTtl) {}

  private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);
  private static final SecureRandom RANDOM = new SecureRandom();

  private final SessionRepository sessions;
  private final MerchantsProperties properties;
  private final Clock clock;

  public SessionService(SessionRepository sessions, MerchantsProperties properties, Clock clock) {
    this.sessions = sessions;
    this.properties = properties;
    this.clock = clock;
  }

  @Transactional
  public Issued open(String userId, String ip, String userAgent) {
    Instant now = clock.instant();
    String access = token("gs_");
    String refresh = token("gr_");
    Session session = new Session(
        Ulid.next(), userId, hash(access), hash(refresh), null,
        now.plus(properties.authAccessTtl()), now.plus(properties.authRefreshTtl()),
        ip, userAgent == null ? null : userAgent.substring(0, Math.min(200, userAgent.length())),
        now, now, null);
    sessions.insert(session);

    return new Issued(session, Secret.of(access), Secret.of(refresh), properties.authAccessTtl());
  }

  @Transactional
  public Optional<Session> authenticate(String accessToken) {
    Instant now = clock.instant();

    return sessions.findByAccessHash(hash(accessToken))
        .filter(session -> session.accessValid(now))
        .map(session -> session.lastUsedAt().plus(TOUCH_EVERY).isBefore(now) ? sessions.save(session.touched(now)) : session);
  }

  @Transactional
  public Optional<Issued> refresh(String refreshToken) {
    Instant now = clock.instant();
    String presented = hash(refreshToken);

    Optional<Session> replayed = sessions.findByPreviousRefreshHash(presented);
    if (replayed.isPresent()) {
      sessions.save(replayed.get().revoked(now));
      return Optional.empty();
    }

    return sessions.findByRefreshHash(presented)
        .filter(session -> session.isLive(now))
        .map(session -> {
          String access = token("gs_");
          String refresh = token("gr_");
          Session rotated = sessions.save(session.rotated(
              hash(access), hash(refresh), now.plus(properties.authAccessTtl()), now.plus(properties.authRefreshTtl()), now));
          return new Issued(rotated, Secret.of(access), Secret.of(refresh), properties.authAccessTtl());
        });
  }

  @Transactional
  public void revoke(String sessionId) {
    sessions.findById(sessionId).filter(session -> session.revokedAt() == null)
        .ifPresent(session -> sessions.save(session.revoked(clock.instant())));
  }

  @Transactional
  public void revokeOthers(String userId, String keepSessionId) {
    Instant now = clock.instant();
    sessions.findLiveByUser(userId, now).stream()
        .filter(session -> !session.id().equals(keepSessionId))
        .forEach(session -> sessions.save(session.revoked(now)));
  }

  @Transactional
  public void revokeAll(String userId) {
    revokeOthers(userId, null);
  }

  @Transactional(readOnly = true)
  public List<Session> listLive(String userId) {
    return sessions.findLiveByUser(userId, clock.instant());
  }

  private String hash(String plain) {
    return ApiKey.hashOf(plain, properties.apiKeyPepper());
  }

  private static String token(String prefix) {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
```
Add `findById` to `SessionRepository`. `MerchantsProperties` gains `Duration authAccessTtl, Duration authRefreshTtl` with defaults in the compact constructor (PT15M, P30D). Wire `SessionRepositoryImpl` and the `SessionService` bean; add `session.persistence` to both scans.

Run: `./mvnw -q -pl gateway-merchants test -Dtest=SessionServiceIntegrationTest` → Expected: PASS 4/4.

- [ ] **Step 3: Commit**

```bash
./mvnw -q spotless:apply && git add gateway-merchants && git commit -m "feat(merchants): sessions — opaque access/refresh tokens, rotation, replay revokes"
```

---

### Task 4: One-time tokens (verify, reset, invite)

**Files:**
- Create: `gateway-merchants/src/main/java/com/gateway/merchants/usertoken/{UserToken,UserTokenService}.java`, `usertoken/persistence/{UserTokenEntity,UserTokenJpaRepository,UserTokenRepository,UserTokenRepositoryImpl}.java`
- Modify: `MerchantsConfiguration.java`
- Test: `gateway-merchants/src/test/java/com/gateway/merchants/usertoken/UserTokenServiceIntegrationTest.java`

**Interfaces:**
- Produces:
  - `enum UserToken.Kind { VERIFY_EMAIL, RESET_PASSWORD, INVITE }`; `record UserToken(String id, String userId, MerchantId merchantId, Kind kind, String tokenHash, Map<String, String> payload, Instant expiresAt, Instant usedAt, Instant createdAt)`.
  - `UserTokenService.Issued(UserToken token, Secret plain)`; `UserTokenService(UserTokenRepository, MerchantsProperties, Clock)`: `Issued issue(Kind, String userId /*nullable for INVITE*/, MerchantId, Map<String,String> payload, Duration ttl)`; `Optional<UserToken> consume(Kind, String plain)` — atomic `UPDATE ... SET used_at = now WHERE token_hash = ? AND kind = ? AND used_at IS NULL AND expires_at > now`, returns the row only when that update hit; `void invalidateOpen(Kind, String userId)` (marks unused tokens of that kind used — a resend replaces the previous link).
  - `UserTokenRepository { void insert(UserToken); Optional<UserToken> findById(String); Optional<UserToken> consume(String tokenHash, Kind kind, Instant now); void markUsedOpenOf(Kind kind, String userId, Instant now); }` — `consume` is a `@Modifying` JPQL update followed by a find, inside one transaction.
  - Payload is stored as JSONB via `@JdbcTypeCode(SqlTypes.JSON) Map<String,String>`.

- [ ] **Step 1: Failing test**

```java
  @Test
  void aTokenIsConsumedOnceAndNeverAfterExpiry() {
    Merchant store = merchants.create("Loja");
    User ana = users.register(store.id(), "Ana", new EmailAddress("ana@loja.com"), Role.OWNER, "senha-forte-1");
    UserTokenService.Issued issued = tokens.issue(UserToken.Kind.VERIFY_EMAIL, ana.id(), store.id(), Map.of(), Duration.ofHours(24));

    assertThat(tokens.consume(UserToken.Kind.RESET_PASSWORD, issued.plain().reveal())).isEmpty();
    assertThat(tokens.consume(UserToken.Kind.VERIFY_EMAIL, issued.plain().reveal())).map(UserToken::userId).contains(ana.id());
    assertThat(tokens.consume(UserToken.Kind.VERIFY_EMAIL, issued.plain().reveal())).isEmpty();

    UserTokenService.Issued expired = tokens.issue(UserToken.Kind.VERIFY_EMAIL, ana.id(), store.id(), Map.of(), Duration.ofSeconds(-1));
    assertThat(tokens.consume(UserToken.Kind.VERIFY_EMAIL, expired.plain().reveal())).isEmpty();
  }

  @Test
  void aResendInvalidatesTheOpenOnes() {
    Merchant store = merchants.create("Loja");
    User ana = users.register(store.id(), "Ana", new EmailAddress("ana@loja.com"), Role.OWNER, "senha-forte-1");
    UserTokenService.Issued first = tokens.issue(UserToken.Kind.VERIFY_EMAIL, ana.id(), store.id(), Map.of(), Duration.ofHours(24));

    tokens.invalidateOpen(UserToken.Kind.VERIFY_EMAIL, ana.id());
    UserTokenService.Issued second = tokens.issue(UserToken.Kind.VERIFY_EMAIL, ana.id(), store.id(), Map.of(), Duration.ofHours(24));

    assertThat(tokens.consume(UserToken.Kind.VERIFY_EMAIL, first.plain().reveal())).isEmpty();
    assertThat(tokens.consume(UserToken.Kind.VERIFY_EMAIL, second.plain().reveal())).isPresent();
  }

  @Test
  void anInviteCarriesItsRoleAndEmailAndNoUser() {
    Merchant store = merchants.create("Loja");
    UserTokenService.Issued invite = tokens.issue(UserToken.Kind.INVITE, null, store.id(), Map.of("email", "bia@loja.com", "role", "FINANCE"), Duration.ofDays(7));

    UserToken consumed = tokens.consume(UserToken.Kind.INVITE, invite.plain().reveal()).orElseThrow();
    assertThat(consumed.userId()).isNull();
    assertThat(consumed.payload()).containsEntry("email", "bia@loja.com").containsEntry("role", "FINANCE");
  }
```
Run → Expected: compilation FAIL.

- [ ] **Step 2: Write it**

`UserToken` record with `Kind`; `UserTokenService`:
```java
  @Transactional
  public Issued issue(UserToken.Kind kind, String userId, MerchantId merchantId, Map<String, String> payload, Duration ttl) {
    Instant now = clock.instant();
    String plain = "gt_" + randomBase64Url32();
    UserToken token = new UserToken(Ulid.next(), userId, merchantId, kind, ApiKey.hashOf(plain, properties.apiKeyPepper()), Map.copyOf(payload), now.plus(ttl), null, now);
    tokens.insert(token);
    return new Issued(token, Secret.of(plain));
  }

  /** One UPDATE decides who gets the token: two concurrent consumers cannot both win. */
  @Transactional
  public Optional<UserToken> consume(UserToken.Kind kind, String plain) {
    return tokens.consume(ApiKey.hashOf(plain, properties.apiKeyPepper()), kind, clock.instant());
  }

  @Transactional
  public void invalidateOpen(UserToken.Kind kind, String userId) {
    tokens.markUsedOpenOf(kind, userId, clock.instant());
  }
```
`UserTokenJpaRepository`:
```java
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update UserTokenEntity t set t.usedAt = :now where t.tokenHash = :hash and t.kind = :kind"
      + " and t.usedAt is null and t.expiresAt > :now")
  int consume(@Param("hash") String hash, @Param("kind") String kind, @Param("now") Instant now);

  Optional<UserTokenEntity> findByTokenHash(String tokenHash);

  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("update UserTokenEntity t set t.usedAt = :now where t.kind = :kind and t.userId = :userId and t.usedAt is null")
  int markUsedOpenOf(@Param("kind") String kind, @Param("userId") String userId, @Param("now") Instant now);
```
`UserTokenRepositoryImpl.consume` = `jpa.consume(...) == 1 ? jpa.findByTokenHash(hash).map(toDomain) : Optional.empty()`. Entity `payload`: `@JdbcTypeCode(SqlTypes.JSON) @Column(name = "payload", nullable = false) Map<String, String> payload;`. Wire scans, import, bean.

Run: `./mvnw -q -pl gateway-merchants test -Dtest=UserTokenServiceIntegrationTest` → Expected: PASS 3/3.

- [ ] **Step 3: Commit**

```bash
./mvnw -q spotless:apply && git add gateway-merchants && git commit -m "feat(merchants): one-time tokens for e-mail verification, password reset and invites"
```

---

### Task 5: Mail — port, SMTP and logging gateways, templates, outbox and the `SEND_EMAIL` job

**Files:**
- Create: `gateway-merchants/src/main/java/com/gateway/merchants/mail/{Email,MailGateway,SmtpMailGateway,LoggingMailGateway,MailTemplates,OutboundEmail,OutboundEmailService}.java`, `mail/persistence/{OutboundEmailEntity,OutboundEmailJpaRepository,OutboundEmailRepository,OutboundEmailRepositoryImpl}.java`, `gateway-merchants/.../MailProperties.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/jobs/JobType.java` (+`SEND_EMAIL`), `gateway-payments/src/test/java/com/gateway/payments/support/BillingJobOwnersStub.java` (+ stub), `MerchantsConfiguration.java`, `gateway-app/src/main/resources/application.yml`, `.env.example`
- Create: `gateway-app/src/main/java/com/gateway/app/mail/{SendEmailJob,MailJobConfiguration}.java`
- Test: `gateway-merchants/src/test/java/com/gateway/merchants/mail/{MailTemplatesTest,LoggingMailGatewayTest}.java`, `gateway-app/src/test/java/com/gateway/app/mail/SendEmailJobIntegrationTest.java`

**Interfaces:**
- Produces:
  - `record Email(String to, String subject, String text, String html)`; `interface MailGateway { void send(Email email); }`.
  - `MailProperties(String host, int port, String username, String password, String from, String panelBaseUrl)` prefix `gateway.mail`; `boolean isConfigured()` = host not blank. `panelBaseUrl` default `http://localhost:5173`.
  - `MailTemplates` (static, pt-BR): `Email verifyEmail(String to, String name, String link)`, `Email resetPassword(String to, String name, String link)`, `Email invite(String to, String storeName, String link)`; links: `panelBaseUrl + "/verify/" + token` etc. built by the caller.
  - `OutboundEmailService(OutboundEmailRepository, Clock)`: `String enqueue(Email)` (returns id), `Optional<OutboundEmail> find(String id)`, `void delete(String id)`.
  - `LoggingMailGateway(boolean revealLinks)`: logs `to` and `subject` at INFO; with `revealLinks` also the first `http…` link found in `text`.
  - `SendEmailJob(OutboundEmailService, MailGateway, JobBackoff)` in `gateway-app/mail`: `type() == SEND_EMAIL`; `run(refId)` loads the outbound e-mail by id (missing → `true`, already sent), sends, deletes, returns `true`; `afterFailure` → `backoff.retry`.
  - Who enqueues the job: the caller (Task 7) does `outbound.enqueue(email)` then `jobs.enqueue(Job.sendEmail(id, clock))` in the same transaction; `Job.sendEmail(String outboundEmailId, Clock)` static factory added to `Job`.

- [ ] **Step 1: Failing template and logging tests**

```java
// mail/MailTemplatesTest.java
  @Test
  void verifyEmailNamesThePersonAndCarriesTheLinkInBothBodies() {
    Email email = MailTemplates.verifyEmail("ana@loja.com", "Ana", "https://painel/verify/gt_abc");

    assertThat(email.to()).isEqualTo("ana@loja.com");
    assertThat(email.subject()).isEqualTo("Confirme seu e-mail");
    assertThat(email.text()).contains("Ana").contains("https://painel/verify/gt_abc");
    assertThat(email.html()).contains("href=\"https://painel/verify/gt_abc\"");
  }

  @Test
  void inviteNamesTheStore() {
    Email email = MailTemplates.invite("bia@loja.com", "Loja da Ana", "https://painel/invite/gt_x");

    assertThat(email.subject()).isEqualTo("Você foi convidado para Loja da Ana");
    assertThat(email.text()).contains("Loja da Ana").contains("/invite/gt_x");
  }
```
```java
// mail/LoggingMailGatewayTest.java — uses a Logback ListAppender on the gateway's logger
  @Test
  void revealsTheLinkOnlyWhenAskedTo() {
    Email email = MailTemplates.resetPassword("ana@loja.com", "Ana", "https://painel/reset/gt_secret");

    new LoggingMailGateway(false).send(email);
    assertThat(lastMessage()).contains("ana@loja.com").doesNotContain("gt_secret");

    new LoggingMailGateway(true).send(email);
    assertThat(lastMessage()).contains("https://painel/reset/gt_secret");
  }
```
Run: `./mvnw -q -pl gateway-merchants test -Dtest='MailTemplatesTest,LoggingMailGatewayTest'` → Expected: compilation FAIL.

- [ ] **Step 2: Write the mail concept**

```java
// mail/MailTemplates.java (excerpt; the three methods share `wrap`)
public final class MailTemplates {
  private MailTemplates() {}

  public static Email verifyEmail(String to, String name, String link) {
    return new Email(to, "Confirme seu e-mail",
        "Olá, " + name + ".\n\nConfirme seu e-mail para ativar o ambiente de produção:\n" + link
            + "\n\nO link vale por 24 horas.",
        wrap("Olá, " + escape(name) + ".", "Confirme seu e-mail para ativar o ambiente de produção.", link, "Confirmar e-mail", "O link vale por 24 horas."));
  }

  public static Email resetPassword(String to, String name, String link) { /* subject "Redefinir senha", 1 hora */ }

  public static Email invite(String to, String storeName, String link) { /* subject "Você foi convidado para " + storeName, 7 dias */ }

  private static String wrap(String greeting, String lead, String link, String button, String footer) {
    return "<!doctype html><html lang=\"pt-BR\"><body style=\"font-family:sans-serif;color:#1a1a1a\">"
        + "<p>" + greeting + "</p><p>" + escape(lead) + "</p>"
        + "<p><a href=\"" + link + "\" style=\"background:#1d4ed8;color:#fff;padding:10px 16px;border-radius:8px;text-decoration:none\">" + escape(button) + "</a></p>"
        + "<p style=\"color:#666;font-size:12px\">" + escape(footer) + "</p></body></html>";
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
  }
}
```
`SmtpMailGateway(JavaMailSender sender, String from)` builds a `MimeMessage` via `MimeMessageHelper(message, true, "UTF-8")` with `setText(text, html)`. `LoggingMailGateway` as described. `MailProperties` record with compact-constructor defaults (`port` 587, `panelBaseUrl`). In `MerchantsConfiguration`:
```java
  @Bean
  public MailGateway mailGateway(MailProperties mail, Environment env) {
    if (!mail.isConfigured()) {
      boolean revealLinks = env.acceptsProfiles(Profiles.of("local", "test"));
      return new LoggingMailGateway(revealLinks);
    }
    JavaMailSenderImpl sender = new JavaMailSenderImpl();
    sender.setHost(mail.host()); sender.setPort(mail.port());
    sender.setUsername(mail.username()); sender.setPassword(mail.password());
    sender.getJavaMailProperties().put("mail.smtp.starttls.enable", "true");
    sender.getJavaMailProperties().put("mail.smtp.auth", String.valueOf(mail.username() != null && !mail.username().isBlank()));
    return new SmtpMailGateway(sender, mail.from());
  }
```
plus a `WARN` logged once at startup by `MerchantsConfiguration` when the logging gateway is chosen ("e-mail is off: GATEWAY_MAIL_HOST is empty"). `OutboundEmail` record + persistence trio + `OutboundEmailService`. `application.yml`:
```yaml
  mail:
    host: ${GATEWAY_MAIL_HOST:}        # empty = e-mails are logged, not sent
    port: ${GATEWAY_MAIL_PORT:587}
    username: ${GATEWAY_MAIL_USERNAME:}
    password: ${GATEWAY_MAIL_PASSWORD:}
    from: ${GATEWAY_MAIL_FROM:no-reply@localhost}
    panel-base-url: ${GATEWAY_PANEL_BASE_URL:http://localhost:5173}
  auth:
    rate-limit-per-minute: 10
    access-ttl: PT15M
    refresh-ttl: P30D
```
(`auth.*` bind to `MerchantsProperties` as `gateway.auth-access-ttl`? No — keep them under `gateway.auth` in a new `AuthProperties(int rateLimitPerMinute, Duration accessTtl, Duration refreshTtl)` in `gateway-app/security`, and have `MerchantsProperties` read `gateway.auth.access-ttl`/`refresh-ttl` via nested record `Auth(Duration accessTtl, Duration refreshTtl)`. Ruling for the executor: one source — put `accessTtl`/`refreshTtl` in `MerchantsProperties` as a nested `Auth` record bound from `gateway.auth`, and the rate limit in `CheckoutProperties` as `authRateLimitPerMinute` bound from `gateway.auth.rate-limit-per-minute`.)

Run: `./mvnw -q -pl gateway-merchants test -Dtest='MailTemplatesTest,LoggingMailGatewayTest'` → Expected: PASS 3/3.

- [ ] **Step 3: `JobType.SEND_EMAIL`, the stub, `Job.sendEmail`, the handler, its test**

`JobType`: add `/** The app's: one outbound e-mail by id; the handler lives in gateway-app. */ SEND_EMAIL`. `BillingJobOwnersStub`: add a `sendEmailStub()` bean returning `true`. `Job.sendEmail(String outboundEmailId, Clock clock)` = pending now. Run `./mvnw -q -pl gateway-payments,gateway-billing test` → Expected: PASS (the registry sees every type).

```java
// gateway-app/src/test/java/com/gateway/app/mail/SendEmailJobIntegrationTest.java
@SpringBootTest(webEnvironment = RANDOM_PORT, properties = {"gateway.mail.host=127.0.0.1", "gateway.mail.port=3025", "gateway.mail.from=no-reply@test"})
@ActiveProfiles("test") @Testcontainers
class SendEmailJobIntegrationTest {
  @Container @ServiceConnection static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");
  @RegisterExtension static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP);

  @Autowired OutboundEmailService outbound;
  @Autowired JobRepository jobs;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;

  @Test
  void sendsTheQueuedEmailAndDeletesTheRow() {
    String id = outbound.enqueue(MailTemplates.verifyEmail("ana@loja.com", "Ana", "http://painel/verify/gt_abc"));
    jobs.enqueue(Job.sendEmail(id, clock));

    Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      assertThat(MAIL.getReceivedMessages()).hasSize(1);
      assertThat(GreenMailUtil.getBody(MAIL.getReceivedMessages()[0])).contains("gt_abc");
    });
    assertThat(jdbc.queryForObject("SELECT count(*) FROM merchants.outbound_emails WHERE id = ?", Integer.class, id)).isZero();
  }
}
```
Run → Expected: FAIL (no handler for `SEND_EMAIL` in the app context → startup error). Then:
```java
// gateway-app/src/main/java/com/gateway/app/mail/SendEmailJob.java
public class SendEmailJob implements JobHandler {
  private final OutboundEmailService outbound;
  private final MailGateway mail;
  private final JobBackoff backoff;
  // constructor…
  @Override public JobType type() { return JobType.SEND_EMAIL; }

  /** A row that is gone was sent by an earlier run whose ack was lost: done, not an error. */
  @Override
  public boolean run(String refId, Instant now) {
    Optional<OutboundEmail> email = outbound.find(refId);
    if (email.isEmpty()) {
      return true;
    }
    mail.send(email.get().asEmail());
    outbound.delete(refId);
    return true;
  }

  @Override public Job afterFailure(Job job, Instant now, String error) { return backoff.retry(job, now, error); }
}
```
`MailJobConfiguration` (`@Configuration`) exposes the bean. Run the test → Expected: PASS.

- [ ] **Step 4: Commit**

```bash
./mvnw -q spotless:apply && git add -A gateway-merchants gateway-payments gateway-app .env.example && git commit -m "feat(mail): a MailGateway with SMTP and logging, pt-BR templates, and SEND_EMAIL on the jobs table"
```

---

### Task 6: `Actor` in `MerchantContext`, `UserSessionFilter`, `RoleRoutes`, CORS, error codes

**Files:**
- Modify: `gateway-app/src/main/java/com/gateway/app/security/{MerchantContext,ApiKeyAuthFilter,RateLimitFilter,ProtectedRoutes,CheckoutRateLimitFilter,CorsFilterConfiguration}.java`, `api/installment/InstallmentSettingsController.java`, `api/support/ErrorHandler.java`, `api/checkout/CheckoutProperties.java` (+`authRateLimitPerMinute`)
- Create: `gateway-app/src/main/java/com/gateway/app/security/{Actor,RoleRoutes,UserSessionFilter}.java`
- Test: `gateway-app/src/test/java/com/gateway/app/security/{RoleRoutesTest,UserSessionFilterIntegrationTest}.java`

**Interfaces:**
- Produces:
  - `sealed interface Actor permits Actor.ApiKey, Actor.User { String id(); }`; `record ApiKey(String apiKeyId)`, `record User(String userId, String sessionId, Role role, boolean emailVerified)`.
  - `MerchantContext.Current(MerchantId merchantId, ApiKeyEnvironment environment, Actor actor)`.
  - `RoleRoutes.required(String method, String normalizedPath) → Optional<Role>` (empty = not a merchant route) and `RoleRoutes.userOnly(path)` for `/v1/me/**`, `/v1/merchant/users/**`, `/v1/invites/**`.
  - `ProtectedRoutes.isAuth(path)` = `/v1/auth/**` (no key, no session; `requiresApiKey` excludes it). `/v1/auth/email/resend` is the one exception: it needs a session — handled as a user-only route under `requiresApiKey` (path `/v1/me/email/resend` instead; **the plan moves it there**, and the spec's `POST /v1/auth/email/resend` becomes `POST /v1/me/email/resend`).
  - `UserSessionFilter` `@Order(19)`: on `requiresApiKey` routes with `Bearer gs_…`: authenticate via `SessionService` + `UserService.get` + `MerchantService.get` (ACTIVE), read `X-Environment`, set context, enforce `userOnly`/`RoleRoutes`, then chain. Failures: `401 SESSION_EXPIRED`, `403 EMAIL_NOT_VERIFIED`, `403 FORBIDDEN_FOR_ROLE` (+`required_role`), `403 USER_SESSION_REQUIRED` when an API key hits a user-only route (checked in `ApiKeyAuthFilter`).
  - `ApiKeyAuthFilter`: returns early when the context attribute is already set.
  - `ErrorHandler.STATUS_BY_CODE` += `EMAIL_TAKEN→409`, `LAST_OWNER→409`, `TOKEN_EXPIRED→410`, `INVALID_CREDENTIALS→401`, `SESSION_EXPIRED→401`, `RESEND_TOO_SOON→429`.
  - CORS: allowed headers += `X-Environment`; a second `CorsConfiguration` for `/v1/auth/**` with `allowCredentials(true)`.

- [ ] **Step 1: Failing `RoleRoutesTest`**

```java
class RoleRoutesTest {
  @Test
  void readsAreForEveryoneWritesNeedFinanceAndSettingsNeedOwner() {
    assertThat(RoleRoutes.required("GET", "/v1/orders")).contains(Role.READONLY);
    assertThat(RoleRoutes.required("POST", "/v1/orders")).contains(Role.FINANCE);
    assertThat(RoleRoutes.required("POST", "/v1/payments/01X/refunds")).contains(Role.FINANCE);
    assertThat(RoleRoutes.required("POST", "/v1/webhooks/deliveries/01X/redeliver")).contains(Role.FINANCE);
    assertThat(RoleRoutes.required("POST", "/v1/webhooks/endpoints")).contains(Role.OWNER);
    assertThat(RoleRoutes.required("PUT", "/v1/installment-settings")).contains(Role.OWNER);
    assertThat(RoleRoutes.required("DELETE", "/v1/customers/01X")).contains(Role.OWNER);
    assertThat(RoleRoutes.required("PATCH", "/v1/customers/01X")).contains(Role.FINANCE);
    assertThat(RoleRoutes.required("POST", "/v1/invites")).contains(Role.OWNER);
    assertThat(RoleRoutes.required("GET", "/v1/me")).contains(Role.READONLY);
    assertThat(RoleRoutes.required("POST", "/v1/checkout/abc/payments")).isEmpty();
  }

  @Test
  void userOnlyRoutesAreTheAccountOnes() {
    assertThat(RoleRoutes.userOnly("/v1/me")).isTrue();
    assertThat(RoleRoutes.userOnly("/v1/merchant/users/01X")).isTrue();
    assertThat(RoleRoutes.userOnly("/v1/invites")).isTrue();
    assertThat(RoleRoutes.userOnly("/v1/orders")).isFalse();
  }
}
```
Run → Expected: compilation FAIL.

- [ ] **Step 2: Write `Actor`, `RoleRoutes`, refactor `MerchantContext`**

```java
// security/RoleRoutes.java
final class RoleRoutes {
  private RoleRoutes() {}

  private static final List<String> OWNER_PREFIXES = List.of(
      "/v1/webhooks/endpoints", "/v1/merchant", "/v1/providers", "/v1/installment-settings", "/v1/invites");

  static Optional<Role> required(String method, String path) {
    if (!path.startsWith("/v1/") || ProtectedRoutes.isAdmin(path) || ProtectedRoutes.isCheckout(path) || ProtectedRoutes.isAuth(path)) {
      return Optional.empty();
    }
    if (method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) {
      return Optional.of(Role.READONLY);
    }
    if (path.startsWith("/v1/me")) {
      return Optional.of(Role.READONLY);
    }
    if (method.equals("DELETE") && path.startsWith("/v1/customers/")) {
      return Optional.of(Role.OWNER);
    }
    if (OWNER_PREFIXES.stream().anyMatch(path::startsWith)) {
      return Optional.of(Role.OWNER);
    }

    return Optional.of(Role.FINANCE);
  }

  static boolean userOnly(String path) {
    return path.startsWith("/v1/me") || path.startsWith("/v1/merchant/users") || path.startsWith("/v1/invites");
  }
}
```
`MerchantContext.Current` becomes `(MerchantId merchantId, ApiKeyEnvironment environment, Actor actor)`. Update the three call sites: `ApiKeyAuthFilter` builds `new Actor.ApiKey(apiKeyId)`; `RateLimitFilter` keys on `actor().id()`; `InstallmentSettingsController` passes `caller.actor().id()`.

Run: `./mvnw -q -pl gateway-app test -Dtest=RoleRoutesTest` → PASS; `./mvnw -q -pl gateway-app -am compile` → OK.

- [ ] **Step 3: Failing filter integration test**

```java
// security/UserSessionFilterIntegrationTest.java (RANDOM_PORT, test profile, Testcontainers; helpers like MerchantPanelApiIntegrationTest)
  @Autowired MerchantService merchants; @Autowired UserService users; @Autowired SessionService sessions;

  record Logged(String access, User user, Merchant store) {}

  private Logged user(Role role, boolean verified) {
    Merchant store = merchants.create("Loja");
    User user = users.register(store.id(), "Ana", new EmailAddress(role + "@loja.com"), role, "senha-forte-1");
    if (verified) { user = users.markEmailVerified(user.id()); }
    return new Logged(sessions.open(user.id(), null, null).accessToken().reveal(), user, store);
  }

  @Test
  void aSessionListsOrdersInTestByDefaultAndLiveOnlyWhenVerified() {
    Logged ana = user(Role.OWNER, false);
    assertThat(status(ana.access(), "GET", "/v1/orders", null)).isEqualTo(200);
    assertThat(status(ana.access(), "GET", "/v1/orders", "LIVE")).isEqualTo(403); // EMAIL_NOT_VERIFIED
    Logged verified = user(Role.OWNER, true);
    assertThat(status(verified.access(), "GET", "/v1/orders", "LIVE")).isEqualTo(200);
  }

  @Test
  void rolesGateWrites() {
    Logged reader = user(Role.READONLY, true);
    assertThat(status(reader.access(), "GET", "/v1/orders", null)).isEqualTo(200);
    assertThat(statusBody(reader.access(), "POST", "/v1/plans")).contains("FORBIDDEN_FOR_ROLE").contains("\"required_role\":\"FINANCE\"");
    Logged finance = user(Role.FINANCE, true);
    assertThat(statusBody(finance.access(), "POST", "/v1/webhooks/endpoints")).contains("FORBIDDEN_FOR_ROLE").contains("OWNER");
  }

  @Test
  void anExpiredOrGarbageSessionIs401() {
    assertThat(status("gs_garbage", "GET", "/v1/orders", null)).isEqualTo(401);
  }

  @Test
  void anApiKeyIsUntouchedByRoles() {
    Keys keys = newMerchant();   // admin helper as in MerchantPanelApiIntegrationTest
    assertThat(status(keys.test(), "POST", "/v1/webhooks/endpoints", null)).isNotEqualTo(403);
    assertThat(status(keys.test(), "GET", "/v1/me", null)).isEqualTo(403); // USER_SESSION_REQUIRED
  }
```
Run → Expected: FAIL (sessions are rejected by `ApiKeyAuthFilter` as invalid keys → 401 everywhere; `/v1/me` 404).

- [ ] **Step 4: Write `UserSessionFilter`, touch the other filters, CORS, errors**

```java
@Component
@Order(19)
public class UserSessionFilter extends OncePerRequestFilter {
  private final SessionService sessions; private final UserService users; private final MerchantService merchants;
  // constructor…

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String auth = request.getHeader("Authorization");
    return !ProtectedRoutes.requiresApiKey(RequestPath.of(request).normalized())
        || auth == null || !auth.startsWith("Bearer gs_");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    String path = RequestPath.of(request).normalized();
    Optional<Session> session = sessions.authenticate(request.getHeader("Authorization").substring(7).trim());
    if (session.isEmpty()) {
      Problems.write(response, 401, "SESSION_EXPIRED", "sign in again");
      return;
    }

    User user = users.get(session.get().userId());
    Merchant merchant = merchants.get(user.merchantId());
    if (!merchant.isActive()) {
      Problems.write(response, 401, "SESSION_EXPIRED", "merchant is suspended");
      return;
    }

    ApiKeyEnvironment environment = environmentOf(request.getHeader("X-Environment"));
    if (environment == ApiKeyEnvironment.LIVE && !user.isEmailVerified()) {
      Problems.write(response, 403, "EMAIL_NOT_VERIFIED", "confirm your e-mail to use LIVE");
      return;
    }

    Optional<Role> required = RoleRoutes.required(request.getMethod(), path);
    if (required.isPresent() && !user.role().atLeast(required.get())) {
      Problems.writeWithExtra(response, 403, "FORBIDDEN_FOR_ROLE", "this action needs the " + required.get() + " role", "required_role", required.get().name());
      return;
    }

    MerchantContext.set(request, new MerchantContext.Current(
        merchant.id(), environment, new Actor.User(user.id(), session.get().id(), user.role(), user.isEmailVerified())));
    chain.doFilter(request, response);
  }

  // Default TEST; anything that is not exactly LIVE stays TEST, never a 400: a wrong header must not
  // widen what the caller reaches.
  private static ApiKeyEnvironment environmentOf(String header) {
    return "LIVE".equals(header) ? ApiKeyEnvironment.LIVE : ApiKeyEnvironment.TEST;
  }
}
```
`Problems.writeWithExtra(response, status, code, detail, extraName, extraValue)` — a sibling of `write` adding one string member. `ApiKeyAuthFilter.doFilterInternal`: first line `if (request.getAttribute(MerchantContext.ATTRIBUTE) != null) { chain.doFilter(request, response); return; }`; and after authenticating, `if (RoleRoutes.userOnly(path)) { Problems.write(response, 403, "USER_SESSION_REQUIRED", "this route is for a signed-in user, not an api key"); return; }`. `ProtectedRoutes.isAuth` + exclusion in `requiresApiKey`. `CheckoutRateLimitFilter`: `shouldNotFilter` = not checkout **and** not auth; bucket key `scope + "|" + ip`, capacity `properties.authRateLimitPerMinute()` for auth. `CorsFilterConfiguration`: allowed headers add `X-Environment`; register a second config for `/v1/auth/**` copied from the first with `setAllowCredentials(true)` (an exact-origin list is what makes credentials safe). `ErrorHandler` map additions as in Interfaces.

Run: `./mvnw -q -pl gateway-app test -Dtest='RoleRoutesTest,UserSessionFilterIntegrationTest'` → Expected: PASS (the `/v1/me` assertion needs `MeController` from Task 8 to be 403-not-404: the filter answers before routing, so 403 holds already).

- [ ] **Step 5: Commit**

```bash
./mvnw -q spotless:apply && git add gateway-app && git commit -m "feat(security): user sessions beside api keys — Actor in MerchantContext, RoleRoutes, X-Environment"
```

---

### Task 7: `/v1/auth/*` — signup, login, refresh, logout, forgot/reset, verify, invite accept

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/api/auth/{AuthController,SignupService,AuthMailService,SessionCookies}.java`, `api/auth/dto/{SignupRequest,LoginRequest,SessionResponse,ForgotPasswordRequest,ResetPasswordRequest,VerifyEmailRequest,AcceptInviteRequest}.java`
- Test: `gateway-app/src/test/java/com/gateway/app/AuthApiIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 2–6; `ApiKeyService.issue`, `MerchantService.create`, `JobRepository.enqueue`, `Job.sendEmail`.
- Produces:
  - `SessionCookies`: `ResponseCookie refresh(Secret token, Duration maxAge)` (`gw_refresh`, HttpOnly, Secure, SameSite=None, Path=/v1/auth) and `ResponseCookie cleared()`; `Optional<String> read(HttpServletRequest)`.
  - `AuthMailService(UserTokenService, OutboundEmailService, JobRepository, MailProperties, Clock)`: `void sendVerification(User)`, `void sendPasswordReset(User)`, `void sendInvite(Merchant, EmailAddress, Role)` — each issues the token, renders via `MailTemplates`, enqueues the outbound e-mail and the job, all `@Transactional`.
  - `SignupService(MerchantService, ApiKeyService, UserService, AuthMailService)`: `User signup(String storeName, String name, EmailAddress, String password)` in one transaction.
  - `SessionResponse(String accessToken, long expiresIn)`.
  - Routes exactly as spec §5 (with `email/resend` moved to `/v1/me/email/resend`, Task 8).

- [ ] **Step 1: Failing integration test (GreenMail on 3025, as Task 5)**

```java
  @Test
  void signupSendsVerificationAndLiveOpensAfterIt() {
    EntityExchangeResult<Map> signed = post("/v1/auth/signup", Map.of("store_name", "Loja", "name", "Ana", "email", "ana@loja.com", "password", "senha-forte-1"));
    assertThat(signed.getStatus().value()).isEqualTo(201);
    String access = (String) signed.getResponseBody().get("access_token");
    String cookie = signed.getResponseHeaders().getFirst("Set-Cookie");
    assertThat(cookie).startsWith("gw_refresh=gr_").contains("HttpOnly").contains("SameSite=None").contains("Path=/v1/auth");

    assertThat(status(access, "GET", "/v1/orders", "LIVE")).isEqualTo(403);
    String link = Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> firstLink(MAIL, "/verify/"), Objects::nonNull);
    assertThat(post("/v1/auth/email/verify", Map.of("token", link.substring(link.lastIndexOf('/') + 1))).getStatus().value()).isEqualTo(204);
    assertThat(status(access, "GET", "/v1/orders", "LIVE")).isEqualTo(200);
    assertThat(post("/v1/auth/email/verify", Map.of("token", link.substring(link.lastIndexOf('/') + 1))).getStatus().value()).isEqualTo(410);
    assertThat(post("/v1/auth/signup", Map.of("store_name", "Outra", "name", "Ana", "email", "ANA@loja.com", "password", "senha-forte-1")).getStatus().value()).isEqualTo(409);
  }

  @Test
  void loginRefreshAndLogout() {
    signup("ana@loja.com");
    EntityExchangeResult<Map> wrong = post("/v1/auth/login", Map.of("email", "ana@loja.com", "password", "errada-errada"));
    EntityExchangeResult<Map> unknown = post("/v1/auth/login", Map.of("email", "x@loja.com", "password", "senha-forte-1"));
    assertThat(wrong.getStatus().value()).isEqualTo(401);
    assertThat(wrong.getResponseBody()).isEqualTo(unknown.getResponseBody());

    EntityExchangeResult<Map> ok = post("/v1/auth/login", Map.of("email", "ana@loja.com", "password", "senha-forte-1"));
    String refreshCookie = cookieValue(ok, "gw_refresh");
    EntityExchangeResult<Map> refreshed = postWithCookie("/v1/auth/refresh", "gw_refresh=" + refreshCookie);
    assertThat(refreshed.getStatus().value()).isEqualTo(200);
    assertThat(cookieValue(refreshed, "gw_refresh")).isNotEqualTo(refreshCookie);
    // replay of the first cookie kills the session
    assertThat(postWithCookie("/v1/auth/refresh", "gw_refresh=" + refreshCookie).getStatus().value()).isEqualTo(401);
    assertThat(status((String) refreshed.getResponseBody().get("access_token"), "GET", "/v1/orders", null)).isEqualTo(401);

    EntityExchangeResult<Map> again = post("/v1/auth/login", Map.of("email", "ana@loja.com", "password", "senha-forte-1"));
    assertThat(postWithCookieAndBearer("/v1/auth/logout", "gw_refresh=" + cookieValue(again, "gw_refresh"), (String) again.getResponseBody().get("access_token")).getStatus().value()).isEqualTo(204);
    assertThat(status((String) again.getResponseBody().get("access_token"), "GET", "/v1/orders", null)).isEqualTo(401);
  }

  @Test
  void forgotAndResetAlwaysAnswer202AndResetRevokesSessions() {
    String access = signup("ana@loja.com");
    assertThat(post("/v1/auth/password/forgot", Map.of("email", "nobody@loja.com")).getStatus().value()).isEqualTo(202);
    assertThat(post("/v1/auth/password/forgot", Map.of("email", "ana@loja.com")).getStatus().value()).isEqualTo(202);
    String link = Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> firstLink(MAIL, "/reset/"), Objects::nonNull);

    assertThat(post("/v1/auth/password/reset", Map.of("token", tokenOf(link), "password", "nova-senha-11")).getStatus().value()).isEqualTo(204);
    assertThat(status(access, "GET", "/v1/orders", null)).isEqualTo(401);
    assertThat(post("/v1/auth/login", Map.of("email", "ana@loja.com", "password", "nova-senha-11")).getStatus().value()).isEqualTo(200);
  }

  @Test
  void authRoutesAreRateLimitedPerIp() {
    for (int i = 0; i < 10; i++) { post("/v1/auth/login", Map.of("email", "a@b.co", "password", "xxxxxxxxxx")); }
    assertThat(post("/v1/auth/login", Map.of("email", "a@b.co", "password", "xxxxxxxxxx")).getStatus().value()).isEqualTo(429);
  }
```
(`gateway.auth.rate-limit-per-minute=10` in the test properties for the last test; the other tests of this class stay under 10 calls each, or set the property to 1000 on a second test class.) Run → Expected: FAIL 404 on `/v1/auth/*`.

- [ ] **Step 2: Write the controller and services**

```java
@RestController
@RequestMapping("/v1/auth")
public class AuthController {
  // SignupService signup, UserService users, SessionService sessions, UserTokenService tokens, AuthMailService mail, Clock clock

  @PostMapping("/signup")
  public ResponseEntity<SessionResponse> signup(@RequestBody SignupRequest request, HttpServletRequest http) {
    request.validate();
    User user = signup.signup(request.storeName(), request.name(), new EmailAddress(request.email()), request.password());
    return opened(sessions.open(user.id(), ClientIp.of(http).value(), http.getHeader("User-Agent")), HttpStatus.CREATED);
  }

  @PostMapping("/login")
  public ResponseEntity<SessionResponse> login(@RequestBody LoginRequest request, HttpServletRequest http) {
    request.validate();
    User user = users.authenticate(new EmailAddress(request.email()), request.password())
        .orElseThrow(() -> new DomainException("INVALID_CREDENTIALS", "e-mail or password is wrong"));
    return opened(sessions.open(user.id(), ClientIp.of(http).value(), http.getHeader("User-Agent")), HttpStatus.OK);
  }

  @PostMapping("/refresh")
  public ResponseEntity<SessionResponse> refresh(HttpServletRequest http) {
    String presented = SessionCookies.read(http).orElseThrow(() -> new DomainException("SESSION_EXPIRED", "sign in again"));
    return opened(sessions.refresh(presented).orElseThrow(() -> new DomainException("SESSION_EXPIRED", "sign in again")), HttpStatus.OK);
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(HttpServletRequest http) {
    SessionCookies.read(http).flatMap(sessions::findByRefresh).ifPresent(session -> sessions.revoke(session.id()));
    return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, SessionCookies.cleared().toString()).build();
  }

  @PostMapping("/password/forgot") @ResponseStatus(HttpStatus.ACCEPTED)
  public void forgot(@RequestBody ForgotPasswordRequest request) {
    // Always 202: the answer must not say whether the e-mail exists.
    users.findActiveByEmail(new EmailAddress(request.email())).ifPresent(mail::sendPasswordReset);
  }

  @PostMapping("/password/reset") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void reset(@RequestBody ResetPasswordRequest request) {
    UserToken token = tokens.consume(UserToken.Kind.RESET_PASSWORD, request.token()).orElseThrow(() -> new DomainException("TOKEN_EXPIRED", "this link is no longer valid"));
    users.resetPassword(token.userId(), request.password());
    sessions.revokeAll(token.userId());
  }

  @PostMapping("/email/verify") @ResponseStatus(HttpStatus.NO_CONTENT)
  public void verify(@RequestBody VerifyEmailRequest request) {
    UserToken token = tokens.consume(UserToken.Kind.VERIFY_EMAIL, request.token()).orElseThrow(() -> new DomainException("TOKEN_EXPIRED", "this link is no longer valid"));
    users.markEmailVerified(token.userId());
  }

  @PostMapping("/invite/accept")
  public ResponseEntity<SessionResponse> acceptInvite(@RequestBody AcceptInviteRequest request, HttpServletRequest http) {
    request.validate();
    UserToken token = tokens.consume(UserToken.Kind.INVITE, request.token()).orElseThrow(() -> new DomainException("TOKEN_EXPIRED", "this invite is no longer valid"));
    User user = users.register(token.merchantId(), request.name(), new EmailAddress(token.payload().get("email")), Role.valueOf(token.payload().get("role")), request.password());
    users.markEmailVerified(user.id());   // the invite reached this inbox: that is the verification
    return opened(sessions.open(user.id(), ClientIp.of(http).value(), http.getHeader("User-Agent")), HttpStatus.CREATED);
  }

  private static ResponseEntity<SessionResponse> opened(SessionService.Issued issued, HttpStatus status) {
    return ResponseEntity.status(status)
        .header(HttpHeaders.SET_COOKIE, SessionCookies.refresh(issued.refreshToken(), Duration.ofDays(30)).toString())
        .body(new SessionResponse(issued.accessToken().reveal(), issued.accessTtl().toSeconds()));
  }
}
```
`UserService` gains `Optional<User> findActiveByEmail(EmailAddress)`; `SessionService` gains `Optional<Session> findByRefresh(String plain)`. `SignupService.signup` (`@Transactional`): `merchants.create(storeName)` → `apiKeys.issue(id, TEST)` → `users.register(…, OWNER, …)` → `mail.sendVerification(user)`. `AuthMailService.sendVerification`: `tokens.invalidateOpen(VERIFY_EMAIL, user.id())`, issue 24 h, `outbound.enqueue(MailTemplates.verifyEmail(user.email().value(), user.name(), base + "/verify/" + plain))`, `jobs.enqueue(Job.sendEmail(id, clock))`. DTO records with `validate()` throwing `IllegalArgumentException` on missing fields (→ 400, like the rest of the API). `SessionResponse` is serialized snake_case by the app's Jackson config (`access_token`, `expires_in`).

Run: `./mvnw -q -pl gateway-app test -Dtest=AuthApiIntegrationTest` → Expected: PASS 4/4.

- [ ] **Step 3: Commit**

```bash
./mvnw -q spotless:apply && git add gateway-app gateway-merchants && git commit -m "feat(auth): signup, login, refresh, logout, forgot/reset, e-mail verification, invite accept"
```

---

### Task 8: `/v1/me/*`, `/v1/merchant/users`, `/v1/invites`

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/api/me/{MeController}.java` + `dto/{MeResponse,RenameRequest,ChangePasswordRequest,SessionSummaryResponse}.java`; `api/team/{TeamController}.java` + `dto/{TeamMemberResponse,InviteRequest,ChangeRoleRequest}.java`
- Modify: `AuthMailService` (+`sendInvite`), `UserTokenRepository` (+`findOpenInvites(MerchantId)` for the pending list)
- Test: `gateway-app/src/test/java/com/gateway/app/TeamApiIntegrationTest.java`

**Interfaces:**
- `GET /v1/me → {user:{id,name,email,role,email_verified}, merchant:{id,name}, onboarding:{email_verified, live_enabled}}` (`live_enabled` = email verified; provider check is B5); `PATCH /v1/me {name}`; `POST /v1/me/password {current, new}` → 204, revokes other sessions; `POST /v1/me/email/resend` → 202, `429 RESEND_TOO_SOON` inside 5 min (last open VERIFY token's `created_at`); `GET /v1/me/sessions → [{id, ip, user_agent, created_at, last_used_at, current}]`; `DELETE /v1/me/sessions/others` → 204.
- `GET /v1/merchant/users → {users:[{id,name,email,role,last_login_at}], invites:[{email, role, expires_at}]}`; `POST /v1/invites {email, role}` → 202 (`409 EMAIL_TAKEN` if already a user); `PATCH /v1/merchant/users/{id} {role}`; `DELETE /v1/merchant/users/{id}` → 204 (revokes sessions). Editing oneself through team routes → `409 USE_ME_ROUTES`... **no**: keep the code list as in Global Constraints — refuse with `400 INVALID_REQUEST "use /v1/me for your own account"`.
- The current `Actor.User` gives `userId`, `sessionId` and `role`; controllers read `MerchantContext.current().actor()` and cast through `instanceof Actor.User`.

- [ ] **Step 1: Failing test**

```java
  @Test
  void theLastOwnerStays() {
    Logged ana = signupVerified("ana@loja.com");
    assertThat(patch(ana.access(), "/v1/merchant/users/" + ana.userId(), Map.of("role", "FINANCE")).getStatus().value()).isEqualTo(400); // own account
    Logged bia = invite(ana, "bia@loja.com", "FINANCE");
    assertThat(patch(bia.access(), "/v1/merchant/users/" + ana.userId(), Map.of("role", "FINANCE")).getStatus().value()).isEqualTo(403); // FINANCE cannot
    // promote bia, then ana can step down; before that, LAST_OWNER
    assertThat(delete(ana.access(), "/v1/merchant/users/" + bia.userId()).getStatus().value()).isEqualTo(204);
    Logged cris = invite(ana, "cris@loja.com", "OWNER");
    assertThat(patchBody(cris.access(), "/v1/merchant/users/" + ana.userId(), Map.of("role", "READONLY"))).contains("200");
    assertThat(deleteBody(ana.access(), "/v1/merchant/users/" + cris.userId())).contains("LAST_OWNER");
  }

  @Test
  void inviteGoesByEmailAndEntersWithTheRole() {
    Logged ana = signupVerified("ana@loja.com");
    assertThat(post(ana.access(), "/v1/invites", Map.of("email", "bia@loja.com", "role", "FINANCE")).getStatus().value()).isEqualTo(202);
    String link = Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> firstLink(MAIL, "/invite/"), Objects::nonNull);
    EntityExchangeResult<Map> accepted = post("/v1/auth/invite/accept", Map.of("token", tokenOf(link), "name", "Bia", "password", "senha-forte-2"));
    assertThat(accepted.getStatus().value()).isEqualTo(201);
    Map<String, Object> me = get((String) accepted.getResponseBody().get("access_token"), "/v1/me");
    assertThat(((Map<String, Object>) me.get("user"))).containsEntry("role", "FINANCE").containsEntry("email_verified", true);
    assertThat(post("/v1/auth/invite/accept", Map.of("token", tokenOf(link), "name", "Bia", "password", "senha-forte-2")).getStatus().value()).isEqualTo(410);
    assertThat(post(ana.access(), "/v1/invites", Map.of("email", "bia@loja.com", "role", "READONLY")).getStatus().value()).isEqualTo(409);
  }

  @Test
  void meSessionsAndPasswordChange() {
    Logged ana = signupVerified("ana@loja.com");
    EntityExchangeResult<Map> phone = post("/v1/auth/login", Map.of("email", "ana@loja.com", "password", "senha-forte-1"));
    assertThat((List<?>) getList(ana.access(), "/v1/me/sessions")).hasSize(2);
    assertThat(delete(ana.access(), "/v1/me/sessions/others").getStatus().value()).isEqualTo(204);
    assertThat(status((String) phone.getResponseBody().get("access_token"), "GET", "/v1/orders", null)).isEqualTo(401);
    assertThat(post(ana.access(), "/v1/me/password", Map.of("current", "errada-errada", "new", "nova-senha-11")).getStatus().value()).isEqualTo(401);
    assertThat(post(ana.access(), "/v1/me/password", Map.of("current", "senha-forte-1", "new", "nova-senha-11")).getStatus().value()).isEqualTo(204);
    assertThat(post(ana.access(), "/v1/me/email/resend", Map.of()).getStatus().value()).isEqualTo(429); // just verified → an open token was issued < 5 min ago? no: verified users get 409 ALREADY_VERIFIED
  }
```
Ruling to encode: `email/resend` on a verified user → `409 ALREADY_VERIFIED`; the test's last line asserts 409. Run → Expected: FAIL 404s.

- [ ] **Step 2: Write both controllers**

`MeController` and `TeamController` straight from the Interfaces block; `TeamController.invite`: `users.findActiveByEmail(email)` present → `EMAIL_TAKEN`; else `mail.sendInvite(merchant, email, role)` which issues an `INVITE` token (7 days, payload `{email, role}`), after `tokens.invalidateOpenInvites(merchantId, email)` (a resend replaces). `pending invites` = `user_tokens` of kind INVITE, unused, unexpired, for the merchant (`UserTokenRepository.findOpenInvites`).

Run: `./mvnw -q -pl gateway-app test -Dtest=TeamApiIntegrationTest` → PASS 3/3.

- [ ] **Step 3: Commit**

```bash
./mvnw -q spotless:apply && git add gateway-app gateway-merchants && git commit -m "feat(api): /v1/me and the team — sessions, password, invites, roles, last owner"
```

---

### Task 9: Plaintext sweep, docs, DECISOES, full verify

**Files:**
- Create: `gateway-app/src/test/java/com/gateway/app/security/SessionsNeverHoldPlaintextTest.java`
- Modify: `README.md` (auth section + route table), `docs/superpowers/DECISOES.md` (the seven entries of spec §9 plus the three rulings below), `.env.example`, `docs/superpowers/ROADMAP-PAINEL.md` (B3 → PR)

- [ ] **Step 1: The sweep test**

```java
  @Test
  void noTableHoldsAPasswordASessionTokenOrAOneTimeToken() {
    String access = signup("ana@loja.com", "senha-unica-xyz");            // helper from AuthApiIntegrationTest
    post("/v1/auth/password/forgot", Map.of("email", "ana@loja.com"));
    String link = Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> firstLink(MAIL, "/reset/"), Objects::nonNull);

    for (String table : List.of("merchants.users", "merchants.sessions", "merchants.user_tokens", "payments.jobs")) {
      String dump = jdbc.queryForList("SELECT t::text FROM " + table + " t").stream().map(row -> row.values().iterator().next().toString()).collect(Collectors.joining("\n"));
      assertThat(dump).as(table).doesNotContain("senha-unica-xyz").doesNotContain(access).doesNotContain(tokenOf(link));
    }
    // outbound_emails holds the link until SEND_EMAIL delivers it; after delivery the row is gone.
    Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchants.outbound_emails", Integer.class)).isZero());
  }
```
Run → Expected: PASS (if it fails, something stored a plaintext — fix the store, not the test).

- [ ] **Step 2: Docs and decisions**

README: a "Panel login" section (signup, roles table, `X-Environment`, cookie + bearer, mail env vars, `GATEWAY_PANEL_BASE_URL`), the new routes in the route table with their codes. `.env.example`: the six `GATEWAY_MAIL_*`, `GATEWAY_PANEL_BASE_URL`. DECISOES: spec §9 entries 1–7 verbatim in the house format, plus:
- **2026-10-08 — `previous_refresh_hash` na sessão** (replay detection needs the hash just replaced; rejected: a separate refresh-token table; cost: one nullable column).
- **2026-10-08 — E-mail renderizado espera em `outbound_emails` e some ao ser enviado** (the job table holds only a ref; rejected: putting the link in the job row; cost: one table, a row that holds a link for seconds).
- **2026-10-08 — `email/resend` vive em `/v1/me`, não em `/v1/auth`** (it needs a session; `/v1/auth/*` is the unauthenticated surface).
- **2026-10-08 — Header `X-Environment` inválido é `TEST`, não 400** (a wrong header must never widen reach).
ROADMAP: B3 row → "PR #<n>".

- [ ] **Step 3: Full verify and commit**

Run: `./mvnw verify` → Expected: `BUILD SUCCESS` (ArchUnit included).
```bash
./mvnw -q spotless:apply && git add -A && git commit -m "docs: panel login, roles and mail in the README, DECISOES and .env.example"
```

---

## Self-review notes

- Spec coverage: §1 modules (T1–T5, T6–T8), §2 tables (T1; `previous_refresh_hash` and `outbound_emails` added by ruling), §3 session (T3, T7), §4 actor/filter/roles/CORS/rate limit (T6), §5 flows (T7; `email/resend` moved to `/v1/me`), §6 me/team (T8), §7 mail (T5), §8 tests (each task + T9 sweep), §9 decisions (T9).
- Interfaces: `SessionService.Issued`, `UserTokenService.Issued`, `Actor.User(userId, sessionId, role, emailVerified)`, `MailTemplates.*(to, name|storeName, link)`, `Job.sendEmail(id, clock)` are named identically in every task that uses them.
- Review Focus 1–5 each have the named test in T3, T2, T4, T8, T6.
- Known executor rulings to record in the ledger when hit: `TestApp` of merchants needs a `Clock` bean (T2); `JobHandlers` completeness forces the `SEND_EMAIL` stub in `BillingJobOwnersStub` (T5); `/v1/me/email/resend` on a verified user is `409 ALREADY_VERIFIED` (T8).
