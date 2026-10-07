# Installments with merchant interest — Implementation Plan

> **For agentic workers:** Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The merchant sets, per environment, how many installments are offered, up to how many are
interest-free and the monthly rate above that; the checkout shows the exact value of every option, and
an order's card attempt charges the total of the option the payer chose.

**Architecture:** One new concept in `gateway-billing`, `installment/`: the settings (record +
`persistence/` over `billing.installment_settings`), `InstallmentPricing` (pure, no Spring) and
`InstallmentSettingsService` (read with default, write + outbox event). `OrderAttemptService` prices a
`CardAttempt` before building the command and passes the total as the amount and the interest as a new
`CreateCardPayment.interestAmount`; `CardPaymentFlow` writes it into `CardDetails`. `CheckoutService`
puts the options in `CheckoutView`; the app shows them only when `CARD` is offered. `gateway-payments`
learns a number (`interestAmount`), never billing.

**Tech Stack:** Java 25, Spring Boot, JPA/Flyway, Testcontainers, RestTestClient, WireMock.

**Spec:** `docs/superpowers/specs/2026-10-07-parcelas-com-juros-design.md`

## Global Constraints

- Branch `feat/installments-interest`. English everywhere except DECISOES (Portuguese).
- Billing migrations: next free `V308`.
- ArchUnit unchanged: the entity is package-private under `..persistence..`; `InstallmentPricing` and
  the records see no Spring; `payments` never imports `billing`.
- The environment comes from the API key (`Environments.toProvider`), never from the body.
- Untouched: `POST /v1/payments` (the merchant chooses the amount there) and `RecurringCardAttempt`
  (a subscription cycle stays 1x at the order amount).
- Verify: `./mvnw -B spotless:check verify` (Docker running). `./mvnw spotless:apply` before committing.

## Review Focus

1. **Rounding is money.** Price-table installment rounded up to the cent, total = installment × n; the
   interest-free installment is `amount / n` truncated (the acquirer spreads the remainder), total stays
   `amount`. Hand-computed table in Task 2.
2. **The total is recomputed, never trusted.** The attempt body has no amount; a count the settings do
   not offer is `422 INVALID_INSTALLMENTS`. Test in Task 5.
3. **Environment isolation.** A LIVE key neither reads nor changes the TEST settings. Test in Task 5.
4. **Old payments read back.** A `details.card` without `interestAmount` reads as 0. Test in Task 1.

---

## Task 1: The payment records the interest

**Files:** `gateway-payments/.../payment/card/{CardDetails,CardDetailsJson}.java`,
`payment/create/{CreateCardPayment,CardPaymentFlow}.java`, `payment/PaymentEvents.java`; tests
`CardDetailsJsonTest`, `PaymentDetailsJsonTest` and the callers of the changed constructors.

- [x] `CardDetails` gains `long interestAmount` (last component); `requested(...)` takes it; every
      `with*` copy carries it.
- [x] `CardDetailsJson` writes `"interestAmount"`; a document without the key reads 0.
- [x] `CreateCardPayment` gains `Long interestAmount` after `installments` (null = 0); the direct
      `POST /v1/payments` and the recurring attempt pass null.
- [x] `CardPaymentFlow` passes it to `CardDetails.requested`.
- [x] `PaymentEvents.paymentJson` card block gains `interest_amount`.
- [x] Tests: round trip with interest, old JSON without the key → 0, the eleven keys, disjoint keys.

## Task 2: Settings and pricing in billing

**Files:** `gateway-billing/.../db/migration/billing/V308__installment_settings.sql`,
`installment/{InstallmentSettings,InstallmentOption,InstallmentPricing,InstallmentSettingsService}.java`,
`installment/persistence/{InstallmentSettingsEntity,InstallmentSettingsKey,InstallmentSettingsJpaRepository,InstallmentSettingsRepository,InstallmentSettingsRepositoryImpl}.java`,
`BillingConfiguration.java`; tests `installment/InstallmentPricingTest.java`,
`installment/InstallmentSettingsTest.java`, `installment/InstallmentSettingsServiceIntegrationTest.java`.

- [x] `V308`: table with the three `CHECK`s and PK `(merchant_id, environment)`.
- [x] `InstallmentSettings.of(...)` validates (`IllegalArgumentException` → 400); `defaults()` is
      12 / 12 / 0.
- [x] `InstallmentPricing.options(amount, settings)` and `option(amount, settings, count)`:
      `BigDecimal`, `MathContext.DECIMAL64`, Price table, ceiling to the cent, R$ 5,00 minimum (1x is
      always offered, as `InstallmentPlan` allows today).
- [x] Repository: `find` and an upsert (`INSERT … ON CONFLICT … DO UPDATE`).
- [x] `InstallmentSettingsService.get` (default when no row) and `update` (upsert + outbox
      `installment_settings.updated` in one transaction); `json(settings, apiKeyId)` for the catalog.
- [x] Unit test: 100,00 at 2,99% in 3x, 6x, 12x against the hand-computed table; rate 0;
      `interest_free_up_to = max`; the minimum; 1x under the minimum; a count outside the settings.
- [x] Integration test: default, upsert twice, environments apart, an invalid update writes nothing,
      the outbox row.
- [x] `OrderAttemptServiceIntegrationTest`: 3x at 2,99% charges 5304 on a 5000 order; 7x of 6 is 422.

## Task 3: The attempt charges the total

**Files:** `gateway-billing/.../order/OrderAttemptService.java`,
`order/checkout/{CheckoutService,CheckoutView}.java`, `BillingConfiguration.java`.

- [x] `OrderAttemptService` (6th dependency: the settings service) prices a `CardAttempt`: amount =
      option total, `interestAmount` = total − order amount; a count not offered →
      `DomainException("INVALID_INSTALLMENTS")`. `RecurringCardAttempt` unchanged.
- [x] `CheckoutView` gains `installmentOptions`; `CheckoutService.get` fills it from the order's
      merchant and environment.

## Task 4: The API

**Files:** `gateway-app/.../api/installment/InstallmentSettingsController.java`,
`api/installment/dto/{InstallmentSettingsRequest,InstallmentSettingsResponse}.java`,
`api/checkout/dto/{CheckoutResponse,CheckoutPaymentResponse}.java`,
`api/payment/dto/PaymentResponse.java`.

- [x] `GET /v1/installment-settings` and `PUT /v1/installment-settings` for the key's environment; PUT
      not in `IdempotencyFilter`; a missing field is 400.
- [x] `CheckoutResponse.installment_options` (`count`, `installment_amount`, `total`,
      `interest_free`) only when `methods` contains `CARD`, otherwise an empty list.
- [x] `interest_amount` in `PaymentResponse.card` and `CheckoutPaymentResponse.card`.
- [x] CORS allows `PUT`, so the merchant panel can write the settings from the browser.

## Task 5: API integration test

**Files:** `gateway-app/src/test/java/com/gateway/app/InstallmentsIntegrationTest.java`.

- [x] No settings: 100,00 offers 1x–12x interest-free; 20,00 offers 1x–4x (the minimum).
- [x] `max 10, free up to 3, 2,99%`: 10 options, 4x+ with interest, values from the table.
- [x] 6x through the checkout: WireMock receives `Payment.Amount = 11076` and `Installments = 6`; the
      payment has `amount` 11076 and `card.interest_amount` 1076; the `payment.completed` outbox row
      carries it.
- [x] 11x → 422 `INVALID_INSTALLMENTS` (merchant route and checkout), no Cielo call.
- [x] LIVE key reads the default and its PUT leaves TEST alone; invalid PUT → 400 (an `environment`
      in the body is an unknown field).
- [x] A merchant without the card gets empty `installment_options`.

## Task 6: Docs

- [x] README: settings routes, `installment_options`, `interest_amount`; event catalog block for
      `installment_settings.updated` and the card blocks; `EventCatalogTest` row.
- [x] DECISOES: the three entries of spec §6. Index in `docs/superpowers/README.md`.
