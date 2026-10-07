# Subscription by link — Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A merchant without the payer's card creates a card subscription anyway: it waits in
`INCOMPLETE` with its first invoice open and a checkout link; the payer pays it with a card that is saved,
and the subscription turns `ACTIVE` and bills that card from then on. Every invoice is born with a link the
merchant gets once, in its event; the panel lists subscriptions by cursor with names.

**Architecture:** All state lives in `gateway-billing`. `SubscriptionStatus` gains `INCOMPLETE` and
`INCOMPLETE_EXPIRED`; `SubscriptionCreation` (new, `subscription/billing/`) is the one door for
`POST /v1/subscriptions`: an `ACTIVE` subscription goes through `SubscriptionService.create` as today, an
`INCOMPLETE` one is inserted and its first invoice opened in the same transaction by `CycleOpener.openFirst`
(the cycle's own code, not a copy). `InvoiceLinks` (new) issues and reissues invoice tokens and builds the
url through a port, `CheckoutLinks`, which the app's `CheckoutProperties` implements. The settlement port
`InvoiceSettlementHook` gains `invoiceClosed` and moves from `Dunning` to `SubscriptionInvoices` (new), which
handles an `INCOMPLETE` subscription's invoice (activate, expire, ignore a failed attempt) and hands every
other invoice to `Dunning` unchanged. The checkout learns what the invoice's subscription demands through a
second port, `InvoiceCheckoutTerms`, implemented in the subscription package, so `order/` never imports
`subscription/`. The app composes: `SubscriptionsController` maps the new fields and the list,
`CheckoutController` narrows `methods`.

**Tech Stack:** Java 25, Spring Boot, JPA/Flyway, Testcontainers, RestTestClient, WireMock.

**Spec:** `docs/superpowers/specs/2026-10-07-assinatura-por-link-design.md`

## Global Constraints

- Branch `feat/subscription-by-link` (on `feat/installments-interest`). English everywhere except DECISOES
  (Portuguese).
- Billing migrations: next free `V309`. `V304` has **no** `CHECK` on `status` or `card_id` and
  `status` is `VARCHAR(10)`, too short for `INCOMPLETE_EXPIRED`: `V309` widens the column and adds both
  constraints the spec describes, plus the list index.
- ArchUnit unchanged: `order/` reaches subscriptions only through ports; `billing` never imports `app`;
  entities stay package-private under `..persistence..`.
- The environment comes from the API key, never from the body; the list filters by it.
- The plain checkout token never reaches a table of `billing`: only its hash. The url travels in the
  creation response and in the `invoice.created` / `invoice.updated` payload (and so in the outbox and
  delivery rows, like every payload).
- Untouched: PIX / BOLECODE subscriptions and CARD with `card_id` (born `ACTIVE`, first cycle by job),
  dunning rules, installments (an invoice attempt by link keeps the checkout's options).
- Verify: `./mvnw -B spotless:check verify` (Docker running). `./mvnw -B spotless:apply` before committing.

## Review Focus

1. **No charge without consent.** A failed or expired first invoice never starts dunning; the job never
   bills an `INCOMPLETE` subscription (there is no `BILL_SUBSCRIPTION` row until activation). Test in Task 6.
2. **The saved card is the one paid with.** Activation reads `card_id` from the paid payment, and the
   checkout of an `INCOMPLETE` invoice only takes a new card, saved whatever `save_card` said. Tests in
   Tasks 4 and 6.
3. **Token shown once.** `billing.orders` keeps the hash; `GET` returns `checkout_url: null`; a resumed
   cycle that announces the invoice for the first time reissues the token instead of announcing none.
   Test in Task 6.
4. **Cancel of an `INCOMPLETE` subscription ends `CANCELED`, not `INCOMPLETE_EXPIRED`.** The
   subscription is written first, then its invoice canceled; the closed-invoice hook ignores anything not
   `INCOMPLETE`. Test in Task 6.
5. **No per-row lookup of names in the list.** Customer names and plans: one query each per page.

---

## Task 1: Status, transitions, migration

**Files:** `gateway-billing/.../db/migration/billing/V309__subscription_by_link.sql`,
`subscription/{SubscriptionStatus,SubscriptionTransitions,Subscription,SubscriptionFactory}.java`,
`subscription/persistence/{SubscriptionEntity,SubscriptionRepository,SubscriptionJpaRepository,SubscriptionRepositoryImpl}.java`;
tests `SubscriptionTransitionsTest`, `SubscriptionFactoryTest`.

- [x] `SubscriptionStatus` gains `INCOMPLETE`, `INCOMPLETE_EXPIRED`; transitions `INCOMPLETE → ACTIVE |
      INCOMPLETE_EXPIRED | CANCELED`; `INCOMPLETE_EXPIRED` is final.
- [x] `V309`: `status` to `VARCHAR(20)`, `ck_subscriptions_status` (six values),
      `ck_subscriptions_card_id` (`method <> 'CARD' OR card_id IS NOT NULL OR status IN ('INCOMPLETE',
      'INCOMPLETE_EXPIRED', 'CANCELED')`), `idx_subscriptions_merchant_env_id (merchant_id, environment,
      id DESC)`.
- [x] `SubscriptionFactory`: CARD without `card_id` is born `INCOMPLETE` (no more `CARD_REQUIRED` on
      create; `PATCH` keeps it). `Subscription.openPeriod` accepts an `INCOMPLETE` subscription's first
      period; `activate(cardId)`, `expireIncomplete()`; `cancelNow` from `INCOMPLETE`.
- [x] Repository: `list(merchantId, environment, status, cursorId, limit)` by `id DESC`;
      `existsActiveForCustomer` counts `INCOMPLETE` too (its invoice may still be paid).
- [x] Tests: transition table; factory births `INCOMPLETE`.

## Task 2: Links on every invoice

**Files:** `order/checkout/CheckoutLinks.java` (port), `subscription/billing/{InvoiceLinks,CycleOpener,OpenedCycle,SubscriptionBilling,InvoicePayloads,Dunning}.java`,
`BillingConfiguration.java`; app `api/checkout/CheckoutProperties.java`; test config `BillingTestConfig`.

- [x] `CheckoutLinks.urlFor(CheckoutToken)`; `CheckoutProperties` implements it.
- [x] `InvoiceLinks.issue()` (token + hash for a new invoice), `urlFor(token)`, `reissue(invoice, at)`
      (requires a transaction: re-reads the order, rotates the hash if open, returns the url; null if
      closed).
- [x] `CycleOpener` keeps the issued token in `OpenedCycle` (null on a resumed cycle); transaction 2 of
      `SubscriptionBilling` puts `checkout_url` in `invoice.created`, reissuing when the cycle was
      resumed. `Dunning` (8th dependency) reissues for `invoice.updated`.
- [x] `InvoicePayloads.created/updated` take the url.

## Task 3: Creation of an `INCOMPLETE` subscription

**Files:** `subscription/billing/{SubscriptionCreation,CycleOpener}.java`,
`subscription/SubscriptionService.java`, `BillingConfiguration.java`; test
`subscription/billing/SubscriptionByLinkIntegrationTest` (creation, checkout, activation and the
second cycle, decline without dunning, expiry, cancel, PIX unchanged).

- [x] `CycleOpener.openFirst(subscription, now)` (caller's transaction, row just inserted): the same
      `openNext` as the job, without enqueuing `BILL_SUBSCRIPTION` for a subscription that is not
      billable.
- [x] `SubscriptionCreation.create(subscription, customer)` → `Created(subscription, firstInvoice)`:
      `ACTIVE` delegates to `SubscriptionService.create`; `INCOMPLETE` inserts, emits
      `subscription.created`, opens the first invoice (`order.created`, `EXPIRE_ORDER`) and emits
      `invoice.created` with the url, in one transaction. `FirstInvoice(orderId, checkoutUrl)`.
- [x] `SubscriptionService.cancel`: `INCOMPLETE` is always immediate; the subscription is written
      `CANCELED` first, then the open invoice canceled at the bank.

## Task 4: Settlement and checkout of an `INCOMPLETE` invoice

**Files:** `order/{InvoiceSettlementHook,OrderService,OrderExpiration}.java`,
`order/checkout/{InvoiceCheckoutTerms,InvoiceTerms,CheckoutService,CheckoutView}.java`,
`subscription/SubscriptionCheckoutTerms.java`, `subscription/billing/SubscriptionInvoices.java`,
`plan/{PlanService}.java` + persistence, `BillingConfiguration.java`.

- [x] `InvoiceSettlementHook.invoiceClosed(order, at)`: called by `OrderExpiration` and
      `OrderService.cancel` (8th dependency) in the closing transaction.
- [x] `SubscriptionInvoices` is the hook: `INCOMPLETE` + paid → `activate(card_id of the paid payment)`,
      enqueue `BILL_SUBSCRIPTION` at `next_billing_at`, `subscription.activated`; no `card_id` on the
      payment → stays `INCOMPLETE`, WARN; `INCOMPLETE` + closed → `INCOMPLETE_EXPIRED`; `INCOMPLETE` +
      failed attempt → nothing. Anything else → `Dunning`, which no longer implements the port.
- [x] `InvoiceCheckoutTerms.of(order)` → `InvoiceTerms(planName, savesCard)` (empty for a standalone
      order); `CheckoutView` carries it; `CheckoutService.attempt` refuses anything but a new card with
      `422 CHECKOUT_CARD_REQUIRED` and forces `save = true`.
- [x] `PlanService.byIds(merchantId, ids)` → map, one query.

## Task 5: The API

**Files:** `gateway-app/.../api/subscription/SubscriptionsController.java`,
`api/subscription/dto/SubscriptionResponse.java`, `api/checkout/{CheckoutController,dto/CheckoutResponse}.java`.

- [x] `POST /v1/subscriptions` through `SubscriptionCreation`; response `first_invoice: {order_id,
      checkout_url}` or null.
- [x] `SubscriptionResponse` gains `customer_name`, `plan_name`, `amount`, `interval`, `interval_count`
      and `first_invoice`; names and plans read once per page.
- [x] `GET /v1/subscriptions?limit=&cursor=&status=` in the key's environment; unknown `status` → 400;
      `customer_id` with `cursor`/`status` → 400; `limit` outside 1–100 → 400.
- [x] `CheckoutResponse` gains `plan_name` and `saves_card_for_subscription`; `methods` only `CARD` for
      an `INCOMPLETE` invoice.

## Task 6: API integration test

**Files:** `gateway-app/src/test/java/com/gateway/app/SubscriptionByLinkIntegrationTest.java`; fix
`CheckoutApiIntegrationTest.springsOwnWebErrorsKeepTheirStatus` (it used the now-optional `customer_id`
as its missing parameter).

- [x] CARD without `card_id` → 201 `INCOMPLETE`, `first_invoice.checkout_url`; `invoice.created` outbox
      row has the url; `billing.orders` only the hash; `GET` order has `checkout_url: null`.
- [x] Checkout of that invoice: `methods = [CARD]`, `saves_card_for_subscription`, `plan_name`; a Pix
      attempt is 422; a card with `save_card: false` is saved anyway.
- [x] Paid → `ACTIVE` with the card, `subscription.activated`; forced second cycle charges the token
      without CVV (WireMock sees `CardToken`, no `SecurityCode`).
- [x] Expired first invoice → `INCOMPLETE_EXPIRED`, no `DUNNING_RETRY` job (the declined first attempt
      is covered in the billing test of Task 3).
- [x] Cancel of an `INCOMPLETE` subscription → `CANCELED`, invoice `CANCELED`.
- [x] PIX subscription born `ACTIVE`, `first_invoice` null, its `invoice.created` has the url.
- [x] List: cursor pages, `status` filter, unknown `status` 400, `customer_id` + `cursor` 400, LIVE key
      does not see TEST, `customer_name` / `plan_name` / `amount` / `interval` / `interval_count`.

## Task 7: Docs

- [x] README: subscriptions table and text (statuses, card without `card_id`, `first_invoice`, list
      params, new fields), checkout fields and `CHECKOUT_CARD_REQUIRED`, event catalog
      (`subscription.activated`, `checkout_url` on `invoice.created` / `invoice.updated`);
      `EventCatalogTest` row.
- [x] DECISOES: the three entries of spec §7 (and the implementation calls that differ from the spec).
      Index this plan in `docs/superpowers/README.md`.
