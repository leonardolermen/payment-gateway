# Listagens, parte 2 (B1b) — Implementation Plan

> **Nota (2026-10-11, reescopo):** as Tasks 2 e 3, e quase toda a Task 1, já tinham saído no #30
> (`ec9f00e`: busca por nome, lista de assinaturas com `status`/cursor, `GET /v1/orders?customer_id=`,
> índices no `V309`). O B1b ficou só com o que faltava: o escape da barra invertida na busca
> (`3e99ca4`), `customer_name` em toda resposta de assinatura e o e-mail em branco no PATCH limpando
> o campo. A migração `pg_trgm` não entra (DECISOES 2026-10-08). O resto do plano fica como foi
> escrito, como registro.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The three listings the panel's Clientes and Assinaturas screens assume and the gateway does not have yet: customers searched by name, subscriptions listed across customers with a status filter and the customer's name, and orders filtered by customer.

**Architecture:** Each listing follows the pattern of `GET /v1/orders` (PR #30): a JPA query in the module's `persistence/`, a method on the repository interface and on the query service, a controller parameter, and the names resolved in the controller through `customers.namesOf`. Cursor = the last id of the previous page (ULIDs), newest first, one environment. No new tables; one index migration.

**Tech Stack:** Spring Boot 4, Java 25, Spring Data JPA, Flyway (billing prefix 3xx), Testcontainers Postgres.

**Spec:** `payment-gateway-web/docs/superpowers/specs/2026-10-08-painel-produto-design.md` §2 (search), §4 (subscriptions list); `payment-gateway-web/docs/superpowers/specs/2026-10-08-produto-completo-design.md` §9 (B1 row). Branch base: `main`.

## Global Constraints

- English everywhere in code; 100 columns; `./mvnw spotless:apply` before committing (revert `gateway-payments/.../CardPaymentFlow.java` if it reflows); `./mvnw verify` green before each commit; commits end with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- ArchUnit: `@Entity` package-private under `..persistence..`; model types never import Spring; `billing` does not import `app`.
- Every listing is scoped to the caller's merchant AND environment (`Environments.toProvider(caller.environment())`), newest first by `id DESC`, `limit` 1–100 (400 outside), `cursor` = last id of the previous page.
- The full document never leaves the server: a name search matches `name` only, never the document column; responses keep `DocumentMask`.
- README route table rows for every new parameter; error codes are contract (no new codes needed here).

## Review Focus

1. A `q` with SQL wildcards (`%`, `_`) or a very long string must be treated as literal text, not as a pattern — Task 1 (`qWithPercentIsLiteral`).
2. A TEST key must never see LIVE subscriptions or orders through the new filters — Tasks 2 and 3 (`aTestKeyListsOnlyTestSubscriptions`, `customerFilterRespectsTheEnvironment`).
3. `customer_id` of another merchant's customer returns an empty page, not 404 and not the other merchant's data — Task 3 (`anotherMerchantsCustomerIdIsAnEmptyPage`).
4. `status` outside the enum is 400 with the allowed values, like orders — Task 2 (`anUnknownStatusIs400`).
5. `customer_name` is resolved in one query per page (`namesOf` with the page's id set), not one per row — Task 2 (assert on the repository call count with a spy, or by inspection in review).

---

### Task 1: `GET /v1/customers?q=` — search by name

**Files:**
- Modify: `gateway-billing/src/main/java/com/gateway/billing/customer/persistence/CustomerJpaRepository.java` (new query), `CustomerRepository.java` + `CustomerRepositoryImpl.java` (`searchByName`), `gateway-billing/src/main/java/com/gateway/billing/customer/CustomerService.java` (`search`)
- Modify: `gateway-app/src/main/java/com/gateway/app/api/customer/CustomersController.java` (`list` gains `q`), `README.md` (route row)
- Create: `gateway-billing/src/main/resources/db/migration/billing/V309__customers_name_search.sql`
- Test: `gateway-app/src/test/java/com/gateway/app/CustomersApiIntegrationTest.java` (existing file; add cases)

**Interfaces:**
- Produces: `CustomerService.search(MerchantId, ProviderEnvironment, String q, String cursor, int limit): List<Customer>`; HTTP `GET /v1/customers?q=&limit=&cursor=`.

- [ ] **Step 1: Failing integration tests** in `CustomersApiIntegrationTest` (follow the file's existing header for the API key and `X-Environment`):

```java
@Test
void searchesByNameCaseInsensitiveAndPaged() {
  createCustomer("Maria Souza", "52998224725");
  createCustomer("Mariana Lima", "11144477735");
  createCustomer("João Pedro", "16899535009");

  List<Map<String, Object>> page = getList("/v1/customers?q=mari&limit=1");
  assertThat(page).hasSize(1);
  String cursor = (String) page.get(0).get("id");

  List<Map<String, Object>> next = getList("/v1/customers?q=MARI&limit=1&cursor=" + cursor);
  assertThat(next).hasSize(1).extracting(row -> row.get("name")).doesNotContain(page.get(0).get("name"));
  assertThat(getList("/v1/customers?q=mari&limit=10")).extracting(row -> row.get("name"))
      .containsExactlyInAnyOrder("Maria Souza", "Mariana Lima");
}

@Test
void qWithPercentIsLiteral() {
  createCustomer("Maria Souza", "52998224725");
  assertThat(getList("/v1/customers?q=%25")).isEmpty();   // "%" URL-encoded
  assertThat(getList("/v1/customers?q=_")).isEmpty();
}

@Test
void qCannotBeCombinedWithDocument() {
  assertStatus("/v1/customers?q=a&document=52998224725", 400);
}
```

- [ ] **Step 2: Run** `./mvnw -pl gateway-app -am test -Dtest=CustomersApiIntegrationTest` → FAIL (400 unknown parameter / wrong size).

- [ ] **Step 3: Migration + query.** `V309__customers_name_search.sql`:

```sql
-- Name search is a prefix/contains ILIKE on a trigram index; the document column is never searched.
CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE INDEX IF NOT EXISTS customers_name_trgm_idx ON billing.customers USING gin (lower(name) gin_trgm_ops);
```

(Check the real schema/table name in `V301..V307`; if `CREATE EXTENSION` is not allowed in the test container's role, use a plain `lower(name)` btree index and `LIKE 'term%'` — say so in the report.)

`CustomerJpaRepository`:

```java
@Query("SELECT c FROM CustomerEntity c WHERE c.merchantId = :merchantId AND c.environment = :environment"
    + " AND c.deletedAt IS NULL AND LOWER(c.name) LIKE CONCAT('%', :term, '%') ESCAPE '!'"
    + " AND (:cursorId IS NULL OR c.id < :cursorId) ORDER BY c.id DESC")
List<CustomerEntity> searchByName(@Param("merchantId") String merchantId, @Param("environment") String environment,
    @Param("term") String term, @Param("cursorId") String cursorId, Pageable page);
```

`CustomerRepositoryImpl.searchByName` escapes the term before binding: `term.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_")`, with a comment saying why (the user's text is literal). Mirror whatever soft-delete/active column the entity really has (read `CustomerEntity`).

- [ ] **Step 4: Service + controller.** `CustomerService.search(merchantId, environment, q, cursor, limit)` delegates. In `CustomersController.list` add `@RequestParam(required = false) String q`; `q` and `document` together → `IllegalArgumentException("q cannot be combined with document")`; blank `q` is treated as absent.

- [ ] **Step 5: Run the tests** → PASS. `./mvnw -q spotless:apply`; `./mvnw -q verify` → green.

- [ ] **Step 6: README** row: `GET /v1/customers?q=&limit=&cursor=` — 200, active customers whose name contains `q` (case-insensitive, literal), newest first; 400 with `document`.

- [ ] **Step 7: Commit** `feat(customers): search by name — GET /v1/customers?q=`.

---

### Task 2: `GET /v1/subscriptions?status=&limit=&cursor=` with `customer_name`

**Files:**
- Modify: `gateway-billing/.../subscription/persistence/SubscriptionJpaRepository.java`, `SubscriptionRepository.java`, `SubscriptionRepositoryImpl.java` (`list(merchantId, environment, status, cursorId, limit)`), `gateway-billing/.../subscription/SubscriptionQueries.java` (`list`)
- Modify: `gateway-app/.../api/subscription/SubscriptionsController.java` (`list` replaces `listByCustomer`: `customer_id` optional, `status`, `limit`, `cursor`), `gateway-app/.../api/subscription/dto/SubscriptionResponse.java` (+ `customerName`), `README.md`
- Create: `gateway-billing/src/main/resources/db/migration/billing/V310__subscriptions_list_index.sql` (`(merchant_id, environment, status, id DESC)`)
- Test: `gateway-app/src/test/java/com/gateway/app/SubscriptionsApiIntegrationTest.java` (existing; add cases)

**Interfaces:**
- Produces: `SubscriptionQueries.list(MerchantId, ProviderEnvironment, SubscriptionStatus status /*nullable*/, String cursorId, int limit): List<Subscription>`; `SubscriptionResponse.customerName` (snake_case `customer_name`) on every response (get, list, create, cancel, patch).
- Keeps: `GET /v1/subscriptions?customer_id=` behaviour (now also paged and filterable).

- [ ] **Step 1: Failing tests:**

```java
@Test
void listsAcrossCustomersNewestFirstWithStatusFilterAndNames() {
  String maria = createCustomer("Maria Souza", "52998224725");
  String joao = createCustomer("João Pedro", "16899535009");
  String plan = createPlan();
  String first = createSubscription(maria, plan, "PIX");
  String second = createSubscription(joao, plan, "PIX");
  cancelNow(second);

  List<Map<String, Object>> all = getList("/v1/subscriptions?limit=10");
  assertThat(all).extracting(row -> row.get("id")).containsExactly(second, first);
  assertThat(all.get(0)).containsEntry("customer_name", "João Pedro");

  assertThat(getList("/v1/subscriptions?status=ACTIVE")).extracting(row -> row.get("id")).containsExactly(first);
  assertThat(getList("/v1/subscriptions?limit=1&cursor=" + second)).extracting(row -> row.get("id")).containsExactly(first);
  assertThat(getList("/v1/subscriptions?customer_id=" + maria)).extracting(row -> row.get("id")).containsExactly(first);
}

@Test
void anUnknownStatusIs400() { assertProblem("/v1/subscriptions?status=NOPE", 400, "status must be one of"); }

@Test
void aTestKeyListsOnlyTestSubscriptions() { /* create one under LIVE via the LIVE key/X-Environment; TEST list is empty */ }
```

- [ ] **Step 2: Run** → FAIL. **Step 3:** JPA query mirroring `OrderJpaRepository` (`:status IS NULL OR s.status = :status`, `:customerId IS NULL OR s.customerId = :customerId`, cursor by `id <`, `ORDER BY s.id DESC`, `Pageable`). Subscriptions carry an `environment` column? Check `SubscriptionEntity`; if not, join through the customer's environment (customers do) — say which in the report. **Step 4:** `SubscriptionQueries.list`; controller `list(@RequestParam(required=false) String customerId, status, limit=20, cursor)`; `statusFilter` like orders'; names via `customers.namesOf(merchantId, ids)` once per page; `SubscriptionResponse.from(subscription, customerName, latestOrder, dunning)` — update the existing `response(...)` helper so every route carries the name. **Step 5:** tests PASS; spotless; verify. **Step 6:** README rows. **Step 7: Commit** `feat(subscriptions): merchant-wide list with status filter, cursor and the customer's name`.

---

### Task 3: `GET /v1/orders?customer_id=`

**Files:**
- Modify: `gateway-billing/.../order/persistence/OrderJpaRepository.java`, `OrderRepository.java`, `OrderRepositoryImpl.java` (`list` gains `customerId` nullable), `gateway-billing/.../order/OrderQueries.java` (or wherever `list` lives), `gateway-app/.../api/order/OrdersController.java` (`customer_id` param; refused with `reference`), `README.md`
- Test: `gateway-app/src/test/java/com/gateway/app/OrdersApiIntegrationTest.java` (existing; add cases `filtersByCustomer`, `customerFilterRespectsTheEnvironment`, `anotherMerchantsCustomerIdIsAnEmptyPage`, `customerIdCannotBeCombinedWithReference`).

- [ ] Steps as in Task 2: failing tests → query param `:customerId IS NULL OR o.customerId = :customerId` → controller → PASS → spotless → verify → README → **Commit** `feat(orders): filter the list by customer_id`.

---

### Task 4: Docs

- [ ] `docs/superpowers/DECISOES.md` entry (2026-10-11): name search is literal `LIKE` with escaping, never on the document (rejected: full-text search — cost: a merchant with 100k customers waits; the index covers it); subscriptions list keeps `customer_id` as a filter instead of a second route.
- [ ] `./mvnw verify` green; commit `docs: listings part 2 in the README and DECISOES`.

## Self-review notes
- Spec coverage: §2 search (T1), §4 list + names (T2), §2 history by customer (T3). Review Focus 1–5 pinned to T1/T2/T3.
- Interfaces: `customer_name` key, `status` values = `SubscriptionStatus` enum names, cursor semantics identical across the three.
