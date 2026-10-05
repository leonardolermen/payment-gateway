# Operations and disputes — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The operator sees and resolves divergences, jobs and stuck payments through the admin API and watches the gateway through Prometheus; the merchant opens a dispute on a payment and follows it to a resolution by webhook.

**Architecture:** Divergences grow a lifecycle (`OPEN → UNDER_REVIEW → RESOLVED|REJECTED`) and an origin (`SYSTEM|MERCHANT`); a merchant dispute is a `MERCHANT` divergence. Admin controllers under `/v1/admin/*` (already guarded by `AdminKeyFilter`) read and act on divergences, jobs and stuck payments; a merchant controller opens and lists disputes; `dispute.updated` goes through the outbox like every other event. Metrics are gauges refreshed by one scheduled aggregator plus a provider-call timer, exposed on a separate management port.

**Tech Stack:** Java 25, Spring Boot, JPA/Flyway, Micrometer + Prometheus registry (already in `gateway-app`), Testcontainers, RestTestClient.

**Spec:** `docs/superpowers/specs/2026-10-04-operacao-e-contestacoes-design.md`

## Global Constraints

- Branch `feat/operations-and-disputes` from `feat/webhook-deliveries`. English everywhere except DECISOES (Portuguese). Code standard `C:\Users\leona\.claude\CLAUDE.md` (full-word names, braces always, state machine as a table, factories, comments say WHY with evidence, rename/format never with logic, ~300 lines / ~7 ctor deps).
- ArchUnit: JPA only under `..persistence..`; entities package-private; domain classes (not `*Service|Runner|Gateway|Relay|Properties|Configuration|Events`) never depend on Spring; `payments` never imports `billing`/`providers`; `billing` never imports `app`. Micrometer (`io.micrometer.core`) is not Spring: a domain class may use `MeterRegistry` only if ArchUnit allows it — it does (the rule names `org.springframework..`). Keep metrics code in `*Service`-free classes only where needed; `ProviderGateway` ends in `Gateway` and may take a `MeterRegistry`.
- Migrations: payments next free `V207`. `ErrorHandler.STATUS_BY_CODE` is at `Map.of`'s 10-pair limit → switch to `Map.ofEntries` in the task that adds a code (pure move, same commit is fine since it is mechanical, but say so).
- Money path: nothing here moves money. Resolving a divergence or dispute records a decision; refunds and cancels keep their own routes.
- Every creating POST (`POST /v1/payments/{id}/disputes`) goes through `IdempotencyFilter.IDEMPOTENT_POST` + `IdempotencyFilterPathsTest`. Admin POSTs are not idempotency-filtered (they are not merchant routes; they are idempotent by state).
- Outbox rows need a `MerchantId`: a dispute event uses the payment's merchant.
- Events documented in the README catalog are verified by `EventCatalogTest`: adding `dispute.updated` requires its `#### dispute.updated` block and a `CATALOG` row.
- Verify: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -B -o -pl <modules> -am verify` (Docker Desktop must be running; never start/stop it). Spotless before every commit. Trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Never push.

## Review Focus

1. **A dispute on another merchant's payment** must be 404, and a merchant must never see a `SYSTEM` divergence through `/v1/disputes`. Test in Task 4.
2. **Two concurrent `POST …/disputes`** on the same payment: exactly one `OPEN` dispute (partial unique index by origin), the other `409 DISPUTE_ALREADY_OPEN`. Test in Task 1 (index) and Task 4 (API).
3. **Resolving an already-resolved divergence** must be a 409, never a silent second resolution or a second `dispute.updated`. Test in Task 2.
4. **`run-now` on a job that a worker currently holds** (claimed, lease live): must not create a second run; the UPDATE is conditional on `claimed_at IS NULL OR claimed_at < lease cutoff`. Test in Task 3.
5. **Metrics on an empty database and under load**: the aggregator never throws (a throw would stop the scheduler thread's next runs) and bounds its queries (counts, not row loads). Test in Task 5.

---

## File structure

```
gateway-payments
  db/migration/payments/V207__divergences_operations.sql
  reconciliation/ReconciliationDivergence.java        (record → class with lifecycle; keeps the name)
  reconciliation/DivergenceOrigin.java, DivergenceStatus.java, DivergenceTransitions.java, DivergenceResolution.java
  reconciliation/Divergences.java                     (+ review, resolve, listing passthroughs)
  reconciliation/persistence/*                        (+ columns, filters with cursor, update)
  dispute/Dispute.java, DisputeReason.java, DisputeService.java, DisputeEvents.java
  jobs/persistence/JobRepository.java (+ findByFilter, forceDue, giveUp), JobJpaRepository.java, JobRepositoryImpl.java
  jobs/JobQuery.java
  payment/StuckPayments.java                          (query only: CREATED too long / PENDING past expiry)
  provider/ProviderGateway.java                       (+ MeterRegistry timer)
  pom.xml (+ micrometer-core)
gateway-app
  api/admin/divergence/DivergencesAdminController.java + dto/
  api/admin/job/JobsAdminController.java + dto/
  api/admin/payment/StuckPaymentsAdminController.java + dto/
  api/dispute/DisputesController.java + dto/
  api/support/ErrorHandler.java (Map.ofEntries + codes), IdempotencyFilter.java (+ route)
  observability/OperationsMetrics.java (gauges), observability/MetricsConfiguration.java
  application.yml (management.server.port), application-test.yml
  test: AdminOperationsIntegrationTest, DisputesFlowIntegrationTest, OperationsMetricsTest, docs/EventCatalogTest (+ row), api/support/IdempotencyFilterPathsTest (+ row)
README.md ("Operations" + "Disputes" sections, metrics table, catalog entry), docs/superpowers/DECISOES.md
```

---

### Task 1: Divergence lifecycle and origin

**Files:**
- Create: `gateway-payments/src/main/resources/db/migration/payments/V207__divergences_operations.sql`
- Create: `reconciliation/DivergenceOrigin.java`, `DivergenceStatus.java`, `DivergenceResolution.java`, `DivergenceTransitions.java`, `DivergenceQuery.java`
- Modify: `reconciliation/ReconciliationDivergence.java`, `reconciliation/Divergences.java`, `reconciliation/persistence/ReconciliationDivergenceEntity.java`, `ReconciliationDivergenceJpaRepository.java`, `ReconciliationDivergenceRepository.java`, `ReconciliationDivergenceRepositoryImpl.java`, every constructor call site of the record (`grep -rn "new ReconciliationDivergence(" gateway-*/src`)
- Test: `reconciliation/DivergenceTransitionsTest.java`, `reconciliation/DivergencesIntegrationTest.java`

**Interfaces:**
- Produces: `enum DivergenceOrigin { SYSTEM, MERCHANT }`; `enum DivergenceStatus { OPEN, UNDER_REVIEW, RESOLVED, REJECTED }` with `isFinal()`; `enum DivergenceResolution { CONFIRMED, FALSE_POSITIVE, RESOLVED, REJECTED }` with `static Set<DivergenceResolution> allowedFor(DivergenceOrigin)` (`SYSTEM` → CONFIRMED|FALSE_POSITIVE; `MERCHANT` → RESOLVED|REJECTED) and `DivergenceStatus toStatus()` (CONFIRMED/FALSE_POSITIVE/RESOLVED → RESOLVED; REJECTED → REJECTED); `DivergenceTransitions.allowed(from, to)`; `ReconciliationDivergence` becomes a final class (name kept — 14 call sites and tests use it) with fields `id, paymentId, origin, gatewayStatus, providerStatus (the kind), detail, reason (DisputeReason name or null), merchantNote, status, resolution, resolutionNote, resolvedBy, resolvedAt, createdAt, updatedAt` and methods `static ReconciliationDivergence system(String paymentId, String gatewayStatus, String kind, String detail, Instant now)`, `static ReconciliationDivergence merchant(String paymentId, String gatewayStatus, String reason, String note, Instant now)` (kind = `"DISPUTE"`), `markUnderReview(Instant)`, `resolve(DivergenceResolution, String note, String by, Instant)` (throws IllegalStateException on a final status or a resolution not allowed for the origin), `rehydrate(...)`, accessors; `DivergenceQuery(DivergenceStatus status, DivergenceOrigin origin, String kind, String merchantId, Instant since, String afterId, int limit)` (limit 0→20, max 100); repository: `openIfAbsent` (unchanged semantics, now keyed by origin too), `Optional<ReconciliationDivergence> findById(String id)`, `List<ReconciliationDivergence> find(DivergenceQuery)` ordered `created_at DESC, id DESC` with `afterId` cursor (ULIDs are time-ordered: `id < afterId`), `boolean update(ReconciliationDivergence)` (optimistic on `updated_at` equality — carry the loaded `updatedAt` and `WHERE updated_at = :loaded`), `Optional<ReconciliationDivergence> findOpenDispute(String paymentId)`, `List<ReconciliationDivergence> findByPayment(String paymentId)`, `long countOpenByOriginAndKind()` → returns `List<DivergenceCount(origin, kind, count)>`; `Divergences.open(Payment, String kind, String detail)` unchanged (origin SYSTEM), plus `openDispute(Payment, DisputeReason reason, String note) → ReconciliationDivergence` (throws `DomainException("DISPUTE_ALREADY_OPEN")` when `findOpenDispute` is present), `review(String id, String by)`, `resolve(String id, DivergenceResolution, String note, String by) → ReconciliationDivergence` (409 `DIVERGENCE_CLOSED` when final; 422 `RESOLUTION_NOT_ALLOWED` when not allowed for the origin), `get(id)`, `list(DivergenceQuery)`.

- [ ] **Step 1: Migration**

```sql
-- Operations (plan "operations and disputes", spec 2026-10-04 §2). A divergence gains a lifecycle
-- and an origin: SYSTEM rows are what reconciliation, settlement and the card/boleto paths open;
-- MERCHANT rows are disputes a merchant opens on its own payment. Both sit in one queue for the
-- operator. Resolving never moves money: refunds and cancels keep their own routes, so the audit of
-- "decided" and "paid back" are two distinct actions.
ALTER TABLE payments.reconciliation_divergences
    ADD COLUMN origin          VARCHAR(10)  NOT NULL DEFAULT 'SYSTEM',
    ADD COLUMN reason          VARCHAR(20),
    ADD COLUMN merchant_note   VARCHAR(500),
    ADD COLUMN resolution      VARCHAR(16),
    ADD COLUMN resolution_note VARCHAR(500),
    ADD COLUMN resolved_by     VARCHAR(80),
    ADD COLUMN resolved_at     TIMESTAMPTZ,
    ADD COLUMN updated_at      TIMESTAMPTZ;
UPDATE payments.reconciliation_divergences SET updated_at = created_at WHERE updated_at IS NULL;
ALTER TABLE payments.reconciliation_divergences ALTER COLUMN updated_at SET NOT NULL;
ALTER TABLE payments.reconciliation_divergences ALTER COLUMN status TYPE VARCHAR(12);

-- One OPEN SYSTEM divergence per (payment, kind) as before; one OPEN dispute per payment. A SYSTEM
-- row and a MERCHANT row may coexist: the bank disagreeing and the merchant complaining are two facts.
DROP INDEX payments.ux_divergences_open_payment_status;
CREATE UNIQUE INDEX ux_divergences_open_system
    ON payments.reconciliation_divergences (payment_id, provider_status)
 WHERE status = 'OPEN' AND origin = 'SYSTEM';
CREATE UNIQUE INDEX ux_divergences_open_dispute
    ON payments.reconciliation_divergences (payment_id)
 WHERE status IN ('OPEN', 'UNDER_REVIEW') AND origin = 'MERCHANT';
CREATE INDEX idx_divergences_listing ON payments.reconciliation_divergences (status, origin, created_at DESC, id DESC);
```

(`hasOpen`/`openIfAbsent` for SYSTEM keep matching `status = 'OPEN'` only: an `UNDER_REVIEW` system divergence of the same kind lets a new one open, which is correct — the operator is looking at the first and the bank disagreed again.)

- [ ] **Step 2: Failing unit test** `DivergenceTransitionsTest`

```java
class DivergenceTransitionsTest {
  @Test
  void openMovesToReviewOrToAFinalState() {
    assertThat(DivergenceTransitions.allowed(OPEN, UNDER_REVIEW)).isTrue();
    assertThat(DivergenceTransitions.allowed(OPEN, RESOLVED)).isTrue();
    assertThat(DivergenceTransitions.allowed(OPEN, REJECTED)).isTrue();
    assertThat(DivergenceTransitions.allowed(UNDER_REVIEW, RESOLVED)).isTrue();
    assertThat(DivergenceTransitions.allowed(UNDER_REVIEW, REJECTED)).isTrue();
  }

  @Test
  void finalStatesNeverMoveAndReviewDoesNotReopen() {
    for (DivergenceStatus from : List.of(RESOLVED, REJECTED)) {
      for (DivergenceStatus to : DivergenceStatus.values()) {
        assertThat(DivergenceTransitions.allowed(from, to)).as(from + "->" + to).isFalse();
      }
    }
    assertThat(DivergenceTransitions.allowed(UNDER_REVIEW, OPEN)).isFalse();
  }

  @Test
  void aResolutionBelongsToAnOrigin() {
    assertThat(DivergenceResolution.allowedFor(DivergenceOrigin.SYSTEM)).containsExactlyInAnyOrder(CONFIRMED, FALSE_POSITIVE);
    assertThat(DivergenceResolution.allowedFor(DivergenceOrigin.MERCHANT)).containsExactlyInAnyOrder(DivergenceResolution.RESOLVED, DivergenceResolution.REJECTED);
    assertThat(DivergenceResolution.REJECTED.toStatus()).isEqualTo(DivergenceStatus.REJECTED);
    assertThat(CONFIRMED.toStatus()).isEqualTo(DivergenceStatus.RESOLVED);
  }
}
```

- [ ] **Step 3: Domain.** `ReconciliationDivergence` as a class in the `Order` style (private constructor, two factories, `transition` through the table, `updatedAt` bumped on every mutation, `rehydrate` with every column). `resolve`:

```java
  /**
   * The operator's decision. Never moves money (spec §3): a CONFIRMED double payment is refunded
   * through POST /refunds by the same operator, so the audit shows two acts, not one.
   */
  public void resolve(DivergenceResolution resolution, String note, String by, Instant at) {
    if (!DivergenceResolution.allowedFor(origin).contains(resolution)) {
      throw new DomainException("RESOLUTION_NOT_ALLOWED", resolution + " does not apply to a " + origin + " divergence");
    }
    transition(resolution.toStatus(), at);
    this.resolution = resolution;
    this.resolutionNote = note;
    this.resolvedBy = by;
    this.resolvedAt = at;
  }
```

`transition` throws `DomainException("DIVERGENCE_CLOSED", "divergence " + id + " is " + status)` when not allowed (a stable code the API maps to 409). `Divergences` gains the methods listed in Interfaces; `review`/`resolve` run in `unitOfWork` (inject `UnitOfWork` — `Divergences` is not a `*Service`, so the port, as elsewhere), with `update` false → `DomainException("CONFLICT")`.

- [ ] **Step 4: Persistence.** Entity gets the eight columns (`@JdbcTypeCode(CHAR)` where CHAR). JPA repository: `findFirstByPaymentIdAndOriginAndStatusIn(paymentId, "MERCHANT", List.of("OPEN","UNDER_REVIEW"))`, `findByPaymentIdOrderByCreatedAtDesc`, a `@Query` list with optional predicates (status, origin, providerStatus, since, `id < :afterId`) joined to `payments` for `merchant_id` (`JOIN PaymentEntity p ON p.id = d.paymentId` with `(:merchantId IS NULL OR p.merchantId = :merchantId)`), `@Modifying updateIfUnchanged(... WHERE id = :id AND updatedAt = :loadedUpdatedAt)`, and a native count `SELECT origin, provider_status, count(*) FROM payments.reconciliation_divergences WHERE status IN ('OPEN','UNDER_REVIEW') GROUP BY 1,2`. `openIfAbsent` keeps catching the unique violation and returning false (now for either index).

- [ ] **Step 5: Failing integration test** `DivergencesIntegrationTest` (extends `ServiceIntegrationTestBase`): (i) `open` twice same kind → second false; `open` with another kind → true; (ii) `openDispute` twice → `DISPUTE_ALREADY_OPEN`; a SYSTEM divergence and a dispute coexist on one payment; (iii) `review` then `resolve(CONFIRMED)` on SYSTEM → RESOLVED with by/at/note; `resolve(RESOLVED)` on SYSTEM → `RESOLUTION_NOT_ALLOWED`; resolve again → `DIVERGENCE_CLOSED`; (iv) list by status/origin/merchant with cursor over 3 rows limit 2; (v) counts grouped.

- [ ] **Step 6: Run** `./mvnw -B -o -pl gateway-payments -am verify` → green (every existing caller still compiles through the unchanged `open`). **Commit** `feat(payments): divergences gain a lifecycle, an origin and listing`.

---

### Task 2: Admin API for divergences

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/api/admin/divergence/DivergencesAdminController.java`, `dto/DivergenceResponse.java`, `dto/ResolveDivergenceRequest.java`, `dto/PaymentSummary.java`
- Modify: `api/support/ErrorHandler.java` (`Map.ofEntries`, + `DIVERGENCE_CLOSED` 409, `DISPUTE_ALREADY_OPEN` 409)
- Create: `gateway-payments/.../dispute/DisputeEvents.java` (writes `dispute.updated` to the outbox; Task 4 uses it too). `Divergences.review/resolve` take a `Consumer<ReconciliationDivergence> afterUpdate` that runs inside the same transaction, so the admin service passes `divergence -> disputeEvents.updated(payment.merchantId(), divergence)` for MERCHANT rows and the event commits with the state change.
- Test: `gateway-app/src/test/java/com/gateway/app/AdminOperationsIntegrationTest.java` (divergence part)

**Interfaces:**
- Produces routes: `GET /v1/admin/divergences?status=&origin=&kind=&merchant_id=&since=&after=&limit=` → `List<DivergenceResponse>` + `X-Next-Cursor` (the last id) when full; `GET /{id}` → `DivergenceResponse` with `payment: PaymentSummary(id, merchant_id, status, method, provider, amount, currency, paid_at)`; `POST /{id}/review` → 200; `POST /{id}/resolve {"resolution": "...", "note": "..."}` → 200 (`resolved_by = "admin"` until operators exist); `DivergenceResponse(id, payment_id, origin, kind, gateway_status, detail, reason, merchant_note, status, resolution, resolution_note, resolved_by, resolved_at, created_at, updated_at)`; `DisputeEvents.updated(MerchantId, ReconciliationDivergence)` writes `dispute.updated` `{id, payment_id, reason, status, resolution, resolution_note, updated_at}` (partition = payment id).

- [ ] **Step 1: Failing API test** (`AdminOperationsIntegrationTest`, `X-Admin-Key: test-admin`): seed a SYSTEM divergence by opening one through `Divergences` (autowired) on a created Pix payment; `GET /v1/admin/divergences?status=OPEN` lists it with `payment.merchant_id`; `POST /{id}/review` → `UNDER_REVIEW`; `POST /{id}/resolve {"resolution":"FALSE_POSITIVE","note":"bank echo"}` → `RESOLVED`, `resolved_by: "admin"`; second resolve → 409 `DIVERGENCE_CLOSED`; `RESOLVED` on a SYSTEM row → 422 `RESOLUTION_NOT_ALLOWED`; no `X-Admin-Key` → 403; a MERCHANT dispute (open through `Divergences.openDispute`) resolved with `REJECTED` → outbox has exactly one `dispute.updated` with `status: REJECTED` (check `payments.outbox` by jdbc).

- [ ] **Step 2: Controller** following `MerchantsAdminController`; `kind` maps to `providerStatus`. The 404 is `NotFoundException("divergence", id)`.

- [ ] **Step 3: Run** app verify → green. **Commit** `feat(app): the operator lists, reviews and resolves divergences`.

---

### Task 3: Jobs and stuck payments admin

**Files:**
- Modify: `gateway-payments/.../jobs/persistence/JobRepository.java`, `JobJpaRepository.java`, `JobRepositoryImpl.java`; create `jobs/JobQuery.java`
- Create: `gateway-payments/.../payment/StuckPayments.java` (reads: `CREATED` older than `stuckCreatedAfter`; `PENDING` with `expires_at < now - expirationGrace`; limit 100 each) — uses `PaymentRepository.findByStatusCreatedBefore` and `findPendingOlderThan`.
- Create: `api/admin/job/JobsAdminController.java` + `dto/JobResponse.java`, `GiveUpRequest.java`; `api/admin/payment/StuckPaymentsAdminController.java` + `dto/StuckPaymentResponse.java`
- Modify: `PaymentsConfiguration` (beans), `BillingConfiguration`? no.
- Test: `gateway-payments/.../jobs/JobRepositoryAdminIntegrationTest.java`, app `AdminOperationsIntegrationTest` (jobs + stuck part)

**Interfaces:**
- Produces: `JobQuery(String status, JobType type, String afterId, int limit)`; `JobRepository.find(JobQuery)` ordered `status='DEAD' first, then attempts DESC, next_run_at ASC` (implement as two queries or one `ORDER BY CASE status WHEN 'DEAD' THEN 0 ELSE 1 END, attempts DESC, next_run_at`), `boolean forceDue(String id, Instant now, Duration lease)` — `UPDATE … SET next_run_at = :now, status = 'PENDING' WHERE id = :id AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)` (keeps `attempts`; returns updated==1), `boolean giveUp(String id, String note, Instant now)` — `UPDATE … SET status = 'DEAD', last_error = :note WHERE id = :id AND status = 'PENDING' AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)`; `long countByStatus(String)`, `long countOverdue(Instant before)` (`status='PENDING' AND next_run_at < before`); `StuckPayments.createdTooLong(Instant now)`, `pendingPastExpiry(Instant now)` → `List<Payment>`, `counts(now)` → `StuckCounts(createdTooLong, pendingPastExpiry)` (count queries: add `countByStatusAndCreatedAtBefore` and `countPendingOlderThan` to the JPA repository).
- Routes: `GET /v1/admin/jobs?status=&type=&after=&limit=` → `JobResponse(id, type, ref_id, status, attempts, last_error, next_run_at, claimed_at, created_at)`; `POST /v1/admin/jobs/{id}/run-now` → 200 `JobResponse` / 409 `JOB_IN_FLIGHT` (claimed) / 404; `POST /v1/admin/jobs/{id}/give-up {"note"}` → 200 / 409 `JOB_NOT_PENDING` / 404; `GET /v1/admin/payments/stuck` → `{"created_too_long": [StuckPaymentResponse(id, merchant_id, method, provider, status, amount, created_at, expires_at)], "pending_past_expiry": [...]}`.

- [ ] **Step 1: Failing repository test**: enqueue three jobs (one `DEAD` via `reschedule` with max 1, one `PENDING` due, one `PENDING` claimed with `claimed_at = now`); `find` orders DEAD first; `forceDue` on the claimed one → false (Review Focus 4), on the DEAD one → true and it is `PENDING` due now with attempts unchanged, then `JobRunner.runDue` picks it (use a `JobType` with a handler in the payments test context: `EXPIRE_PAYMENT` with a nonexistent payment id → handler returns true and the job is `DONE`); `giveUp` on PENDING → DEAD with the note; `giveUp` on DEAD → false.

- [ ] **Step 2: Failing API test** for the three admin routes incl. 409s and 404, and `stuck` showing a `CREATED` payment aged by inserting it with `created_at` in the past via jdbc (or `MutableClock` is not available in the app context — use jdbc).

- [ ] **Step 3: Implement**, run `./mvnw -B -o -pl gateway-payments,gateway-app -am verify`. **Commit** `feat(app): the operator sees jobs and stuck payments, reruns or gives up a job`.

---

### Task 4: Merchant disputes

**Files:**
- Create: `gateway-payments/.../dispute/DisputeReason.java` (`AMOUNT_MISMATCH, NOT_SETTLED, DUPLICATE, OTHER`), `dispute/Dispute.java` (record view: `id, paymentId, reason, note, status, resolution, resolutionNote, createdAt, resolvedAt` + `static from(ReconciliationDivergence)`), `dispute/DisputeService.java` (`open(MerchantId, paymentId, DisputeReason, String note) → Dispute` — loads the payment by merchant (404), `divergences.openDispute(...)` inside a unit of work with `DisputeEvents.updated` in the same transaction; `get(MerchantId, id)` (404 unless the divergence is MERCHANT and its payment belongs to the merchant); `list(MerchantId, DivergenceStatus status, Instant since, String after, int limit)` → `DivergenceQuery` with `origin = MERCHANT, merchantId`)
- Create: `gateway-app/.../api/dispute/DisputesController.java` + `dto/OpenDisputeRequest.java`, `DisputeResponse.java`
- Modify: `IdempotencyFilter` (`|^/v1/payments/[^/]+/disputes$`) + `IdempotencyFilterPathsTest`; `ErrorHandler` (`DISPUTE_ALREADY_OPEN` 409 — added in Task 2); `README.md` (`#### dispute.updated` in the catalog + a "Disputes" subsection under "Customers, orders…"? no — its own `### Disputes` after "Idempotency"); `EventCatalogTest` (+ row with a fixture built from `ReconciliationDivergence.merchant(...)` → `DisputeEvents.json`); `PaymentsConfiguration` beans.
- Test: `gateway-payments/.../dispute/DisputeServiceIntegrationTest.java`, app `DisputesFlowIntegrationTest.java`

**Interfaces:**
- Routes: `POST /v1/payments/{id}/disputes {"reason","note"}` → 201 `DisputeResponse(id, payment_id, reason, note, status, resolution, resolution_note, created_at, resolved_at)` / 409 `DISPUTE_ALREADY_OPEN` / 404 / 400 (unknown reason); `GET /v1/disputes?status=&since=&after=&limit=` + `X-Next-Cursor`; `GET /v1/disputes/{id}`.
- Event `dispute.updated` emitted on open (`status: OPEN`) and on every admin transition (review, resolve) via `DisputeEvents.updated`.

- [ ] **Step 1: Failing service test**: open → `OPEN` + one `dispute.updated` in the outbox with `status: OPEN`; second open → `DISPUTE_ALREADY_OPEN`; another merchant's payment → `NotFoundException`; `get` of a SYSTEM divergence id by the merchant → `NotFoundException` (Review Focus 1); concurrent opens (two threads) → one succeeds (Review Focus 2).
- [ ] **Step 2: Failing app flow test**: merchant opens (needs `Idempotency-Key`; without → 400), lists, reads; admin reviews and resolves `REJECTED` with a note; merchant `GET` shows `REJECTED` + `resolution_note`; the sink (as in `PaymentsFlowIntegrationTest`) received `dispute.updated` three times (OPEN, UNDER_REVIEW, REJECTED) in order on the payment's partition.
- [ ] **Step 3: README** catalog block + `### Disputes` section; `EventCatalogTest` row. **Run** app verify. **Commit** `feat: a merchant disputes a payment and follows it to the operator's decision`.

---

### Task 5: Metrics

**Files:**
- Modify: `gateway-payments/pom.xml` (+ `io.micrometer:micrometer-core`, version managed by Boot), `provider/ProviderGateway.java` (+ `MeterRegistry` timer `gateway_provider_call_seconds` tags `provider, operation, outcome` where outcome ∈ `ok|provider_error|timeout|unexpected`), `PaymentsConfiguration` (`ProviderGateway` bean takes `MeterRegistry`; `ServiceTestConfig` provides a `SimpleMeterRegistry`)
- Create: `gateway-app/.../observability/OperationsMetrics.java` (`@Scheduled(fixedDelayString = "${gateway.metrics.refresh-ms:30000}")` refresh writing into `AtomicLong`s registered as gauges: `gateway_payments_total{status,method,provider,environment}` from a new `PaymentRepository.countByStatusMethodProviderEnvironment()` grouped native query; `gateway_payments_stuck{kind}` from `StuckPayments.counts`; `gateway_divergences_open{origin,kind}` from Task 1's counts; `gateway_jobs{status,type}` + `gateway_jobs_overdue` from Task 3's counts; `gateway_webhook_deliveries{status}` read as counts from `webhook_delivery.deliveries` through a `JdbcTemplate` in the app (the lib has no count API and a release for one COUNT is not worth it; the app already owns the lib's JPA wiring — WHY comment required); `gateway_outbox_pending` via `JdbcTemplate`). Every refresh is wrapped in try/catch logging WARN (Review Focus 5).
- Create: `observability/MetricsConfiguration.java` (the scheduled bean; `@EnableScheduling` exists on the app)
- Modify: `application.yml` (`management.server.port: ${GATEWAY_MANAGEMENT_PORT:9090}`; keep `include: health,info,prometheus`), `application-test.yml` (`management.server.port: 0` → random; tests read it from `@Value("${local.management.port}")`)
- Modify: `README.md` (section `### Operations` with the admin routes of Tasks 2–3 and a metrics table with suggested alert thresholds; a line that the management port must not be public)
- Test: `gateway-payments/.../provider/ProviderGatewayMetricsTest.java` (unit: a fake provider call → timer count 1 with outcome `ok`; a `ProviderException` TIMEOUT → outcome `timeout`), app `OperationsMetricsTest` (`@SpringBootTest` RANDOM_PORT + management port: after opening a divergence and creating a Pix, `GET http://localhost:{management}/actuator/prometheus` (no API key) contains `gateway_divergences_open{origin="SYSTEM",kind="…"} 1` and `gateway_payments_total{status="PENDING",method="PIX",…}`; the merchant port's `/actuator/prometheus` is 404 or 401 — assert it is not 200)

- [ ] **Step 1: Failing tests** as above. **Step 2: Implement.** Gauge labels are dynamic (status × method × provider): register a gauge per distinct label set the first time it is seen (`Gauge.builder(name, holder, AtomicLong::get).tags(...).register(registry)`); keep a `ConcurrentHashMap<Tags, AtomicLong>`; a label set that disappears keeps reporting 0 (document: Prometheus prefers a stable series over a vanishing one).
- [ ] **Step 3: Run** `./mvnw -B -o -pl gateway-payments,gateway-app -am verify`. **Commit** `feat(app): Prometheus metrics for payments, divergences, jobs, deliveries and provider calls`.

---

### Task 6: Decisions and the operator's runbook

**Files:**
- Modify: `docs/superpowers/DECISOES.md` (the four decisions of spec §7 — disputes as MERCHANT divergences; resolving never moves money; gauges every 30 s; management port without its own auth — plus two from this plan: `resolved_by = "admin"` until operators exist; the lib's delivery counts read by SQL in the app), `README.md` (the `### Operations` section gets a short runbook: what each divergence kind means and what to do — one line per kind from the list in `Divergences` call sites — and the job statuses), `docs/architecture.md` (one paragraph on operations and disputes + the management port), `docs/superpowers/README.md` (plan listed).
- Final: `./mvnw -B -o verify` green. **Commit** `docs(operations): decisions, runbook and architecture note`.

---

## Self-review notes
- Spec §2 columns and indexes → Task 1 (the unique-index rule by origin is the plan's precise form of "pode coexistir"); §3 routes → Tasks 2–3 (`run-now` on DEAD allowed, `give-up` only on PENDING); §4 → Task 4; §5 metrics → Task 5 (delivery counts via SQL instead of a lib change: recorded as a decision); §6 tests → each task; §7 → Task 6.
- Review Focus 1 → Task 4 service test; 2 → Task 1 index + Task 4 concurrency; 3 → Task 2 (409 on second resolve, one event); 4 → Task 3 repository test; 5 → Task 5 (try/catch + count queries; `OperationsMetricsTest` runs on an empty DB first).
- `ErrorHandler` codes added: `DIVERGENCE_CLOSED`, `DISPUTE_ALREADY_OPEN`, `JOB_IN_FLIGHT`, `JOB_NOT_PENDING` (409); `RESOLUTION_NOT_ALLOWED` stays 422 by default.
