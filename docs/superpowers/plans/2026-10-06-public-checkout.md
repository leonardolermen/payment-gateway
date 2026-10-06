# Public Checkout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A payer opens a link with a per-order token and pays the order (Pix, boleto or card) without a merchant API key.

**Architecture:** Every new order gets a `chk_` token whose SHA-256 (peppered) lives in `billing.orders.checkout_token_hash`; the plain token is returned once as `checkout_url`. A `CheckoutService` in `billing/order/checkout/` resolves token → order and delegates to the existing `OrderAttemptService` with a new `EventSource.CHECKOUT`. The app exposes `/v1/checkout/{token}` routes outside the API-key filter, with a per-IP rate limit, and a CORS allowlist for the separate front end.

**Tech Stack:** Java 25, Spring Boot 4 (`spring-boot-starter-web`), Spring Data JPA, Flyway, Bucket4j, Testcontainers + WireMock, ArchUnit, Maven (`./mvnw -B -o`), spotless.

**Spec:** `docs/superpowers/specs/2026-10-06-checkout-publico-design.md`

## Global Constraints

- Code, comments, javadoc and commit subjects in English; DECISOES entries in Portuguese with `Rejeitado:` and `Custo se errado:`.
- Commit format `tipo(escopo): lowercase subject`, body with the why, trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- `billing` never imports `merchants`; `kernel` imports nothing; `app` is the only module that imports everything (ArchUnit `businessModulesDoNotImportEachOther`, `kernelImportsNothing`).
- Plain-model classes outside `persistence/` and `support/` not named `*Service|Runner|Gateway|Relay|Properties|Configuration|Events` may not import Spring (`modelsHaveNoSpring`).
- Red-first: every behavior change starts with a failing test. Full `./mvnw -B -o verify` green before every commit; `spotless:apply` before committing.
- The plain checkout token is never stored, logged or put in an event payload; only its hash is persisted.
- No new credential values anywhere; `.env.example` gets placeholders only.
- JDK: `JAVA_HOME=C:\Users\leona\.jdks\corretto-25.0.4.1`. Docker Desktop must already be running for Testcontainers (never start it yourself).

## Review Focus

1. A token with the wrong length or alphabet (`chk_` + anything else) must answer 404 without touching the database or throwing. Pinned in Task 3 (`CheckoutTokenTest.aMalformedTokenHashesToNothing`) and Task 5 (`unknownTokenIs404`).
2. A PAID order's `GET /v1/checkout/{token}` must still answer 200 with `status: PAID` and `active_payment` null, so the payer who reloads sees "paid" rather than an error. Pinned in Task 5 (`aPaidOrderStillAnswersTheGet`).
3. The checkout response must never carry the payer's document, email or address, nor the merchant id. Pinned in Task 5 (`theCheckoutResponseNeverCarriesThePayer`).
4. `POST /v1/checkout/{token}/payments` on an order whose merchant has no provider credential for that method must fail as 422 from the existing flow, not 500. Pinned in Task 5 (`aMethodWithoutACredentialIs422`).
5. A preflight (`OPTIONS`) from a listed origin must succeed without an API key, and from an unlisted origin must carry no `Access-Control-Allow-Origin`. Pinned in Task 6.

---

### Task 1: Migration and the hash column on the order

**Files:**
- Create: `gateway-billing/src/main/resources/db/migration/billing/V306__orders_checkout_token.sql`
- Modify: `gateway-billing/src/main/java/com/gateway/billing/order/Order.java`
- Modify: `gateway-billing/src/main/java/com/gateway/billing/order/OrderFactory.java`
- Modify: `gateway-billing/src/main/java/com/gateway/billing/order/persistence/OrderEntity.java`
- Modify: `gateway-billing/src/main/java/com/gateway/billing/order/persistence/OrderRepositoryImpl.java` (insert at ~line 51, `toDomain` at ~line 208)
- Modify: `gateway-billing/src/main/java/com/gateway/billing/order/persistence/OrderRepository.java`
- Modify: `gateway-billing/src/main/java/com/gateway/billing/order/persistence/OrderJpaRepository.java`
- Test: `gateway-billing/src/test/java/com/gateway/billing/order/persistence/OrderRepositoryCheckoutTokenIntegrationTest.java`

**Interfaces:**
- Consumes: `Order` (class, constructor package-private, `rehydrate(...)` static), `OrderFactory.standalone(...)`/`invoice(...)`, `OrderRepository.insert/find/findById`, `ServiceIntegrationTestBase` from `gateway-payments` test support (the billing tests already extend it; copy the pattern of `gateway-billing/src/test/java/com/gateway/billing/order/OrderAttemptServiceIntegrationTest.java`).
- Produces:
  - `Order.checkoutTokenHash()` (`String`, nullable) and `Order.rotateCheckoutToken(String newHash, Instant at)` (bumps `version`, sets `updatedAt`).
  - `OrderFactory.standalone(..., String checkoutTokenHash, Clock clock)` and `OrderFactory.invoice(..., String checkoutTokenHash, Clock clock)` — the hash is a constructor input, so the factory stays Spring-free and the caller (Task 2) decides where the token comes from.
  - `Order.rehydrate(...)` gains a `String checkoutTokenHash` parameter right after `updatedAt`.
  - `OrderRepository.findByCheckoutTokenHash(String hash)` → `Optional<Order>`.
  - `OrderRepository.update(Order)` also writes `checkout_token_hash` (rotation).

- [ ] **Step 1: Write the failing repository test**

```java
package com.gateway.billing.order.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderFactory;
import com.gateway.billing.order.OrderPayer;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderRepositoryCheckoutTokenIntegrationTest extends ServiceIntegrationTestBase {
  static final String HASH = "a".repeat(64);

  @Autowired OrderRepository orders;

  Order standalone(String hash) {
    OrderPayer payer =
        new OrderPayer(PersonName.of("Ana Silva"), Document.of("52998224725"), "ana@example.com", null);
    return OrderFactory.standalone(
        MerchantId.next(), ProviderEnvironment.TEST, Money.brl(4990), "ref-1", "Coffee",
        null, payer, null, hash, clock);
  }

  @Test
  void theHashIsStoredAndTheOrderIsFoundByIt() {
    Order order = standalone(HASH);
    unitOfWork.run(() -> orders.insert(order));

    Optional<Order> found = orders.findByCheckoutTokenHash(HASH);

    assertThat(found).isPresent();
    assertThat(found.get().id()).isEqualTo(order.id());
    assertThat(found.get().checkoutTokenHash()).isEqualTo(HASH);
    assertThat(
            jdbc.queryForObject(
                "SELECT checkout_token_hash FROM billing.orders WHERE id = ?",
                String.class,
                order.id()))
        .isEqualTo(HASH);
  }

  @Test
  void anUnknownHashFindsNothing() {
    assertThat(orders.findByCheckoutTokenHash("b".repeat(64))).isEmpty();
  }

  @Test
  void rotationReplacesTheHashAndTheOldOneNoLongerResolves() {
    Order order = standalone(HASH);
    unitOfWork.run(() -> orders.insert(order));
    String newHash = "c".repeat(64);

    order.rotateCheckoutToken(newHash, clock.instant());
    boolean updated = unitOfWork.inTransaction(() -> orders.update(order));

    assertThat(updated).isTrue();
    assertThat(orders.findByCheckoutTokenHash(HASH)).isEmpty();
    assertThat(orders.findByCheckoutTokenHash(newHash)).map(Order::id).contains(order.id());
  }

  @Test
  void anOrderWithoutATokenHasANullHash() {
    Order order = standalone(null);
    unitOfWork.run(() -> orders.insert(order));

    assertThat(orders.findById(order.id())).map(Order::checkoutTokenHash).isEmpty();
  }
}
```

Check how the sibling test obtains `clock`, `unitOfWork` and `jdbc` from `ServiceIntegrationTestBase` and mirror it exactly (field names differ per base class; read the base first).

- [ ] **Step 2: Run it to see it fail**

Run: `./mvnw -B -o -pl gateway-billing -am test -Dtest=OrderRepositoryCheckoutTokenIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compilation failure (`checkoutTokenHash`, `rotateCheckoutToken`, `findByCheckoutTokenHash` undefined; `standalone` arity).

- [ ] **Step 3: Migration**

```sql
-- V306__orders_checkout_token.sql
-- The payer pays by a link the merchant forwards. The link carries a random token; the row keeps
-- only its peppered SHA-256, like merchants.api_keys: whoever reads the database cannot pay as the
-- payer. Nullable: orders created before this migration never had a token shown to anyone, and a
-- backfilled one would be a secret nobody holds. UNIQUE so a token resolves to one order and the
-- index serves the public lookup.
ALTER TABLE billing.orders ADD COLUMN checkout_token_hash CHAR(64);
CREATE UNIQUE INDEX ux_orders_checkout_token ON billing.orders (checkout_token_hash);
```

- [ ] **Step 4: Domain**

In `Order.java`: add field `private String checkoutTokenHash;` (mutable, next to `status`), constructor parameter `String checkoutTokenHash` placed right after `periodEnd` and before `createdAt`, accessor, and:

```java
  /**
   * The merchant lost the link or wants the old one dead: a new hash replaces it in place, and the
   * previous token stops resolving the moment this row is written. Bumps the version like any other
   * change so a concurrent cancel still loses or wins cleanly.
   */
  public void rotateCheckoutToken(String newHash, Instant at) {
    this.checkoutTokenHash = newHash;
    this.version++;
    this.updatedAt = at;
  }

  public String checkoutTokenHash() {
    return checkoutTokenHash;
  }
```

`rehydrate(...)` gains `String checkoutTokenHash` as its last parameter and passes it into the constructor. Update every `new Order(` and `Order.rehydrate(` call site (grep `rehydrate(` and `new Order(` in `gateway-billing`).

In `OrderFactory.java`: both factories take `String checkoutTokenHash` as the parameter before `Clock clock` and pass it through. Update every caller (`OrdersController` via `CreateOrderRequest.toOrder`, `CycleOpener.openNext`, dunning reissue if it builds orders, tests): pass `null` for now — Task 2 fills in the real token. Grep `OrderFactory.` across all modules.

- [ ] **Step 5: Persistence**

`OrderEntity`: add
```java
  @Column(name = "checkout_token_hash", length = 64)
  @JdbcTypeCode(SqlTypes.CHAR)
  String checkoutTokenHash;
```

`OrderRepositoryImpl.insert`: `entity.checkoutTokenHash = order.checkoutTokenHash();`. `toDomain`: pass `entity.checkoutTokenHash` to `rehydrate`. `OrderJpaRepository`: add `Optional<OrderEntity> findByCheckoutTokenHash(String checkoutTokenHash);` and extend `updateIfVersion` with `o.checkoutTokenHash = :checkoutTokenHash` (and the parameter) so `update(Order)` persists a rotation; `OrderRepositoryImpl.update` passes `order.checkoutTokenHash()`. Add to `OrderRepository`:

```java
  /** The public checkout lookup: a peppered SHA-256, never the token itself. */
  Optional<Order> findByCheckoutTokenHash(String hash);
```

- [ ] **Step 6: Run the test and the module**

Run: `./mvnw -B -o -pl gateway-billing -am test -Dtest=OrderRepositoryCheckoutTokenIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 4 tests pass.
Then: `./mvnw -B -o spotless:apply verify` — green (ArchUnit floor, existing order tests with the `null` hash).

- [ ] **Step 7: Commit**

```bash
git add gateway-billing gateway-app
git commit -m "feat(orders): checkout token hash on the order, with rotation" -m "V306 adds checkout_token_hash (nullable, unique). The token itself is never stored: only its peppered SHA-256, like api_keys. Factories take the hash so the domain stays free of where the token comes from." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Token generation and hashing (`CheckoutToken`, `TokenHasher` port) and the token on every new order

**Files:**
- Create: `gateway-billing/src/main/java/com/gateway/billing/order/checkout/CheckoutToken.java`
- Create: `gateway-billing/src/main/java/com/gateway/billing/order/checkout/TokenHasher.java`
- Create: `gateway-billing/src/main/java/com/gateway/billing/order/checkout/CheckoutTokens.java`
- Create: `gateway-app/src/main/java/com/gateway/app/api/checkout/PepperedTokenHasher.java`
- Modify: `gateway-billing/src/main/java/com/gateway/billing/BillingConfiguration.java`
- Modify: `gateway-billing/src/main/java/com/gateway/billing/order/OrderService.java` (`create` ~line 50)
- Modify: `gateway-billing/src/main/java/com/gateway/billing/subscription/billing/CycleOpener.java` (`openNext` ~line 92)
- Modify: `gateway-app/src/main/java/com/gateway/app/api/order/dto/CreateOrderRequest.java` (`toOrder`)
- Modify: `gateway-app/src/main/java/com/gateway/app/api/order/OrdersController.java`
- Test: `gateway-billing/src/test/java/com/gateway/billing/order/checkout/CheckoutTokenTest.java`
- Test: `gateway-billing/src/test/java/com/gateway/billing/order/checkout/CheckoutTokensTest.java`

**Interfaces:**
- Consumes: Task 1's `OrderFactory.standalone(..., checkoutTokenHash, clock)`, `Order.rotateCheckoutToken`.
- Produces:
  - `record CheckoutToken(String value)` with `static CheckoutToken generate(SecureRandom random)` → `"chk_" + base64url(32 bytes)` (43 chars after the prefix, no padding), `static Optional<CheckoutToken> parse(String raw)` (accepts only `^chk_[A-Za-z0-9_-]{43}$`), `toString()` returns `"chk_****"`.
  - `interface TokenHasher { String hash(String token); }` — in `billing/order/checkout/`; the app implements it with the API-key pepper.
  - `final class CheckoutTokens` with constructor `(TokenHasher hasher, SecureRandom random)`, `Issued issue()` → `record Issued(CheckoutToken token, String hash)`, and `Optional<String> hashOf(String raw)` (empty when `parse` rejects it — no database hit for garbage).
  - `OrderService.create(Order order)` unchanged in signature; `OrderService.issueCheckout()` is NOT added — instead the controller asks `CheckoutTokens.issue()` and passes `issued.hash()` into the factory, then returns `issued.token()` in the response. Reason: the plain token must exist only on the request path, never inside a service that logs or emits.
  - `CycleOpener.openNext` issues a token the same way for invoices (`checkoutTokens.issue()`), so every order has a hash; the invoice's token is exposed by `GET /v1/orders/{id}` only via rotation (Task 4), since nobody saw it at creation.
  - `OrderService.rotateCheckoutToken(MerchantId, String orderId, String newHash)` → `Order` (409 `ORDER_CLOSED` unless OPEN; optimistic update, `CONFLICT` on a lost race).

- [ ] **Step 1: Failing unit tests**

```java
package com.gateway.billing.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class CheckoutTokenTest {
  @Test
  void aGeneratedTokenHasThePrefixAndFortyThreeUrlSafeChars() {
    CheckoutToken token = CheckoutToken.generate(new SecureRandom());

    assertThat(token.value()).matches("^chk_[A-Za-z0-9_-]{43}$");
    assertThat(CheckoutToken.parse(token.value())).contains(token);
  }

  @Test
  void twoTokensDiffer() {
    SecureRandom random = new SecureRandom();
    assertThat(CheckoutToken.generate(random)).isNotEqualTo(CheckoutToken.generate(random));
  }

  @Test
  void aMalformedTokenHashesToNothing() {
    assertThat(CheckoutToken.parse("chk_short")).isEmpty();
    assertThat(CheckoutToken.parse("gk_test_" + "a".repeat(43))).isEmpty();
    assertThat(CheckoutToken.parse("chk_" + "a".repeat(42) + "=")).isEmpty();
    assertThat(CheckoutToken.parse(null)).isEmpty();
  }

  @Test
  void toStringNeverShowsTheValue() {
    CheckoutToken token = CheckoutToken.generate(new SecureRandom());
    assertThat(token.toString()).doesNotContain(token.value().substring(4));
  }
}
```

```java
package com.gateway.billing.order.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import org.junit.jupiter.api.Test;

class CheckoutTokensTest {
  /** A fake hasher: the real one is the app's peppered SHA-256; here only the wiring matters. */
  static final TokenHasher REVERSE = token -> new StringBuilder(token).reverse().toString();

  CheckoutTokens tokens = new CheckoutTokens(REVERSE, new SecureRandom());

  @Test
  void issueReturnsTheTokenAndItsHashAndTheHashIsNotTheToken() {
    CheckoutTokens.Issued issued = tokens.issue();

    assertThat(issued.hash()).isEqualTo(REVERSE.hash(issued.token().value()));
    assertThat(issued.hash()).isNotEqualTo(issued.token().value());
  }

  @Test
  void hashOfARawTokenMatchesTheIssuedHash() {
    CheckoutTokens.Issued issued = tokens.issue();

    assertThat(tokens.hashOf(issued.token().value())).contains(issued.hash());
  }

  @Test
  void hashOfGarbageIsEmptyWithoutCallingTheHasher() {
    TokenHasher explodes = token -> { throw new AssertionError("hasher called for garbage"); };

    assertThat(new CheckoutTokens(explodes, new SecureRandom()).hashOf("not-a-token")).isEmpty();
  }
}
```

- [ ] **Step 2: Run, expect compilation failure**

Run: `./mvnw -B -o -pl gateway-billing -am test -Dtest='CheckoutToken*' -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement**

```java
package com.gateway.billing.order.checkout;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The secret in the payer's link. 32 random bytes (256 bits): a brute force against the hash is
 * out of reach even with a fast hash, which is why the row keeps a peppered SHA-256 and not bcrypt
 * (same reasoning as ApiKey). The prefix lets a log scrubber and a human recognise one; it carries
 * no information.
 */
public record CheckoutToken(String value) {
  private static final Pattern SHAPE = Pattern.compile("^chk_[A-Za-z0-9_-]{43}$");
  private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

  public static CheckoutToken generate(SecureRandom random) {
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    return new CheckoutToken("chk_" + URL.encodeToString(bytes));
  }

  /** Empty for anything that is not shaped like a token: those never reach the database. */
  public static Optional<CheckoutToken> parse(String raw) {
    if (raw == null || !SHAPE.matcher(raw).matches()) {
      return Optional.empty();
    }
    return Optional.of(new CheckoutToken(raw));
  }

  @Override
  public String toString() {
    return "chk_****";
  }
}
```

```java
package com.gateway.billing.order.checkout;

/** Port: the app hashes with the same pepper as API keys; billing must not import merchants. */
public interface TokenHasher {
  String hash(String token);
}
```

```java
package com.gateway.billing.order.checkout;

import java.security.SecureRandom;
import java.util.Optional;

/** Issues and recognises checkout tokens. The plain token leaves here once, in {@link Issued}. */
public final class CheckoutTokens {
  public record Issued(CheckoutToken token, String hash) {}

  private final TokenHasher hasher;
  private final SecureRandom random;

  public CheckoutTokens(TokenHasher hasher, SecureRandom random) {
    this.hasher = hasher;
    this.random = random;
  }

  public Issued issue() {
    CheckoutToken token = CheckoutToken.generate(random);
    return new Issued(token, hasher.hash(token.value()));
  }

  public Optional<String> hashOf(String raw) {
    return CheckoutToken.parse(raw).map(token -> hasher.hash(token.value()));
  }
}
```

App side:

```java
package com.gateway.app.api.checkout;

import com.gateway.billing.order.checkout.TokenHasher;
import com.gateway.merchants.MerchantsProperties;
import com.gateway.merchants.apikey.ApiKey;
import org.springframework.stereotype.Component;

/** The API-key pepper, through the API-key hash: one secret to rotate, one algorithm to audit. */
@Component
public class PepperedTokenHasher implements TokenHasher {
  private final String pepper;

  public PepperedTokenHasher(MerchantsProperties properties) {
    this.pepper = properties.apiKeyPepper();
  }

  @Override
  public String hash(String token) {
    return ApiKey.hashOf(token, pepper);
  }
}
```

`BillingConfiguration`: `@Bean CheckoutTokens checkoutTokens(TokenHasher hasher) { return new CheckoutTokens(hasher, new SecureRandom()); }`.

`OrderService`: add

```java
  /**
   * A new token in place of the old one. Only an OPEN order: a closed order's link is dead already,
   * and issuing a fresh one would say otherwise.
   */
  public Order rotateCheckoutToken(MerchantId merchantId, String id, String newHash) {
    return unitOfWork.inTransaction(
        () -> {
          Order order = get(merchantId, id);
          if (!order.isOpen()) {
            throw new DomainException("ORDER_CLOSED", "order " + id + " is " + order.status());
          }

          order.rotateCheckoutToken(newHash, clock.instant());
          if (!orders.update(order)) {
            throw new DomainException("CONFLICT", "order " + id + " changed concurrently");
          }

          return order;
        });
  }
```

`CycleOpener.openNext`: inject `CheckoutTokens` (constructor + `BillingConfiguration` bean) and pass `checkoutTokens.issue().hash()` into `OrderFactory.invoice(...)`.

`CreateOrderRequest.toOrder(merchantId, environment, checkoutTokenHash, clock)` and `OrdersController.create`:

```java
    CheckoutTokens.Issued issued = checkoutTokens.issue();
    Order order =
        request.toOrder(
            caller.merchantId(), Environments.toProvider(caller.environment()), issued.hash(), clock);
    Order created = orders.create(order);

    return withResource(
        HttpStatus.CREATED, created.id(), OrderResponse.from(created, List.of(), issued.token()));
```

`OrderResponse.from(Order, List<Payment>, CheckoutToken tokenOrNull)` is Task 4's job; in this task make `OrderResponse.from(order, attempts)` keep compiling by adding an overload that ignores the token (a `String checkoutUrl` field set to null) — minimal, Task 4 finishes it.

- [ ] **Step 4: Run, expect green; then full verify**

Run: `./mvnw -B -o -pl gateway-billing -am test -Dtest='CheckoutToken*' -Dsurefire.failIfNoSpecifiedTests=false` then `./mvnw -B -o spotless:apply verify`.
Expected: green. `BillingApiIntegrationTest` still passes (order creation now carries a hash).

- [ ] **Step 5: Commit**

```bash
git add gateway-billing gateway-app
git commit -m "feat(checkout): every new order is born with a checkout token" -m "chk_ + 32 random bytes, hashed with the API-key pepper through a TokenHasher port so billing stays clear of merchants. The plain token exists only on the create request path." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: `EventSource.CHECKOUT` and `CheckoutService`

**Files:**
- Modify: `gateway-payments/src/main/java/com/gateway/payments/payment/EventSource.java`
- Create: `gateway-billing/src/main/java/com/gateway/billing/order/checkout/CheckoutService.java`
- Create: `gateway-billing/src/main/java/com/gateway/billing/order/checkout/CheckoutView.java`
- Modify: `gateway-billing/src/main/java/com/gateway/billing/BillingConfiguration.java`
- Test: `gateway-billing/src/test/java/com/gateway/billing/order/checkout/CheckoutServiceIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2's `CheckoutTokens.hashOf`, Task 1's `OrderRepository.findByCheckoutTokenHash`, `OrderAttemptService.attempt(Order, AttemptRequest, EventSource)`, `PaymentQueries.listByOrder(MerchantId, String)` / `activeAttempt(String)` / `get(MerchantId, String)`, `PaymentCancellation.cancel(MerchantId, String)`.
- Produces:
  - `EventSource.CHECKOUT` (javadoc: "the payer, through the public checkout link; the merchant reads it on the payment events to tell its own API calls from the payer's").
  - `record CheckoutView(Order order, List<Payment> attempts)` with `Optional<Payment> activeAttempt()` (status `PENDING`/`AUTHORIZED`/`CREATED`, i.e. `Payment::isActive` if it exists, else the same statuses `PaymentQueries.activeAttempt` uses — read it and reuse the predicate).
  - `CheckoutService`:
    - `CheckoutView get(String rawToken)` → 404 `NotFoundException("checkout", "token")` when the token is malformed or unknown. **Never** includes the raw token in the exception message.
    - `Payment attempt(String rawToken, AttemptRequest request)` → resolves, then `attempts.attempt(order, request, EventSource.CHECKOUT)`; a closed order → `DomainException("ORDER_CLOSED", ...)` (the attempt service already throws it; the service re-checks before claiming so a closed order never touches `AttemptSlot`).
    - `Payment payment(String rawToken, String paymentId)` → the payment only if `payment.orderId().equals(order.id())`, else 404.
    - `Payment cancelAttempt(String rawToken, String paymentId)` → only Pix/Bolecode and only an active attempt; card → `DomainException("CHECKOUT_CANNOT_CANCEL_CARD", "a card attempt is canceled by the merchant")` (422 by default mapping).

- [ ] **Step 1: Failing integration test**

Model it on `gateway-billing/src/test/java/com/gateway/billing/order/OrderAttemptServiceIntegrationTest.java` (read it first: it shows how a merchant with an Itaú WireMock credential and a Pix attempt is set up in the billing module). Tests:

```java
  @Test
  void aValidTokenResolvesTheOrderAndItsAttempts()        // get(token).order().id() == order.id(); attempts empty
  @Test
  void anUnknownOrMalformedTokenIs404()                   // get("chk_" + "z".repeat(43)) and get("garbage") throw NotFoundException whose message does not contain the raw token
  @Test
  void aPixAttemptThroughTheCheckoutIsRecordedAsCheckout() // attempt(token, new AttemptRequest.PixAttempt(600)) -> PENDING; the payment_events row for the creation has source CHECKOUT (query payments.payment_events by payment_id — read the column name in V2xx)
  @Test
  void aClosedOrderRefusesAnAttemptWith409()              // cancel the order via OrderService.cancel, then attempt -> DomainException code ORDER_CLOSED
  @Test
  void aPaymentOfAnotherOrderIs404()                      // payment(tokenA, paymentOfB) -> NotFoundException
  @Test
  void cancelAttemptCancelsAPendingPixAndRefusesACard()   // Pix -> CANCELED (WireMock PATCH /cob stub); card -> CHECKOUT_CANNOT_CANCEL_CARD
```

Write each with real assertions (`assertThat(...)`, `assertThatThrownBy(...)`), following the sibling test's helpers for stubs.

If the Pix creation path records `EventSource.API` regardless of `by` (the javadoc on `OrderAttemptService.attempt` says "the flows record API today"), then the third test asserts what the flow actually records and the plan's ruling is: keep `by` for the audit trail of the attempt service's own log line; add a `log.info("checkout attempt {} on order {}", payment.id(), order.id())` in `CheckoutService.attempt` so the source is at least observable. Record this in the task report so the controller can rule.

- [ ] **Step 2: Run, expect compilation failure**

Run: `./mvnw -B -o -pl gateway-billing -am test -Dtest=CheckoutServiceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 3: Implement**

```java
package com.gateway.billing.order.checkout;

import com.gateway.billing.order.AttemptRequest;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderAttemptService;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentCancellation;
import com.gateway.payments.payment.PaymentQueries;

/**
 * The payer's door. Everything here is keyed by the checkout token, never by an id the merchant
 * API also uses: a payer who holds a link can see and pay one order, and nothing else. A malformed
 * token never reaches the database (CheckoutTokens.hashOf), and no error message echoes it.
 */
public class CheckoutService {
  private final CheckoutTokens tokens;
  private final OrderRepository orders;
  private final OrderAttemptService attempts;
  private final PaymentQueries payments;
  private final PaymentCancellation cancellation;

  public CheckoutService(
      CheckoutTokens tokens,
      OrderRepository orders,
      OrderAttemptService attempts,
      PaymentQueries payments,
      PaymentCancellation cancellation) {
    this.tokens = tokens;
    this.orders = orders;
    this.attempts = attempts;
    this.payments = payments;
    this.cancellation = cancellation;
  }

  public CheckoutView get(String rawToken) {
    Order order = resolve(rawToken);

    return new CheckoutView(order, payments.listByOrder(order.merchantId(), order.id()));
  }

  public Payment attempt(String rawToken, AttemptRequest request) {
    Order order = resolve(rawToken);
    if (!order.isOpen()) {
      throw new DomainException("ORDER_CLOSED", "order " + order.id() + " is " + order.status());
    }

    return attempts.attempt(order, request, EventSource.CHECKOUT);
  }

  public Payment payment(String rawToken, String paymentId) {
    Order order = resolve(rawToken);

    return ownAttempt(order, paymentId);
  }

  /** Pix and boleto only: a card is voided by the merchant, with its own audit (Plan H). */
  public Payment cancelAttempt(String rawToken, String paymentId) {
    Order order = resolve(rawToken);
    Payment attempt = ownAttempt(order, paymentId);
    if (attempt.method() == PaymentMethod.CARD) {
      throw new DomainException(
          "CHECKOUT_CANNOT_CANCEL_CARD", "a card attempt is canceled by the merchant");
    }

    return cancellation.cancel(order.merchantId(), attempt.id());
  }

  private Order resolve(String rawToken) {
    return tokens
        .hashOf(rawToken)
        .flatMap(orders::findByCheckoutTokenHash)
        .orElseThrow(() -> new NotFoundException("checkout", "token"));
  }

  private Payment ownAttempt(Order order, String paymentId) {
    Payment payment =
        payments
            .find(order.merchantId(), paymentId)
            .orElseThrow(() -> new NotFoundException("payment", paymentId));
    if (!order.id().equals(payment.orderId())) {
      throw new NotFoundException("payment", paymentId);
    }

    return payment;
  }
}
```

Check `PaymentQueries` for a non-throwing finder (`find`/`findById`); if only `get(merchantId, id)` (throwing `NotFoundException`) exists, use it and let its exception stand. Check `NotFoundException`'s constructor shape `(String kind, String id)` in `gateway-kernel/.../errors/NotFoundException.java` and match it.

`CheckoutView`:

```java
package com.gateway.billing.order.checkout;

import com.gateway.billing.order.Order;
import com.gateway.payments.payment.Payment;
import java.util.List;
import java.util.Optional;

/** What the payer may see: the order and its attempts; the app decides which fields leave. */
public record CheckoutView(Order order, List<Payment> attempts) {
  public Optional<Payment> activeAttempt() {
    return attempts.stream().filter(Payment::isActive).findFirst();
  }
}
```

(If `Payment.isActive()` does not exist, add it to `Payment` as `status == CREATED || PENDING || AUTHORIZED` — the same set `uq_payments_order_active` covers — with a javadoc pointing at the index. Grep for an existing predicate first.)

`BillingConfiguration` bean:

```java
  @Bean
  CheckoutService checkoutService(
      CheckoutTokens tokens,
      OrderRepository orders,
      OrderAttemptService attempts,
      PaymentQueries payments,
      PaymentCancellation cancellation) {
    return new CheckoutService(tokens, orders, attempts, payments, cancellation);
  }
```

`EventSource`: add `CHECKOUT` with the javadoc from Interfaces. Check `PaymentTransitions` (or whatever table the `EventSource` javadoc refers to) for a source-keyed allow-list; if `API` is listed for creation transitions, add `CHECKOUT` beside it.

- [ ] **Step 4: Run tests, then verify, then commit**

Run: the test class, then `./mvnw -B -o spotless:apply verify`.

```bash
git add gateway-billing gateway-payments
git commit -m "feat(checkout): checkout service resolves a token to one order and pays it" -m "Keyed by token only; a malformed token never reaches the database and no message echoes it. EventSource.CHECKOUT lets the merchant tell its own calls from the payer's." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Merchant side of the API: `checkout_url` and rotation

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/api/checkout/CheckoutProperties.java`
- Modify: `gateway-app/src/main/java/com/gateway/app/api/order/dto/OrderResponse.java`
- Modify: `gateway-app/src/main/java/com/gateway/app/api/order/OrdersController.java`
- Modify: `gateway-app/src/main/java/com/gateway/app/api/support/IdempotencyFilter.java` (`IDEMPOTENT_POST` ~line 55)
- Modify: `gateway-app/src/test/java/com/gateway/app/api/support/IdempotencyFilterPathsTest.java`
- Modify: `gateway-app/src/main/resources/application.yml`, `gateway-app/src/test/resources/application-test.yml`, `.env.example`
- Test: `gateway-app/src/test/java/com/gateway/app/CheckoutUrlApiIntegrationTest.java`

**Interfaces:**
- Consumes: Task 2's `CheckoutTokens`, `OrderService.rotateCheckoutToken`.
- Produces:
  - `@ConfigurationProperties("gateway.checkout") record CheckoutProperties(String baseUrl, List<String> corsOrigins, int rateLimitPerMinute)` with defaults `baseUrl = "http://localhost:5173/pay/"`, `corsOrigins = List.of()`, `rateLimitPerMinute = 60`. Registered via `@EnableConfigurationProperties` in `AppConfiguration`.
  - `OrderResponse` gains `String checkoutUrl` (after `createdAt`): `baseUrl + token` when a token is in hand, else `null`. `OrderResponse.from(Order, List<Payment>, String checkoutUrl)`.
  - `POST /v1/orders/{id}/checkout-token/rotate` → 200 `OrderResponse` with the new `checkout_url`; 409 `ORDER_CLOSED`; requires `Idempotency-Key` (added to `IDEMPOTENT_POST`: `|^/v1/orders/[^/]+/checkout-token/rotate$`).
  - `GET /v1/orders/{id}` returns `checkout_url: null` always (the token is not stored); document it. **Ruling against the spec's "devolvido em todo GET"**: the spec also says the token lives only in the response; both cannot hold. The hash-only design wins (spec §2 decision 1); the README says "shown once; rotate to get a new one".

- [ ] **Step 1: Failing API test**

Self-contained `@SpringBootTest` like `BillingApiIntegrationTest` (copy its container, admin bootstrap and `client()` helpers; no WireMock needed here). Property `gateway.checkout.base-url=https://pay.test/pay/`.

```java
  @Test
  void createReturnsACheckoutUrlOnceAndGetReturnsNull() {
    // POST /v1/orders with inline customer -> 201; body.checkout_url starts with "https://pay.test/pay/chk_" and has 43 more chars
    // GET /v1/orders/{id} -> checkout_url null
    // SELECT checkout_token_hash FROM billing.orders WHERE id = ? -> 64 hex chars, not equal to the token
  }

  @Test
  void rotateIssuesANewUrlAndNeedsAnIdempotencyKey() {
    // POST .../checkout-token/rotate without the header -> 400 IDEMPOTENCY_KEY_REQUIRED
    // with header -> 200, checkout_url differs from the first, hash in the DB changed
  }

  @Test
  void rotateOnACanceledOrderIs409() {
    // POST /v1/orders/{id}/cancel then rotate -> 409 ORDER_CLOSED
  }
```

Also extend `IdempotencyFilterPathsTest` with the new route (`"/v1/orders/01ABC/checkout-token/rotate", true`) in its parameter list.

- [ ] **Step 2: Run, expect failure (404 on rotate; `checkout_url` missing)**

- [ ] **Step 3: Implement**

`CheckoutProperties`:

```java
package com.gateway.app.api.checkout;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param baseUrl where the payer-facing front serves /pay/; the token is appended as is
 * @param corsOrigins exact origins allowed to call /v1 from a browser; empty = no CORS at all
 * @param rateLimitPerMinute per client IP on /v1/checkout; a payer needs a handful per minute
 */
@ConfigurationProperties("gateway.checkout")
public record CheckoutProperties(String baseUrl, List<String> corsOrigins, int rateLimitPerMinute) {
  public CheckoutProperties {
    if (baseUrl == null || baseUrl.isBlank()) {
      baseUrl = "http://localhost:5173/pay/";
    }
    if (corsOrigins == null) {
      corsOrigins = List.of();
    }
    if (rateLimitPerMinute <= 0) {
      rateLimitPerMinute = 60;
    }
  }

  public String urlFor(String token) {
    return baseUrl + token;
  }
}
```

`application.yml` under `gateway:`:

```yaml
  checkout:
    base-url: ${GATEWAY_CHECKOUT_BASE_URL:http://localhost:5173/pay/}   # the front's /pay/ route; the token is appended
    cors-origins: ${GATEWAY_CORS_ORIGINS:}                               # comma-separated exact origins; empty = CORS off
    rate-limit-per-minute: 60                                            # per client IP on /v1/checkout/**
```

`.env.example`: `GATEWAY_CHECKOUT_BASE_URL=` and `GATEWAY_CORS_ORIGINS=` with one-line comments.

`OrdersController`: inject `CheckoutTokens` and `CheckoutProperties`; `create` (from Task 2) now builds `OrderResponse.from(created, List.of(), checkout.urlFor(issued.token().value()))`; add

```java
  /** The old link dies here; the new one is in the body, once. */
  @PostMapping("/{id}/checkout-token/rotate")
  public ResponseEntity<OrderResponse> rotateCheckoutToken(@PathVariable String id) {
    MerchantId merchantId = MerchantContext.current().merchantId();
    CheckoutTokens.Issued issued = checkoutTokens.issue();

    Order rotated = orders.rotateCheckoutToken(merchantId, id, issued.hash());

    return withResource(
        HttpStatus.OK,
        rotated.id(),
        OrderResponse.from(
            rotated, orders.attemptsOf(merchantId, id), checkout.urlFor(issued.token().value())));
  }
```

Every other `OrderResponse.from(...)` call passes `null`.

- [ ] **Step 4: Tests green, full verify, commit**

```bash
git add gateway-app .env.example
git commit -m "feat(orders): checkout_url shown once on create, rotate to get a new one" -m "The token is not stored, so GET cannot show it again; rotation issues a fresh one and kills the old. Idempotent like every POST that changes an order." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Public routes `/v1/checkout/{token}`

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/api/checkout/CheckoutController.java`
- Create: `gateway-app/src/main/java/com/gateway/app/api/checkout/dto/CheckoutResponse.java`
- Create: `gateway-app/src/main/java/com/gateway/app/api/checkout/dto/CheckoutPaymentResponse.java`
- Modify: `gateway-app/src/main/java/com/gateway/app/security/ProtectedRoutes.java`
- Modify: `gateway-app/src/test/java/com/gateway/app/security/ProtectedRoutesTest.java`
- Modify: `gateway-app/src/main/java/com/gateway/app/api/support/ErrorHandler.java` (`STATUS_BY_CODE`)
- Modify: `gateway-app/src/test/java/com/gateway/app/CardDataNeverLeavesTheRequestTest.java`
- Test: `gateway-app/src/test/java/com/gateway/app/CheckoutApiIntegrationTest.java`

**Interfaces:**
- Consumes: Task 3's `CheckoutService`, `CheckoutView`; `OrderAttemptRequest.toAttempt()`; `MerchantService.get(MerchantId)` for the name; `ProviderCredentialService.list(MerchantId)` (`ProviderCredential(provider, environment, active, ...)`) for `methods`.
- Produces: the three public routes plus cancel, per spec §3:
  - `GET /v1/checkout/{token}` → `CheckoutResponse(orderId, merchantName, amount, currency, description, status, expiresAt, methods, activePayment)`; `methods` = `["PIX","BOLECODE"]` if an active ITAU credential exists for the order's environment, plus `"CARD"` if CIELO does (map `ProviderEnvironment` ↔ `ApiKeyEnvironment` through `Environments`; add the reverse mapping there if missing).
  - `POST /v1/checkout/{token}/payments` body `OrderAttemptRequest` → 201 `CheckoutPaymentResponse`.
  - `GET /v1/checkout/{token}/payments/{id}` → 200 `CheckoutPaymentResponse`.
  - `POST /v1/checkout/{token}/payments/{id}/cancel` → 200 `CheckoutPaymentResponse`.
  - `CheckoutPaymentResponse(id, method, status, pix{copiaECola, expiresAt}, boleto{linhaDigitavel, dueDate, paymentLimitDate}, card{brand, last4, installments}, paidAt, createdAt)` — a strict subset of `PaymentResponse`; no provider, no tid, no reference, no amount beyond the order's.
  - `ErrorHandler.STATUS_BY_CODE`: `ORDER_CLOSED` stays 409 for the merchant routes; the checkout controller maps a closed order to **410** by catching `DomainException` with code `ORDER_CLOSED` in a controller-local `@ExceptionHandler`? No — simpler and consistent: add `Map.entry("CHECKOUT_ORDER_CLOSED", HttpStatus.GONE)` and have `CheckoutService.attempt`/`cancelAttempt` throw `CHECKOUT_ORDER_CLOSED` instead of `ORDER_CLOSED` (update Task 3's test expectation accordingly: the service throws `CHECKOUT_ORDER_CLOSED`; `OrderAttemptService` behind it would throw `ORDER_CLOSED` only on a race, which still maps to 409 and is acceptable).
  - `ProtectedRoutes.requiresApiKey` excludes `/v1/checkout/`; a new `static boolean isCheckout(String path)`.

- [ ] **Step 1: Failing tests**

`ProtectedRoutesTest`: add cases `"/v1/checkout/chk_x", false` and `"/v1/checkout", false`... read the test's shape and add rows for `requiresApiKey` false on `/v1/checkout/...` and `isCheckout` true.

`CheckoutApiIntegrationTest` (copy the bootstrap and Itaú/Cielo WireMock stubs from `BillingApiIntegrationTest`; same `paid(...)`, fixtures):

```java
  @Test void theCheckoutIsReadWithoutAKey()               // GET /v1/checkout/{token} no Authorization -> 200; merchant_name == "Billing Store"; methods contains PIX, BOLECODE, CARD; active_payment null
  @Test void unknownTokenIs404()                          // "chk_" + "z".repeat(43) -> 404 NOT_FOUND; "garbage" -> 404; body does not contain the token
  @Test void theCheckoutResponseNeverCarriesThePayer()    // response body text does not contain "52998224725", "ana@example.com", merchantId, "payer", "customer"
  @Test void aPixAttemptThenPollingThenCancel()           // POST .../payments {"method":"PIX","expires_in":600} -> 201 PENDING with pix.copia_e_cola; GET .../payments/{id} -> same; POST .../payments/{id}/cancel -> 200 CANCELED; GET /v1/checkout/{token} active_payment null
  @Test void aSecondActiveAttemptIs409()                  // two PIX posts -> second 409 ORDER_HAS_ACTIVE_PAYMENT with payment_id
  @Test void aCardAttemptIsPaid()                         // {"method":"CARD","card":{number,holder,expiry,cvv},"installments":1} with CARD_NUMBER -> 201 COMPLETED; card.last4 present; no "number" key in response; cancel -> 422 CHECKOUT_CANNOT_CANCEL_CARD
  @Test void aPaidOrderStillAnswersTheGet()               // after the card paid and the relay ran (Awaitility until order status PAID via merchant GET) -> GET /v1/checkout/{token} 200 status PAID, active_payment null; POST payments -> 410 CHECKOUT_ORDER_CLOSED
  @Test void aCanceledOrderIs410ForPostAnd200ForGet()
  @Test void aMethodWithoutACredentialIs422()             // a second merchant with only ITAU; CARD attempt -> 422 (whatever code the flow uses today, assert the status and that it is not 500); methods in GET lacks CARD
  @Test void aPaymentOfAnotherOrderIs404()
```

Read the card request body shape from `CardAttemptBody` (field names `number`, `holder`, `expiry`, `cvv`? read it) and use exactly those.

`CardDataNeverLeavesTheRequestTest`: add one more scenario in its list of card paths: the public checkout card attempt (`POST /v1/checkout/{token}/payments` with `NUMBER` and `CVV`) so the sweep covers the new route. Read how that test enumerates scenarios and add one in the same style.

- [ ] **Step 2: Run, expect 401/404 failures**

- [ ] **Step 3: Implement**

`ProtectedRoutes`:

```java
  static boolean requiresApiKey(String path) {
    return path.startsWith("/v1/")
        && !isAdmin(path)
        && !path.startsWith("/v1/providers/")
        && !isCheckout(path);
  }

  /** The payer's routes: no key, a token in the path, limited per IP by CheckoutRateLimitFilter. */
  static boolean isCheckout(String path) {
    return path.startsWith("/v1/checkout/");
  }
```

(Make `isCheckout` package-visible; Task 6's filter lives in `security` too.)

`CheckoutController`:

```java
package com.gateway.app.api.checkout;

import com.gateway.app.api.checkout.dto.CheckoutPaymentResponse;
import com.gateway.app.api.checkout.dto.CheckoutResponse;
import com.gateway.app.api.order.dto.OrderAttemptRequest;
import com.gateway.billing.order.checkout.CheckoutService;
import com.gateway.billing.order.checkout.CheckoutView;
import com.gateway.merchants.credential.ProviderCredentialService;
import com.gateway.merchants.merchant.MerchantService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The payer's API: no key, the token in the path is the whole authorization. Responses are a
 * strict subset of the merchant's — never the payer's own data back to the browser, never provider
 * ids. Not under IdempotencyFilter: there is no merchant scope for a key, and the one-active-attempt
 * rule already stops a double charge.
 */
@RestController
@RequestMapping("/v1/checkout/{token}")
public class CheckoutController {
  private final CheckoutService checkout;
  private final MerchantService merchants;
  private final ProviderCredentialService credentials;

  public CheckoutController(
      CheckoutService checkout, MerchantService merchants, ProviderCredentialService credentials) {
    this.checkout = checkout;
    this.merchants = merchants;
    this.credentials = credentials;
  }

  @GetMapping
  public CheckoutResponse get(@PathVariable String token) {
    CheckoutView view = checkout.get(token);
    String merchantName = merchants.get(view.order().merchantId()).name();

    return CheckoutResponse.from(view, merchantName, Methods.available(credentials, view.order()));
  }

  @PostMapping("/payments")
  public ResponseEntity<CheckoutPaymentResponse> attempt(
      @PathVariable String token, @RequestBody OrderAttemptRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(CheckoutPaymentResponse.from(checkout.attempt(token, request.toAttempt())));
  }

  @GetMapping("/payments/{id}")
  public CheckoutPaymentResponse payment(@PathVariable String token, @PathVariable String id) {
    return CheckoutPaymentResponse.from(checkout.payment(token, id));
  }

  @PostMapping("/payments/{id}/cancel")
  public CheckoutPaymentResponse cancel(@PathVariable String token, @PathVariable String id) {
    return CheckoutPaymentResponse.from(checkout.cancelAttempt(token, id));
  }
}
```

`Methods` is a small package-private final class in the same package: `static List<String> available(ProviderCredentialService credentials, Order order)` → ITAU active in the order's environment ⇒ `PIX`, `BOLECODE`; CIELO ⇒ `CARD`. Environment mapping: `ProviderEnvironment.LIVE ↔ ApiKeyEnvironment.LIVE`, else TEST.

DTOs as records with static `from(...)`; `CheckoutResponse.from(CheckoutView view, String merchantName, List<String> methods)` sets `activePayment = view.activeAttempt().map(CheckoutPaymentResponse::from).orElse(null)`; `CheckoutPaymentResponse.Pix(copiaECola, expiresAt)` with `@JsonProperty("copia_e_cola")` exactly as `PaymentResponse` does.

`ErrorHandler`: `Map.entry("CHECKOUT_ORDER_CLOSED", HttpStatus.GONE)` with a javadoc sentence: "410, not 409: for the payer the link is gone, nothing they do changes that."

`CheckoutService` (Task 3): switch the code to `CHECKOUT_ORDER_CLOSED` in `attempt` and `cancelAttempt`; update that test.

- [ ] **Step 4: Tests green, verify, commit**

```bash
git add gateway-app gateway-billing
git commit -m "feat(checkout): public routes under /v1/checkout/{token}" -m "No key: the token is the authorization and the responses are a strict subset of the merchant's. A closed order is 410 for the payer. CardDataNeverLeavesTheRequestTest now sweeps the new card path." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Per-IP rate limit on checkout and CORS by allowlist

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/security/CheckoutRateLimitFilter.java`
- Create: `gateway-app/src/main/java/com/gateway/app/security/ClientIp.java`
- Create: `gateway-app/src/main/java/com/gateway/app/security/CorsConfiguration.java`
- Test: `gateway-app/src/test/java/com/gateway/app/security/ClientIpTest.java`
- Test: `gateway-app/src/test/java/com/gateway/app/security/CheckoutRateLimitFilterTest.java`
- Test: `gateway-app/src/test/java/com/gateway/app/CorsIntegrationTest.java`

**Interfaces:**
- Consumes: Task 4's `CheckoutProperties`, Task 5's `ProtectedRoutes.isCheckout`.
- Produces:
  - `record ClientIp(String value)` with `static ClientIp of(HttpServletRequest request)`: first entry of `X-Forwarded-For` when present and the remote address is a loopback or private address (10/8, 172.16/12, 192.168/16, 127/8, ::1) — i.e. only trust the header when the hop before us is our own proxy; otherwise `request.getRemoteAddr()`.
  - `CheckoutRateLimitFilter` `@Order(31)`, `shouldNotFilter` = `!ProtectedRoutes.isCheckout(normalized)`; one Bucket4j bucket per `ClientIp`, capacity `rateLimitPerMinute`, 429 `RATE_LIMITED` with `Retry-After` exactly like `RateLimitFilter`. Buckets are evicted when idle for 10 minutes (a `ConcurrentHashMap` plus a last-seen timestamp swept on each miss when the map exceeds 10 000 entries — keep it simple and comment why).
  - `CorsConfiguration` (`@Configuration`, `WebMvcConfigurer.addCorsMappings` or a `CorsFilter` bean at `@Order(-5)`, before `PathSanityFilter`): only when `corsOrigins` is non-empty; path `/v1/**`; allowed origins exact; methods `GET,POST,PATCH,DELETE,OPTIONS`; headers `Content-Type, Authorization, Idempotency-Key`; exposed `X-Next-Cursor, Retry-After`; `allowCredentials(false)`; `maxAge(3600)`. **Note:** the merchant key travels in `Authorization: Bearer` (see `ApiKeyAuthFilter`), not `X-Api-Key` as the spec draft said; the spec is corrected in the README wording and the front spec uses `Authorization`.

- [ ] **Step 1: Failing tests**

`ClientIpTest` (plain unit, `MockHttpServletRequest`):

```java
  @Test void withoutAForwardedHeaderItIsTheRemoteAddress()
  @Test void aForwardedHeaderIsTrustedOnlyBehindAPrivateHop()     // remoteAddr 10.0.0.5, XFF "203.0.113.9, 10.0.0.5" -> 203.0.113.9
  @Test void aForwardedHeaderFromAPublicHopIsIgnored()             // remoteAddr 198.51.100.7, XFF "1.2.3.4" -> 198.51.100.7
```

`CheckoutRateLimitFilterTest` (filter unit, `MockFilterChain`): properties with `rateLimitPerMinute = 3`; four requests from one IP to `/v1/checkout/chk_x` → 4th is 429 with `Retry-After`; a request from another IP still passes; `/v1/orders` is not filtered.

`CorsIntegrationTest` (`@SpringBootTest` RANDOM_PORT, properties `gateway.checkout.cors-origins=https://pay.test`): `OPTIONS /v1/checkout/chk_x` with `Origin: https://pay.test`, `Access-Control-Request-Method: POST` → 200/204 and `Access-Control-Allow-Origin: https://pay.test`, `Access-Control-Allow-Headers` contains `Authorization` and `Idempotency-Key`; same with `Origin: https://evil.test` → no `Access-Control-Allow-Origin`; `GET /v1/merchant` with `Origin: https://pay.test` and a valid key → `Access-Control-Allow-Origin` present and `Access-Control-Expose-Headers` contains `X-Next-Cursor`. A second context without the property: no CORS headers at all for `https://pay.test`.

- [ ] **Step 2: Run, expect failures**

- [ ] **Step 3: Implement** (code per the Interfaces block; `CorsConfiguration`):

```java
package com.gateway.app.security;

import com.gateway.app.api.checkout.CheckoutProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Exact origins or nothing. A wildcard would let any site call the API with a key the browser
 * already holds (a merchant who pasted it into an extension), so the default is CORS off, and each
 * front-end environment is listed by hand. Registered before PathSanityFilter so a preflight never
 * reaches the key filters.
 */
@Configuration(proxyBeanMethods = false)
public class CorsConfiguration {
  @Bean
  FilterRegistrationBean<CorsFilter> corsFilter(CheckoutProperties properties) {
    org.springframework.web.cors.CorsConfiguration cors =
        new org.springframework.web.cors.CorsConfiguration();
    cors.setAllowedOrigins(properties.corsOrigins());
    cors.setAllowedMethods(java.util.List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));
    cors.setAllowedHeaders(java.util.List.of("Content-Type", "Authorization", "Idempotency-Key"));
    cors.setExposedHeaders(java.util.List.of("X-Next-Cursor", "Retry-After"));
    cors.setAllowCredentials(false);
    cors.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    if (!properties.corsOrigins().isEmpty()) {
      source.registerCorsConfiguration("/v1/**", cors);
    }

    FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(new CorsFilter(source));
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
    return registration;
  }
}
```

(Rename the class to `CorsSetup` if the name clash with Spring's `CorsConfiguration` reads badly — the `modelsHaveNoSpring` rule exempts `*Configuration`, so keep a `Configuration` suffix: `CorsFilterConfiguration`.)

- [ ] **Step 4: Tests green, verify, commit**

```bash
git add gateway-app
git commit -m "feat(checkout): per-ip rate limit on the public routes and cors by allowlist" -m "X-Forwarded-For is trusted only behind a private hop. CORS is off until GATEWAY_CORS_ORIGINS lists the front's exact origins; a wildcard with a key in the header was rejected." -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Docs — README, DECISOES, architecture note

**Files:**
- Modify: `README.md` (section "Customers, orders, plans and subscriptions" ~line 1144: route table rows + a new subsection "Public checkout"; the "Run"/config section for `GATEWAY_CHECKOUT_BASE_URL`, `GATEWAY_CORS_ORIGINS`)
- Modify: `docs/superpowers/DECISOES.md` (append 4 entries, date-headed `## 2026-10-06 — …`)
- Modify: `docs/architecture.md` (one paragraph: the payer's door and why billing has a `TokenHasher` port)
- Modify: `docs/superpowers/README.md` (index: the spec and this plan)

- [ ] **Step 1: README**

Add rows to the route table: `POST /v1/orders/{id}/checkout-token/rotate` * | 200 with a new `checkout_url` | 409 `ORDER_CLOSED`. Add subsection:

```markdown
### Public checkout

Every order is born with a link for the payer: `checkout_url` in the `POST /v1/orders` response,
`<GATEWAY_CHECKOUT_BASE_URL><token>`. The token is shown **once** (the row keeps only its hash, like an
API key); `GET /v1/orders/{id}` returns `checkout_url: null`. Lost it? `POST /v1/orders/{id}/checkout-token/rotate`
issues a new one and the old link stops working.

The payer's routes need no key — the token is the authorization — and are limited per client IP
(`gateway.checkout.rate-limit-per-minute`, 60):

| Route | Success | Errors |
|---|---|---|
| `GET /v1/checkout/{token}` | 200 `{order_id, merchant_name, amount, currency, description, status, expires_at, methods, active_payment}` | 404 `NOT_FOUND` |
| `POST /v1/checkout/{token}/payments` (same body as `POST /v1/orders/{id}/payments`) | 201 | 410 `CHECKOUT_ORDER_CLOSED`; 409 `ORDER_HAS_ACTIVE_PAYMENT`; 402 `CARD_DECLINED` |
| `GET /v1/checkout/{token}/payments/{id}` | 200 | 404 |
| `POST /v1/checkout/{token}/payments/{id}/cancel` | 200 `CANCELED` (Pix and boleto) | 422 `CHECKOUT_CANNOT_CANCEL_CARD` |

Responses carry no payer data and no provider ids. Payment events of an attempt made through the link
have `source: CHECKOUT`. A browser front on another origin needs `GATEWAY_CORS_ORIGINS` (comma-separated
exact origins; empty = CORS off).
```

Verify every claim against the code (the `source` claim depends on Task 3's finding; if the flows record `API`, drop that sentence and say so in the report).

- [ ] **Step 2: DECISOES** — four entries from spec §6 in the existing format (Portuguese, `Rejeitado:` and `Custo se errado:`), plus one for "`GET` não reexibe o link; rotação em vez de armazenar o token" (the ruling in Task 4) and one for "`Authorization: Bearer`, não `X-Api-Key`, no CORS" if the spec said otherwise.

- [ ] **Step 3: Architecture note and index; commit**

```bash
git add README.md docs
git commit -m "docs(checkout): public checkout routes, token lifecycle, cors and the decisions behind them" -m "Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review notes

- Spec §2 "devolvido em todo GET" conflicts with "o token em claro só existe na resposta"; ruled in Task 4 for hash-only + rotation, recorded in DECISOES (Task 7).
- Spec §4 lists `X-Api-Key`; the gateway authenticates with `Authorization: Bearer` (`ApiKeyAuthFilter`). Task 6 uses the real header and Task 7 documents it; the front spec must say `Authorization`.
- Spec §3's "`Idempotency-Key` optional" is implemented as "not applied" (the filter does not cover the routes) — equivalent for the client; documented.
- `EventSource.CHECKOUT` may not be persisted by the flows today (javadoc on `OrderAttemptService.attempt`); Task 3 surfaces this for a ruling rather than pretending.
