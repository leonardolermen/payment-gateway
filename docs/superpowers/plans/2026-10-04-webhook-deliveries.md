# Webhook deliveries (listing, redelivery, contract) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A merchant can list and inspect the deliveries of its webhooks, redeliver the ones that died, and integrate against a documented, test-verified contract (signature, headers, event catalog).

**Architecture:** Two repositories. The library `webhook-delivery` (C:\Dev\webhook-delivery, branch `feat/listagem-e-reentrega`) gains tenant-scoped listing and redelivery and is released as `0.2.0` by tag. The gateway (C:\Dev\payment-gateway, branch `feat/webhook-deliveries`, based on `docs/planos-f-g-h-specs`) bumps to 0.2.0, exposes `/v1/webhooks/deliveries`, documents the outbound contract in the README with a test that keeps the event catalog honest, and proves the whole path end to end with a WireMock receiver.

**Tech Stack:** Java 25, Spring Boot 4 (lib) / Boot + Flyway + Testcontainers (gateway), Lombok in the lib's persistence only, WireMock and RestTestClient in the gateway's app tests.

**Spec:** `docs/superpowers/specs/2026-10-04-webhooks-de-saida-design.md`

## Global Constraints

- Library: identifiers in English, comments/javadoc/commit subjects in Portuguese (the repo's convention: `fix: concorrencia, recuperacao e vazao da entrega`); google-java-format is not enforced there — keep 2-space, 100 columns by hand. Gateway: everything in English except DECISOES.
- Code standard C:\Users\leona\.claude\CLAUDE.md in both repos (full-word names, braces always, factories for invariants, comments say WHY with evidence, rename/format never with logic).
- Library tests: `@SpringBootTest(classes = com.barrier.webhookdelivery.testapp.TestApplication.class, properties = "webhook-delivery.scheduler.enabled=false")` + `@Testcontainers` + `@Container @ServiceConnection PostgreSQLContainer("postgres:17-alpine")`, `@BeforeEach` cleaning `webhook_delivery.deliveries`. Verify: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -B verify` (Docker Desktop must be running — never start or stop it).
- Library migrations live in `src/main/resources/db/webhook-delivery/`, unqualified table names (default schema `webhook_delivery`), next version `V3`.
- Library persistence: `DeliveryEntity` (Lombok package-private getters/setters), `DeliveryEntityMapper.toEntity/toDomain`, `Delivery.rehydrate` with 17 args today, native `INSERT ... ON CONFLICT (event_id, endpoint_id) DO NOTHING` in `DeliveryRepositoryImpl.saveIfAbsent`. **A new column touches all four** (entity, mapper, `rehydrate`, the INSERT) or `ddl-auto: validate`/the INSERT breaks.
- Library release: bump `pom.xml` to `0.2.0` (no `-SNAPSHOT`), merge to `main`, then tag `v0.2.0`; the CI job `publish` checks `pom == tag` and runs `deploy`. Pushing the tag is an outward action: the executor stops and asks the user before tagging.
- Gateway: `gateway-app` consumes the lib through `@EntityScan/@EnableJpaRepositories("com.barrier.webhookdelivery.repository")` in `AppConfiguration`; headers prefix `X-Gateway`; `MerchantContext.current().merchantId().value()` is the tenant id; errors map in `ErrorHandler` (`NotFoundException` → 404 `NOT_FOUND`, `DomainException` default 422, 409 only for codes in `STATUS_BY_CODE`); every creating/charging POST is in `IdempotencyFilter.IDEMPOTENT_POST` + `IdempotencyFilterPathsTest`.
- Gateway verify: `./mvnw -B -o -pl gateway-app -am verify` (after `./mvnw -B dependency:resolve` once online to fetch 0.2.0). Spotless (`./mvnw spotless:apply`) before every gateway commit.
- Commit trailer in both repos: `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never push, never tag, without the controller/user saying so.
- Secrets: endpoint secrets never appear in list/get responses (only at register/rotate, as today); payloads carry no PAN/CVV by construction (PCI test exists); logs never print the signature secret.

## Review Focus

1. **Tenant isolation on every new read and write**: `GET /deliveries/{id}` and `POST …/redeliver` with another merchant's delivery id must answer 404, never 403 or the row. Test in Task 3.
2. **Redelivery of a delivery whose endpoint was deactivated or whose secret rotated** must not send with a stale secret nor crash: `NOT_REDELIVERABLE` when inactive; current secret when rotated. Test in Task 2 (lib) and Task 5 (E2E).
3. **A redelivered row re-entering `claimDue` under the partition-key ordering rule**: an older sibling `PENDING/FAILED` of the same partition must still go first; a redelivered row must not starve or jump the queue. Test in Task 2.
4. **Cursor pagination with equal `created_at`** (two deliveries created in the same transaction have the same instant): the cursor is `(created_at, id)` and must not skip or repeat. Test in Task 1.
5. **README catalog drifting from the code**: the `EventCatalogTest` must fail when a key is added to a builder and the README is not updated. Test in Task 4.

---

## File structure

```
webhook-delivery (lib)
  src/main/resources/db/webhook-delivery/V3__listagem_e_reentrega.sql
  src/main/java/com/barrier/webhookdelivery/domain/DeliveryQuery.java, DeliveryCursor.java, RedeliverResult.java
  src/main/java/com/barrier/webhookdelivery/domain/Delivery.java            (+ redeliveredAt, lastErrorBeforeRedelivery, redeliver(now))
  src/main/java/com/barrier/webhookdelivery/repository/DeliveryEntity.java, DeliveryEntityMapper.java,
      DeliveryJpaRepository.java, DeliveryRepository.java, DeliveryRepositoryImpl.java
  src/main/java/com/barrier/webhookdelivery/service/WebhookDeliveryService.java (+ redeliver, redeliverDead)
  src/test/java/com/barrier/webhookdelivery/repository/DeliveryListingIntegrationTest.java
  src/test/java/com/barrier/webhookdelivery/service/RedeliveryIntegrationTest.java
  README.md, pom.xml (0.2.0)

payment-gateway
  pom.xml (<webhook-delivery.version>0.2.0</webhook-delivery.version>)
  gateway-app/src/main/java/com/gateway/app/api/webhook/WebhookDeliveriesController.java
  gateway-app/src/main/java/com/gateway/app/api/webhook/dto/DeliveryResponse.java, RedeliverDeadRequest.java, DeliveryCursorCodec.java
  gateway-app/src/main/java/com/gateway/app/api/support/IdempotencyFilter.java (+ two routes), ErrorHandler.java (+ 409 code)
  gateway-app/src/test/java/com/gateway/app/api/webhook/dto/DeliveryCursorCodecTest.java
  gateway-app/src/test/java/com/gateway/app/api/support/IdempotencyFilterPathsTest.java (+ lines)
  gateway-app/src/test/java/com/gateway/app/WebhookDeliveriesApiIntegrationTest.java
  gateway-app/src/test/java/com/gateway/app/WebhookDeliveryFlowIntegrationTest.java
  gateway-app/src/test/java/com/gateway/app/docs/EventCatalogTest.java
  README.md ("Outbound webhooks" section), docs/superpowers/DECISOES.md
```

---

### Task 1 (lib): tenant listing with cursor, and the redelivery columns

**Repo:** C:\Dev\webhook-delivery, branch `feat/listagem-e-reentrega` from `main`.

**Files:**
- Create: `src/main/resources/db/webhook-delivery/V3__listagem_e_reentrega.sql`
- Create: `domain/DeliveryQuery.java`, `domain/DeliveryCursor.java`
- Modify: `domain/Delivery.java`, `repository/DeliveryEntity.java`, `repository/DeliveryEntityMapper.java`, `repository/DeliveryJpaRepository.java`, `repository/DeliveryRepository.java`, `repository/DeliveryRepositoryImpl.java`
- Test: `src/test/java/com/barrier/webhookdelivery/repository/DeliveryListingIntegrationTest.java`, `src/test/java/com/barrier/webhookdelivery/domain/DeliveryQueryTest.java`

**Interfaces:**
- Produces: `record DeliveryQuery(DeliveryStatus status, String eventType, String aggregateId, Instant since, DeliveryCursor after, int limit)` (canonical constructor: `limit` 0 → 20, >100 → IllegalArgumentException("limit deve ficar entre 1 e 100")); `record DeliveryCursor(Instant createdAt, UUID id)`; `DeliveryRepository.findByTenant(String tenantId, DeliveryQuery query) → List<Delivery>` ordered `created_at DESC, id DESC`, returning at most `limit` rows; `DeliveryRepository.findByTenantAndId(String tenantId, UUID id) → Optional<Delivery>`; `Delivery.redeliveredAt()`, `Delivery.lastErrorBeforeRedelivery()`; `Delivery.rehydrate(...)` with 19 args (the two new ones LAST: `Instant redeliveredAt, String lastErrorBeforeRedelivery`).

- [ ] **Step 1: Migration**

```sql
-- V3: listagem por tenant e reentrega (gateway, plano "webhook deliveries", 2026-10-04).
-- A listagem do merchant ordena por created_at DESC, id DESC com cursor nos dois: duas entregas
-- criadas na mesma transação têm o mesmo created_at, e um cursor só por data pularia ou repetiria.
CREATE INDEX idx_deliveries_tenant_listagem ON deliveries (tenant_id, created_at DESC, id DESC);
CREATE INDEX idx_deliveries_tenant_status ON deliveries (tenant_id, status);

-- Reentrega manual guarda o que a entrega era antes de voltar a PENDING: o merchant que pede a
-- reentrega quer saber por que ela morreu, e last_error é sobrescrito pela próxima tentativa.
ALTER TABLE deliveries ADD COLUMN redelivered_at TIMESTAMPTZ;
ALTER TABLE deliveries ADD COLUMN last_error_before_redelivery VARCHAR(500);
```

- [ ] **Step 2: Failing unit test** `DeliveryQueryTest.java`

```java
package com.barrier.webhookdelivery.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DeliveryQueryTest {
  @Test
  void limiteZeroViraVinte() {
    DeliveryQuery query = new DeliveryQuery(null, null, null, null, null, 0);
    assertThat(query.limit()).isEqualTo(20);
  }

  @Test
  void limiteAcimaDeCemEhRecusado() {
    assertThatThrownBy(() -> new DeliveryQuery(null, null, null, null, null, 101))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit");
  }
}
```

- [ ] **Step 3: Domain records**

```java
package com.barrier.webhookdelivery.domain;

import java.time.Instant;
import java.util.UUID;

/** Posição de paginação: a última linha vista, na ordem created_at DESC, id DESC. */
public record DeliveryCursor(Instant createdAt, UUID id) {}
```

```java
package com.barrier.webhookdelivery.domain;

import java.time.Instant;

/** Filtros da listagem por tenant. Todo campo é opcional menos o limite, que tem teto. */
public record DeliveryQuery(
    DeliveryStatus status,
    String eventType,
    String aggregateId,
    Instant since,
    DeliveryCursor after,
    int limit) {
  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;

  public DeliveryQuery {
    if (limit == 0) {
      limit = DEFAULT_LIMIT;
    }
    if (limit < 1 || limit > MAX_LIMIT) {
      throw new IllegalArgumentException("limit deve ficar entre 1 e " + MAX_LIMIT);
    }
  }
}
```

- [ ] **Step 4: `Delivery` gains the two fields.** Add `private Instant redeliveredAt; private String lastErrorBeforeRedelivery;` (mutable), accessors, and extend `rehydrate` with the two trailing parameters (update every caller: `DeliveryEntityMapper.toDomain` and any test calling `rehydrate` — `grep -rn "rehydrate(" src`). `markDelivered`/`markFailed` leave them untouched. Do NOT add `redeliver()` yet (Task 2).

- [ ] **Step 5: Persistence.** `DeliveryEntity`: `@Column(name = "redelivered_at") private Instant redeliveredAt; @Column(name = "last_error_before_redelivery", length = 500) private String lastErrorBeforeRedelivery;`. `DeliveryEntityMapper`: map both ways. `DeliveryRepositoryImpl.saveIfAbsent` native INSERT: add the two columns and parameters (null on create). `DeliveryJpaRepository`:

```java
  Optional<DeliveryEntity> findByIdAndTenantId(UUID id, String tenantId);

  @Query(
      """
      SELECT d FROM DeliveryEntity d
       WHERE d.tenantId = :tenantId
         AND (:status IS NULL OR d.status = :status)
         AND (:eventType IS NULL OR d.eventType = :eventType)
         AND (:aggregateId IS NULL OR d.aggregateId = :aggregateId)
         AND (CAST(:since AS timestamp) IS NULL OR d.createdAt >= :since)
         AND (CAST(:cursorCreatedAt AS timestamp) IS NULL
              OR d.createdAt < :cursorCreatedAt
              OR (d.createdAt = :cursorCreatedAt AND d.id < :cursorId))
       ORDER BY d.createdAt DESC, d.id DESC
      """)
  List<DeliveryEntity> listByTenant(
      @Param("tenantId") String tenantId,
      @Param("status") DeliveryStatus status,
      @Param("eventType") String eventType,
      @Param("aggregateId") String aggregateId,
      @Param("since") Instant since,
      @Param("cursorCreatedAt") Instant cursorCreatedAt,
      @Param("cursorId") UUID cursorId,
      Limit limit);
```

If Hibernate rejects the `CAST(... AS timestamp) IS NULL` form for `Instant` parameters, replace the two optional date predicates with a Specification/Criteria query in the impl (one method, same semantics) and say so in the report. `UUID` ordering: `d.id < :cursorId` compares UUIDs; Postgres orders `uuid` bytewise and Hibernate passes them through — the test in Step 6 pins it.

`DeliveryRepository` + impl:

```java
  /** Listagem do merchant: created_at DESC, id DESC, com cursor nos dois campos (índice V3). */
  List<Delivery> findByTenant(String tenantId, DeliveryQuery query);

  /** Pelo tenant junto, para a borda nunca precisar checar posse depois de ler. */
  Optional<Delivery> findByTenantAndId(String tenantId, UUID id);
```

- [ ] **Step 6: Failing integration test** `DeliveryListingIntegrationTest.java` (same header as `DeliveryOwnershipIntegrationTest`; helper `grava(tenant, eventType, status, createdAt)` inserting with explicit `created_at` and returning the id):

```java
  @Test
  void listaSoDoTenantNaOrdemMaisRecentePrimeiro() {
    Instant base = Instant.parse("2026-10-04T12:00:00Z");
    UUID antiga = grava("t1", "payment.completed", "DELIVERED", base);
    UUID nova = grava("t1", "payment.failed", "DEAD", base.plusSeconds(60));
    grava("t2", "payment.completed", "DELIVERED", base.plusSeconds(120));

    List<Delivery> lista = repository.findByTenant("t1", new DeliveryQuery(null, null, null, null, null, 20));

    assertThat(lista).extracting(Delivery::id).containsExactly(nova, antiga);
  }

  @Test
  void cursorComMesmoCreatedAtNaoPulaNemRepete() {
    Instant mesmo = Instant.parse("2026-10-04T12:00:00Z");
    List<UUID> ids = new ArrayList<>();
    for (int i = 0; i < 5; i++) { ids.add(grava("t1", "payment.completed", "DELIVERED", mesmo)); }

    List<Delivery> primeira = repository.findByTenant("t1", new DeliveryQuery(null, null, null, null, null, 2));
    Delivery ultima = primeira.get(1);
    List<Delivery> segunda = repository.findByTenant("t1", new DeliveryQuery(null, null, null, null,
        new DeliveryCursor(ultima.createdAt(), ultima.id()), 2));
    Delivery ultima2 = segunda.get(1);
    List<Delivery> terceira = repository.findByTenant("t1", new DeliveryQuery(null, null, null, null,
        new DeliveryCursor(ultima2.createdAt(), ultima2.id()), 2));

    List<UUID> vistos = Stream.of(primeira, segunda, terceira).flatMap(List::stream).map(Delivery::id).toList();
    assertThat(vistos).hasSize(5).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(ids);
  }

  @Test
  void filtraPorStatusTipoAgregadoEDesde() {
    Instant base = Instant.parse("2026-10-04T12:00:00Z");
    UUID alvo = grava("t1", "payment.failed", "DEAD", base.plusSeconds(10));
    grava("t1", "payment.failed", "DELIVERED", base.plusSeconds(20));
    grava("t1", "payment.completed", "DEAD", base.plusSeconds(30));
    grava("t1", "payment.failed", "DEAD", base.minusSeconds(3600));

    List<Delivery> lista = repository.findByTenant("t1",
        new DeliveryQuery(DeliveryStatus.DEAD, "payment.failed", "a-1", base, null, 20));

    assertThat(lista).extracting(Delivery::id).containsExactly(alvo);
  }

  @Test
  void porIdExigeOTenantCerto() {
    UUID id = grava("t1", "payment.completed", "DELIVERED", Instant.now());

    assertThat(repository.findByTenantAndId("t1", id)).isPresent();
    assertThat(repository.findByTenantAndId("t2", id)).isEmpty();
  }
```

(`grava` writes `aggregate_id = 'a-1'`, `partition_key = NULL`, `target_url`, `payload '{}'`, the given status and `created_at`, `next_attempt_at = created_at`.)

- [ ] **Step 7: Run** `./mvnw -B verify` → green (the whole suite, including the existing ownership/ordering tests which now go through the longer `rehydrate`).

- [ ] **Step 8: Commit** `feat: listagem por tenant com cursor e colunas de reentrega` (body: por quê do cursor duplo e das duas colunas) + trailer.

---

### Task 2 (lib): redelivery, README and version 0.2.0

**Files:**
- Create: `domain/RedeliverResult.java`
- Modify: `domain/Delivery.java` (+`redeliver(Instant now)`), `repository/DeliveryJpaRepository.java`, `repository/DeliveryRepository.java`, `repository/DeliveryRepositoryImpl.java`, `service/WebhookDeliveryService.java`, `README.md`, `pom.xml`
- Test: `src/test/java/com/barrier/webhookdelivery/service/RedeliveryIntegrationTest.java`, `src/test/java/com/barrier/webhookdelivery/domain/DeliveryTest.java` (+ cases)

**Interfaces:**
- Produces: `enum RedeliverResult { SCHEDULED, NOT_FOUND, NOT_REDELIVERABLE }`; `WebhookDeliveryService.redeliver(String tenantId, UUID deliveryId) → RedeliverResult`; `WebhookDeliveryService.redeliverDead(String tenantId, Instant since) → int`; `DeliveryRepository.save(Delivery)` for the redelivery write? No — a targeted `boolean markRedelivered(UUID id, Instant now)` conditional UPDATE and `int markDeadRedelivered(String tenantId, Instant since, Instant now, int max)`; `Delivery.redeliver(now)` for the in-memory rule + unit test.

- [ ] **Step 1: Failing unit tests** in `DeliveryTest` (create the class if absent):

```java
  @Test
  void reentregaVoltaParaPendingGuardandoOErroAnterior() {
    Delivery d = deadDelivery("timeout 3x");   // helper: create + markFailed until DEAD
    Instant agora = Instant.parse("2026-10-04T12:00:00Z");

    d.redeliver(agora);

    assertThat(d.status()).isEqualTo(DeliveryStatus.PENDING);
    assertThat(d.attempts()).isZero();
    assertThat(d.nextAttemptAt()).isEqualTo(agora);
    assertThat(d.lastError()).isNull();
    assertThat(d.lastErrorBeforeRedelivery()).isEqualTo("timeout 3x");
    assertThat(d.redeliveredAt()).isEqualTo(agora);
  }

  @Test
  void pendingEDeliveredNaoReentregam() {
    assertThatThrownBy(() -> Delivery.create(...).redeliver(Instant.now()))
        .isInstanceOf(IllegalStateException.class);
    Delivery entregue = Delivery.create(...); entregue.markDelivered();
    assertThatThrownBy(() -> entregue.redeliver(Instant.now())).isInstanceOf(IllegalStateException.class);
  }
```

- [ ] **Step 2: `Delivery.redeliver`**

```java
  /**
   * Reentrega manual: só DEAD ou FAILED. PENDING está em voo ou na fila; DELIVERED já chegou e o
   * merchant que quer o payload de novo lê a entrega, não a reenvia. A assinatura na próxima
   * tentativa usa o segredo vigente do endpoint, nunca o da época (decisão do plano).
   */
  public void redeliver(Instant now) {
    if (status != DeliveryStatus.DEAD && status != DeliveryStatus.FAILED) {
      throw new IllegalStateException("entrega " + id + " está " + status + "; só DEAD ou FAILED reentregam");
    }
    this.lastErrorBeforeRedelivery = lastError;
    this.lastError = null;
    this.attempts = 0;
    this.status = DeliveryStatus.PENDING;
    this.nextAttemptAt = now;
    this.claimedAt = null;
    this.claimToken = null;
    this.redeliveredAt = now;
  }
```

- [ ] **Step 3: Repository writes** (`DeliveryJpaRepository`):

```java
  @Modifying
  @Query(
      """
      UPDATE DeliveryEntity d
         SET d.lastErrorBeforeRedelivery = d.lastError, d.lastError = NULL, d.attempts = 0,
             d.status = com.barrier.webhookdelivery.domain.DeliveryStatus.PENDING,
             d.nextAttemptAt = :now, d.claimedAt = NULL, d.claimToken = NULL, d.redeliveredAt = :now
       WHERE d.id = :id AND d.tenantId = :tenantId
         AND d.status IN (com.barrier.webhookdelivery.domain.DeliveryStatus.DEAD,
                          com.barrier.webhookdelivery.domain.DeliveryStatus.FAILED)
         AND (d.claimedAt IS NULL OR d.claimedAt < :leaseCutoff)
      """)
  int marcarReentrega(UUID id, String tenantId, Instant now, Instant leaseCutoff);
```

and a native batch for `redeliverDead`:

```sql
UPDATE webhook_delivery.deliveries
   SET last_error_before_redelivery = last_error, last_error = NULL, attempts = 0,
       status = 'PENDING', next_attempt_at = :now, claimed_at = NULL, claim_token = NULL,
       redelivered_at = :now
 WHERE id IN (SELECT id FROM webhook_delivery.deliveries
               WHERE tenant_id = :tenantId AND status = 'DEAD' AND created_at >= :since
               ORDER BY created_at LIMIT :max)
```

`DeliveryRepository`: `boolean markRedelivered(String tenantId, UUID id, Instant now, Duration lease)` (the `leaseCutoff` guard keeps a FAILED row that a worker is holding right now from being reset under it — a FAILED with a live claim is "in flight", the same rule `claimDue` uses) and `int markDeadRedelivered(String tenantId, Instant since, Instant now, int max)`; both `@Transactional` in the impl. `Delivery.redeliver` stays as the readable statement of the rule and is what the service uses to decide `NOT_REDELIVERABLE` before writing (read, decide, conditional write; the UPDATE's WHERE is the race guard).

- [ ] **Step 4: Service**

```java
  /** Reentrega manual de uma entrega do tenant. A escrita é condicional: o WHERE repete a regra. */
  public RedeliverResult redeliver(String tenantId, UUID deliveryId) {
    Optional<Delivery> found = repository.findByTenantAndId(tenantId, deliveryId);
    if (found.isEmpty()) {
      return RedeliverResult.NOT_FOUND;
    }
    Delivery delivery = found.get();
    if (delivery.status() != DeliveryStatus.DEAD && delivery.status() != DeliveryStatus.FAILED) {
      return RedeliverResult.NOT_REDELIVERABLE;
    }
    if (endpoints.resolveSigningMaterial(delivery.endpointId()).isEmpty()) {
      return RedeliverResult.NOT_REDELIVERABLE; // endpoint desativado: a tentativa morreria de novo
    }
    boolean marcada = repository.markRedelivered(tenantId, deliveryId, Instant.now(), lease);
    return marcada ? RedeliverResult.SCHEDULED : RedeliverResult.NOT_REDELIVERABLE;
  }

  public static final int REDELIVER_DEAD_MAX = 1000;

  public int redeliverDead(String tenantId, Instant since) {
    return repository.markDeadRedelivered(tenantId, since, Instant.now(), REDELIVER_DEAD_MAX);
  }
```

(`redeliverDead` does not check endpoint state per row: a dead row of a deactivated endpoint comes back and dies again on the first attempt with "endpoint desativado ou removido", which is the existing, honest outcome; comment it.)

- [ ] **Step 5: Failing integration test** `RedeliveryIntegrationTest` (header like `WebhookDeliveryIntegrationTest`; uses a `WebhookClient` test double registered as a `@TestConfiguration` bean that records sends and answers failure/success on demand — read how `WebhookDeliveryIntegrationTest` fakes the client and reuse its approach):

```java
  @Test
  void umaEntregaMortaVoltaASairEUsaOSegredoVigente() {
    WebhookEndpoint endpoint = endpoints.register("t1", "http://localhost:9/hook", List.of("payment.*"));
    client.falhaSempre();
    service.accept(request("t1", "payment.completed"));
    esgota();                                              // retryDue até DEAD (maxAttempts baixo via properties)
    Delivery morta = unica("t1");
    assertThat(morta.status()).isEqualTo(DeliveryStatus.DEAD);

    endpoints.rotateSecret(endpoint.id());
    client.sucessoSempre();
    assertThat(service.redeliver("t1", morta.id())).isEqualTo(RedeliverResult.SCHEDULED);
    service.retryDue();

    Delivery entregue = unica("t1");
    assertThat(entregue.status()).isEqualTo(DeliveryStatus.DELIVERED);
    assertThat(entregue.lastErrorBeforeRedelivery()).isNotBlank();
    assertThat(client.ultimaAssinatura()).isEqualTo(assinaturaCom(endpoints.find(endpoint.id()).get().secret(), client.ultimoCorpo(), client.ultimoInstante()));
  }

  @Test
  void pendingDeliveredEOutroTenantNaoReentregam() { /* NOT_REDELIVERABLE, NOT_REDELIVERABLE, NOT_FOUND */ }

  @Test
  void endpointDesativadoNaoReentrega() { /* deactivate → NOT_REDELIVERABLE */ }

  @Test
  void reentregaEmLoteRespeitaOLimiteEODesde() { /* 3 DEAD after since + 1 before → redeliverDead returns 3; a second call returns 0 */ }

  @Test
  void entregaReentregueRespeitaAOrdemDaParticao() {
    // sibling A (PENDING, older, same partition_key) and B (DEAD, newer) → redeliver B → claimDue
    // returns A first; B only after A is DELIVERED.
  }
```

Write every test body fully (no `/* … */` left in the file); the comments above are the expected behaviour.

- [ ] **Step 6: README** (lib): bump the dependency snippet to `0.2.0`; add section `## Listagem e reentrega` documenting `findByTenant`/`findByTenantAndId`, `redeliver`/`redeliverDead` with the rules (DEAD/FAILED only, endpoint ativo, segredo vigente, lote de 1000, `last_error_before_redelivery`). `pom.xml`: `<version>0.2.0</version>`.

- [ ] **Step 7: Run** `./mvnw -B verify` → green. **Commit** `feat: reentrega manual de entregas mortas e versao 0.2.0`.

- [ ] **Step 8: Release (user-gated).** Push `feat/listagem-e-reentrega`, open the PR to `main`; after merge, the tag `v0.2.0` on `main` triggers `publish`. The executor STOPS here and reports: the push, the PR and the tag are outward actions the user approves. Tasks 3–5 need `0.2.0` resolvable from GitHub Packages (`./mvnw -B dependency:resolve` in the gateway).

---

### Task 3 (gateway): `/v1/webhooks/deliveries`

**Repo:** C:\Dev\payment-gateway, branch `feat/webhook-deliveries` from `docs/planos-f-g-h-specs`.

**Files:**
- Modify: `pom.xml` (`<webhook-delivery.version>0.2.0</webhook-delivery.version>`), `gateway-app/src/main/java/com/gateway/app/api/support/IdempotencyFilter.java`, `ErrorHandler.java`
- Create: `gateway-app/src/main/java/com/gateway/app/api/webhook/WebhookDeliveriesController.java`, `api/webhook/dto/DeliveryResponse.java`, `dto/RedeliverDeadRequest.java`, `dto/DeliveryCursorCodec.java`
- Test: `gateway-app/src/test/java/com/gateway/app/api/webhook/dto/DeliveryCursorCodecTest.java`, `api/support/IdempotencyFilterPathsTest.java` (+ rows), `gateway-app/src/test/java/com/gateway/app/WebhookDeliveriesApiIntegrationTest.java`

**Interfaces:**
- Consumes: lib 0.2.0 (`DeliveryRepository.findByTenant/findByTenantAndId`, `WebhookDeliveryService.redeliver/redeliverDead`, `DeliveryQuery`, `DeliveryCursor`, `RedeliverResult`). Note: `DeliveryRepository` is a bean (`DeliveryRepositoryImpl` is `@Import`ed by the lib auto-config) — inject the interface.
- Produces: routes of spec §3.1; `DeliveryResponse(id, event_id, event_type, aggregate_id, endpoint_id, target_url, status, attempts, last_error, last_error_before_redelivery, next_attempt_at, created_at, delivered_at, redelivered_at, payload)` with `payload` null in lists (Jackson `@JsonInclude(NON_NULL)` on that field only); `DeliveryCursorCodec.encode(DeliveryCursor) → String` (base64url of `createdAt.toString() + "|" + id`) / `decode(String) → DeliveryCursor` (IllegalArgumentException "after is not a valid cursor" on garbage); response header `X-Next-Cursor` when `list.size() == limit`.

- [ ] **Step 1: Failing codec test**

```java
class DeliveryCursorCodecTest {
  @Test
  void roundTrips() {
    DeliveryCursor cursor = new DeliveryCursor(Instant.parse("2026-10-04T12:00:00.123456Z"), UUID.randomUUID());
    assertThat(DeliveryCursorCodec.decode(DeliveryCursorCodec.encode(cursor))).isEqualTo(cursor);
  }

  @Test
  void garbageIsABadRequest() {
    assertThatThrownBy(() -> DeliveryCursorCodec.decode("not-a-cursor"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("after");
  }
}
```

- [ ] **Step 2: Codec, DTOs, controller**

```java
package com.gateway.app.api.webhook;

@RestController
@RequestMapping("/v1/webhooks/deliveries")
public class WebhookDeliveriesController {
  public static final String NEXT_CURSOR_HEADER = "X-Next-Cursor";
  private static final int MAX_REDELIVER_WINDOW_DAYS = 30;

  private final DeliveryRepository deliveries;
  private final WebhookDeliveryService service;
  private final Clock clock;

  public WebhookDeliveriesController(DeliveryRepository deliveries, WebhookDeliveryService service, Clock clock) { ... }

  @GetMapping
  public ResponseEntity<List<DeliveryResponse>> list(
      @RequestParam(required = false) DeliveryStatus status,
      @RequestParam(name = "event_type", required = false) String eventType,
      @RequestParam(name = "aggregate_id", required = false) String aggregateId,
      @RequestParam(required = false) Instant since,
      @RequestParam(required = false) String after,
      @RequestParam(defaultValue = "20") int limit) {
    DeliveryQuery query = query(status, eventType, aggregateId, since, after, limit);   // IllegalArgumentException → 400
    List<Delivery> page = deliveries.findByTenant(tenant(), query);

    ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (page.size() == query.limit()) {
      Delivery last = page.get(page.size() - 1);
      response.header(NEXT_CURSOR_HEADER, DeliveryCursorCodec.encode(new DeliveryCursor(last.createdAt(), last.id())));
    }
    return response.body(page.stream().map(DeliveryResponse::summary).toList());
  }

  @GetMapping("/{id}")
  public DeliveryResponse get(@PathVariable UUID id) {
    return DeliveryResponse.full(mine(id));
  }

  @PostMapping("/{id}/redeliver")
  public ResponseEntity<Map<String, String>> redeliver(@PathVariable UUID id) {
    return switch (service.redeliver(tenant(), id)) {
      case SCHEDULED -> ResponseEntity.accepted().body(Map.of("status", "PENDING"));
      case NOT_FOUND -> throw new NotFoundException("delivery", id.toString());
      case NOT_REDELIVERABLE -> throw new DomainException("DELIVERY_NOT_REDELIVERABLE", "delivery " + id + " is not DEAD or FAILED, or its endpoint is inactive");
    };
  }

  @PostMapping("/redeliver-dead")
  public ResponseEntity<Map<String, Integer>> redeliverDead(@RequestBody RedeliverDeadRequest body) {
    Instant since = body.since();
    if (since == null) { throw new IllegalArgumentException("since is required"); }
    if (since.isBefore(clock.instant().minus(Duration.ofDays(MAX_REDELIVER_WINDOW_DAYS)))) {
      throw new DomainException("WINDOW_TOO_WIDE", "since must be within the last " + MAX_REDELIVER_WINDOW_DAYS + " days");
    }
    return ResponseEntity.accepted().body(Map.of("scheduled", service.redeliverDead(tenant(), since)));
  }

  private Delivery mine(UUID id) {
    return deliveries.findByTenantAndId(tenant(), id).orElseThrow(() -> new NotFoundException("delivery", id.toString()));
  }

  private String tenant() { return MerchantContext.current().merchantId().value(); }
}
```

`ErrorHandler.STATUS_BY_CODE` + `"DELIVERY_NOT_REDELIVERABLE", HttpStatus.CONFLICT`. `IdempotencyFilter.IDEMPOTENT_POST` + `"|^/v1/webhooks/deliveries/([^/]+/redeliver|redeliver-dead)$"` and two rows in `IdempotencyFilterPathsTest` (plus a negative `POST /v1/webhooks/endpoints` stays unfiltered — it is not idempotent today; leave it).

- [ ] **Step 3: Failing API integration test** `WebhookDeliveriesApiIntegrationTest` (same skeleton as `PaymentsFlowIntegrationTest`: Testcontainers, Itaú WireMock, a sink `HttpServer`, merchant + key + credential + endpoint). Seed deliveries by creating Pix payments (each `payment.pending` yields one delivery) with the sink answering 500 for the first N requests so some die (`webhook-delivery.max-attempts=2`, `retry-delay-ms=200`, `base-backoff=PT0.1S` in `properties`): then
  - `GET /v1/webhooks/deliveries` → 200, newest first, `payload` absent; `?status=DEAD` filters; `limit=1` returns `X-Next-Cursor` and the next page continues without repeating (Review Focus 4 at the API level);
  - `GET /{id}` → `payload` present and equal to the webhook body the sink received; another merchant's key → 404 (Review Focus 1);
  - `POST /{id}/redeliver` with `Idempotency-Key` → 202 and the sink receives it again (Awaitility) with the `X-Gateway-Signature` valid for the endpoint secret; without key → 400; on a DELIVERED one → 409 `DELIVERY_NOT_REDELIVERABLE`;
  - `POST /redeliver-dead {"since": <31 days ago>}` → 422 `WINDOW_TOO_WIDE`; with a valid `since` → 202 `{"scheduled": n}`.

- [ ] **Step 4: Run** `./mvnw -B dependency:resolve` (online, once) then `./mvnw -B -o -pl gateway-app -am verify` → green. **Commit** `feat(app): merchants list, inspect and redeliver their webhook deliveries` (+ a first separate `build: webhook-delivery 0.2.0` commit for the version bump).

---

### Task 4 (gateway): the outbound contract in the README, verified by test

**Files:**
- Modify: `README.md` (new section `### Outbound webhooks` right before `### Idempotency`)
- Create: `gateway-app/src/test/java/com/gateway/app/docs/EventCatalogTest.java`, `gateway-app/src/test/java/com/gateway/app/docs/ReadmeBlocks.java` (helper: reads `README.md` from the repo root — find it by walking up from `user.dir` until a `pom.xml` with `<artifactId>payment-gateway-parent</artifactId>`; extracts the fenced block that follows a heading)
- Modify: `gateway-billing/.../customer/CustomerService.json` → make it `public static` (the test needs it), same for any package-private builder.

**Interfaces:**
- Produces: README section with (1) delivery semantics, (2) headers and signature with verification examples in Python and Java, (3) the event catalog: one `#### <event.type>` per type with a JSON example; `EventCatalogTest` builds each example from the real builders with fixed fixture objects and compares **key sets (recursively)** with the README block for that type — values may differ (ids, dates), keys may not.

- [ ] **Step 1: Failing test** `EventCatalogTest` — a table `List<CatalogEntry(String type, Supplier<Map<String,Object>> example)>` covering: `payment.pending|authorized|completed|failed|expired|canceled` (one `Payment` fixture per method via `Payment.create/createBolecode/createCard` + `PaymentEvents.paymentJson`), `refund.requested|completed|failed|unknown` (`PaymentEvents.refundJson` — make it `public static` if package-private), `customer.created|updated` (`CustomerService.json`), `order.created|paid|canceled|expired` (`OrderService.json`), `invoice.created` and `invoice.updated` (extract `SubscriptionBilling.invoiceCreated` and `Dunning.invoiceUpdated` into a `public final class InvoicePayloads` with `created(...)`/`updated(...)` — a move, committed separately as `refactor(billing): invoice payload builders in one place`), `subscription.created|past_due|recovered|dunning_exhausted|canceled|ended` (`SubscriptionService.json`, plus `invoice_id` for `dunning_exhausted`). For each entry: `assertThat(ReadmeBlocks.keysOf("#### " + type)).as(type).isEqualTo(keysOf(example.get()))` where `keysOf` returns the sorted set of dotted key paths (`pix.copia_e_cola`, `period.start`, …), nulls included as keys. The test fails first because the README has no section.

- [ ] **Step 2: Write the README section.** Structure:

```
### Outbound webhooks

How delivery works — at-least-once; `X-Gateway-Event-Id` dedupe; ordering per partition key (payment / order / subscription id); retries: `webhook-delivery.max-attempts` (5) with backoff `base-backoff` × 2^n capped at 64× → `DEAD`; manual redelivery (`POST /v1/webhooks/deliveries/{id}/redeliver`, `/redeliver-dead`); timeouts 2 s connect / 10 s read; any 2xx is success; private targets refused unless `allow-private-targets`.

Headers — `X-Gateway-Event-Id`, `X-Gateway-Event-Type`, `X-Gateway-Signature: t=<epoch-seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>`, `X-Gateway-Signature-Previous` during the 24 h after a secret rotation.

Verifying — pseudocode + Python (`hmac.compare_digest`, 5-minute tolerance) + Java (`Mac`, `MessageDigest.isEqual`) examples; "verify against the raw body bytes, before any JSON parsing".

Listing and redelivering — the four routes with examples and the 409/422 codes.

Event catalog — `#### payment.pending` … one block each, in the order of the test table; a line before each saying when it fires.
```

Examples are rendered by running the test's suppliers once and pasting (the test keeps them honest afterwards).

- [ ] **Step 3: Run** `./mvnw -B -o -pl gateway-app -am verify` → green. **Commits**: the refactor move, then `docs(webhooks): outbound contract, signature verification and the event catalog, kept honest by a test`.

---

### Task 5 (gateway): end-to-end proof and decisions

**Files:**
- Create: `gateway-app/src/test/java/com/gateway/app/WebhookDeliveryFlowIntegrationTest.java`
- Modify: `docs/superpowers/DECISOES.md` (the four decisions of spec §5, dated 2026-10-04, Portuguese, "Rejeitado:" and "Custo se errado:"), `README.md` (one line under "End-to-end against the sandboxes" pointing to the flow test as the webhook proof — the sandboxes cannot receive webhooks)

**Interfaces:** consumes everything above.

- [ ] **Step 1: The flow test** (`properties`: `webhook-delivery.max-attempts=3`, `retry-delay-ms=200`, `base-backoff=PT0.2S`, `secret-rotation-overlap=PT1H`; WireMock as the merchant receiver this time, so the test can toggle 500/200 and inspect headers):
  1. merchant, key, Itaú credential, endpoint subscribed to `payment.*` → secret S1;
  2. receiver 200; create a Pix → receiver got `payment.pending` with `X-Gateway-Signature` valid for S1 (recompute with `HmacSigner` from the lib on the received body and `t=`), `X-Gateway-Event-Id` = UUID v3 of the outbox id (just assert it is a UUID and stable across a redelivery);
  3. receiver 500; create another Pix → within 3 s the list shows `attempts >= 2`, `last_error` mentions 500; after `max-attempts` → `DEAD`;
  4. `POST …/redeliver` → 202; receiver back to 200 → `DELIVERED`, same `X-Gateway-Event-Id`, `redelivered_at` set, `last_error_before_redelivery` kept;
  5. rotate the secret → S2; create a third Pix → receiver got both `X-Gateway-Signature` (S2) and `X-Gateway-Signature-Previous` (S1), both valid;
  6. deactivate the endpoint; redeliver the step-4 delivery again → 409 `DELIVERY_NOT_REDELIVERABLE`.

- [ ] **Step 2: DECISOES** entries; README line.

- [ ] **Step 3: Full build** `./mvnw -B -o verify` → green. **Commit** `test(app): the outbound webhook path proved end to end, and the decisions`.

---

## Self-review notes
- Spec §2.1 asks for `limit` default 20 / max 100 → `DeliveryQuery`; §2.2 lease guard on `FAILED` in flight is the plan's addition (Review Focus 3 sibling); §3.1 `X-Next-Cursor` + opaque `after`; §3.2 catalog by test; §3.3 flow test; §4 codes; §5 decisions → Task 5.
- The lib's release is user-gated (Task 2 Step 8); Tasks 3–5 cannot start before `0.2.0` is published.
- Review Focus 1 → Task 3 (404 for another merchant); 2 → Task 2 + 5; 3 → Task 2 partition test; 4 → Task 1 + 3; 5 → Task 4.
