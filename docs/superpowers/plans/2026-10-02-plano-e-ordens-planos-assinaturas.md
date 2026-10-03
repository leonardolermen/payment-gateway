# Plano E — Ordens, planos e assinaturas: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A merchant creates customers, orders with one active payment attempt at a time, plans, and subscriptions in any method; the gateway bills each cycle by itself, retries failed cycles (dunning) without canceling, and tells the merchant by webhook.

**Architecture:** New Maven module `gateway-billing` (schema `billing`) above `gateway-payments`. Billing creates payment attempts through `PaymentFlows` and reacts to payment events through an internal `OutboxListener` called by the app's `OutboxRelay` before the merchant webhook. `payments` only gains an opaque `order_id` and a partial unique index that enforces one active attempt per order. Cycles and dunning are `JobHandler`s in the existing job runner.

**Tech Stack:** Java 25, Spring Boot (JPA, Flyway), PostgreSQL 17 (Testcontainers), ArchUnit, google-java-format via spotless, WireMock for app tests.

**Spec:** `docs/superpowers/specs/2026-10-02-ordens-planos-assinaturas-design.md`

## Global Constraints

- All identifiers, comments and commit messages in English; docs prose in Portuguese in `DECISOES.md` (memory `payment-gateway-codigo-em-ingles`).
- Code standard `C:\Users\leona\.claude\CLAUDE.md`: full-word names, folders by concept, factories for objects with invariants, value objects validate in their canonical constructor, no boolean behaviour parameters, strategy over scattered `switch`, ~300 lines / ~7 constructor deps per class, comments say WHY with evidence, rename/format commits separate from logic.
- Spotless: `./mvnw spotless:apply` before every commit (google-java-format, 2 spaces, 100 columns). Verification command for every task: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -B -o -pl <module> -am verify` (Docker Desktop must be running for Testcontainers; `-o` offline works because every dependency is already in `~/.m2`).
- No component scan in modules: every bean is an explicit `@Bean` in the module's `*Configuration`; `@EntityScan`/`@EnableJpaRepositories` point at the module's own package; `*RepositoryImpl` classes are `@Import`ed.
- ArchUnit (`gateway-app/src/test/java/com/gateway/app/architecture/ArchitectureTest.java`): JPA only under `..persistence..`; entities package-private; classes outside `..persistence..`/`..support..` whose names do not end in `Service|Runner|Gateway|Relay|Properties|Configuration|Events` may not depend on Spring → domain classes take the `UnitOfWork` port (`com.gateway.payments.UnitOfWork`), never `TransactionTemplate`.
- `JobHandlers` and `PaymentFlows` fail startup when an enum value has no handler: a new `JobType` and its handler land in the same task.
- Money path rules: billing never decides whether money moved; it mirrors `payments` events. Nothing is marked paid or failed on doubt.
- Migrations: `billing` schema uses versions `V3xx` (`application.yml` comment reserves 3xx); `payments` additions use `V205`, `V206`.
- Every POST that creates or charges requires `Idempotency-Key` (extend `IdempotencyFilter`).
- Commit trailer: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never push.

## Review Focus

1. **Two attempts racing on one order** (two `POST /v1/orders/{id}/payments` at once): the partial unique index must turn the loser into `409 ORDER_HAS_ACTIVE_PAYMENT`, never a 500. Test in Task 4.
2. **A `payment.completed` for an order already `PAID`** (Pix webhook and card capture landing together): a `DOUBLE_PAYMENT` divergence, never an exception that blocks the relay. Test in Task 5.
3. **A subscription whose anchor day is 31**: February bills on the 28th/29th and March returns to the 31st; a cycle must never be skipped or doubled. Test in Task 8.
4. **The bill job that dies between creating the invoice order and recording the attempt**: a rerun must not charge the card twice (synthetic idempotency key) and must not create a second invoice for the same period. Test in Task 9.
5. **Dunning on a method that changed mid-way** (`PATCH method` from CARD to BOLECODE after the first failure): the retry issues a boleto, not a card charge, and the customer without an address fails with `CUSTOMER_ADDRESS_REQUIRED` recorded as the attempt outcome rather than a crashed job. Test in Task 10.

---

## File structure

```
gateway-billing/
  pom.xml
  src/main/java/com/gateway/billing/
    BillingConfiguration.java            @Bean wiring, @EntityScan/@EnableJpaRepositories("com.gateway.billing")
    BillingProperties.java               gateway.billing.* (dunning.retry-days, billing-hour, card-recurring-enabled)
    BillingEvents.java                   writes billing events to the payments outbox (same table, own payloads)
    customer/
      Customer.java, CustomerFactory.java, CustomerService.java, DocumentMask.java
      persistence/CustomerEntity.java, CustomerJpaRepository.java, CustomerRepository.java, CustomerRepositoryImpl.java
    order/
      Order.java, OrderStatus.java, OrderTransitions.java, OrderPayer.java, OrderService.java,
      OrderPayments.java, OrderSettlement.java, ExpireOrderJob.java
      persistence/OrderEntity.java, OrderJpaRepository.java, OrderRepository.java, OrderRepositoryImpl.java,
                  ProcessedEventEntity.java, ProcessedEventRepository.java (+Impl)
    plan/
      Plan.java, PlanInterval.java, PlanService.java
      persistence/PlanEntity.java, PlanJpaRepository.java, PlanRepository.java, PlanRepositoryImpl.java
    subscription/
      Subscription.java, SubscriptionStatus.java, SubscriptionTransitions.java, BillingCalendar.java,
      BillingPeriod.java, SubscriptionService.java
      billing/BillSubscriptionJob.java, DunningRetryJob.java, DunningSchedule.java, DunningAttempt.java,
              DunningOutcome.java, InvoiceIssuer.java
      persistence/SubscriptionEntity.java, SubscriptionJpaRepository.java, SubscriptionRepository.java,
                  SubscriptionRepositoryImpl.java, DunningAttemptEntity.java, DunningAttemptRepository.java (+Impl)
  src/main/resources/db/migration/billing/
    V301__customers.sql, V302__plans.sql, V303__orders.sql, V304__subscriptions.sql
  src/test/java/com/gateway/billing/
    BillingTestApp.java, support/BillingIntegrationTestBase.java, support/BillingTestConfig.java
    (one test class per production class, mirroring main)

gateway-payments (modified):
  db/migration/payments/V205__cards_customer_id.sql, V206__payments_order_id.sql
  card/SavedCard.java (+customerId), card/SavedCards.java (+adoptByDocumentHash, +listByCustomer, +tokenForRecurring)
  card/persistence/* (new column + queries)
  payment/Payment.java (+orderId), payment/persistence/PaymentEntity.java, PaymentRepositoryImpl.java
  payment/create/CreatePaymentCommand.java (+orderId()), CreatePixPayment/CreateBolecodePayment/CreateCardPayment (+orderId field)
  payment/create/CardChoice.java (+RecurringCard), CardPaymentFlow.java (RecurringCard branch)
  payment/PaymentEvents.java (order_id in paymentJson)
  jobs/JobType.java (+EXPIRE_ORDER, +BILL_SUBSCRIPTION, +DUNNING_RETRY), jobs/Job.java (factories)
  outbox/OutboxListener.java (new interface)

gateway-app (modified):
  pom.xml (+gateway-billing), providers/ProviderWiring.java (@Import BillingConfiguration)
  application.yml + test application-test.yml (flyway schemas/locations + billing)
  outbound/OutboxRelay.java (listeners before merchant delivery)
  api/customer/CustomersController.java + dto/, api/order/OrdersController.java + dto/,
  api/plan/PlansController.java + dto/, api/subscription/SubscriptionsController.java + dto/
  api/support/ErrorHandler.java (409 codes), api/support/IdempotencyFilter.java (new paths)
  test: architecture/ArchitectureTest.java, BillingFlowIntegrationTest.java
scripts/e2e_sandbox.py (+subscription and order sections), README.md, docs/superpowers/DECISOES.md
```

---

### Task 1: Module scaffold, schema, wiring and ArchUnit

**Files:**
- Create: `gateway-billing/pom.xml`
- Create: `gateway-billing/src/main/java/com/gateway/billing/BillingConfiguration.java`
- Create: `gateway-billing/src/main/java/com/gateway/billing/BillingProperties.java`
- Create: `gateway-billing/src/main/resources/db/migration/billing/V300__schema.sql`
- Create: `gateway-billing/src/test/java/com/gateway/billing/BillingTestApp.java`
- Create: `gateway-billing/src/test/java/com/gateway/billing/support/BillingTestConfig.java`
- Create: `gateway-billing/src/test/java/com/gateway/billing/support/BillingIntegrationTestBase.java`
- Create: `gateway-billing/src/test/resources/application.yml`
- Create: `gateway-billing/src/test/java/com/gateway/billing/BillingPropertiesTest.java`
- Modify: `pom.xml` (modules + dependencyManagement)
- Modify: `gateway-payments/pom.xml` (publish a test-jar so billing tests reuse `ServiceTestConfig`, `RecordingCardProvider`, `MutableClock`, `TestSealer`)
- Modify: `gateway-app/pom.xml`, `gateway-app/src/main/java/com/gateway/app/providers/ProviderWiring.java`
- Modify: `gateway-app/src/main/resources/application.yml`, `gateway-app/src/test/resources/application-test.yml`
- Modify: `gateway-app/src/test/java/com/gateway/app/architecture/ArchitectureTest.java`

**Interfaces:**
- Produces: `BillingProperties(List<Integer> dunningRetryDays, int billingHour, boolean cardRecurringEnabled)` bound to `gateway.billing`; `BillingIntegrationTestBase` with `@Autowired protected MutableClock clock; RecordingCardProvider cards; RecordingPixProvider bank; RecordingBoletoProvider boletos; JdbcTemplate jdbc; PaymentService paymentService; PaymentQueries paymentQueries;` and `protected MerchantId merchant` reset per test.

- [ ] **Step 1: Make `gateway-payments` publish a test-jar.** Open `gateway-providers/pom.xml`, copy its `maven-jar-plugin` `<execution>` with goal `test-jar` verbatim into `gateway-payments/pom.xml` under `<build><plugins>`. Add to root `pom.xml` `dependencyManagement`:

```xml
<dependency><groupId>com.gateway</groupId><artifactId>gateway-billing</artifactId><version>${project.version}</version></dependency>
<dependency><groupId>com.gateway</groupId><artifactId>gateway-payments</artifactId><version>${project.version}</version><type>test-jar</type><scope>test</scope></dependency>
```

and `<module>gateway-billing</module>` after `gateway-payments` in `<modules>`.

- [ ] **Step 2: Create `gateway-billing/pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent><groupId>com.gateway</groupId><artifactId>payment-gateway-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
    <artifactId>gateway-billing</artifactId>
    <dependencies>
        <dependency><groupId>com.gateway</groupId><artifactId>gateway-kernel</artifactId></dependency>
        <dependency><groupId>com.gateway</groupId><artifactId>gateway-payments</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
        <dependency><groupId>tools.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
        <dependency><groupId>com.gateway</groupId><artifactId>gateway-payments</artifactId><type>test-jar</type><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

- [ ] **Step 3: Write the failing properties test** `gateway-billing/src/test/java/com/gateway/billing/BillingPropertiesTest.java`

```java
package com.gateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class BillingPropertiesTest {

  @Test
  void defaultsFillEveryMissingValue() {
    BillingProperties properties = new BillingProperties(null, 0, null);

    assertThat(properties.dunningRetryDays()).containsExactly(1, 3, 7);
    assertThat(properties.billingHour()).isEqualTo(3);
    assertThat(properties.cardRecurringEnabled()).isTrue();
  }

  @Test
  void retryDaysMustBeAscendingAndPositive() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> new BillingProperties(List.of(3, 1), 3, true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 4: Run it, expect compilation failure** (`BillingProperties` missing):
`./mvnw -B -o -pl gateway-billing -am test -Dtest=BillingPropertiesTest -Dsurefire.failIfNoSpecifiedTests=false`

- [ ] **Step 5: Create `BillingProperties`**

```java
package com.gateway.billing;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables of billing. Every field has a default so the module runs with no {@code gateway.billing.*}
 * keys: a missing key must never become "retry forever" or "bill at midnight".
 *
 * @param dunningRetryDays days after a failed invoice on which to retry (spec §7); ascending
 * @param billingHour São Paulo hour at which a cycle is billed, after the day turns and outside the
 *     bank's boleto windows (spec §6.1)
 * @param cardRecurringEnabled whether a subscription may charge a stored card without a CVV; false
 *     until the Cielo sandbox proves the token works without SecurityCode (spec §6 step 2)
 */
@ConfigurationProperties("gateway.billing")
public record BillingProperties(
    List<Integer> dunningRetryDays, int billingHour, Boolean cardRecurringEnabled) {

  public BillingProperties {
    if (dunningRetryDays == null || dunningRetryDays.isEmpty()) {
      dunningRetryDays = List.of(1, 3, 7);
    }
    requireAscendingPositive(dunningRetryDays);
    if (billingHour <= 0 || billingHour > 23) {
      billingHour = 3;
    }
    if (cardRecurringEnabled == null) {
      cardRecurringEnabled = true;
    }
  }

  private static void requireAscendingPositive(List<Integer> days) {
    int previous = 0;
    for (Integer day : days) {
      if (day == null || day <= previous) {
        throw new IllegalArgumentException("dunning.retry-days must be ascending positive days");
      }
      previous = day;
    }
  }

  public static BillingProperties defaults() {
    return new BillingProperties(null, 0, null);
  }
}
```

- [ ] **Step 6: Create `BillingConfiguration`** (beans are added task by task; start with the properties only)

```java
package com.gateway.billing;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * What billing exposes, chosen one by one; no component scan, same reasoning as
 * {@code PaymentsConfiguration}. Repositories are {@code @Import}ed as they appear.
 */
@Configuration(proxyBeanMethods = false)
@EntityScan("com.gateway.billing")
@EnableJpaRepositories("com.gateway.billing")
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfiguration {}
```

- [ ] **Step 7: Schema migration** `gateway-billing/src/main/resources/db/migration/billing/V300__schema.sql`

```sql
-- Flyway creates the schema from spring.flyway.schemas; this file only pins the version range (3xx)
-- the application.yml comment reserved for this module and gives the history a first row.
SELECT 1;
```

- [ ] **Step 8: Test context.** `gateway-billing/src/test/resources/application.yml`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    schemas: payments,billing
    locations: classpath:db/migration/payments,classpath:db/migration/billing
```

`BillingTestApp.java`:

```java
package com.gateway.billing;

import com.gateway.billing.support.BillingTestConfig;
import com.gateway.payments.PaymentsConfiguration;
import com.gateway.payments.support.ServiceTestConfig;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = "com.gateway.billing.none")
@Import({PaymentsConfiguration.class, ServiceTestConfig.class, BillingConfiguration.class, BillingTestConfig.class})
public class BillingTestApp {}
```

`support/BillingTestConfig.java` (empty for now; later tasks add fakes):

```java
package com.gateway.billing.support;

import org.springframework.boot.test.context.TestConfiguration;

@TestConfiguration(proxyBeanMethods = false)
public class BillingTestConfig {}
```

`support/BillingIntegrationTestBase.java`:

```java
package com.gateway.billing.support;

import com.gateway.billing.BillingTestApp;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.support.MutableClock;
import com.gateway.payments.support.RecordingBoletoProvider;
import com.gateway.payments.support.RecordingCardProvider;
import com.gateway.payments.support.RecordingPixProvider;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/** One container and one context per module test run; each test uses a fresh merchant. */
@SpringBootTest(classes = BillingTestApp.class)
public abstract class BillingIntegrationTestBase {

  @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired protected MutableClock clock;
  @Autowired protected RecordingPixProvider bank;
  @Autowired protected RecordingBoletoProvider boletos;
  @Autowired protected RecordingCardProvider cards;
  @Autowired protected JdbcTemplate jdbc;
  @Autowired protected PaymentService paymentService;
  @Autowired protected PaymentQueries paymentQueries;

  protected MerchantId merchant;

  @BeforeEach
  void freshMerchant() {
    clock.reset();
    merchant = MerchantId.next();
  }
}
```

Add a smoke test `gateway-billing/src/test/java/com/gateway/billing/BillingContextTest.java`:

```java
package com.gateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.support.BillingIntegrationTestBase;
import org.junit.jupiter.api.Test;

class BillingContextTest extends BillingIntegrationTestBase {
  @Test
  void theBillingSchemaExists() {
    Integer count =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'billing'",
            Integer.class);
    assertThat(count).isEqualTo(1);
  }
}
```

- [ ] **Step 9: App wiring.** `gateway-app/pom.xml`: add `<dependency><groupId>com.gateway</groupId><artifactId>gateway-billing</artifactId></dependency>` after `gateway-payments`. `ProviderWiring.java`: `@Import({ProvidersConfiguration.class, PaymentsConfiguration.class, BillingConfiguration.class})` with the import `com.gateway.billing.BillingConfiguration`. `application.yml` and `application-test.yml`: `schemas: merchants,payments,billing` and `locations: classpath:db/migration/merchants,classpath:db/migration/payments,classpath:db/migration/billing`; update the comment to `(1xx merchants, 2xx payments, 3xx billing)`.

- [ ] **Step 10: ArchUnit.** In `ArchitectureTest.java` replace every `"com.gateway.orders.."` with `"com.gateway.billing.."`; add `"com.gateway.billing.."` to the `resideInAnyPackage` list of `modelsHaveNoSpring`; add:

```java
  @ArchTest
  static final ArchRule paymentsDoesNotImportBilling =
      noClasses()
          .that()
          .resideInAPackage("com.gateway.payments..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("com.gateway.billing..");

  @ArchTest
  static final ArchRule billingOnlyKnowsKernelPaymentsMerchants =
      noClasses()
          .that()
          .resideInAPackage("com.gateway.billing..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("com.gateway.providers..", "com.gateway.app..", "com.barrier..");
```

- [ ] **Step 11: Verify** `./mvnw -B -o -pl gateway-billing,gateway-app -am verify` → `BillingPropertiesTest`, `BillingContextTest` and the whole app suite green (ArchUnit included).

- [ ] **Step 12: Commit**

```bash
git add pom.xml gateway-payments/pom.xml gateway-billing gateway-app/pom.xml gateway-app/src/main/java/com/gateway/app/providers/ProviderWiring.java gateway-app/src/main/resources/application.yml gateway-app/src/test/resources/application-test.yml gateway-app/src/test/java/com/gateway/app/architecture/ArchitectureTest.java
git commit -m "feat(billing): module scaffold, schema and wiring

gateway-billing sits above gateway-payments with its own Flyway schema
(3xx versions, reserved by the application.yml comment). ArchUnit now
forbids payments from importing billing and billing from importing
providers or the app.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Payments groundwork — `customer_id` on cards, `order_id` on payments, `OutboxListener`, new job types

**Files:**
- Create: `gateway-payments/src/main/resources/db/migration/payments/V205__cards_customer_id.sql`
- Create: `gateway-payments/src/main/resources/db/migration/payments/V206__payments_order_id.sql`
- Create: `gateway-payments/src/main/java/com/gateway/payments/outbox/OutboxListener.java`
- Modify: `card/SavedCard.java`, `card/SavedCards.java`, `card/persistence/SavedCardEntity.java`, `SavedCardJpaRepository.java`, `SavedCardRepository.java`, `SavedCardRepositoryImpl.java`
- Modify: `payment/Payment.java`, `payment/persistence/PaymentEntity.java`, `payment/persistence/PaymentRepositoryImpl.java`, `payment/PaymentEvents.java`
- Modify: `payment/create/CreatePaymentCommand.java`, `CreatePixPayment.java`, `CreateBolecodePayment.java`, `CreateCardPayment.java`, `PixPaymentFlow.java`, `BolecodePaymentFlow.java`, `CardPaymentFlow.java` (pass `orderId` into the factories)
- Modify: every caller of the three command constructors (tests included) — add the trailing `orderId` argument as `null`.
- Test: `gateway-payments/src/test/java/com/gateway/payments/card/SavedCardsCustomerTest.java`, `gateway-payments/src/test/java/com/gateway/payments/payment/PaymentOrderIdIntegrationTest.java`

**Interfaces:**
- Produces: `CreatePaymentCommand.orderId()` (`String`, nullable) on all three records as the **last** component; `Payment.orderId()`; `Payment.create/createBolecode/createCard(..., String orderId, Clock clock)`; `SavedCard.customerId()`; `SavedCards.adoptByDocumentHash(MerchantId, ProviderEnvironment, String documentHash, String customerId) -> int`; `SavedCards.listByCustomer(MerchantId, String customerId) -> List<SavedCard>`; `SavedCards.tokenForRecurring(MerchantId, ProviderEnvironment, String cardId) -> CardToken` (no CVV, `CardOnFileUsage.USED`); `OutboxListener { boolean handles(String eventType); void on(OutboxMessage message); }`.
- Not in this task: `JobType` values and `Job` factories. `JobHandlers` fails startup for a type without a handler, so each new type lands in the task that adds its handler (`EXPIRE_ORDER` in Task 6, `BILL_SUBSCRIPTION` in Task 8, `DUNNING_RETRY` in Task 10).

- [ ] **Step 1: Migrations**

`V205__cards_customer_id.sql`:
```sql
-- A stored card belongs to a customer once the merchant registers one (plan E, spec §4.1). Cards
-- saved before customers existed carry only the document hash; CustomerService adopts them by it.
ALTER TABLE payments.cards ADD COLUMN customer_id CHAR(26);
CREATE INDEX idx_cards_customer ON payments.cards (customer_id) WHERE deleted_at IS NULL;
```

`V206__payments_order_id.sql`:
```sql
-- An order groups attempts (plan E, spec §4.3). The partial unique index is the rule "one active
-- attempt per order": the database refuses the second, and OrderPayments turns the violation into
-- 409 ORDER_HAS_ACTIVE_PAYMENT. Opaque to payments: no foreign key, billing owns the table.
ALTER TABLE payments.payments ADD COLUMN order_id CHAR(26);
CREATE INDEX idx_payments_order ON payments.payments (order_id) WHERE order_id IS NOT NULL;
CREATE UNIQUE INDEX uq_payments_order_active ON payments.payments (order_id)
 WHERE order_id IS NOT NULL AND status IN ('CREATED', 'PENDING', 'AUTHORIZED');
```

- [ ] **Step 2: Failing test for card adoption and recurring token** `SavedCardsCustomerTest.java` (extends `ServiceIntegrationTestBase`):

```java
package com.gateway.payments.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CustomerDocumentHash;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SavedCardsCustomerTest extends ServiceIntegrationTestBase {
  @Autowired SavedCards savedCards;

  Payment savedCardPayment() {
    return paymentService.create(
        cardCommand(1000, new CardChoice.NewCard(card(APPROVES), true), null, null));
  }

  @Test
  void aCardSavedBeforeTheCustomerExistedIsAdoptedByDocumentHash() {
    Payment payment = savedCardPayment();
    String customerId = Ulid.next();

    int adopted =
        savedCards.adoptByDocumentHash(
            merchant, ProviderEnvironment.TEST, CustomerDocumentHash.of("12345678901"), customerId);

    assertThat(adopted).isEqualTo(1);
    assertThat(savedCards.listByCustomer(merchant, customerId))
        .extracting(SavedCard::id)
        .containsExactly(payment.card().cardId());
  }

  @Test
  void aRecurringTokenCarriesNoSecurityCode() {
    Payment payment = savedCardPayment();

    CardToken token =
        savedCards.tokenForRecurring(merchant, ProviderEnvironment.TEST, payment.card().cardId());

    assertThat(token.securityCode()).isNull();
    assertThat(token.usage()).isEqualTo(CardOnFileUsage.USED);
  }
}
```

- [ ] **Step 3: Run, expect compile failure** (`adoptByDocumentHash`, `listByCustomer`, `tokenForRecurring` missing).

- [ ] **Step 4: Implement.** `SavedCard` gains `String customerId` after `customerDocumentHash` (update the two constructors' call sites in `SavedCards.save` → `null`, and `SavedCardRepositoryImpl.toDomain` → `entity.customerId`). `SavedCardEntity`: `@Column(name = "customer_id", length = 26) @JdbcTypeCode(SqlTypes.CHAR) String customerId;`. `SavedCardJpaRepository`:

```java
  List<SavedCardEntity> findByMerchantIdAndCustomerIdAndDeletedAtIsNullOrderByCreatedAt(
      String merchantId, String customerId);

  @Modifying
  @Query(
      "update SavedCardEntity c set c.customerId = :customerId where c.merchantId = :merchantId "
          + "and c.environment = :environment and c.customerDocumentHash = :hash "
          + "and c.customerId is null and c.deletedAt is null")
  int adopt(String merchantId, String environment, String hash, String customerId);
```

`SavedCardRepository`: `List<SavedCard> findActiveByCustomer(MerchantId, String customerId);` and `/** Requires a transaction; returns how many rows were adopted. */ int adoptByDocumentHash(MerchantId, ProviderEnvironment, String documentHash, String customerId);` with the impl delegating (`@Transactional(propagation = MANDATORY)` on adopt). `SavedCards`:

```java
  public List<SavedCard> listByCustomer(MerchantId merchantId, String customerId) {
    return cards.findActiveByCustomer(merchantId, customerId);
  }

  /** Joins the caller's transaction: adoption happens with the customer's own insert. */
  public int adoptByDocumentHash(
      MerchantId merchantId, ProviderEnvironment environment, String documentHash, String customerId) {
    if (documentHash == null) {
      return 0;
    }
    return cards.adoptByDocumentHash(merchantId, environment, documentHash, customerId);
  }

  /**
   * A token for a charge nobody is typing a CVV for (a subscription cycle). The CVV rule for the
   * API stays (DECISOES 2026-09-28): this path is reachable only from billing's jobs.
   */
  public CardToken tokenForRecurring(
      MerchantId merchantId, ProviderEnvironment environment, String cardId) {
    return tokenFor(merchantId, environment, cardId, null);
  }
```

(`tokenFor` already builds `new CardToken(token, brand, USED, securityCode)`; passing `null` is what the test asserts. Check `CardToken` is a plain record without a null check on `securityCode`; if it has one, relax it with a comment pointing to this method.)

- [ ] **Step 5: `order_id` on `Payment`.** Add `private final String orderId;` to `Payment`, a `String orderId` parameter **before `Clock clock`** on `create`, `createBolecode`, `createCard` and on each `rehydrate` overload; accessor `public String orderId()`. `PaymentEntity`: `@Column(name = "order_id", length = 26) @JdbcTypeCode(SqlTypes.CHAR) String orderId;` mapped both ways in `PaymentRepositoryImpl`. `PaymentEvents.paymentJson`: `json.put("order_id", payment.orderId());` after `reference`. Commands: add `String orderId` as the last component of `CreatePixPayment`, `CreateBolecodePayment`, `CreateCardPayment` and `String orderId();` to the sealed interface; the three flows pass `command.orderId()` into the factory. Fix every constructor call site (`grep -rn "new CreatePixPayment\|new CreateBolecodePayment\|new CreateCardPayment" --include=*.java gateway-*/src`) by appending `null` (DTOs in gateway-app included).

- [ ] **Step 6: Failing integration test for the index** `PaymentOrderIdIntegrationTest.java` (extends `ServiceIntegrationTestBase`):

```java
package com.gateway.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.create.CreatePixPayment;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class PaymentOrderIdIntegrationTest extends ServiceIntegrationTestBase {

  CreatePixPayment pixFor(String orderId) {
    return new CreatePixPayment(
        merchant, ProviderEnvironment.TEST, Money.brl(1000), "o-1", null, null, null, orderId);
  }

  @Test
  void theOrderIdTravelsToTheRowAndTheEvent() {
    String orderId = Ulid.next();

    Payment payment = paymentService.create(pixFor(orderId));

    assertThat(paymentQueries.get(merchant, payment.id()).orderId()).isEqualTo(orderId);
    assertThat(outboxPayload(payment.id(), "payment.pending")).contains("\"order_id\":\"" + orderId);
  }

  @Test
  void aSecondActiveAttemptOnTheSameOrderIsRefusedByTheDatabase() {
    String orderId = Ulid.next();
    paymentService.create(pixFor(orderId));

    assertThatThrownBy(() -> paymentService.create(pixFor(orderId)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
```

- [ ] **Step 7: `OutboxListener`** `gateway-payments/src/main/java/com/gateway/payments/outbox/OutboxListener.java`:

```java
package com.gateway.payments.outbox;

/**
 * An in-process consumer of the outbox, called by the app's relay before the merchant's webhook
 * (spec §8). A listener that throws keeps the row unsent, so the next pass calls it again: every
 * implementation must be idempotent by {@link OutboxMessage#id()}.
 */
public interface OutboxListener {
  boolean handles(String eventType);

  void on(OutboxMessage message);
}
```

- [ ] **Step 8: Run the module**: `./mvnw -B -o -pl gateway-payments -am verify` → green, including the two new tests. Then `./mvnw -B -o -pl gateway-app -am verify` (constructor call sites in app DTOs and tests).

- [ ] **Step 9: Commit**

```bash
git add gateway-payments gateway-app/src
git commit -m "feat(payments): order_id on payments, customer_id on cards, outbox listener port

The partial unique index on payments(order_id) for active statuses is
the rule one-attempt-per-order (plan E §4.3); billing turns its
violation into 409. Stored cards can be adopted by a customer through
the document hash, and a recurring token carries no CVV for billing's
jobs only. OutboxListener is the port the app relay calls before the
merchant webhook.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Customers

**Files:**
- Create: `gateway-billing/src/main/resources/db/migration/billing/V301__customers.sql`
- Create: `customer/Customer.java`, `customer/CustomerFactory.java`, `customer/CustomerAddress.java`, `customer/DocumentMask.java`, `customer/CustomerService.java`
- Create: `customer/persistence/CustomerEntity.java`, `CustomerJpaRepository.java`, `CustomerRepository.java`, `CustomerRepositoryImpl.java`
- Create: `BillingEvents.java`
- Modify: `BillingConfiguration.java`
- Test: `customer/CustomerFactoryTest.java`, `customer/DocumentMaskTest.java`, `customer/CustomerServiceIntegrationTest.java`

**Interfaces:**
- Produces: `Customer` (immutable record-like class with `id, merchantId, environment, name (PersonName), document (Document), email, address (CustomerAddress nullable), version, createdAt, updatedAt, deletedAt`); `CustomerFactory.fromRequest(MerchantId, ProviderEnvironment, String name, String document, String email, CustomerAddress.Raw address, Clock) → Customer` (throws `InvalidValue` with the field name as `DomainException("CUSTOMER_INVALID", "customer.<field> " + reason)`); `CustomerService.create(Customer) → Customer` (409 `CUSTOMER_EXISTS`), `get(MerchantId, String id)`, `findByDocument(MerchantId, ProviderEnvironment, String document) → Optional<Customer>`, `update(MerchantId, id, name, email, address)`, `delete(MerchantId, id)` (409 `CUSTOMER_HAS_ACTIVE_SUBSCRIPTION` — the check is a port `ActiveSubscriptionsCheck` implemented in Task 8; until then a bean that returns false), `cardsOf(MerchantId, id)`; `CustomerAddress(street, district, city, Uf state, ZipCode zip)` with `static CustomerAddress of(Raw)`; `DocumentMask.mask(Document)` → `***.***.247-25` / `**.***.***/0001-**`; `BillingEvents.emit(MerchantId, String type, String aggregateId, String partitionKey, Map<String,Object> body)`.

- [ ] **Step 1: Migration** `V301__customers.sql`

```sql
-- Customers are the merchant's (spec §4.1). The document is sealed with the merchants' envelope
-- (AAD merchant|customer) and searched by its SHA-256, the same hash payments keeps on cards, so a
-- card saved before the customer existed can be adopted. Address is optional: only a Bolecode
-- subscription needs it, and the service refuses that subscription instead of the customer.
CREATE TABLE billing.customers (
    id                  CHAR(26)     PRIMARY KEY,
    merchant_id         CHAR(26)     NOT NULL,
    environment         VARCHAR(10)  NOT NULL,
    name                VARCHAR(120) NOT NULL,
    document_ciphertext BYTEA        NOT NULL,
    document_hash       CHAR(64)     NOT NULL,
    document_kind       VARCHAR(4)   NOT NULL,
    email               VARCHAR(254),
    address             JSONB,
    version             BIGINT       NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL,
    updated_at          TIMESTAMPTZ  NOT NULL,
    deleted_at          TIMESTAMPTZ
);
CREATE UNIQUE INDEX uq_customers_document
    ON billing.customers (merchant_id, environment, document_hash) WHERE deleted_at IS NULL;
```

- [ ] **Step 2: Failing unit tests**

`DocumentMaskTest.java`:
```java
package com.gateway.billing.customer;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.party.Document;
import org.junit.jupiter.api.Test;

class DocumentMaskTest {
  @Test
  void aCpfShowsOnlyItsMiddleBlockAndCheckDigits() {
    assertThat(DocumentMask.mask(Document.of("529.982.247-25"))).isEqualTo("***.982.***-25");
  }

  @Test
  void aCnpjShowsOnlyItsBranchAndCheckDigits() {
    assertThat(DocumentMask.mask(Document.of("08.867.659/0001-51"))).isEqualTo("**.***.***/0001-51");
  }
}
```

`CustomerFactoryTest.java`:
```java
package com.gateway.billing.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class CustomerFactoryTest {
  static final MerchantId MERCHANT = MerchantId.next();

  @Test
  void normalisesTheDocumentAndKeepsTheAddressOptional() {
    Customer customer =
        CustomerFactory.fromRequest(
            MERCHANT, ProviderEnvironment.TEST, "Ana Silva", "529.982.247-25", null, null,
            Clock.systemUTC());

    assertThat(customer.document().digits()).isEqualTo("52998224725");
    assertThat(customer.address()).isNull();
    assertThat(customer.version()).isEqualTo(1L);
  }

  @Test
  void namesTheFieldTheClientSent() {
    assertThatThrownBy(
            () ->
                CustomerFactory.fromRequest(
                    MERCHANT, ProviderEnvironment.TEST, "Ana", "123", null,
                    new CustomerAddress.Raw("Rua A", "Centro", "SP", "XX", "01310100"),
                    Clock.systemUTC()))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("document");
  }

  @Test
  void anAddressFieldErrorNamesItsPath() {
    assertThatThrownBy(
            () ->
                CustomerFactory.fromRequest(
                    MERCHANT, ProviderEnvironment.TEST, "Ana", "52998224725", null,
                    new CustomerAddress.Raw("Rua A", "Centro", "SP", "XX", "01310100"),
                    Clock.systemUTC()))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("address.state");
  }
}
```

- [ ] **Step 3: Run, expect compile failures.**

- [ ] **Step 4: Implement the domain.**

`CustomerAddress.java`:
```java
package com.gateway.billing.customer;

import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.InvalidValue;

/** The Bolecode's payer address, validated once here and never again. */
public record CustomerAddress(String street, String district, String city, Uf state, ZipCode zip) {

  /** Exactly as the merchant sent it; {@link #of} is the door. */
  public record Raw(String street, String district, String city, String state, String zip) {}

  public static CustomerAddress of(Raw raw) {
    requireText(raw.street(), "street");
    requireText(raw.district(), "district");
    requireText(raw.city(), "city");
    return new CustomerAddress(
        raw.street(), raw.district(), raw.city(),
        field("state", () -> Uf.of(raw.state())),
        field("zip", () -> ZipCode.of(raw.zip())));
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new DomainException("CUSTOMER_INVALID", "customer.address." + name + " is required");
    }
  }

  private static <T> T field(String name, java.util.function.Supplier<T> parse) {
    try {
      return parse.get();
    } catch (InvalidValue invalid) {
      throw new DomainException(
          "CUSTOMER_INVALID", "customer.address." + name + " " + invalid.reason());
    }
  }
}
```

`DocumentMask.java`:
```java
package com.gateway.billing.customer;

import com.gateway.kernel.party.Document;

/** What a response shows: enough to recognise, not enough to reuse. */
public final class DocumentMask {
  private DocumentMask() {}

  public static String mask(Document document) {
    String digits = document.digits();
    if (document.isCompany()) {
      return "**.***.***/" + digits.substring(8, 12) + "-" + digits.substring(12);
    }
    return "***." + digits.substring(3, 6) + ".***-" + digits.substring(9);
  }
}
```

`Customer.java`:
```java
package com.gateway.billing.customer;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Instant;

public record Customer(
    String id,
    MerchantId merchantId,
    ProviderEnvironment environment,
    PersonName name,
    Document document,
    String email,
    CustomerAddress address,
    long version,
    Instant createdAt,
    Instant updatedAt,
    Instant deletedAt) {

  public Customer withChanges(PersonName newName, String newEmail, CustomerAddress newAddress, Instant at) {
    return new Customer(
        id, merchantId, environment, newName, document, newEmail, newAddress, version + 1,
        createdAt, at, deletedAt);
  }

  public Customer deleted(Instant at) {
    return new Customer(
        id, merchantId, environment, name, document, email, address, version + 1, createdAt, at, at);
  }

  public boolean hasAddress() {
    return address != null;
  }
}
```

`CustomerFactory.java`:
```java
package com.gateway.billing.customer;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.InvalidValue;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import java.time.Instant;

public final class CustomerFactory {
  private CustomerFactory() {}

  public static Customer fromRequest(
      MerchantId merchantId,
      ProviderEnvironment environment,
      String name,
      String document,
      String email,
      CustomerAddress.Raw address,
      Clock clock) {
    PersonName personName = field("name", () -> PersonName.of(name));
    Document parsedDocument = field("document", () -> Document.of(document));
    CustomerAddress parsedAddress = address == null ? null : CustomerAddress.of(address);
    Instant now = clock.instant();

    return new Customer(
        Ulid.next(), merchantId, environment, personName, parsedDocument, blankToNull(email),
        parsedAddress, 1L, now, now, null);
  }

  static <T> T field(String name, java.util.function.Supplier<T> parse) {
    try {
      return parse.get();
    } catch (InvalidValue invalid) {
      throw new DomainException("CUSTOMER_INVALID", "customer." + name + " " + invalid.reason());
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
```

- [ ] **Step 5: Persistence.** `CustomerRepository` port:

```java
package com.gateway.billing.customer.persistence;

import com.gateway.billing.customer.Customer;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.util.Optional;

public interface CustomerRepository {
  /** Requires a transaction. Throws DataIntegrityViolationException on a duplicate document. */
  void insert(Customer customer, byte[] documentCiphertext, String documentHash);

  /** Optimistic: writes only when the stored version is {@code customer.version() - 1}. */
  boolean update(Customer customer);

  Optional<Customer> findActive(MerchantId merchantId, String id);

  Optional<Customer> findActiveByDocumentHash(
      MerchantId merchantId, ProviderEnvironment environment, String documentHash);
}
```

`CustomerEntity` (package-private, `@Table(name = "customers", schema = "billing")`): columns as in V301, `address` as `@JdbcTypeCode(SqlTypes.JSON) String address` (store the `CustomerAddress` as JSON `{"street","district","city","state","zip"}` through a small `tools.jackson` `JsonMapper` in the impl), `@JdbcTypeCode(SqlTypes.CHAR)` on CHAR columns. `CustomerJpaRepository`: `Optional<CustomerEntity> findByIdAndMerchantIdAndDeletedAtIsNull(String, String)`, `Optional<CustomerEntity> findByMerchantIdAndEnvironmentAndDocumentHashAndDeletedAtIsNull(String, String, String)`, and
```java
  @Modifying
  @Query("update CustomerEntity c set c.name = :name, c.email = :email, c.address = :address, "
      + "c.version = :version, c.updatedAt = :updatedAt, c.deletedAt = :deletedAt "
      + "where c.id = :id and c.version = :expectedVersion")
  int updateIfVersion(String id, long expectedVersion, String name, String email, String address,
      long version, java.time.Instant updatedAt, java.time.Instant deletedAt);
```
`CustomerRepositoryImpl` (`@Repository`, `@PersistenceContext EntityManager`): `insert` persists (`Propagation.MANDATORY`); `findActive*` open the document with the `Sealer` using context `merchantId + "|customer"`; `update` is `@Transactional` and returns `updateIfVersion(...) == 1`. The impl takes `Sealer` in its constructor (persistence may see Spring; the sealing stays in one place so the domain never holds ciphertext).

- [ ] **Step 6: `BillingEvents`** `gateway-billing/src/main/java/com/gateway/billing/BillingEvents.java`:

```java
package com.gateway.billing;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.outbox.persistence.OutboxRepository;
import java.time.Clock;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * Billing writes to the same outbox as payments: one relay, one ordering per partition, one
 * webhook pipeline. Payloads are built by hand as maps, never by serialising the aggregate (same
 * rule as PaymentEvents).
 */
public class BillingEvents {
  private final OutboxRepository outbox;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public BillingEvents(OutboxRepository outbox, Clock clock) {
    this.outbox = outbox;
    this.clock = clock;
  }

  /** Same transaction as the caller. */
  public void emit(
      MerchantId merchantId,
      String type,
      String aggregateId,
      String partitionKey,
      Map<String, Object> body) {
    outbox.append(
        new OutboxMessage(
            Ulid.next(), merchantId, aggregateId, partitionKey, type,
            json.writeValueAsString(body), "PENDING", null, clock.instant()));
  }
}
```

- [ ] **Step 7: Failing integration test** `CustomerServiceIntegrationTest.java` (extends `BillingIntegrationTestBase`):

```java
package com.gateway.billing.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.card.SavedCards;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardCustomerData;
import com.gateway.payments.payment.create.CardDataFactory;
import com.gateway.payments.payment.create.CreateCardPayment;
import com.gateway.kernel.money.Money;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CustomerServiceIntegrationTest extends BillingIntegrationTestBase {
  @Autowired CustomerService customers;
  @Autowired SavedCards savedCards;

  Customer ana() {
    return customers.create(
        CustomerFactory.fromRequest(
            merchant, ProviderEnvironment.TEST, "Ana Silva", "529.982.247-25",
            "ana@example.com", null, clock));
  }

  @Test
  void createsReadsBackAndEmits() {
    Customer created = ana();

    Customer found = customers.get(merchant, created.id());

    assertThat(found.name().value()).isEqualTo("Ana Silva");
    assertThat(found.document().digits()).isEqualTo("52998224725");
    assertThat(jdbc.queryForList(
            "SELECT event_type FROM payments.outbox WHERE aggregate_id = ?", String.class, created.id()))
        .containsExactly("customer.created");
  }

  @Test
  void theSameDocumentTwiceIsAConflictNamingTheExistingId() {
    Customer first = ana();

    assertThatThrownBy(this::ana)
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("CUSTOMER_EXISTS");
    assertThat(customers.findByDocument(merchant, ProviderEnvironment.TEST, "52998224725"))
        .map(Customer::id)
        .contains(first.id());
  }

  @Test
  void aCardSavedEarlierWithTheSameDocumentIsAdopted() {
    Payment payment =
        paymentService.create(
            new CreateCardPayment(
                merchant, ProviderEnvironment.TEST, Money.brl(1000), "o-1", null,
                new CardChoice.NewCard(
                    CardDataFactory.from(
                        "4024007153763171", "ANA SILVA", "12/2030", "123", null,
                        YearMonth.of(2026, 9)),
                    true),
                null, null, "LOJA", new CardCustomerData("Ana Silva", "52998224725", null), null));

    Customer created = ana();

    assertThat(customers.cardsOf(merchant, created.id()))
        .extracting(card -> card.id())
        .containsExactly(payment.card().cardId());
  }

  @Test
  void updateKeepsTheDocumentAndBumpsTheVersion() {
    Customer created = ana();

    Customer updated =
        customers.update(merchant, created.id(), "Ana S.", null,
            new CustomerAddress.Raw("Rua A 1", "Centro", "Sao Paulo", "sp", "01310-100"));

    assertThat(updated.version()).isEqualTo(2L);
    assertThat(updated.address().state().value()).isEqualTo("SP");
    assertThat(updated.document()).isEqualTo(created.document());
  }

  @Test
  void deleteIsLogicalAndHidesTheCustomer() {
    Customer created = ana();

    customers.delete(merchant, created.id());

    assertThatThrownBy(() -> customers.get(merchant, created.id()))
        .isInstanceOf(DomainException.class);
  }
}
```

- [ ] **Step 8: `CustomerService`**

```java
package com.gateway.billing.customer;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.customer.persistence.CustomerRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.security.Sealer;
import com.gateway.kernel.security.Sha256;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.card.SavedCard;
import com.gateway.payments.card.SavedCards;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;

public class CustomerService {
  private final CustomerRepository customers;
  private final SavedCards savedCards;
  private final ActiveSubscriptionsCheck activeSubscriptions;
  private final BillingEvents events;
  private final Sealer sealer;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public CustomerService(
      CustomerRepository customers,
      SavedCards savedCards,
      ActiveSubscriptionsCheck activeSubscriptions,
      BillingEvents events,
      Sealer sealer,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.customers = customers;
    this.savedCards = savedCards;
    this.activeSubscriptions = activeSubscriptions;
    this.events = events;
    this.sealer = sealer;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public Customer create(Customer customer) {
    String hash = documentHash(customer.document());
    byte[] sealed =
        sealer.seal(
            customer.document().digits().getBytes(StandardCharsets.UTF_8),
            context(customer.merchantId()));

    try {
      return unitOfWork.inTransaction(
          () -> {
            customers.insert(customer, sealed, hash);
            // Cards saved before this customer existed carry only the hash (plan D); they are his.
            savedCards.adoptByDocumentHash(
                customer.merchantId(), customer.environment(), hash, customer.id());
            events.emit(
                customer.merchantId(), "customer.created", customer.id(), customer.id(),
                json(customer));
            return customer;
          });
    } catch (DataIntegrityViolationException duplicate) {
      Customer existing =
          customers
              .findActiveByDocumentHash(customer.merchantId(), customer.environment(), hash)
              .orElseThrow(() -> duplicate);
      throw new CustomerExistsException(existing.id());
    }
  }

  public Customer get(MerchantId merchantId, String id) {
    return customers.findActive(merchantId, id).orElseThrow(() -> new NotFoundException("customer", id));
  }

  public Optional<Customer> findByDocument(
      MerchantId merchantId, ProviderEnvironment environment, String document) {
    return customers.findActiveByDocumentHash(
        merchantId, environment, documentHash(Document.of(document)));
  }

  public Customer update(
      MerchantId merchantId, String id, String name, String email, CustomerAddress.Raw address) {
    Customer current = get(merchantId, id);
    PersonName newName = name == null ? current.name() : CustomerFactory.field("name", () -> PersonName.of(name));
    CustomerAddress newAddress = address == null ? current.address() : CustomerAddress.of(address);
    Customer changed = current.withChanges(newName, email == null ? current.email() : email, newAddress, clock.instant());

    return unitOfWork.inTransaction(
        () -> {
          if (!customers.update(changed)) {
            throw new DomainException("CONFLICT", "customer " + id + " changed concurrently");
          }
          events.emit(merchantId, "customer.updated", id, id, json(changed));
          return changed;
        });
  }

  public void delete(MerchantId merchantId, String id) {
    Customer current = get(merchantId, id);
    if (activeSubscriptions.hasActive(merchantId, id)) {
      throw new DomainException(
          "CUSTOMER_HAS_ACTIVE_SUBSCRIPTION", "customer " + id + " has an active subscription");
    }

    unitOfWork.run(() -> customers.update(current.deleted(clock.instant())));
  }

  public List<SavedCard> cardsOf(MerchantId merchantId, String id) {
    get(merchantId, id);
    return savedCards.listByCustomer(merchantId, id);
  }

  static String documentHash(Document document) {
    return Sha256.hex(document.digits());
  }

  private static String context(MerchantId merchantId) {
    return merchantId.value() + "|customer";
  }

  static Map<String, Object> json(Customer customer) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", customer.id());
    body.put("name", customer.name().value());
    body.put("document", DocumentMask.mask(customer.document()));
    body.put("email", customer.email());
    body.put("has_address", customer.hasAddress());
    body.put("created_at", customer.createdAt().toString());
    return body;
  }
}
```

Check `Sha256` in `gateway-kernel/src/main/java/com/gateway/kernel/security/Sha256.java` for its method name; if it is not `hex(String)`, use what exists (the test in Task 2 proves the hash must equal `CustomerDocumentHash.of(digits)`; both are SHA-256 hex of the ASCII digits). `CustomerExistsException`:

```java
package com.gateway.billing.customer;

import com.gateway.kernel.errors.DomainException;

/** 409 at the edge, with the existing id so the merchant can use it instead of retrying. */
public class CustomerExistsException extends DomainException {
  private final String customerId;

  public CustomerExistsException(String customerId) {
    super("CUSTOMER_EXISTS", "a customer with this document already exists: " + customerId);
    this.customerId = customerId;
  }

  public String customerId() {
    return customerId;
  }
}
```

`ActiveSubscriptionsCheck` (port, implemented in Task 8):

```java
package com.gateway.billing.customer;

import com.gateway.kernel.ids.MerchantId;

public interface ActiveSubscriptionsCheck {
  boolean hasActive(MerchantId merchantId, String customerId);
}
```

- [ ] **Step 9: Wire in `BillingConfiguration`**: `@Import(CustomerRepositoryImpl.class)`; beans `BillingEvents billingEvents(OutboxRepository outbox, Clock clock)`, `ActiveSubscriptionsCheck noActiveSubscriptionsYet()` returning `(merchant, id) -> false` **with a comment that Task 8 replaces it**, `CustomerService customerService(CustomerRepository, SavedCards, ActiveSubscriptionsCheck, BillingEvents, Sealer, UnitOfWork, Clock)`.

- [ ] **Step 10: Run** `./mvnw -B -o -pl gateway-billing -am verify` → green.

- [ ] **Step 11: Commit** `feat(billing): customers with sealed document and card adoption`.

---

### Task 4: Orders — model, repository, service and `OrderPayments`

**Files:**
- Create: `gateway-billing/src/main/resources/db/migration/billing/V303__orders.sql`
- Create: `order/Order.java`, `order/OrderStatus.java`, `order/OrderTransitions.java`, `order/OrderPayer.java`, `order/OrderFactory.java`, `order/OrderService.java`, `order/OrderPayments.java`, `order/AttemptRequest.java`
- Create: `order/persistence/OrderEntity.java`, `OrderJpaRepository.java`, `OrderRepository.java`, `OrderRepositoryImpl.java`
- Modify: `BillingConfiguration.java`
- Test: `order/OrderTransitionsTest.java`, `order/OrderFactoryTest.java`, `order/OrderServiceIntegrationTest.java`, `order/OrderPaymentsIntegrationTest.java`

**Interfaces:**
- Consumes: `CustomerService.get`, `PaymentFlows.forMethod(PaymentMethod).create(CreatePaymentCommand)`, `PaymentCancellation.cancel(MerchantId, String paymentId)`, `PaymentQueries.get`, `SavedCards.tokenForRecurring` (Task 2), `BillingEvents`.
- Produces: `Order` with `id, merchantId, environment, customerId (nullable), payer (OrderPayer nullable), amount, currency, reference, description, status, paidPaymentId, paidAt, expiresAt, subscriptionId, invoiceNumber, periodStart, periodEnd, version, createdAt, updatedAt` and methods `markPaid(String paymentId, Instant at)`, `markCanceled(Instant)`, `markExpired(Instant)`, `isOpen()`, `isInvoice()`; `OrderTransitions.allowed(OrderStatus from, OrderStatus to)`; `OrderPayer(PersonName name, String documentHash, Document document (nullable, only in memory), String email, CustomerAddress address)`; `OrderFactory.standalone(MerchantId, ProviderEnvironment, Money, String reference, String description, String customerId, OrderPayer payer, Instant expiresAt, Clock)` and `OrderFactory.invoice(Subscription-ish args: MerchantId, ProviderEnvironment, String customerId, Money, String subscriptionId, int invoiceNumber, LocalDate periodStart, LocalDate periodEnd, Instant expiresAt, Clock)`; `OrderService.create(Order) → Order`, `get(MerchantId, id)`, `listByReference(MerchantId, reference, limit)`, `cancel(MerchantId, id) → Order` (409 `ORDER_CLOSED`, 409 `ALREADY_PAID` if the active attempt turns out paid), `attemptsOf(MerchantId, id) → List<Payment>`; `OrderRepository.findOpenExpiredBefore(Instant, int limit)`, `findActiveAttempt(orderId) → Optional<Payment>` (via `PaymentQueries`/`PaymentRepository.findActiveByOrder` — add `Optional<Payment> findActiveByOrder(String orderId)` to `payments` `PaymentRepository` + `PaymentQueries.activeAttempt(String orderId)` in this task, it is a one-query addition); `OrderPayments.attempt(Order order, AttemptRequest request, EventSource by) → Payment` where `AttemptRequest` is a sealed interface `permits PixAttempt(Integer expiresInSeconds), BolecodeAttempt(LocalDate dueDate, Integer paymentLimitDays), CardAttempt(CardChoice card, Integer installments, Boolean capture, String softDescriptor), RecurringCardAttempt(String cardId, Integer installments)`; exception `OrderHasActivePaymentException(orderId, paymentId)` (`DomainException` code `ORDER_HAS_ACTIVE_PAYMENT`).

- [ ] **Step 1: Migration** `V303__orders.sql` (numbered 303 because 302 is plans, Task 7; Flyway accepts out-of-order creation of files as long as versions are unique and increasing at first run — both are new in this plan, so order of commits does not matter):

```sql
-- An order is a sale: value, who pays, and the attempts that try to settle it (spec §4.2).
-- customer_id XOR payer: a registered customer, or the inline payer of a checkout without one.
-- payer keeps the document as a hash only (the Bolecode needs name and address, nothing needs
-- the number). The invoice columns are set only when the order is a subscription cycle.
CREATE TABLE billing.orders (
    id              CHAR(26)     PRIMARY KEY,
    merchant_id     CHAR(26)     NOT NULL,
    environment     VARCHAR(10)  NOT NULL,
    customer_id     CHAR(26),
    payer           JSONB,
    amount          BIGINT       NOT NULL,
    currency        CHAR(3)      NOT NULL,
    reference       VARCHAR(100),
    description     VARCHAR(200),
    status          VARCHAR(10)  NOT NULL,
    paid_payment_id CHAR(26),
    paid_at         TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    subscription_id CHAR(26),
    invoice_number  INTEGER,
    period_start    DATE,
    period_end      DATE,
    version         BIGINT       NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    CONSTRAINT ck_orders_who_pays CHECK ((customer_id IS NULL) <> (payer IS NULL))
);
CREATE INDEX idx_orders_merchant_reference ON billing.orders (merchant_id, environment, reference);
CREATE INDEX idx_orders_merchant_created ON billing.orders (merchant_id, environment, created_at DESC);
CREATE UNIQUE INDEX uq_orders_invoice ON billing.orders (subscription_id, invoice_number)
 WHERE subscription_id IS NOT NULL;
CREATE INDEX idx_orders_open_expiry ON billing.orders (expires_at) WHERE status = 'OPEN' AND expires_at IS NOT NULL;

-- Idempotency of the internal outbox consumer (spec §8): one row per event the billing module
-- already reacted to, written in the same transaction as the reaction.
CREATE TABLE billing.processed_events (
    event_id     CHAR(26)    PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);
```

- [ ] **Step 2: Failing unit tests**

`OrderTransitionsTest.java`:
```java
package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrderTransitionsTest {
  @Test
  void openGoesToEveryFinalState() {
    assertThat(OrderTransitions.allowed(OrderStatus.OPEN, OrderStatus.PAID)).isTrue();
    assertThat(OrderTransitions.allowed(OrderStatus.OPEN, OrderStatus.CANCELED)).isTrue();
    assertThat(OrderTransitions.allowed(OrderStatus.OPEN, OrderStatus.EXPIRED)).isTrue();
  }

  @Test
  void finalStatesNeverMove() {
    for (OrderStatus from : new OrderStatus[] {OrderStatus.PAID, OrderStatus.CANCELED, OrderStatus.EXPIRED}) {
      for (OrderStatus to : OrderStatus.values()) {
        assertThat(OrderTransitions.allowed(from, to)).as(from + "->" + to).isFalse();
      }
    }
  }
}
```

`OrderFactoryTest.java`:
```java
package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class OrderFactoryTest {
  static final MerchantId MERCHANT = MerchantId.next();

  @Test
  void aStandaloneOrderNeedsExactlyOneOfCustomerAndPayer() {
    assertThatThrownBy(
            () ->
                OrderFactory.standalone(
                    MERCHANT, ProviderEnvironment.TEST, Money.brl(1000), "r", null, null, null, null,
                    Clock.systemUTC()))
        .hasMessageContaining("customer_id or customer");
  }

  @Test
  void aStandaloneOrderWithACustomerStartsOpenAtVersionOne() {
    Order order =
        OrderFactory.standalone(
            MERCHANT, ProviderEnvironment.TEST, Money.brl(1000), "r", null, "01CUSTOMER", null, null,
            Clock.systemUTC());

    assertThat(order.status()).isEqualTo(OrderStatus.OPEN);
    assertThat(order.version()).isEqualTo(1L);
    assertThat(order.isInvoice()).isFalse();
  }
}
```

- [ ] **Step 3: Run, expect compile failure.**

- [ ] **Step 4: Domain.**

`OrderStatus.java`: `public enum OrderStatus { OPEN, PAID, CANCELED, EXPIRED }`.

`OrderTransitions.java`:
```java
package com.gateway.billing.order;

import java.util.Set;

/** The state machine as a table (spec §4.2): OPEN is the only state that moves; the rest is final. */
public final class OrderTransitions {
  public record Transition(OrderStatus from, OrderStatus to) {}

  private static final Set<Transition> TABLE =
      Set.of(
          new Transition(OrderStatus.OPEN, OrderStatus.PAID),
          new Transition(OrderStatus.OPEN, OrderStatus.CANCELED),
          new Transition(OrderStatus.OPEN, OrderStatus.EXPIRED));

  private OrderTransitions() {}

  public static boolean allowed(OrderStatus from, OrderStatus to) {
    return TABLE.contains(new Transition(from, to));
  }
}
```

`OrderPayer.java`:
```java
package com.gateway.billing.order;

import com.gateway.billing.customer.CustomerAddress;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;

/**
 * The inline payer of an order without a registered customer. {@code document} is present only
 * while the request is in memory: the row keeps {@code documentHash}, name, email and address,
 * which is all a boleto issue and a card customer need (spec §4.2).
 */
public record OrderPayer(
    PersonName name, String documentHash, Document document, String email, CustomerAddress address) {

  public OrderPayer forStorage() {
    return new OrderPayer(name, documentHash, null, email, address);
  }
}
```

`Order.java` (a class, not a record: it mutates through the table like `Payment`):
```java
package com.gateway.billing.order;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Instant;
import java.time.LocalDate;

public final class Order {
  private final String id;
  private final MerchantId merchantId;
  private final ProviderEnvironment environment;
  private final String customerId;
  private final OrderPayer payer;
  private final Money amount;
  private final String reference;
  private final String description;
  private final Instant expiresAt;
  private final String subscriptionId;
  private final Integer invoiceNumber;
  private final LocalDate periodStart;
  private final LocalDate periodEnd;
  private final Instant createdAt;

  private OrderStatus status;
  private String paidPaymentId;
  private Instant paidAt;
  private long version;
  private Instant updatedAt;

  Order(
      String id, MerchantId merchantId, ProviderEnvironment environment, String customerId,
      OrderPayer payer, Money amount, String reference, String description, Instant expiresAt,
      String subscriptionId, Integer invoiceNumber, LocalDate periodStart, LocalDate periodEnd,
      Instant createdAt) {
    this.id = id;
    this.merchantId = merchantId;
    this.environment = environment;
    this.customerId = customerId;
    this.payer = payer;
    this.amount = amount;
    this.reference = reference;
    this.description = description;
    this.expiresAt = expiresAt;
    this.subscriptionId = subscriptionId;
    this.invoiceNumber = invoiceNumber;
    this.periodStart = periodStart;
    this.periodEnd = periodEnd;
    this.createdAt = createdAt;
    this.status = OrderStatus.OPEN;
    this.version = 1;
    this.updatedAt = createdAt;
  }

  /** The bank said a payment of this order settled. Only payments decide that; this mirrors it. */
  public void markPaid(String paymentId, Instant at) {
    transition(OrderStatus.PAID, at);
    this.paidPaymentId = paymentId;
    this.paidAt = at;
  }

  public void markCanceled(Instant at) {
    transition(OrderStatus.CANCELED, at);
  }

  public void markExpired(Instant at) {
    transition(OrderStatus.EXPIRED, at);
  }

  private void transition(OrderStatus to, Instant at) {
    if (!OrderTransitions.allowed(status, to)) {
      throw new IllegalStateException("order " + id + " is " + status + ", cannot become " + to);
    }
    this.status = to;
    this.version++;
    this.updatedAt = at;
  }

  public boolean isOpen() {
    return status == OrderStatus.OPEN;
  }

  public boolean isInvoice() {
    return subscriptionId != null;
  }

  // accessors, one per line, for every field (id(), merchantId(), ..., updatedAt())

  public static Order rehydrate(
      String id, MerchantId merchantId, ProviderEnvironment environment, String customerId,
      OrderPayer payer, Money amount, String reference, String description, OrderStatus status,
      String paidPaymentId, Instant paidAt, Instant expiresAt, String subscriptionId,
      Integer invoiceNumber, LocalDate periodStart, LocalDate periodEnd, long version,
      Instant createdAt, Instant updatedAt) {
    Order order =
        new Order(
            id, merchantId, environment, customerId, payer, amount, reference, description,
            expiresAt, subscriptionId, invoiceNumber, periodStart, periodEnd, createdAt);
    order.status = status;
    order.paidPaymentId = paidPaymentId;
    order.paidAt = paidAt;
    order.version = version;
    order.updatedAt = updatedAt;
    return order;
  }
}
```

`OrderFactory.java`:
```java
package com.gateway.billing.order;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

public final class OrderFactory {
  private OrderFactory() {}

  public static Order standalone(
      MerchantId merchantId, ProviderEnvironment environment, Money amount, String reference,
      String description, String customerId, OrderPayer payer, Instant expiresAt, Clock clock) {
    if ((customerId == null) == (payer == null)) {
      throw new IllegalArgumentException("exactly one of customer_id or customer is required");
    }
    if (amount.cents() <= 0) {
      throw new IllegalArgumentException("amount must be a positive number of cents");
    }

    return new Order(
        Ulid.next(), merchantId, environment, customerId, payer, amount, reference, description,
        expiresAt, null, null, null, null, clock.instant());
  }

  public static Order invoice(
      MerchantId merchantId, ProviderEnvironment environment, String customerId, Money amount,
      String subscriptionId, int invoiceNumber, LocalDate periodStart, LocalDate periodEnd,
      Instant expiresAt, Clock clock) {
    return new Order(
        Ulid.next(), merchantId, environment, customerId, null, amount,
        "sub:" + subscriptionId + ":" + invoiceNumber, null, expiresAt, subscriptionId,
        invoiceNumber, periodStart, periodEnd, clock.instant());
  }
}
```

- [ ] **Step 5: Persistence.** `OrderRepository`:

```java
package com.gateway.billing.order.persistence;

import com.gateway.billing.order.Order;
import com.gateway.kernel.ids.MerchantId;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface OrderRepository {
  /** Requires a transaction. */
  void insert(Order order);

  /** Optimistic: writes only when the stored version is {@code order.version() - 1}. */
  boolean update(Order order);

  Optional<Order> find(MerchantId merchantId, String id);

  /** Without the merchant: the outbox consumer knows only the order id. */
  Optional<Order> findById(String id);

  List<Order> findByReference(MerchantId merchantId, String reference, int limit);

  List<Order> findBySubscription(String subscriptionId, int limit);

  List<Order> findOpenExpiredBefore(Instant now, int limit);

  /** Returns whether the row was inserted (false = already processed). Same transaction. */
  boolean recordProcessedEvent(String eventId, Instant at);
}
```

`OrderEntity` (package-private, `@Table(name = "orders", schema = "billing")`, `payer` as `@JdbcTypeCode(SqlTypes.JSON) String`), `ProcessedEventEntity` (`@Table(name = "processed_events", schema = "billing")`, `@Id String eventId; Instant processedAt`). `OrderJpaRepository`: `findByIdAndMerchantId`, `findByMerchantIdAndReferenceOrderByCreatedAtDesc(String, String, Pageable)`, `findBySubscriptionIdOrderByInvoiceNumberDesc(String, Pageable)`, `findByStatusAndExpiresAtBefore(String status, Instant before, Pageable)`, and `updateIfVersion` as a `@Modifying @Query` setting `status, paidPaymentId, paidAt, version, updatedAt where id = :id and version = :expectedVersion`. `OrderRepositoryImpl` maps both ways (payer JSON via `JsonMapper`); `recordProcessedEvent` does `entityManager.persist` and catches `jakarta.persistence.EntityExistsException`/`DataIntegrityViolationException` → `false` (use `INSERT ... ON CONFLICT DO NOTHING` via `entityManager.createNativeQuery` to avoid poisoning the transaction: `"INSERT INTO billing.processed_events (event_id, processed_at) VALUES (?, ?) ON CONFLICT DO NOTHING"`, return `executeUpdate() == 1`).

- [ ] **Step 6: `payments` addition.** In `gateway-payments`: `PaymentRepository.findActiveByOrder(String orderId) → Optional<Payment>` (`status IN (CREATED, PENDING, AUTHORIZED)`), `PaymentJpaRepository.findFirstByOrderIdAndStatusIn(String, Collection<String>)`, and `PaymentQueries.activeAttempt(String orderId)` plus `PaymentQueries.listByOrder(MerchantId, String orderId)` (`findByMerchantIdAndOrderIdOrderByCreatedAt`). Unit-level coverage comes from the integration tests below.

- [ ] **Step 7: Failing integration tests**

`OrderServiceIntegrationTest.java`:
```java
package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderServiceIntegrationTest extends BillingIntegrationTestBase {
  @Autowired OrderService orders;
  @Autowired OrderPayments attempts;
  @Autowired CustomerService customers;

  Customer customer() {
    return customers.create(
        CustomerFactory.fromRequest(
            merchant, ProviderEnvironment.TEST, "Ana Silva", "52998224725", null, null, clock));
  }

  Order open() {
    return orders.create(
        OrderFactory.standalone(
            merchant, ProviderEnvironment.TEST, Money.brl(5000), "order-42", null,
            customer().id(), null, null, clock));
  }

  @Test
  void createsOpenAndEmits() {
    Order order = open();

    assertThat(orders.get(merchant, order.id()).status()).isEqualTo(OrderStatus.OPEN);
    assertThat(jdbc.queryForList(
            "SELECT event_type FROM payments.outbox WHERE aggregate_id = ?", String.class, order.id()))
        .containsExactly("order.created");
  }

  @Test
  void cancelWithAPendingPixCancelsItAtTheBankAndClosesTheOrder() {
    Order order = open();
    Payment pix = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);

    Order canceled = orders.cancel(merchant, order.id());

    assertThat(canceled.status()).isEqualTo(OrderStatus.CANCELED);
    assertThat(paymentQueries.get(merchant, pix.id()).status()).isEqualTo(PaymentStatus.CANCELED);
  }

  @Test
  void cancelOfAClosedOrderIsAConflict() {
    Order order = open();
    orders.cancel(merchant, order.id());

    assertThatThrownBy(() -> orders.cancel(merchant, order.id()))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("ORDER_CLOSED");
  }
}
```

`OrderPaymentsIntegrationTest.java`:
```java
package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerAddress;
import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardDataFactory;
import java.time.YearMonth;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderPaymentsIntegrationTest extends BillingIntegrationTestBase {
  @Autowired OrderService orders;
  @Autowired OrderPayments attempts;
  @Autowired CustomerService customers;

  Order orderWithAddress() {
    Customer customer =
        customers.create(
            CustomerFactory.fromRequest(
                merchant, ProviderEnvironment.TEST, "Ana Silva", "52998224725", null,
                new CustomerAddress.Raw("Rua A 1", "Centro", "Sao Paulo", "SP", "01310100"), clock));
    return orders.create(
        OrderFactory.standalone(
            merchant, ProviderEnvironment.TEST, Money.brl(5000), "order-42", null, customer.id(),
            null, null, clock));
  }

  @Test
  void anAttemptInheritsTheOrdersAmountAndPayer() {
    Order order = orderWithAddress();

    Payment boleto =
        attempts.attempt(order, new AttemptRequest.BolecodeAttempt(null, null), EventSource.API);

    assertThat(boleto.amount()).isEqualTo(Money.brl(5000));
    assertThat(boleto.orderId()).isEqualTo(order.id());
    assertThat(boleto.status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void aSecondAttemptWhileOneIsActiveIsRefusedNamingIt() {
    Order order = orderWithAddress();
    Payment first = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);

    assertThatThrownBy(
            () -> attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API))
        .isInstanceOf(OrderHasActivePaymentException.class)
        .extracting(e -> ((OrderHasActivePaymentException) e).paymentId())
        .isEqualTo(first.id());
  }

  @Test
  void twoAttemptsRacingLeaveExactlyOneActive() throws Exception {
    Order order = orderWithAddress();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch go = new CountDownLatch(1);
    java.util.concurrent.Callable<Object> race =
        () -> {
          go.await();
          try {
            return attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);
          } catch (OrderHasActivePaymentException refused) {
            return refused;
          }
        };
    Future<Object> a = pool.submit(race);
    Future<Object> b = pool.submit(race);
    go.countDown();

    long accepted = java.util.stream.Stream.of(a.get(), b.get()).filter(Payment.class::isInstance).count();

    assertThat(accepted).isEqualTo(1);
    pool.shutdown();
  }

  @Test
  void afterTheActiveAttemptExpiresAnotherMethodMayTry() {
    Order order = orderWithAddress();
    Payment pix = attempts.attempt(order, new AttemptRequest.PixAttempt(60), EventSource.API);
    jdbc.update("UPDATE payments.payments SET status = 'EXPIRED' WHERE id = ?", pix.id());

    Payment card =
        attempts.attempt(
            order,
            new AttemptRequest.CardAttempt(
                new CardChoice.NewCard(
                    CardDataFactory.from("4024007153763171", "ANA SILVA", "12/2030", "123", null,
                        YearMonth.of(2026, 9)),
                    false),
                1, true, "LOJA"),
            EventSource.API);

    assertThat(card.status()).isEqualTo(PaymentStatus.COMPLETED);
  }
}
```

- [ ] **Step 8: `AttemptRequest`, `OrderPayments`, `OrderService`.**

`AttemptRequest.java`:
```java
package com.gateway.billing.order;

import com.gateway.payments.payment.create.CardChoice;
import java.time.LocalDate;

/** What a caller may choose for an attempt; amount, currency and payer come from the order. */
public sealed interface AttemptRequest {
  record PixAttempt(Integer expiresInSeconds) implements AttemptRequest {}

  record BolecodeAttempt(LocalDate dueDate, Integer paymentLimitDays) implements AttemptRequest {}

  record CardAttempt(CardChoice card, Integer installments, Boolean capture, String softDescriptor)
      implements AttemptRequest {}

  /** A subscription cycle: the stored card, no CVV, captured at once (spec §6). */
  record RecurringCardAttempt(String cardId, Integer installments) implements AttemptRequest {}
}
```

`OrderHasActivePaymentException.java`:
```java
package com.gateway.billing.order;

import com.gateway.kernel.errors.DomainException;

public class OrderHasActivePaymentException extends DomainException {
  private final String paymentId;

  public OrderHasActivePaymentException(String orderId, String paymentId) {
    super("ORDER_HAS_ACTIVE_PAYMENT", "order " + orderId + " already has an active payment: " + paymentId);
    this.paymentId = paymentId;
  }

  public String paymentId() {
    return paymentId;
  }
}
```

`OrderPayments.java`:
```java
package com.gateway.billing.order;

import com.gateway.billing.customer.Customer;
import com.gateway.billing.customer.CustomerAddress;
import com.gateway.billing.customer.CustomerService;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.party.Document;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardCustomerData;
import com.gateway.payments.payment.create.CreateBolecodePayment;
import com.gateway.payments.payment.create.CreateCardPayment;
import com.gateway.payments.payment.create.CreatePaymentCommand;
import com.gateway.payments.payment.create.CreatePixPayment;
import com.gateway.payments.payment.create.PayerData;
import com.gateway.payments.payment.create.PaymentFlows;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The one door from an order to a payment attempt. Builds the method's command from the order
 * (amount, currency, payer) and what the caller chose, and translates the database's refusal of a
 * second active attempt into the 409 the API promises (spec §4.3).
 */
public class OrderPayments {
  private final PaymentFlows flows;
  private final PaymentQueries payments;
  private final CustomerService customers;

  public OrderPayments(PaymentFlows flows, PaymentQueries payments, CustomerService customers) {
    this.flows = flows;
    this.payments = payments;
    this.customers = customers;
  }

  public Payment attempt(Order order, AttemptRequest request, EventSource by) {
    if (!order.isOpen()) {
      throw new DomainException("ORDER_CLOSED", "order " + order.id() + " is " + order.status());
    }
    Payer payer = payerOf(order);
    CreatePaymentCommand command = commandFor(order, payer, request);

    try {
      return flows.forMethod(command.method()).create(command);
    } catch (DataIntegrityViolationException refused) {
      // The partial unique index spoke: name the attempt that holds the slot.
      String active = payments.activeAttempt(order.id()).map(Payment::id).orElse("unknown");
      throw new OrderHasActivePaymentException(order.id(), active);
    }
  }

  private CreatePaymentCommand commandFor(Order order, Payer payer, AttemptRequest request) {
    return switch (request) {
      case AttemptRequest.PixAttempt pix ->
          new CreatePixPayment(
              order.merchantId(), order.environment(), order.amount(), order.reference(),
              order.description(), payer.document(), pix.expiresInSeconds(), order.id());
      case AttemptRequest.BolecodeAttempt boleto ->
          new CreateBolecodePayment(
              order.merchantId(), order.environment(), order.amount(), order.reference(),
              order.description(), payer.asPayerData(), boleto.dueDate(), boleto.paymentLimitDays(),
              order.id());
      case AttemptRequest.CardAttempt card ->
          new CreateCardPayment(
              order.merchantId(), order.environment(), order.amount(), order.reference(),
              order.description(), card.card(), card.installments(), card.capture(),
              card.softDescriptor(), payer.asCardCustomer(), order.id());
      case AttemptRequest.RecurringCardAttempt recurring ->
          new CreateCardPayment(
              order.merchantId(), order.environment(), order.amount(), order.reference(),
              order.description(), new CardChoice.RecurringCard(recurring.cardId()),
              recurring.installments(), true, null, payer.asCardCustomer(), order.id());
    };
  }

  private Payer payerOf(Order order) {
    if (order.customerId() != null) {
      Customer customer = customers.get(order.merchantId(), order.customerId());
      return new Payer(
          customer.name().value(), customer.document(), customer.email(), customer.address());
    }
    OrderPayer inline = order.payer();
    return new Payer(inline.name().value(), inline.document(), inline.email(), inline.address());
  }

  /** The payer in the shapes the three commands take. */
  record Payer(String name, Document document, String email, CustomerAddress address) {
    PayerData asPayerData() {
      if (address == null) {
        throw new DomainException(
            "CUSTOMER_ADDRESS_REQUIRED", "customer.address is required for a boleto");
      }
      return new PayerData(
          name, document(), new PayerData.AddressData(
              address.street(), address.district(), address.city(), address.state().value(),
              address.zip().digits()));
    }

    CardCustomerData asCardCustomer() {
      return new CardCustomerData(name, document(), email);
    }

    String document() {
      return document == null ? null : document.digits();
    }
  }
}
```

Note on `payer.document()` for a stored inline payer: `OrderPayer.forStorage()` drops the `Document`, so a stored standalone order with an inline payer re-attempting a **Bolecode** has no document digits. Resolve it now, not later: `OrderPayer` stores the document **sealed** like a customer (column `payer` JSON gets `"document_ciphertext"` base64 and the impl opens it on read). Update `OrderPayer` to `record OrderPayer(PersonName name, Document document, String email, CustomerAddress address)` (no hash field; the hash is derived when needed) and have `OrderRepositoryImpl` seal/open `document.digits()` with context `merchantId + "|order-payer"`. Remove `forStorage()`. Add a test to `OrderServiceIntegrationTest`: an order created with an inline payer is read back with the same document digits.

`CardChoice.RecurringCard`: in `gateway-payments` add `record RecurringCard(String cardId) implements CardChoice {}` to the sealed interface and handle it in `CardPaymentFlow.choose(...)` with `savedCards.tokenForRecurring(merchantId, environment, cardId)`; the `ArchitectureTest` is unaffected. Guard it: `CardPaymentFlow` refuses `RecurringCard` when `EventSource` is not `SYSTEM`? The flow does not receive an `EventSource`; instead the API never builds a `RecurringCard` (only billing's jobs do), and `CardPaymentRequest.toCommand` cannot produce it. State this in the record's Javadoc.

`OrderService.java`:
```java
package com.gateway.billing.order;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentCancellation;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.PaymentStatus;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class OrderService {
  private final OrderRepository orders;
  private final PaymentQueries payments;
  private final PaymentCancellation cancellation;
  private final BillingEvents events;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public OrderService(
      OrderRepository orders, PaymentQueries payments, PaymentCancellation cancellation,
      BillingEvents events, UnitOfWork unitOfWork, Clock clock) {
    this.orders = orders;
    this.payments = payments;
    this.cancellation = cancellation;
    this.events = events;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public Order create(Order order) {
    return unitOfWork.inTransaction(
        () -> {
          orders.insert(order);
          events.emit(order.merchantId(), "order.created", order.id(), order.id(), json(order));
          return order;
        });
  }

  public Order get(MerchantId merchantId, String id) {
    return orders.find(merchantId, id).orElseThrow(() -> new NotFoundException("order", id));
  }

  public List<Order> listByReference(MerchantId merchantId, String reference, int limit) {
    return orders.findByReference(merchantId, reference, limit);
  }

  public List<Payment> attemptsOf(MerchantId merchantId, String id) {
    get(merchantId, id);
    return payments.listByOrder(merchantId, id);
  }

  /**
   * Cancels the active attempt at the bank first, outside any transaction (the bank decides), then
   * the order. If the bank says the attempt already paid, the order is not canceled: the settlement
   * listener will mark it PAID, and the merchant gets ALREADY_PAID like on a payment.
   */
  public Order cancel(MerchantId merchantId, String id) {
    Order order = get(merchantId, id);
    if (!order.isOpen()) {
      throw new DomainException("ORDER_CLOSED", "order " + id + " is " + order.status());
    }

    Optional<Payment> active = payments.activeAttempt(order.id());
    if (active.isPresent()) {
      Payment afterCancel = cancellation.cancel(merchantId, active.get().id());
      if (afterCancel.status() == PaymentStatus.COMPLETED) {
        throw new DomainException("ALREADY_PAID", "order " + id + " was paid by " + afterCancel.id());
      }
    }

    return unitOfWork.inTransaction(
        () -> {
          Order current = orders.find(merchantId, id).orElseThrow();
          if (!current.isOpen()) {
            throw new DomainException("ORDER_CLOSED", "order " + id + " is " + current.status());
          }
          current.markCanceled(clock.instant());
          if (!orders.update(current)) {
            throw new DomainException("CONFLICT", "order " + id + " changed concurrently");
          }
          events.emit(merchantId, "order.canceled", id, id, json(current));
          return current;
        });
  }

  public static Map<String, Object> json(Order order) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", order.id());
    body.put("status", order.status().name());
    body.put("amount", order.amount().cents());
    body.put("currency", order.amount().currency());
    body.put("reference", order.reference());
    body.put("customer_id", order.customerId());
    body.put("paid_payment_id", order.paidPaymentId());
    body.put("paid_at", order.paidAt() == null ? null : order.paidAt().toString());
    body.put("expires_at", order.expiresAt() == null ? null : order.expiresAt().toString());
    body.put("subscription_id", order.subscriptionId());
    body.put("invoice_number", order.invoiceNumber());
    body.put("created_at", order.createdAt().toString());
    return body;
  }
}
```

Check `PaymentCancellation.cancel` behaviour when the bank says paid: in Plan C it completes the payment and throws `DomainException("ALREADY_PAID")`. If it throws rather than returning `COMPLETED`, let that exception propagate (it is already the right code) and drop the `afterCancel.status()` branch. Read `PaymentCancellation.cancel` before writing this and keep whichever matches.

- [ ] **Step 9: Wire.** `BillingConfiguration`: `@Import({CustomerRepositoryImpl.class, OrderRepositoryImpl.class})`; beans `OrderPayments orderPayments(PaymentFlows, PaymentQueries, CustomerService)`, `OrderService orderService(OrderRepository, PaymentQueries, PaymentCancellation, BillingEvents, UnitOfWork, Clock)`.

- [ ] **Step 10: Run** `./mvnw -B -o -pl gateway-payments,gateway-billing -am verify` → green.

- [ ] **Step 11: Commit** `feat(billing): orders with one active attempt and attempts through the payment flows`.

---

### Task 5: `OutboxRelay` listeners and `OrderSettlement`

**Files:**
- Create: `gateway-billing/src/main/java/com/gateway/billing/order/OrderSettlement.java`
- Create: `gateway-billing/src/main/java/com/gateway/billing/order/InvoiceSettlementHook.java` (port; Task 10 implements)
- Modify: `gateway-app/src/main/java/com/gateway/app/outbound/OutboxRelay.java`
- Modify: `BillingConfiguration.java`
- Test: `gateway-billing/src/test/java/com/gateway/billing/order/OrderSettlementIntegrationTest.java`, `gateway-app/src/test/java/com/gateway/app/outbound/OutboxRelayListenersTest.java`

**Interfaces:**
- Consumes: `OutboxListener` (Task 2), `OrderRepository.findById/update/recordProcessedEvent`, `Divergences` (`com.gateway.payments.reconciliation.Divergences` — read its public API; it opens a `reconciliation_divergences` row keyed by payment; use its existing `open(Payment, String kind, String detail)` or the closest method).
- Produces: `OrderSettlement implements OutboxListener` handling `payment.completed|failed|expired|canceled` with an `order_id` in the payload; `InvoiceSettlementHook { void invoicePaid(Order order, Instant at); void invoiceAttemptFailed(Order order, String paymentId, String eventType, Instant at); }` with a no-op bean until Task 10.

- [ ] **Step 1: Failing relay test** `gateway-app/src/test/java/com/gateway/app/outbound/OutboxRelayListenersTest.java` (plain unit test with Mockito, which spring-boot-starter-test brings):

```java
package com.gateway.app.outbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.outbox.OutboxListener;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.outbox.persistence.OutboxRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class OutboxRelayListenersTest {
  OutboxRepository outbox = mock(OutboxRepository.class);
  MerchantEvents events = mock(MerchantEvents.class);
  TransactionTemplate template = mock(TransactionTemplate.class);
  OutboxListener listener = mock(OutboxListener.class);

  OutboxMessage message =
      new OutboxMessage("01MSG", MerchantId.next(), "pay-1", "pay-1", "payment.completed", "{}",
          "PENDING", null, Instant.EPOCH);

  OutboxRelay relay() {
    when(template.execute(any())).thenAnswer(call -> ((org.springframework.transaction.support.TransactionCallback<?>) call.getArgument(0)).doInTransaction(new SimpleTransactionStatus()));
    when(outbox.claimPending(anyInt(), any())).thenReturn(List.of(message));
    when(listener.handles("payment.completed")).thenReturn(true);
    return new OutboxRelay(outbox, events, template, PaymentsProperties.defaults(), List.of(listener));
  }

  @Test
  void theListenerRunsBeforeTheMerchantAndTheRowIsMarkedOnce() {
    relay().relay();

    var order = inOrder(listener, events, outbox);
    order.verify(listener).on(message);
    order.verify(events).emitRaw(any(), any(), any(), any(), any(), any());
    order.verify(outbox).markSent("01MSG");
  }

  @Test
  void aListenerThatThrowsKeepsTheRowUnsentAndTheMerchantUnnotified() {
    OutboxRelay relay = relay();
    doThrow(new IllegalStateException("db down")).when(listener).on(message);

    relay.relay();

    verify(events, never()).emitRaw(any(), any(), any(), any(), any(), any());
    verify(outbox, never()).markSent(any());
    verify(outbox).release("01MSG");
  }
}
```

- [ ] **Step 2: Run, expect compile failure** (constructor has no listener list).

- [ ] **Step 3: Modify `OutboxRelay`.** Add `private final List<OutboxListener> listeners;` as the fifth constructor parameter (`List<OutboxListener> listeners`; Spring injects every bean of the type, an empty list when none). In the `try` block, before `events.emitRaw(...)`:

```java
        for (OutboxListener listener : listeners) {
          if (listener.handles(message.eventType())) {
            // Internal consumers go first (spec §8): the merchant must never learn "order paid"
            // from a webhook before the order row says so. A throw here keeps the row for the
            // next pass; listeners are idempotent by message id.
            listener.on(message);
          }
        }
```

Update the class Javadoc: "Moves the payments outbox into webhook-delivery, after the in-process listeners."

- [ ] **Step 4: Run** `./mvnw -B -o -pl gateway-app -am test -Dtest=OutboxRelayListenersTest -Dsurefire.failIfNoSpecifiedTests=false` → green.

- [ ] **Step 5: Failing settlement test** `OrderSettlementIntegrationTest.java`:

```java
package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderSettlementIntegrationTest extends BillingIntegrationTestBase {
  @Autowired OrderService orders;
  @Autowired OrderPayments attempts;
  @Autowired OrderSettlement settlement;
  @Autowired CustomerService customers;

  Order open() {
    String customerId =
        customers.create(CustomerFactory.fromRequest(
            merchant, ProviderEnvironment.TEST, "Ana Silva", "52998224725", null, null, clock)).id();
    return orders.create(OrderFactory.standalone(
        merchant, ProviderEnvironment.TEST, Money.brl(5000), "o", null, customerId, null, null, clock));
  }

  OutboxMessage event(String type, Order order, Payment payment) {
    String payload = "{\"id\":\"" + payment.id() + "\",\"order_id\":\"" + order.id()
        + "\",\"paid_at\":\"2026-10-02T12:00:00Z\"}";
    return new OutboxMessage(Ulid.next(), merchant, payment.id(), payment.id(), type, payload,
        "PENDING", null, Instant.now());
  }

  @Test
  void aCompletedAttemptPaysTheOrderOnceEvenIfTheEventRepeats() {
    Order order = open();
    Payment pix = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);
    OutboxMessage completed = event("payment.completed", order, pix);

    settlement.on(completed);
    settlement.on(completed);

    Order paid = orders.get(merchant, order.id());
    assertThat(paid.status()).isEqualTo(OrderStatus.PAID);
    assertThat(paid.paidPaymentId()).isEqualTo(pix.id());
    assertThat(jdbc.queryForList(
            "SELECT event_type FROM payments.outbox WHERE aggregate_id = ? ORDER BY id",
            String.class, order.id()))
        .containsExactly("order.created", "order.paid");
  }

  @Test
  void aSecondCompletedPaymentOpensADoublePaymentDivergence() {
    Order order = open();
    Payment first = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);
    // Only one attempt may be active: the first expires (the bank may still settle it later,
    // EXPIRED -> COMPLETED is a real transition), then a card tries.
    jdbc.update("UPDATE payments.payments SET status = 'EXPIRED' WHERE id = ?", first.id());
    Payment second = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);

    settlement.on(event("payment.completed", order, first));
    settlement.on(event("payment.completed", order, second));

    Order paid = orders.get(merchant, order.id());
    assertThat(paid.paidPaymentId()).isEqualTo(first.id());
    Integer divergences =
        jdbc.queryForObject(
            "SELECT count(*) FROM payments.reconciliation_divergences WHERE payment_id = ?",
            Integer.class,
            second.id());
    assertThat(divergences).isEqualTo(1);
  }

  @Test
  void aFailedAttemptLeavesTheOrderOpen() {
    Order order = open();
    Payment pix = attempts.attempt(order, new AttemptRequest.PixAttempt(600), EventSource.API);

    settlement.on(event("payment.expired", order, pix));

    assertThat(orders.get(merchant, order.id()).status()).isEqualTo(OrderStatus.OPEN);
  }

  @Test
  void anEventWithoutAnOrderIsIgnored() {
    Payment plain = paymentService.create(new com.gateway.payments.payment.create.CreatePixPayment(
        merchant, ProviderEnvironment.TEST, Money.brl(100), "x", null, null, null, null));
    OutboxMessage completed = new OutboxMessage(Ulid.next(), merchant, plain.id(), plain.id(),
        "payment.completed", "{\"id\":\"" + plain.id() + "\"}", "PENDING", null, Instant.now());

    settlement.on(completed); // no exception, nothing to do
  }
}
```

Read `V200__payments.sql`/`V201__divergence_unique_open.sql` for the real column names of `reconciliation_divergences` (the test above assumes `payment_id`; adjust the query, not the assertion).

- [ ] **Step 6: `OrderSettlement`**

```java
package com.gateway.billing.order;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.outbox.OutboxListener;
import com.gateway.payments.outbox.OutboxMessage;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.reconciliation.Divergences;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Mirrors payment outcomes onto orders (spec §8). Never decides money: a {@code payment.completed}
 * is the bank's word, relayed. Idempotent by message id through {@code billing.processed_events},
 * written in the same transaction as the reaction, because the relay re-delivers on any throw.
 */
public class OrderSettlement implements OutboxListener {
  private static final Logger log = LoggerFactory.getLogger(OrderSettlement.class);
  private static final Set<String> HANDLED =
      Set.of("payment.completed", "payment.failed", "payment.expired", "payment.canceled");

  private final OrderRepository orders;
  private final PaymentQueries payments;
  private final Divergences divergences;
  private final InvoiceSettlementHook invoices;
  private final BillingEvents events;
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public OrderSettlement(
      OrderRepository orders, PaymentQueries payments, Divergences divergences,
      InvoiceSettlementHook invoices, BillingEvents events, UnitOfWork unitOfWork, Clock clock) {
    this.orders = orders;
    this.payments = payments;
    this.divergences = divergences;
    this.invoices = invoices;
    this.events = events;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  @Override
  public boolean handles(String eventType) {
    return HANDLED.contains(eventType);
  }

  @Override
  public void on(OutboxMessage message) {
    JsonNode payload = json.readTree(message.payload());
    JsonNode orderNode = payload.get("order_id");
    if (orderNode == null || orderNode.isNull()) {
      return;
    }
    String orderId = orderNode.asText();
    String paymentId = payload.get("id").asText();

    unitOfWork.run(
        () -> {
          if (!orders.recordProcessedEvent(message.id(), clock.instant())) {
            return;
          }
          Optional<Order> found = orders.findById(orderId);
          if (found.isEmpty()) {
            log.warn("payment {} names order {} which does not exist", paymentId, orderId);
            return;
          }
          Order order = found.get();
          Instant now = clock.instant();

          if (message.eventType().equals("payment.completed")) {
            completed(order, paymentId, now);
          } else {
            attemptEnded(order, paymentId, message.eventType(), now);
          }
        });
  }

  private void completed(Order order, String paymentId, Instant now) {
    if (!order.isOpen()) {
      if (order.status() == OrderStatus.PAID && !paymentId.equals(order.paidPaymentId())) {
        // Two attempts settled (Pix and card landing together). Nobody refunds by itself:
        // a human sees both ids in the divergence and decides.
        Payment second = payments.get(order.merchantId(), paymentId);
        divergences.open(
            second, "DOUBLE_PAYMENT", "order " + order.id() + " already paid by " + order.paidPaymentId());
      }
      return;
    }

    order.markPaid(paymentId, now);
    requireUpdated(order);
    events.emit(order.merchantId(), "order.paid", order.id(), order.id(), OrderService.json(order));
    if (order.isInvoice()) {
      invoices.invoicePaid(order, now);
    }
  }

  private void attemptEnded(Order order, String paymentId, String eventType, Instant now) {
    if (order.isInvoice() && order.isOpen()) {
      invoices.invoiceAttemptFailed(order, paymentId, eventType, now);
    }
  }

  private void requireUpdated(Order order) {
    if (!orders.update(order)) {
      throw new IllegalStateException("order " + order.id() + " changed concurrently; retrying");
    }
  }
}
```

Read `Divergences` in `gateway-payments/.../reconciliation/Divergences.java` and use its real method for opening a divergence from a `Payment`; if it needs a `Payment` object and `payments.get` requires the merchant, `order.merchantId()` is available. `InvoiceSettlementHook.java`:

```java
package com.gateway.billing.order;

import java.time.Instant;

/** What a subscription wants to know about its invoice; implemented in the subscription package. */
public interface InvoiceSettlementHook {
  void invoicePaid(Order order, Instant at);

  void invoiceAttemptFailed(Order order, String paymentId, String eventType, Instant at);
}
```

- [ ] **Step 7: Wire.** `BillingConfiguration`: `InvoiceSettlementHook noInvoiceHookYet()` returning an implementation with two empty methods (comment: replaced in Task 10), and `OrderSettlement orderSettlement(OrderRepository, PaymentQueries, Divergences, InvoiceSettlementHook, BillingEvents, UnitOfWork, Clock)`. Because the bean type is `OrderSettlement` and the relay asks for `List<OutboxListener>`, Spring matches by assignability — verify with the app test in Task 12.

- [ ] **Step 8: Run** `./mvnw -B -o -pl gateway-billing,gateway-app -am verify` → green.

- [ ] **Step 9: Commit** `feat(billing): orders settle from payment events through an in-process outbox listener`.

---

### Task 6: `ExpireOrderJob`

**Files:**
- Modify: `gateway-payments/.../jobs/JobType.java` (+`EXPIRE_ORDER`), `jobs/Job.java` (+`expireOrder(String orderId, Instant when, Clock)`)
- Create: `gateway-billing/.../order/ExpireOrderJob.java`, `order/OrderExpiration.java`
- Modify: `OrderService.create` (enqueue the job when `expiresAt != null`), `BillingConfiguration`
- Test: `order/OrderExpirationIntegrationTest.java`

**Interfaces:**
- Consumes: `JobRepository.enqueue(Job)`, `JobBackoff` (bean from `PaymentsConfiguration`), `PaymentQueries.activeAttempt`.
- Produces: `OrderExpiration.expireOne(String orderId, Instant now) → boolean` (true = done; false = an attempt is still active, ask again later), `ExpireOrderJob implements JobHandler` (`type() == EXPIRE_ORDER`; `run` returns `expireOne`; `afterFailure` = `backoff.retry`).

- [ ] **Step 1: Failing test** `OrderExpirationIntegrationTest.java`:

```java
package com.gateway.billing.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.customer.CustomerFactory;
import com.gateway.billing.customer.CustomerService;
import com.gateway.billing.support.BillingIntegrationTestBase;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.EventSource;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OrderExpirationIntegrationTest extends BillingIntegrationTestBase {
  @Autowired OrderService orders;
  @Autowired OrderPayments attempts;
  @Autowired OrderExpiration expiration;
  @Autowired CustomerService customers;

  Order expiringInAnHour() {
    String customerId = customers.create(CustomerFactory.fromRequest(
        merchant, ProviderEnvironment.TEST, "Ana", "52998224725", null, null, clock)).id();
    return orders.create(OrderFactory.standalone(
        merchant, ProviderEnvironment.TEST, Money.brl(100), "o", null, customerId, null,
        clock.instant().plus(Duration.ofHours(1)), clock));
  }

  @Test
  void creatingAnOrderWithExpiryQueuesItsJob() {
    Order order = expiringInAnHour();

    Integer queued = jdbc.queryForObject(
        "SELECT count(*) FROM payments.jobs WHERE type = 'EXPIRE_ORDER' AND ref_id = ?",
        Integer.class, order.id());

    assertThat(queued).isEqualTo(1);
  }

  @Test
  void anOpenOrderWithoutAnActiveAttemptExpires() {
    Order order = expiringInAnHour();
    clock.advance(Duration.ofHours(2));

    boolean done = expiration.expireOne(order.id(), clock.instant());

    assertThat(done).isTrue();
    assertThat(orders.get(merchant, order.id()).status()).isEqualTo(OrderStatus.EXPIRED);
  }

  @Test
  void anOrderWithAnActiveAttemptWaitsForIt() {
    Order order = expiringInAnHour();
    attempts.attempt(order, new AttemptRequest.PixAttempt(7200), EventSource.API);
    clock.advance(Duration.ofHours(2));

    boolean done = expiration.expireOne(order.id(), clock.instant());

    assertThat(done).isFalse();
    assertThat(orders.get(merchant, order.id()).status()).isEqualTo(OrderStatus.OPEN);
  }

  @Test
  void aPaidOrderIsLeftAlone() {
    Order order = expiringInAnHour();
    jdbc.update("UPDATE billing.orders SET status = 'PAID' WHERE id = ?", order.id());
    clock.advance(Duration.ofHours(2));

    assertThat(expiration.expireOne(order.id(), clock.instant())).isTrue();
    assertThat(orders.get(merchant, order.id()).status()).isEqualTo(OrderStatus.PAID);
  }
}
```

- [ ] **Step 2: Run, expect compile failure.**

- [ ] **Step 3: Implement.** `JobType`: add `EXPIRE_ORDER` (document: "billing's; the handler lives in gateway-billing, the runner does not care"). `Job.expireOrder`:

```java
  public static Job expireOrder(String orderId, Instant when, Clock clock) {
    return new Job(
        Ulid.next(), JobType.EXPIRE_ORDER, orderId, when, 0, "PENDING", null, null, clock.instant());
  }
```

`OrderExpiration.java`:
```java
package com.gateway.billing.order;

import com.gateway.billing.BillingEvents;
import com.gateway.billing.order.persistence.OrderRepository;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.PaymentQueries;
import java.time.Instant;

/**
 * Expires an OPEN order on its limit (spec §4.2). An active attempt wins: the Pix or boleto has its
 * own expiry at the bank, and the order expires on the pass after that attempt ends, never under a
 * charge someone may still be paying.
 */
public class OrderExpiration {
  private final OrderRepository orders;
  private final PaymentQueries payments;
  private final BillingEvents events;
  private final UnitOfWork unitOfWork;

  public OrderExpiration(
      OrderRepository orders, PaymentQueries payments, BillingEvents events, UnitOfWork unitOfWork) {
    this.orders = orders;
    this.payments = payments;
    this.events = events;
    this.unitOfWork = unitOfWork;
  }

  /** true = nothing more to do; false = an attempt is active, ask again later. */
  public boolean expireOne(String orderId, Instant now) {
    return unitOfWork.inTransaction(
        () -> {
          Order order = orders.findById(orderId).orElse(null);
          if (order == null || !order.isOpen()) {
            return true;
          }
          if (order.expiresAt() == null || order.expiresAt().isAfter(now)) {
            return true;
          }
          if (payments.activeAttempt(orderId).isPresent()) {
            return false;
          }

          order.markExpired(now);
          if (!orders.update(order)) {
            throw new IllegalStateException("order " + orderId + " changed concurrently");
          }
          events.emit(order.merchantId(), "order.expired", orderId, orderId, OrderService.json(order));
          return true;
        });
  }
}
```

`ExpireOrderJob.java`:
```java
package com.gateway.billing.order;

import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.JobBackoff;
import com.gateway.payments.jobs.JobHandler;
import com.gateway.payments.jobs.JobType;
import java.time.Instant;

public class ExpireOrderJob implements JobHandler {
  private final OrderExpiration expiration;
  private final JobBackoff backoff;

  public ExpireOrderJob(OrderExpiration expiration, JobBackoff backoff) {
    this.expiration = expiration;
    this.backoff = backoff;
  }

  @Override
  public JobType type() {
    return JobType.EXPIRE_ORDER;
  }

  @Override
  public boolean run(String refId, Instant now) {
    return expiration.expireOne(refId, now);
  }

  @Override
  public Job afterFailure(Job job, Instant now, String error) {
    return backoff.retry(job, now, error);
  }
}
```

`OrderService`: add `JobRepository jobs` (7th constructor dep; at the limit, acceptable) and in `create`, inside the transaction after `insert`: `if (order.expiresAt() != null) { jobs.enqueue(Job.expireOrder(order.id(), order.expiresAt(), clock)); }`.

- [ ] **Step 4: Wire** beans `OrderExpiration`, `ExpireOrderJob` in `BillingConfiguration`. The `JobHandlers` bean in `PaymentsConfiguration` takes `List<JobHandler>` from the whole context, so the billing handler is found; the payments module's own `TestApp` context would now fail (`no job handler for EXPIRE_ORDER`) — add to `gateway-payments/src/test/.../ServiceTestConfig` a `JobHandler` stub bean for `EXPIRE_ORDER` (and later `BILL_SUBSCRIPTION`, `DUNNING_RETRY`) with a comment "billing's handlers live in gateway-billing; here the type only needs an owner so the registry starts".

- [ ] **Step 5: Run** `./mvnw -B -o -pl gateway-payments,gateway-billing,gateway-app -am verify` → green.

- [ ] **Step 6: Commit** `feat(billing): orders expire by job, never under an active attempt`.

---

### Task 7: Plans

**Files:**
- Create: `V302__plans.sql`, `plan/Plan.java`, `plan/PlanInterval.java`, `plan/PlanFactory.java`, `plan/PlanService.java`, `plan/persistence/{PlanEntity, PlanJpaRepository, PlanRepository, PlanRepositoryImpl}.java`
- Modify: `BillingConfiguration.java`
- Test: `plan/PlanFactoryTest.java`, `plan/PlanServiceIntegrationTest.java`

**Interfaces:**
- Produces: `PlanInterval { DAY, WEEK, MONTH, YEAR }`; `Plan(id, merchantId, name, amount (Money), interval, intervalCount, trialDays, active, version, createdAt, updatedAt)` record with `rename(String, Instant)`, `activate/deactivate(Instant)`; `PlanFactory.fromRequest(MerchantId, String name, Money amount, PlanInterval, Integer intervalCount, Integer trialDays, Clock)` (defaults 1 / 0; validates 1..12 and 0..365, name 1..80); `PlanService.create/get/list(MerchantId, Boolean active)/rename/setActive`; 422 `PLAN_INACTIVE` is raised by the subscription service (Task 8), not here.

- [ ] **Step 1: Migration** `V302__plans.sql`:

```sql
-- A plan is catalogue, not money in flight: no environment, immutable price and interval (spec
-- §4.4). Changing the price is a new plan, so running subscriptions never move by accident.
CREATE TABLE billing.plans (
    id             CHAR(26)     PRIMARY KEY,
    merchant_id    CHAR(26)     NOT NULL,
    name           VARCHAR(80)  NOT NULL,
    amount         BIGINT       NOT NULL,
    currency       CHAR(3)      NOT NULL,
    interval       VARCHAR(5)   NOT NULL,
    interval_count SMALLINT     NOT NULL,
    trial_days     SMALLINT     NOT NULL,
    active         BOOLEAN      NOT NULL,
    version        BIGINT       NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_plans_merchant ON billing.plans (merchant_id, active);
```

- [ ] **Step 2: Failing tests**

`PlanFactoryTest.java`:
```java
package com.gateway.billing.plan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class PlanFactoryTest {
  @Test
  void defaultsIntervalCountAndTrial() {
    Plan plan = PlanFactory.fromRequest(
        MerchantId.next(), "Pro", Money.brl(9900), PlanInterval.MONTH, null, null, Clock.systemUTC());

    assertThat(plan.intervalCount()).isEqualTo(1);
    assertThat(plan.trialDays()).isEqualTo(0);
    assertThat(plan.active()).isTrue();
  }

  @Test
  void refusesMoreThanTwelveIntervals() {
    assertThatThrownBy(() -> PlanFactory.fromRequest(
            MerchantId.next(), "Pro", Money.brl(9900), PlanInterval.MONTH, 13, null, Clock.systemUTC()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("interval_count");
  }
}
```

`PlanServiceIntegrationTest.java` (extends `BillingIntegrationTestBase`): creates a plan, reads it back, lists `active=true`, renames (version 2), deactivates and asserts it is absent from `list(merchant, true)` but present in `get`.

- [ ] **Step 3: Implement** `Plan` (record with the three `with*` methods bumping `version`), `PlanFactory` (IllegalArgumentException with the client's field names: `name`, `interval_count`, `trial_days`, `amount`), persistence following the `Order` pattern (`updateIfVersion` on `name, active, version, updatedAt`), `PlanService` (`create` in a unit of work emitting nothing — plans have no events in spec §9; `get` → `NotFoundException("plan", id)`; `list`; `rename`; `setActive`). Wire `PlanRepositoryImpl` and `PlanService` in `BillingConfiguration`.

- [ ] **Step 4: Run** `./mvnw -B -o -pl gateway-billing -am verify` → green. **Commit** `feat(billing): plans, immutable in price and interval`.

---

### Task 8: Subscriptions — calendar, model, service

**Files:**
- Create: `V304__subscriptions.sql`
- Create: `subscription/Subscription.java`, `SubscriptionStatus.java`, `SubscriptionTransitions.java`, `BillingCalendar.java`, `BillingPeriod.java`, `SubscriptionFactory.java`, `SubscriptionService.java`, `subscription/ActiveSubscriptions.java` (implements `ActiveSubscriptionsCheck`)
- Create: `subscription/persistence/{SubscriptionEntity, SubscriptionJpaRepository, SubscriptionRepository, SubscriptionRepositoryImpl, DunningAttemptEntity, DunningAttemptRepository, DunningAttemptRepositoryImpl}.java`
- Modify: `BillingConfiguration.java` (replace the `ActiveSubscriptionsCheck` stub)
- Test: `subscription/BillingCalendarTest.java`, `SubscriptionTransitionsTest.java`, `SubscriptionFactoryTest.java`, `SubscriptionServiceIntegrationTest.java`

**Interfaces:**
- Consumes: `PlanService.get`, `CustomerService.get`, `SavedCards.get(MerchantId, cardId)` + `SavedCard.customerId()`, `BillingProperties.billingHour()`, `JobRepository.enqueue`, `Job.billSubscription` (added here with `JobType.BILL_SUBSCRIPTION`; its handler lands in Task 9, so this task also adds the payments-test stub for the type as in Task 6).
- Produces: `BillingPeriod(LocalDate start, LocalDate end)` (end exclusive: the next period starts on `end`); `BillingCalendar.firstPeriod(LocalDate start, PlanInterval, int count, int anchorDay) → BillingPeriod` and `next(BillingPeriod current, PlanInterval, int count, int anchorDay) → BillingPeriod`; `BillingCalendar.billingInstant(LocalDate day, int hour) → Instant` in `America/Sao_Paulo`; `BillingCalendar.endOfDay(LocalDate) → Instant` (23:59:59 SP); `Subscription` class with `id, merchantId, environment, customerId, planId, method (PaymentMethod), cardId, status, anchorDay, currentPeriod (BillingPeriod nullable before start), nextBillingAt, lastInvoiceNumber, cancelAtPeriodEnd, canceledAt, endedAt, version, createdAt, updatedAt` and methods `openPeriod(BillingPeriod period, Instant nextBillingAt, Instant at) → int invoiceNumber` (advances period and invoice counter), `markPastDue(Instant)`, `recover(Instant)`, `requestCancelAtPeriodEnd(Instant)`, `cancelNow(Instant)`, `end(Instant)`, `changeMethod(PaymentMethod, String cardId, Instant)`, `isBillable()` (ACTIVE or PAST_DUE); `SubscriptionTransitions.allowed(from, to)`; `SubscriptionFactory.fromRequest(MerchantId, ProviderEnvironment, Customer, Plan, PaymentMethod, String cardId, LocalDate startDay, Clock)` (validates: plan active → `PLAN_INACTIVE`; CARD needs cardId → `CARD_REQUIRED`; BOLECODE needs `customer.hasAddress()` → `CUSTOMER_ADDRESS_REQUIRED`); `SubscriptionService.create(...) → Subscription` (persists, enqueues `BILL_SUBSCRIPTION` at `nextBillingAt`, emits `subscription.created`), `get`, `listByCustomer`, `cancel(MerchantId, id, boolean atPeriodEnd)`, `changeMethod(MerchantId, id, PaymentMethod, String cardId)`, `invoicesOf(MerchantId, id) → List<Order>`, `dunningOf(MerchantId, id) → List<DunningAttempt>`; `SubscriptionRepository.insert/update/find(MerchantId,id)/findById(id)/findByCustomer/existsActiveForCustomer(MerchantId, customerId)`; `DunningAttempt(id, subscriptionId, orderId, attempt, scheduledAt, ranAt, outcome (DunningOutcome nullable), paymentId)` record with `DunningOutcome { PAID, DECLINED, ISSUED, EXPIRED, SKIPPED }` and `DunningAttemptRepository.insert/update/findById/findBySubscription/findPendingByOrder(orderId) → Optional<DunningAttempt>`.

- [ ] **Step 1: Migration** `V304__subscriptions.sql`:

```sql
-- A subscription is customer + plan + method; each cycle becomes an order with invoice_number
-- (spec §4.5). anchor_day keeps the day the subscription started so a month that is too short
-- (31 → Feb 28) does not shorten every month after it. ENDED is the natural end after
-- cancel_at_period_end; CANCELED is immediate.
CREATE TABLE billing.subscriptions (
    id                   CHAR(26)     PRIMARY KEY,
    merchant_id          CHAR(26)     NOT NULL,
    environment          VARCHAR(10)  NOT NULL,
    customer_id          CHAR(26)     NOT NULL,
    plan_id              CHAR(26)     NOT NULL,
    method               VARCHAR(10)  NOT NULL,
    card_id              CHAR(26),
    status               VARCHAR(10)  NOT NULL,
    anchor_day           SMALLINT     NOT NULL,
    current_period_start DATE,
    current_period_end   DATE,
    next_billing_at      TIMESTAMPTZ,
    last_invoice_number  INTEGER      NOT NULL,
    cancel_at_period_end BOOLEAN      NOT NULL,
    canceled_at          TIMESTAMPTZ,
    ended_at             TIMESTAMPTZ,
    version              BIGINT       NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_subscriptions_customer ON billing.subscriptions (merchant_id, environment, customer_id);
CREATE INDEX idx_subscriptions_due ON billing.subscriptions (next_billing_at)
 WHERE status IN ('ACTIVE', 'PAST_DUE');

-- What dunning did and when (spec §4.6): the merchant reads it on the subscription.
CREATE TABLE billing.dunning_attempts (
    id              CHAR(26)    PRIMARY KEY,
    subscription_id CHAR(26)    NOT NULL,
    order_id        CHAR(26)    NOT NULL,
    attempt         SMALLINT    NOT NULL,
    scheduled_at    TIMESTAMPTZ NOT NULL,
    ran_at          TIMESTAMPTZ,
    outcome         VARCHAR(20),
    payment_id      CHAR(26)
);
CREATE INDEX idx_dunning_subscription ON billing.dunning_attempts (subscription_id, attempt);
CREATE UNIQUE INDEX uq_dunning_pending_order ON billing.dunning_attempts (order_id) WHERE outcome IS NULL;
```

- [ ] **Step 2: Failing calendar test** `BillingCalendarTest.java`:

```java
package com.gateway.billing.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.billing.plan.PlanInterval;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BillingCalendarTest {

  @Test
  void aMonthlyPeriodStartedOnThe31stShortensInFebruaryAndReturnsInMarch() {
    BillingPeriod january = BillingCalendar.firstPeriod(LocalDate.of(2026, 1, 31), PlanInterval.MONTH, 1, 31);
    BillingPeriod february = BillingCalendar.next(january, PlanInterval.MONTH, 1, 31);
    BillingPeriod march = BillingCalendar.next(february, PlanInterval.MONTH, 1, 31);

    assertThat(january.end()).isEqualTo(LocalDate.of(2026, 2, 28));
    assertThat(february).isEqualTo(new BillingPeriod(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 31)));
    assertThat(march).isEqualTo(new BillingPeriod(LocalDate.of(2026, 3, 31), LocalDate.of(2026, 4, 30)));
  }

  @Test
  void aYearlyPeriodOnFebruary29thFallsBackToThe28th() {
    BillingPeriod first = BillingCalendar.firstPeriod(LocalDate.of(2028, 2, 29), PlanInterval.YEAR, 1, 29);

    assertThat(first.end()).isEqualTo(LocalDate.of(2029, 2, 28));
  }

  @Test
  void weeksAndDaysAddPlainly() {
    assertThat(BillingCalendar.firstPeriod(LocalDate.of(2026, 10, 2), PlanInterval.WEEK, 2, 2).end())
        .isEqualTo(LocalDate.of(2026, 10, 16));
    assertThat(BillingCalendar.firstPeriod(LocalDate.of(2026, 10, 2), PlanInterval.DAY, 10, 2).end())
        .isEqualTo(LocalDate.of(2026, 10, 12));
  }

  @Test
  void billingInstantIsTheConfiguredSaoPauloHour() {
    assertThat(BillingCalendar.billingInstant(LocalDate.of(2026, 10, 2), 3))
        .isEqualTo(java.time.Instant.parse("2026-10-02T06:00:00Z"));
    assertThat(BillingCalendar.endOfDay(LocalDate.of(2026, 10, 2)))
        .isEqualTo(java.time.Instant.parse("2026-10-03T02:59:59Z"));
  }
}
```

- [ ] **Step 3: Run, expect compile failure.**

- [ ] **Step 4: `BillingPeriod` and `BillingCalendar`**

```java
package com.gateway.billing.subscription;

import java.time.LocalDate;

/** {@code end} is exclusive: it is the first day of the next period, and the day it is billed. */
public record BillingPeriod(LocalDate start, LocalDate end) {}
```

```java
package com.gateway.billing.subscription;

import com.gateway.billing.plan.PlanInterval;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * Cycle arithmetic in São Paulo time, like the Bolecode (spec §6.1). Month and year steps are
 * computed from the anchor day, not from the previous end, so a short month never shortens the
 * ones after it: 31 Jan → 28 Feb → 31 Mar, not 28 Mar.
 */
public final class BillingCalendar {
  static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

  private BillingCalendar() {}

  public static BillingPeriod firstPeriod(
      LocalDate start, PlanInterval interval, int count, int anchorDay) {
    return new BillingPeriod(start, advance(start, interval, count, anchorDay));
  }

  public static BillingPeriod next(
      BillingPeriod current, PlanInterval interval, int count, int anchorDay) {
    return new BillingPeriod(current.end(), advance(current.end(), interval, count, anchorDay));
  }

  private static LocalDate advance(LocalDate from, PlanInterval interval, int count, int anchorDay) {
    return switch (interval) {
      case DAY -> from.plusDays(count);
      case WEEK -> from.plusWeeks(count);
      case MONTH -> onAnchor(YearMonth.from(from).plusMonths(count), anchorDay);
      case YEAR -> onAnchor(YearMonth.from(from).plusYears(count), anchorDay);
    };
  }

  private static LocalDate onAnchor(YearMonth month, int anchorDay) {
    return month.atDay(Math.min(anchorDay, month.lengthOfMonth()));
  }

  public static Instant billingInstant(LocalDate day, int hour) {
    return day.atTime(LocalTime.of(hour, 0)).atZone(SAO_PAULO).toInstant();
  }

  public static Instant endOfDay(LocalDate day) {
    return day.atTime(LocalTime.of(23, 59, 59)).atZone(SAO_PAULO).toInstant();
  }

  public static LocalDate today(Instant now) {
    return now.atZone(SAO_PAULO).toLocalDate();
  }
}
```

- [ ] **Step 5: Transitions test and table.** `SubscriptionTransitionsTest`: `ACTIVE→PAST_DUE`, `PAST_DUE→ACTIVE`, `ACTIVE|PAST_DUE→CANCELED`, `ACTIVE|PAST_DUE→ENDED` allowed; `CANCELED`/`ENDED` never move; `ACTIVE→ACTIVE` not allowed. Table class mirrors `OrderTransitions`.

- [ ] **Step 6: `Subscription` and `SubscriptionFactory`.** Class in the `Order` style. Key methods:

```java
  /** Opens the next cycle: the invoice number this period gets, and when the following one bills. */
  public int openPeriod(BillingPeriod period, Instant nextBillingAt, Instant at) {
    requireBillable();
    this.currentPeriod = period;
    this.nextBillingAt = nextBillingAt;
    this.lastInvoiceNumber++;
    touch(at);
    return lastInvoiceNumber;
  }

  public void markPastDue(Instant at) { transition(SubscriptionStatus.PAST_DUE, at); }

  public void recover(Instant at) {
    if (status == SubscriptionStatus.PAST_DUE) { transition(SubscriptionStatus.ACTIVE, at); }
  }

  public void requestCancelAtPeriodEnd(Instant at) {
    requireBillable();
    this.cancelAtPeriodEnd = true;
    this.canceledAt = at;
    touch(at);
  }

  public void cancelNow(Instant at) { transition(SubscriptionStatus.CANCELED, at); this.canceledAt = at; this.nextBillingAt = null; }

  public void end(Instant at) { transition(SubscriptionStatus.ENDED, at); this.endedAt = at; this.nextBillingAt = null; }

  public void changeMethod(PaymentMethod newMethod, String newCardId, Instant at) {
    requireBillable();
    this.method = newMethod;
    this.cardId = newCardId;
    touch(at);
  }

  public boolean isBillable() { return status == SubscriptionStatus.ACTIVE || status == SubscriptionStatus.PAST_DUE; }
```

`touch` bumps `version` and `updatedAt`; `transition` checks `SubscriptionTransitions.allowed` then `touch`. `SubscriptionFactory.fromRequest` (`SubscriptionFactoryTest` covers the three refusals and the defaults):

```java
  public static Subscription fromRequest(
      MerchantId merchantId, ProviderEnvironment environment, Customer customer, Plan plan,
      PaymentMethod method, String cardId, LocalDate startDay, Clock clock) {
    if (!plan.active()) {
      throw new DomainException("PLAN_INACTIVE", "plan_id " + plan.id() + " is inactive");
    }
    if (method == PaymentMethod.CARD && cardId == null) {
      throw new DomainException("CARD_REQUIRED", "card_id is required for method CARD");
    }
    if (method != PaymentMethod.CARD && cardId != null) {
      throw new IllegalArgumentException("card_id applies to method CARD only");
    }
    if (method == PaymentMethod.BOLECODE && !customer.hasAddress()) {
      throw new DomainException("CUSTOMER_ADDRESS_REQUIRED", "customer.address is required for BOLECODE");
    }
    LocalDate firstBillingDay = startDay.plusDays(plan.trialDays());

    return new Subscription(
        Ulid.next(), merchantId, environment, customer.id(), plan.id(), method, cardId,
        firstBillingDay.getDayOfMonth(), firstBillingDay, clock.instant());
  }
```

The constructor sets `status = ACTIVE`, `lastInvoiceNumber = 0`, `currentPeriod = null`, `nextBillingAt` computed by the **service** (it needs `BillingProperties.billingHour()`); the factory stores `firstBillingDay` in a field `startDay` the service reads once. Card ownership (`CARD_NOT_OWNED_BY_CUSTOMER`) is checked in the service, which has `SavedCards`.

- [ ] **Step 7: Persistence** following the `Order` pattern (`updateIfVersion` on every mutable column). `SubscriptionJpaRepository.existsByMerchantIdAndCustomerIdAndStatusIn(String, String, Collection<String>)`. `DunningAttemptRepository` is a simple insert/update/find set; `findPendingByOrder` = `outcome IS NULL`.

- [ ] **Step 8: Failing service test** `SubscriptionServiceIntegrationTest.java`: creates customer + plan (monthly, trial 0) and subscription with `PIX`; asserts status `ACTIVE`, `nextBillingAt` equals `BillingCalendar.billingInstant(today, 3)` and **one** `BILL_SUBSCRIPTION` job queued for the id and `subscription.created` emitted; a `CARD` subscription with a card of another customer → `CARD_NOT_OWNED_BY_CUSTOMER`; `cancel(atPeriodEnd=false)` → `CANCELED`, `nextBillingAt` null, event `subscription.canceled`; `cancel(atPeriodEnd=true)` → still `ACTIVE` with `cancelAtPeriodEnd` true; `changeMethod` to `BOLECODE` on a customer without address → `CUSTOMER_ADDRESS_REQUIRED`; `ActiveSubscriptionsCheck.hasActive` true for the customer, and `CustomerService.delete` on him → `CUSTOMER_HAS_ACTIVE_SUBSCRIPTION`.

- [ ] **Step 9: `SubscriptionService`**

```java
public class SubscriptionService {
  private final SubscriptionRepository subscriptions;
  private final DunningAttemptRepository dunning;
  private final OrderRepository orders;
  private final SavedCards savedCards;
  private final JobRepository jobs;
  private final BillingEvents events;
  private final BillingProperties properties;
  private final UnitOfWork unitOfWork;
  private final Clock clock;
  // nine deps: over the ~7 guideline. Split now: CustomerService/PlanService lookups stay in the
  // API layer's SubscriptionRequests (Task 11) which passes Customer and Plan in; dunning and
  // invoice reads go to SubscriptionQueries (get, listByCustomer, invoicesOf, dunningOf).
```

So this task produces **two** classes: `SubscriptionService` (create, cancel, changeMethod — deps: `SubscriptionRepository, SavedCards, JobRepository, BillingEvents, BillingProperties, UnitOfWork, Clock` = 7) and `SubscriptionQueries` (get, listByCustomer, invoicesOf, dunningOf — deps: `SubscriptionRepository, OrderRepository, DunningAttemptRepository`). `create`:

```java
  public Subscription create(Subscription subscription, Customer customer) {
    if (subscription.cardId() != null) {
      SavedCard card = savedCards.get(subscription.merchantId(), subscription.cardId());
      if (!customer.id().equals(card.customerId())) {
        throw new DomainException(
            "CARD_NOT_OWNED_BY_CUSTOMER", "card_id " + card.id() + " does not belong to customer_id " + customer.id());
      }
    }
    Instant firstBilling =
        BillingCalendar.billingInstant(subscription.startDay(), properties.billingHour());
    // A start today bills now, not at 03:00 tomorrow: the merchant pressed the button.
    Instant now = clock.instant();
    Instant nextBillingAt = firstBilling.isBefore(now) ? now : firstBilling;
    subscription.scheduleFirstBilling(nextBillingAt, now);

    return unitOfWork.inTransaction(
        () -> {
          subscriptions.insert(subscription);
          jobs.enqueue(Job.billSubscription(subscription.id(), nextBillingAt, clock));
          events.emit(subscription.merchantId(), "subscription.created", subscription.id(),
              subscription.id(), json(subscription));
          return subscription;
        });
  }
```

`json(Subscription)` → `id, status, customer_id, plan_id, method, card_id, current_period {start,end}, next_billing_at, cancel_at_period_end, created_at`. `JobType.BILL_SUBSCRIPTION` + `Job.billSubscription(String subscriptionId, Instant when, Clock)` added in `gateway-payments` (and the `ServiceTestConfig` stub handler). `ActiveSubscriptions implements ActiveSubscriptionsCheck` delegates to `existsActiveForCustomer`; replace the stub bean.

- [ ] **Step 10: Run** `./mvnw -B -o -pl gateway-payments,gateway-billing -am verify` → green. **Commit** `feat(billing): subscriptions with a São Paulo billing calendar`.

---

### Task 9: `BillSubscriptionJob` and `InvoiceIssuer`

**Files:**
- Create: `subscription/billing/InvoiceIssuer.java`, `subscription/billing/BillSubscriptionJob.java`, `subscription/billing/SubscriptionBilling.java`, `subscription/billing/IssuedInvoice.java`
- Modify: `payments` `IdempotencyService`? No: the synthetic idempotency key lives in **billing**: `OrderPayments.attempt` gains an overload taking `String idempotencyKey` that is passed to the flows? The flows do not take keys; idempotency is an HTTP filter. So the rerun guard is structural instead: `uq_orders_invoice` (one order per subscription+invoice number) and `uq_payments_order_active` + "an invoice order that already has a COMPLETED or active attempt is not attempted again". Document this in `SubscriptionBilling`; spec §6 "synthetic key" is amended to this (record in DECISOES, Task 12).
- Modify: `BillingConfiguration.java`
- Test: `subscription/billing/SubscriptionBillingIntegrationTest.java`

**Interfaces:**
- Consumes: `OrderFactory.invoice`, `OrderRepository.insert/findBySubscription`, `OrderPayments.attempt`, `PaymentQueries.activeAttempt/listByOrder`, `BillingCalendar`, `BillingProperties.cardRecurringEnabled()`, `Job.billSubscription`, `DunningSchedule` (Task 10 — in this task, a failed card attempt only marks `PAST_DUE` and emits; the dunning row is Task 10's; keep a `DunningStarter` port with a no-op bean, replaced in Task 10).
- Produces: `SubscriptionBilling.billOne(String subscriptionId, Instant now) → boolean` (true = done), `InvoiceIssuer.issue(Subscription, Order) → IssuedInvoice` (`IssuedInvoice(Payment payment, boolean charged, String declineCode)` where `charged` is true only for a COMPLETED card), `BillSubscriptionJob implements JobHandler` (`BILL_SUBSCRIPTION`), `DunningStarter { void firstFailure(Subscription, Order, String paymentId, Instant now); }`.

- [ ] **Step 1: Failing test** `SubscriptionBillingIntegrationTest.java` (extends `BillingIntegrationTestBase`; helper builds customer with address, a monthly plan of 9900 cents, and a subscription; `RecordingCardProvider` approves card `...3171`; a stored card for the customer is made through a `save_card` payment then `CustomerService.create` adoption, as in Task 3):

```java
  @Test
  void aCardCycleCreatesTheInvoiceChargesItAndSchedulesTheNext() {
    Subscription subscription = cardSubscription();

    boolean done = billing.billOne(subscription.id(), clock.instant());

    assertThat(done).isTrue();
    Order invoice = invoicesOf(subscription).get(0);
    assertThat(invoice.invoiceNumber()).isEqualTo(1);
    assertThat(invoice.amount()).isEqualTo(Money.brl(9900));
    assertThat(paymentQueries.listByOrder(merchant, invoice.id())).singleElement()
        .extracting(Payment::status).isEqualTo(PaymentStatus.COMPLETED);
    Subscription after = queries.get(merchant, subscription.id());
    assertThat(after.lastInvoiceNumber()).isEqualTo(1);
    assertThat(after.nextBillingAt()).isEqualTo(
        BillingCalendar.billingInstant(after.currentPeriod().end(), 3));
    assertThat(jobsFor("BILL_SUBSCRIPTION", subscription.id())).isEqualTo(1); // the next one; the first was consumed by the test calling billOne directly
    assertThat(outboxTypes(invoice.id())).contains("order.created", "invoice.created");
  }

  @Test
  void aPixCycleIssuesTheChargeAndPublishesTheCopyPaste() {
    Subscription subscription = pixSubscription();

    billing.billOne(subscription.id(), clock.instant());

    String invoiceCreated = outboxPayload(invoicesOf(subscription).get(0).id(), "invoice.created");
    assertThat(invoiceCreated).contains("\"method\":\"PIX\"").contains("copia_e_cola");
  }

  @Test
  void aDeclinedCardLeavesTheInvoiceOpenAndTheSubscriptionPastDue() {
    Subscription subscription = cardSubscription();
    cards.nextAuthorizeStatus(CardStatus.DENIED);

    billing.billOne(subscription.id(), clock.instant());

    assertThat(invoicesOf(subscription).get(0).status()).isEqualTo(OrderStatus.OPEN);
    assertThat(queries.get(merchant, subscription.id()).status()).isEqualTo(SubscriptionStatus.PAST_DUE);
    assertThat(outboxTypes(subscription.id())).contains("subscription.past_due");
  }

  @Test
  void aRerunAfterTheInvoiceExistsDoesNotChargeTwice() {
    Subscription subscription = cardSubscription();
    billing.billOne(subscription.id(), clock.instant());
    // Simulate the crash between transaction 1 and the attempt: rewind next_billing_at.
    jdbc.update("UPDATE billing.subscriptions SET next_billing_at = ? WHERE id = ?",
        java.sql.Timestamp.from(clock.instant()), subscription.id());

    billing.billOne(subscription.id(), clock.instant());

    assertThat(invoicesOf(subscription)).hasSize(1);
    assertThat(paymentQueries.listByOrder(merchant, invoicesOf(subscription).get(0).id())).hasSize(1);
  }

  @Test
  void cancelAtPeriodEndEndsInsteadOfBilling() {
    Subscription subscription = pixSubscription();
    billing.billOne(subscription.id(), clock.instant());
    service.cancel(merchant, subscription.id(), true);
    clock.advance(Duration.ofDays(31));

    billing.billOne(subscription.id(), clock.instant());

    assertThat(queries.get(merchant, subscription.id()).status()).isEqualTo(SubscriptionStatus.ENDED);
    assertThat(invoicesOf(subscription)).hasSize(1);
  }

  @Test
  void withRecurringDisabledACardCycleIsNotAttempted() {
    // BillingProperties.cardRecurringEnabled=false is set through a @TestPropertySource on a
    // nested test class; the invoice is created, no attempt is made, invoice.created says
    // "charged": false with reason CARD_RECURRING_UNSUPPORTED, subscription goes PAST_DUE.
  }
```

Write the last test as a nested `@SpringBootTest(properties = "gateway.billing.card-recurring-enabled=false")` class or, simpler, make `SubscriptionBilling` take `BillingProperties` and in the test construct a second `SubscriptionBilling` by hand with `new BillingProperties(null, 0, false)` and the autowired collaborators. Prefer the hand-built instance: no second context.

- [ ] **Step 2: Run, expect compile failures.**

- [ ] **Step 3: Implement.**

`IssuedInvoice.java`: `public record IssuedInvoice(Payment payment, boolean charged, String declineCode) {}`.

`InvoiceIssuer.java`:
```java
package com.gateway.billing.subscription.billing;

import com.gateway.billing.order.AttemptRequest;
import com.gateway.billing.order.Order;
import com.gateway.billing.order.OrderPayments;
import com.gateway.billing.subscription.BillingCalendar;
import com.gateway.billing.subscription.Subscription;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.card.CardDeclinedException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One attempt for one invoice, by the subscription's method (spec §6 step 2). Outside any
 * transaction: the bank is called here. A card decline is an outcome, not an exception, so the
 * caller can book PAST_DUE in its own transaction.
 */
public class InvoiceIssuer {
  private final OrderPayments payments;

  public InvoiceIssuer(OrderPayments payments) {
    this.payments = payments;
  }

  public IssuedInvoice issue(Subscription subscription, Order invoice, Instant now, Instant until) {
    AttemptRequest request = requestFor(subscription, invoice, now, until);
    try {
      Payment payment = payments.attempt(invoice, request, EventSource.SYSTEM);
      return new IssuedInvoice(payment, payment.status() == PaymentStatus.COMPLETED, null);
    } catch (CardDeclinedException declined) {
      return new IssuedInvoice(null, false, declined.declineCode());
    }
  }

  private static AttemptRequest requestFor(
      Subscription subscription, Order invoice, Instant now, Instant until) {
    return switch (subscription.method()) {
      case CARD -> new AttemptRequest.RecurringCardAttempt(subscription.cardId(), 1);
      case PIX -> new AttemptRequest.PixAttempt((int) Math.min(86400, Duration.between(now, until).toSeconds()));
      case BOLECODE -> {
        LocalDate due = invoice.periodEnd() == null ? BillingCalendar.today(until) : invoice.periodEnd();
        int limitDays = (int) Math.max(1, Duration.between(now, until).toDays() + 7);
        yield new AttemptRequest.BolecodeAttempt(due, limitDays);
      }
    };
  }
}
```

`SubscriptionBilling.java`:
```java
package com.gateway.billing.subscription.billing;

/**
 * The cycle (spec §6). Transaction 1 opens the period and creates the invoice order; the bank is
 * called outside; transaction 2 records what happened. A rerun after a crash is safe without any
 * key: uq_orders_invoice makes the second insert impossible, and an invoice that already has an
 * attempt (active or completed) is not attempted again — that is the idempotency, not a header.
 */
public class SubscriptionBilling {
  // deps: SubscriptionRepository subscriptions, OrderRepository orders, PaymentQueries payments,
  //       InvoiceIssuer issuer, DunningStarter dunning, BillingEvents events,
  //       BillingProperties properties, PlanService plans, JobRepository jobs, UnitOfWork, Clock
  // → 11. Split: CycleOpener (transaction 1: subscriptions, orders, plans, jobs, properties, clock)
  //   and SubscriptionBilling (orchestration: CycleOpener, OrderRepository, PaymentQueries,
  //   InvoiceIssuer, DunningStarter, BillingEvents, UnitOfWork, Clock = 8; acceptable, document).

  public boolean billOne(String subscriptionId, Instant now) {
    Optional<OpenedCycle> opened = cycleOpener.open(subscriptionId, now); // tx 1
    if (opened.isEmpty()) {
      return true; // not billable, future, or ended by cancel_at_period_end
    }
    Subscription subscription = opened.get().subscription();
    Order invoice = opened.get().invoice();

    if (payments.listByOrder(invoice.merchantId(), invoice.id()).stream()
        .anyMatch(p -> p.status() == PaymentStatus.COMPLETED || p.status().isActive())) {
      return true; // a rerun: the attempt already happened
    }
    if (subscription.method() == PaymentMethod.CARD && !properties.cardRecurringEnabled()) {
      recordUnsupported(subscription, invoice, now); // invoice.created charged=false reason=CARD_RECURRING_UNSUPPORTED, PAST_DUE
      return true;
    }

    IssuedInvoice issued = issuer.issue(subscription, invoice, now, invoice.expiresAt()); // bank

    unitOfWork.run(() -> record(subscription, invoice, issued, now)); // tx 2
    return true;
  }
```

`CycleOpener.open` (transaction, `FOR UPDATE` read of the subscription via `SubscriptionRepository.lock(id)` — add it): returns empty when not billable or `nextBillingAt` is after `now`; when `cancelAtPeriodEnd` → `end(now)`, update, emit `subscription.ended`, return empty; else compute `period = currentPeriod == null ? BillingCalendar.firstPeriod(startDay, plan.interval(), plan.intervalCount(), anchorDay) : BillingCalendar.next(currentPeriod, ...)`, `invoiceNumber = subscription.openPeriod(period, BillingCalendar.billingInstant(period.end(), billingHour), now)`, create `OrderFactory.invoice(..., expiresAt = BillingCalendar.endOfDay(period.end().minusDays(1)))`, insert order (on `DataIntegrityViolationException` from `uq_orders_invoice` → load the existing invoice instead: the rerun case), update subscription, enqueue `Job.billSubscription(id, nextBillingAt)`, emit `order.created`. `PaymentStatus.isActive()` — add to `payments` `PaymentStatus` (`CREATED, PENDING, AUTHORIZED`) if absent. `record(...)`: emit `invoice.created` with `{invoice_id, subscription_id, invoice_number, amount, method, period {start,end}, payment_id, charged, decline_code, pix {copia_e_cola}, boleto {linha_digitavel, due_date}}` (read the payment's `pix()`/`boleto()` details for the two fields, null for card); if `!charged` and the payment is null (declined) or `declineCode != null`: `subscription.markPastDue(now)` (if ACTIVE), update, emit `subscription.past_due`, `dunning.firstFailure(subscription, invoice, null, now)`.

`BillSubscriptionJob` mirrors `ExpireOrderJob` with `JobType.BILL_SUBSCRIPTION`. `DunningStarter` port + no-op bean (comment: Task 10).

- [ ] **Step 4: Wire** `InvoiceIssuer`, `CycleOpener`, `SubscriptionBilling`, `BillSubscriptionJob`, `DunningStarter` stub. Remove the payments-test stub handler for `BILL_SUBSCRIPTION`? No: `gateway-payments`' own test context still has no billing beans; keep the stub there.

- [ ] **Step 5: Run** `./mvnw -B -o -pl gateway-payments,gateway-billing -am verify` → green. **Commit** `feat(billing): the subscription cycle creates the invoice and charges it by the subscription's method`.

---

### Task 10: Dunning

**Files:**
- Create: `subscription/billing/DunningSchedule.java`, `DunningRetryJob.java`, `Dunning.java` (implements `DunningStarter` and `InvoiceSettlementHook`)
- Modify: `payments` `JobType` (+`DUNNING_RETRY`), `Job.dunningRetry(String attemptId, Instant when, Clock)`, `ServiceTestConfig` stub
- Modify: `BillingConfiguration.java` (replace both stubs with `Dunning`)
- Test: `subscription/billing/DunningScheduleTest.java`, `DunningIntegrationTest.java`

**Interfaces:**
- Consumes: `BillingProperties.dunningRetryDays()`, `DunningAttemptRepository`, `SubscriptionRepository`, `OrderRepository`, `InvoiceIssuer.issue`, `PaymentQueries.activeAttempt`, `BillingEvents`, `JobRepository`, `UnitOfWork`, `Clock`.
- Produces: `DunningSchedule.nextAfter(int attemptsSoFar, Instant failedAt) → Optional<Instant>` (empty when `attemptsSoFar >= retryDays.size()`); `Dunning.firstFailure(...)` creates attempt 1 and enqueues `DUNNING_RETRY`; `Dunning.invoicePaid(order, at)` → subscription `recover`, `subscription.recovered`, pending attempt `SKIPPED`; `Dunning.invoiceAttemptFailed(order, paymentId, eventType, at)` → if no pending attempt, `firstFailure` (for Pix/Bolecode expiry that was not a sync decline), else nothing (the job handles it); `Dunning.retryOne(String attemptId, Instant now) → boolean`; `DunningRetryJob implements JobHandler` (`DUNNING_RETRY`; `run` = `retryOne`; `afterFailure` = backoff; `notYet` when an attempt is active).

- [ ] **Step 1: Failing unit test** `DunningScheduleTest`: with `retryDays = [1,3,7]` and `failedAt = 2026-10-02T15:00Z`, `nextAfter(0)` = `+1 day` at the billing hour (03:00 SP next day → use `BillingCalendar.billingInstant(today(failedAt).plusDays(1), hour)`), `nextAfter(2)` = `+7 days`, `nextAfter(3)` empty.

- [ ] **Step 2: Failing integration test** `DunningIntegrationTest` (card subscription declined twice then approved, using `cards.nextAuthorizeStatus(CardStatus.DENIED)` before each `retryOne`):
  - after `billOne` with a decline: one `dunning_attempts` row (attempt 1, outcome null, `scheduled_at` = day+1 03:00 SP) and one `DUNNING_RETRY` job for its id;
  - `clock.advance(1 day)`, decline again, `retryOne(attempt1)` → attempt 1 `DECLINED`, attempt 2 created with `scheduled_at` day+3, job queued;
  - approve, `retryOne(attempt2)` → attempt 2 `PAID` after `OrderSettlement.on(payment.completed event)` (the test feeds the outbox message like Task 5, or lets the attempt's COMPLETED be read directly: `retryOne` records `PAID` when the issued payment is `COMPLETED`), subscription `ACTIVE`, events `subscription.recovered`;
  - exhausted: three declines → `subscription.dunning_exhausted` emitted, status still `PAST_DUE`, order still `OPEN`;
  - Pix invoice: `payment.expired` event through `OrderSettlement` starts dunning (attempt 1) when none pending; `retryOne` with an active Pix attempt returns `false` (not yet);
  - method changed to BOLECODE on a customer without address between failures: `retryOne` records outcome `SKIPPED` with the attempt's `payment_id` null and emits `subscription.dunning_exhausted`? No: records `SKIPPED` and schedules the next attempt anyway (the merchant may fix the address); when attempts run out, exhausted. Assert the job does not throw and the row says `SKIPPED`.

- [ ] **Step 3: Implement.** `DunningSchedule`:

```java
public class DunningSchedule {
  private final BillingProperties properties;
  public DunningSchedule(BillingProperties properties) { this.properties = properties; }

  public Optional<Instant> nextAfter(int attemptsSoFar, Instant failedAt) {
    List<Integer> days = properties.dunningRetryDays();
    if (attemptsSoFar >= days.size()) { return Optional.empty(); }
    LocalDate day = BillingCalendar.today(failedAt).plusDays(days.get(attemptsSoFar));
    return Optional.of(BillingCalendar.billingInstant(day, properties.billingHour()));
  }

  public int maxAttempts() { return properties.dunningRetryDays().size(); }
}
```

`Dunning` (deps: `DunningAttemptRepository attempts, SubscriptionRepository subscriptions, OrderRepository orders, PaymentQueries payments, InvoiceIssuer issuer, DunningSchedule schedule, JobRepository jobs, BillingEvents events, UnitOfWork, Clock` = 10 → split: `DunningLedger` (attempts rows + jobs + schedule: `start(subscription, order, now)`, `close(attempt, outcome, paymentId, now)`, `scheduleNext(...)`, `pendingFor(orderId)`) and `Dunning` (orchestration with `DunningLedger, SubscriptionRepository, OrderRepository, PaymentQueries, InvoiceIssuer, BillingEvents, UnitOfWork, Clock` = 8). `retryOne`:

```java
  public boolean retryOne(String attemptId, Instant now) {
    Context context = unitOfWork.inTransaction(() -> load(attemptId)); // attempt, order, subscription
    if (context.attempt().outcome() != null) { return true; }
    if (!context.order().isOpen() || !context.subscription().isBillable()) {
      unitOfWork.run(() -> ledger.close(context.attempt(), DunningOutcome.SKIPPED, null, now));
      return true;
    }
    if (payments.activeAttempt(context.order().id()).isPresent()) {
      return false; // a boleto someone may still pay is not replaced (spec §7 step 2)
    }

    IssuedInvoice issued;
    try {
      issued = issuer.issue(context.subscription(), context.order(), now, nextDeadline(context, now));
    } catch (DomainException refused) { // e.g. CUSTOMER_ADDRESS_REQUIRED after a method change
      unitOfWork.run(() -> { ledger.close(context.attempt(), DunningOutcome.SKIPPED, null, now); scheduleOrExhaust(context, now); });
      return true;
    }

    unitOfWork.run(() -> {
      if (issued.charged()) {
        ledger.close(context.attempt(), DunningOutcome.PAID, issued.payment().id(), now);
        // The order itself becomes PAID through OrderSettlement when payment.completed relays.
      } else if (issued.payment() != null) {
        ledger.close(context.attempt(), DunningOutcome.ISSUED, issued.payment().id(), now);
        events.emit(..., "invoice.updated", order.id(), order.id(), invoiceJson(order, issued));
        // Expiry of this Pix/boleto comes back as payment.expired → invoiceAttemptFailed → next attempt.
      } else {
        ledger.close(context.attempt(), DunningOutcome.DECLINED, null, now);
        scheduleOrExhaust(context, now);
      }
    });
    return true;
  }
```

`scheduleOrExhaust`: `schedule.nextAfter(attempt.attempt(), now)` → new row + job, or emit `subscription.dunning_exhausted` (status stays `PAST_DUE`). `nextDeadline`: the next scheduled retry instant if any, else `order.expiresAt()`. `invoicePaid`: `subscription.recover(at)`, update, emit `subscription.recovered` if it was `PAST_DUE`; pending attempt → `SKIPPED`. `invoiceAttemptFailed`: if `ledger.pendingFor(order.id()).isEmpty()` → `firstFailure` (mark `PAST_DUE` + emit if ACTIVE); if a pending attempt exists with outcome `ISSUED`-then-expired semantics: the pending row is the **next** scheduled one, so nothing to do. `firstFailure`: `markPastDue` if ACTIVE (+ event), `ledger.start(subscription, order, now)` (attempt 1 at `schedule.nextAfter(0, now)`; if the schedule is empty → exhausted at once).

`DunningRetryJob`: `type() == DUNNING_RETRY`, `run` → `retryOne`, `afterFailure` → `backoff.retry`. `JobType.DUNNING_RETRY`, `Job.dunningRetry`.

- [ ] **Step 4: Wire** `DunningSchedule`, `DunningLedger`, `Dunning` (as both `DunningStarter` and `InvoiceSettlementHook` beans: declare one `Dunning` bean and two `@Bean` methods returning it typed as each port, or declare the ports as `@Bean DunningStarter dunningStarter(Dunning d) { return d; }` — the second is clearer), `DunningRetryJob`. Remove the two stub beans.

- [ ] **Step 5: Run** `./mvnw -B -o -pl gateway-payments,gateway-billing -am verify` → green. **Commit** `feat(billing): dunning retries a failed invoice on configured days and never cancels`.

---

### Task 11: API — customers, orders, plans, subscriptions

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/api/customer/CustomersController.java`, `api/customer/dto/{CustomerRequest, CustomerPatchRequest, CustomerResponse, AddressFields}.java`
- Create: `api/order/OrdersController.java`, `api/order/dto/{CreateOrderRequest, OrderResponse, OrderAttemptRequest (sealed by method, mirrors CreatePaymentRequest minus amount/currency/customer)}.java`
- Create: `api/plan/PlansController.java`, `api/plan/dto/{PlanRequest, PlanPatchRequest, PlanResponse}.java`
- Create: `api/subscription/SubscriptionsController.java`, `api/subscription/dto/{SubscriptionRequest, SubscriptionCancelRequest, SubscriptionPatchRequest, SubscriptionResponse, DunningAttemptResponse}.java`
- Modify: `api/support/ErrorHandler.java` (`STATUS_BY_CODE` + `CustomerExistsException` and `OrderHasActivePaymentException` handlers adding `customer_id` / `payment_id` properties), `api/support/IdempotencyFilter.java`
- Modify: `api/payment/dto/PaymentResponse.java` (+`order_id`)
- Test: `gateway-app/src/test/java/com/gateway/app/api/order/dto/OrderAttemptRequestTest.java`, `api/customer/dto/CustomerRequestTest.java`, and `BillingApiIntegrationTest.java` (`@SpringBootTest` RANDOM_PORT like `CardFlowIntegrationTest`, WireMock for the Cielo)

**Interfaces:**
- Consumes: everything from Tasks 3–10; `MerchantContext.current()`; `PaymentsController.providerEnvironment(ApiKeyEnvironment)` (make it `public static` or copy it into a shared `api/support/Environments.java` — do the latter and have `PaymentsController` call it too; that is a move, commit it separately as `refactor(app): environment mapping shared by controllers`).
- Produces: the routes of spec §5, exactly.

- [ ] **Step 1: `IdempotencyFilter`.** Replace the two-pattern check with one pattern:

```java
  private static final Pattern IDEMPOTENT_POST =
      Pattern.compile(
          "^/v1/(payments|customers|orders|plans|subscriptions)$"
              + "|^/v1/payments/[^/]+/(cancel|refunds|capture)$"
              + "|^/v1/orders/[^/]+/(payments|cancel)$"
              + "|^/v1/subscriptions/[^/]+/cancel$");
```

and `shouldNotFilter` → `!IDEMPOTENT_POST.matcher(path).matches()`. Unit test `IdempotencyFilterPathsTest` with a table of (path → filtered?) covering each line plus `/v1/customers/abc` POST (not filtered: no such route).

- [ ] **Step 2: `ErrorHandler`.** `STATUS_BY_CODE` gains `CUSTOMER_EXISTS, CUSTOMER_HAS_ACTIVE_SUBSCRIPTION, ORDER_CLOSED, ORDER_HAS_ACTIVE_PAYMENT, SUBSCRIPTION_NOT_ACTIVE, CONFLICT → CONFLICT`; `CUSTOMER_NOT_FOUND`-style codes are not needed: `NotFoundException` already yields 404 `NOT_FOUND` with "customer not found: id" — spec §10's `*_NOT_FOUND` codes are **folded into `NOT_FOUND`** (record in DECISOES: one code, the message names the resource). Add handlers:

```java
  @ExceptionHandler(CustomerExistsException.class)
  public ProblemDetail customerExists(CustomerExistsException e) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, e.code(), e.getMessage());
    problem.setProperty("customer_id", e.customerId());
    return problem;
  }

  @ExceptionHandler(OrderHasActivePaymentException.class)
  public ProblemDetail orderHasActivePayment(OrderHasActivePaymentException e) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, e.code(), e.getMessage());
    problem.setProperty("payment_id", e.paymentId());
    return problem;
  }
```

- [ ] **Step 3: DTOs and controllers.** Follow `PaymentsController` exactly (constructor injection, `MerchantContext.current()`, `withResource` header on creates, `IllegalArgumentException` for body-shape rules, `@RequestBody` records with Jackson snake_case). Shapes:

`CustomerRequest(String name, String document, String email, AddressFields address)` → `CustomerFactory.fromRequest(...)` with `address == null ? null : address.toRaw()`. `CustomerResponse(id, name, document (masked), email, address, created_at, updated_at)`. `CustomersController`: `POST /v1/customers` (201), `GET /{id}`, `GET ?document=`, `PATCH /{id}`, `DELETE /{id}` (204), `GET /{id}/cards` → list of the existing `CardResponse` from `api/card`.

`CreateOrderRequest(Long amount, String currency, String reference, String description, String customerId, CustomerRequest customer, Instant expiresAt)` → `OrderFactory.standalone(...)` with `OrderPayer` built from `customer` (`PersonName.of`, `Document.of`, `CustomerAddress.of`), `IllegalArgumentException` when both or neither of `customer_id`/`customer`. `OrderAttemptRequest` sealed by `method` with `PixAttemptBody(Integer expiresIn)`, `BolecodeAttemptBody(LocalDate dueDate, Integer paymentLimitDays)`, `CardAttemptBody(CardFields card, String cardId, String cvv, Integer installments, Boolean capture, String softDescriptor, Boolean saveCard)` → `AttemptRequest`; unknown `method` → 400 like payments. `OrderResponse(id, status, amount, currency, reference, description, customer_id, paid_payment_id, paid_at, expires_at, subscription_id, invoice_number, period, payments: [{id, method, status, created_at}], created_at)`. `OrdersController`: `POST /v1/orders`, `POST /{id}/payments` (201 with `PaymentResponse`), `POST /{id}/cancel`, `GET /{id}`, `GET ?reference=`, `GET /{id}/payments`.

`PlanRequest(String name, Long amount, String currency, PlanInterval interval, Integer intervalCount, Integer trialDays)`, `PlanPatchRequest(String name, Boolean active)` — any other key is refused by `fail-on-unknown-properties` (400), and the controller maps a body with neither field to 400; `PLAN_IMMUTABLE` is therefore unreachable through JSON... the spec promises it, so keep the DTO permissive for `amount`, `currency`, `interval`, `interval_count`, `trial_days` as nullable fields and throw `DomainException("PLAN_IMMUTABLE", "<field> cannot change; create a new plan")` when any is non-null. `PlansController`: `POST`, `GET /{id}`, `GET ?active=`, `PATCH /{id}`.

`SubscriptionRequest(String customerId, String planId, PaymentMethod method, String cardId, LocalDate startAt)` → controller loads `Customer` and `Plan`, calls `SubscriptionFactory.fromRequest` then `SubscriptionService.create(subscription, customer)`. `SubscriptionCancelRequest(Boolean atPeriodEnd)` default true. `SubscriptionPatchRequest(PaymentMethod method, String cardId)` → `changeMethod` (loads the customer for the address rule). `SubscriptionResponse(id, status, customer_id, plan_id, method, card_id, current_period {start, end}, next_billing_at, cancel_at_period_end, latest_order (OrderResponse summary or null), dunning: [DunningAttemptResponse(attempt, scheduled_at, ran_at, outcome, payment_id)], created_at)`. `SubscriptionsController`: `POST`, `GET /{id}`, `GET ?customer_id=`, `POST /{id}/cancel`, `PATCH /{id}`, `GET /{id}/orders`.

`PaymentResponse`: add `order_id` (read `PaymentResponse.from`; add the field after `reference`).

- [ ] **Step 4: Unit tests for the DTO rules** (`OrderAttemptRequestTest`: card body with both `card` and `card_id` → `IllegalArgumentException`; `CustomerRequestTest`: `address` partially filled → the factory error names `customer.address.<field>`).

- [ ] **Step 5: `BillingApiIntegrationTest`** (RestTestClient, `@ActiveProfiles("test")`, Testcontainers, WireMock Cielo stubs copied from `CardFlowIntegrationTest` for an approved sale): admin creates merchant + key + Cielo TEST credential; then: `POST /v1/customers` 201 and a second time 409 with `customer_id`; `POST /v1/plans` 201; `POST /v1/orders` with `customer_id` 201; `POST /v1/orders/{id}/payments` with a Pix body 201 (the Itaú is WireMocked too — reuse `PaymentsFlowIntegrationTest`'s Pix stubs) and a second one 409 with `payment_id`; `POST /v1/orders/{id}/cancel` 200 `CANCELED`; `POST /v1/orders` + card attempt with `save_card: true` 201 `COMPLETED` → the order `GET` shows `PAID` within 5 s (Awaitility; the relay runs at 200 ms); `GET /v1/customers/{id}/cards` lists the saved card; `POST /v1/subscriptions` with that `card_id` 201 `ACTIVE` with `next_billing_at`; within 5 s `GET /v1/subscriptions/{id}` shows `latest_order.status == PAID` and `GET /v1/subscriptions/{id}/orders` has one invoice (the job runner polls at 200 ms and `next_billing_at` is "now" for a start today); `POST /v1/subscriptions/{id}/cancel` `{at_period_end:false}` 200 `CANCELED`; `DELETE /v1/customers/{id}` 204 afterwards; every POST without `Idempotency-Key` → 400 `IDEMPOTENCY_KEY_REQUIRED`.

- [ ] **Step 6: Run** `./mvnw -B -o -pl gateway-app -am verify` → green (ArchUnit, `CardDataNeverLeavesTheRequestTest` included: the order card attempt goes through the same masking; if the PCI test scans a new table, add `billing.orders`'s `payer` column to its scan list — it already scans "every table"; confirm by reading the test).

- [ ] **Step 7: Commits**: `refactor(app): environment mapping shared by controllers` (the move only), then `feat(app): customers, orders, plans and subscriptions API`.

---

### Task 12: App wiring proof, E2E script, README and DECISOES

**Files:**
- Modify: `scripts/e2e_sandbox.py` (two new sections), `README.md` (new "Customers, orders, plans and subscriptions" section after "Card (Cielo)"), `docs/superpowers/DECISOES.md` (the seven decisions of spec §12 plus the three amendments from the plan: no synthetic idempotency key — structural idempotency instead; `*_NOT_FOUND` folded into `NOT_FOUND`; `SubscriptionService`/`SubscriptionBilling`/`Dunning` splits), `gateway-app/src/test/java/com/gateway/app/architecture/ArchitectureTest.java` (class-count floor from `160` to `220`: count with `find gateway-*/src/main -name '*.java' | wc -l` and set the floor at ~50 below the real count).
- Test: the E2E run itself against the sandboxes (needs `.env`; the gateway up as in `docs/e2e`).

- [ ] **Step 1: E2E sections.** In `scripts/e2e_sandbox.py` add after `card(gateway)`:

```python
def order_and_subscription(gateway):
    gateway.report.heading(2, "ORDER: Pix attempt canceled, then card pays; SUBSCRIPTION by stored card")
    _, customer = gateway.merchant("Create a customer", "POST", "/v1/customers",
        {"name": "Ana Silva", "document": "529.982.247-25", "email": "ana@example.com"}, expect={201}, idempotency="e2e-cust-1")
    customer_id = customer.get("id", "missing")

    _, order = gateway.merchant("Create an order for her", "POST", "/v1/orders",
        {"amount": 4990, "currency": "BRL", "reference": "e2e-order-1", "customer_id": customer_id}, expect={201}, idempotency="e2e-order-1")
    order_id = order.get("id", "missing")
    _, pix = gateway.merchant("First attempt: Pix", "POST", f"/v1/orders/{order_id}/payments",
        {"method": "PIX", "expires_in": 600}, expect={201}, idempotency="e2e-order-1-pix")
    gateway.merchant("A second attempt while the Pix is open is refused", "POST", f"/v1/orders/{order_id}/payments",
        {"method": "PIX", "expires_in": 600}, expect={409}, idempotency="e2e-order-1-pix2")
    gateway.merchant("Cancel the Pix attempt through the order", "POST", f"/v1/payments/{pix.get('id','missing')}/cancel", expect={200}, idempotency="e2e-order-1-pixcancel")
    card = {"number": "4024007153763171", "holder": "ANA SILVA", "expiry": "12/2030", "cvv": "123"}
    gateway.merchant("Second attempt: card, saved for later", "POST", f"/v1/orders/{order_id}/payments",
        {"method": "CARD", "card": card, "installments": 1, "capture": True, "save_card": True, "soft_descriptor": "E2E"}, expect={201}, idempotency="e2e-order-1-card")
    time.sleep(3)
    gateway.merchant("The order is PAID once the relay ran", "GET", f"/v1/orders/{order_id}", expect={200})

    _, cards = gateway.merchant("Her saved cards", "GET", f"/v1/customers/{customer_id}/cards", expect={200})
    card_id = (cards[0].get("id") if isinstance(cards, list) and cards else "missing")
    _, plan = gateway.merchant("A monthly plan", "POST", "/v1/plans",
        {"name": "E2E Monthly", "amount": 2990, "currency": "BRL", "interval": "MONTH"}, expect={201}, idempotency="e2e-plan-1")
    _, subscription = gateway.merchant("Subscribe her by stored card", "POST", "/v1/subscriptions",
        {"customer_id": customer_id, "plan_id": plan.get("id", "missing"), "method": "CARD", "card_id": card_id}, expect={201}, idempotency="e2e-sub-1")
    subscription_id = subscription.get("id", "missing")
    time.sleep(5)
    gateway.merchant("The first invoice was billed by the job", "GET", f"/v1/subscriptions/{subscription_id}", expect={200})
    gateway.merchant("Its invoices", "GET", f"/v1/subscriptions/{subscription_id}/orders", expect={200})
    gateway.merchant("Cancel at period end", "POST", f"/v1/subscriptions/{subscription_id}/cancel", {"at_period_end": True}, expect={200}, idempotency="e2e-sub-1-cancel")
```

(`import time` at the top; the attempt body for Pix/card inside an order carries no `amount`/`currency`/`customer`.) Call it from `main` after `card(gateway)`; the module docstring gains the two paths. Note in the report text that the Cielo sandbox has no tokenization, so `card_id` may be absent and the subscription section then records `422 CARD_REQUIRED`-style failure honestly: write the section so that when `card_id == "missing"` it switches to `method: PIX` and says so in the report.

- [ ] **Step 2: README section** after "Card (Cielo)": the five resources in one page: customer JSON, order + attempt (`POST /v1/orders/{id}/payments` with the method body minus amount/currency/customer), the 409s, plan JSON, subscription JSON with `next_billing_at`, what `invoice.created` carries per method, dunning days property `gateway.billing.dunning.retry-days`, `PAST_DUE` semantics ("we keep charging; cutting service is yours"), `card-recurring-enabled`.

- [ ] **Step 3: DECISOES** entries (Portuguese, with "Rejeitado" and "Custo se errado"), dated 2026-10-02 (or the execution date): the seven of spec §12 and the three amendments listed above.

- [ ] **Step 4: Run the E2E** against the sandboxes exactly as in `docs/e2e/README`-style instructions of the script docstring (gateway from the branch's jar, `.env` credentials), save the report, and read it: Pix and card paths unchanged, the order path shows `PAID`, the subscription path shows one invoice.

- [ ] **Step 5: Full build** `./mvnw -B -o verify` (all modules) → green; spotless applied.

- [ ] **Step 6: Commit** `docs(billing): README, decisions and the end-to-end order and subscription paths`.

---

## Self-review notes (kept for the executor)

- Spec §5 `DOCUMENT_IMMUTABLE`: `CustomerPatchRequest` has no `document` field, so Jackson's `fail-on-unknown-properties` answers 400 before the service; the spec's 422 is replaced by that 400, record it with the `NOT_FOUND` amendment in Task 12.
- Spec §6 synthetic idempotency key: replaced by structural idempotency (Task 9); amended in DECISOES.
- Spec §8 `processed_events` lives in `billing.orders`'s migration (V303) because `OrderSettlement` is its only writer.
- Spec §11 "PATCH method valendo só na fatura seguinte": covered by Task 10's method-change test (the open invoice is not reissued by `changeMethod`; only the next dunning attempt or cycle uses the new method).
- Review Focus 1 → Task 4 `twoAttemptsRacingLeaveExactlyOneActive`; 2 → Task 5 double payment; 3 → Task 8 calendar; 4 → Task 9 rerun; 5 → Task 10 method change.
