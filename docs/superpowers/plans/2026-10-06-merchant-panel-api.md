# Merchant panel API — Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The panel's home screen and its Customers tab load: orders and customers list by cursor, orders
filter by status, and every order carries its customer's name.

**Architecture:** Two new repository queries in `gateway-billing` (orders by merchant + environment with
an optional status, customers likewise), one batch name lookup, and one batch attempts lookup in
`gateway-payments`; `OrdersController` and `CustomersController` grow the list branch next to the
existing `reference` / `document` lookups. No new module edge: the controller is where orders and
customers meet, as it already is for `POST /v1/orders` with an inline customer.

**Tech Stack:** Java 25, Spring Boot, JPA/Flyway, Testcontainers, RestTestClient.

**Spec:** `docs/superpowers/specs/2026-10-06-painel-do-merchant-design.md`

## Global Constraints

- Branch `feat/merchant-panel-api` from `main`. English everywhere except DECISOES (Portuguese).
- Billing migrations: next free `V307`.
- ArchUnit unchanged: JPA only under `..persistence..`, entities package-private, `payments` never
  imports `billing`.
- Nothing here moves money or changes a write path; every change is a read.
- Verify: `./mvnw -B verify` (Docker running). `./mvnw spotless:apply` before every commit.

## Review Focus

1. **Environment isolation.** A LIVE key must not list a TEST order or customer. Test in Task 3.
2. **Cursor pages neither repeat nor skip** across a page boundary. Test in Task 3.
3. **No per-row queries in the list.** Names and attempts are one query each; the name lookup never
   opens the sealed document. Test in Task 1 (repository) and by reading Task 2.

---

## Task 1: Repository queries

**Files:** `gateway-billing/.../db/migration/billing/V307__merchant_panel_lists.sql`,
`order/persistence/{OrderRepository,OrderJpaRepository,OrderRepositoryImpl}.java`,
`customer/persistence/{CustomerRepository,CustomerJpaRepository,CustomerRepositoryImpl}.java`,
`gateway-payments/.../payment/persistence/{PaymentRepository,PaymentJpaRepository,PaymentRepositoryImpl}.java`,
`payment/PaymentQueries.java`; tests in the billing repository integration tests.

- [x] `V307`: `idx_orders_merchant_env_id (merchant_id, environment, id DESC)` and the same on
      `billing.customers`, partial on `deleted_at IS NULL`.
- [x] `OrderRepository.list(merchantId, environment, status /* nullable */, cursorId, limit)`, `id DESC`.
- [x] `CustomerRepository.listActive(merchantId, environment, cursorId, limit)`, `id DESC`.
- [x] `CustomerRepository.activeNames(merchantId, ids)` → `Map<id, name>`; a projection, no `toDomain`.
- [x] `PaymentRepository.listByMerchantAndOrders(merchantId, orderIds)` + `PaymentQueries.listByOrders`.
- [x] Repository tests: names skip deleted and another merchant's; order list pages by cursor and filters.

## Task 2: Services and controllers

**Files:** `gateway-billing/.../order/OrderService.java`, `customer/CustomerService.java`,
`gateway-app/.../api/order/OrdersController.java`, `api/order/dto/OrderResponse.java`,
`api/customer/CustomersController.java`.

- [x] `OrderService.list(...)` and `attemptsOfOrders(merchantId, orders)` → `Map<orderId, List<Payment>>`.
- [x] `CustomerService.list(...)`, `CustomerService.namesOf(merchantId, ids)`.
- [x] `OrderResponse` gains `customerName`; every order response sets it.
- [x] `GET /v1/orders`: `reference` optional; with it, `cursor`/`status` → 400; unknown status → 400.
- [x] `GET /v1/customers`: `document` optional; with it, `cursor` → 400.

## Task 3: API integration test

**Files:** `gateway-app/src/test/java/com/gateway/app/MerchantPanelApiIntegrationTest.java`.

- [x] Two pages by cursor, no repeat or skip; `status=PAID`; `status=XYZ` 400; `reference`+`cursor` 400.
- [x] LIVE key of the same merchant sees no TEST order or customer.
- [x] `customer_name` on list and `GET`; null once the customer is deleted.
- [x] Customers by cursor, deleted one absent, document masked; `document`+`cursor` 400.

## Task 4: Docs

- [x] README route table: the new list forms and `customer_name`.
- [x] DECISOES: the two entries of spec §6. Index in `docs/superpowers/README.md`.
