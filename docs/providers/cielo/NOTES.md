# Cielo E-commerce — notes

Source: https://docs.cielo.com.br/ecommerce-cielo/ (the `.md` of each page; index at `llms.txt`), read
2026-09-28. The old manual at developercielo.github.io was discontinued on 2024-08-14 and is not a
source. The Cielo publishes no OpenAPI file: the examples embedded in each reference page are the
fixtures (`gateway-providers/src/test/resources/cielo/fixtures/README.md` lists each file's page).

## Hosts

| | transactional (POST/PUT) | query (GET) |
|---|---|---|
| production | https://api.cieloecommerce.cielo.com.br | https://apiquery.cieloecommerce.cielo.com.br |
| sandbox | https://apisandbox.cieloecommerce.cielo.com.br | https://apiquerysandbox.cieloecommerce.cielo.com.br |

## Authentication

Headers `MerchantId` (GUID) and `MerchantKey` on every call, `RequestId` (36 chars) optional — the
gateway sends the correlation id. No OAuth, no mTLS. The docs call the key a 40-character GUID; their
example is 40 upper-case letters, so the gateway checks 40 letters or digits. Sandbox credentials are
created without a contract at the signup form embedded in the docs (docs.cielo.com.br/ecommerce-cielo/page/sandbox-api-ecommerce); the old host cadastrosandbox.cieloecommerce.cielo.com.br no longer resolves (checked 2026-09-29). The form shows the Merchant ID and Merchant Key once; copy them into .env.

## Operations used

| operation | call | answer |
|---|---|---|
| authorize | `POST /1/sales` | 200 or 201 for every business answer, a decline included (Status 3) |
| capture | `PUT /1/sales/{PaymentId}/capture[?amount=]` | 200 `{Status, Tid, ProofOfSale, AuthorizationCode, ReturnCode, ReturnMessage}` — no captured amount, so the gateway re-queries the sale after it; if the re-query fails or comes back empty, the PUT's own answer is adopted instead (WARN) rather than losing a capture the Cielo already took |
| void / refund | `PUT /1/sales/{PaymentId}/void[?amount=]` | 200 same shape; Status 10 (same day) or 11 (after 23h59 of the authorization day) — the gateway accepts either as a completed void/cancel |
| query | `GET /1/sales/{PaymentId}` | the sale |
| query by order | `GET /1/sales?merchantOrderId=` | `{ReasonCode, ReasonMessage, Payments[{PaymentId, ReceveidDate}]}` — the field is spelled `ReceveidDate`; only the last three months |
| tokenize | `POST /1/card/` (trailing slash) | `{CardToken}` — not used by payments in this phase |

## Status (reference/payment-status)

0 NotFinished, 1 Authorized, 2 PaymentConfirmed, 3 Denied, 10 Voided, 11 Refunded, 12 Pending, 13
Aborted, 20 Scheduled. The spec's 14 Processing and 15 Refunded are tolerated (14 → in doubt, 15 →
refunded). Unknown is in doubt: asked again, never adopted.

## Rules the gateway enforces because the Cielo does

- `MerchantOrderId` only `[A-Za-z0-9]`, up to 50; above 20 characters (our ULID has 26) the Cielo
  generates a `SentOrderId` — the gateway ignores it and queries by its own id.
- `Holder` without accents, 25 characters; `Customer.Name` letters only; `SoftDescriptor` 13 letters or
  digits.
- `Interest: "ByMerchant"` requires installments of at least R$ 5,00; 12 installments is the default
  ceiling.
- One capture per sale (total or one partial); ~50 attempts before code 841; captures under 20 cents are
  not settled.
- Card On File only for Visa, Master and Elo; `InitiatedTransactionIndicator` only for Master
  (`C1`/`CredentialsOnFile` for a customer-present charge with a stored card).
- `SecurityCode` is required with `CardToken` (schema of reference/cartao-tokenizado-api) — the gateway
  requires `cvv` with `card_id` for the same reason, even though the original spec allowed it optional.
- Declines are ABECS codes (page/abecs), not the API codes of reference/api-codes; the sandbox's codes
  differ from production's (its 57 means "expired", production's 57 "not allowed for the card").

## Notifications (docs/webhook)

One HTTPS URL per store, port 443, static, up to 255 characters, configured in the Cielo site. Body
`{PaymentId, ChangeType, RecurrentPaymentId?}`. Sent every 30 minutes with three retries until a
**200**. No signature: up to three fixed headers are the authentication, so the gateway requires its
`X-Gateway-Notification-Key` on top of the URL token. ChangeType 1 status, 2 recurrence created, 3
antifraud, 4 recurrence status, 5 cancel denied, 6 boleto underpaid, 7 chargeback (legacy), 8 fraud
alert, 25 partial cancel/refund. The sandbox sends notifications once the URL is registered by e-mail to
the Cielo support (reference/como-usar-o-sandbox).

ChangeType 25 (partial cancel/refund) opens a `PARTIAL_REFUND_AT_PROVIDER` divergence: the Cielo's sale
stays `PaymentConfirmed`/PAID after a partial refund and `CardAuthorization` carries no voided amount, so
the notification is the only evidence a human sees. This also fires for the gateway's own partial
refunds (it has no way yet to tell "we already know" from "the Cielo did this on its own") — a known
follow-up, not fixed in this phase. An ignorable `ChangeType` on a payment still `CREATED` is IGNORED
without touching the payment.

## Sandbox cards

The last digit decides (reference/credito-sandbox): 0/1/4 authorized, 2 declined (05), 3 expired (57),
5 blocked (78), 6 timeout (99), 7 canceled (77), 8 card problem (70), 9 random (6 or 9). CVV and expiry
are free (3-digit CVV, MM/YYYY). The page's own example, `4024.0071.5376.3191`, **fails the Luhn check**,
which the gateway applies before the Cielo; use Luhn-valid numbers with the right last digit:

| last digit | number |
|---|---|
| 1 | 4024007153763171 |
| 2 | 4024007153760052 |
| 6 | 4024007153760086 |
| 9 | 4024007153760029 |

## Smoke in the sandbox (run by hand, with the credentials the owner creates)

Placeholders only; nothing here is a real credential. `$GW` is the running gateway, `$ADMIN` the admin
key, `$MERCHANT` a merchant id, `$KEY` its TEST API key.

1. Register `{"merchant_id":"<CIELO_SANDBOX_MERCHANT_ID>","merchant_key":"<CIELO_SANDBOX_MERCHANT_KEY>"}`
   as the merchant's CIELO TEST credential (README, "Card (Cielo)").
2. Card ending 1, `capture: true` → 201 `COMPLETED`. Record `ReturnCode` from `provider_requests`/log.
3. Card ending 2 → 402 `CARD_DECLINED`; record the `decline_code` (expected `GENERIC`, sandbox 05).
4. Card ending 6 → the Cielo answers ReturnCode 99. Record how it arrives (a decline, or a Status 0
   in doubt) and what the gateway books; see "Smoke results".
5. Card ending 9, five times → a mix of approved and 402.
6. Card ending 1, `capture: false` → `AUTHORIZED`; `POST …/capture {"amount": 5000}` → `COMPLETED`,
   `paid_amount` 5000; a second capture → 409 `ALREADY_CAPTURED`. **Record** whether the Cielo's second
   call answered 308.
7. Card ending 1, `capture: false`, then `POST …/cancel` → `CANCELED`; record the void's Status (10).
8. Card ending 1, captured; `POST …/refunds {"amount": 1000}` then `{"amount": 2000}` → both
   `COMPLETED`. **Record** whether a same-day refund answered 10 or 11.
9. Card ending 1 with `save_card: true` → `card_id` in the response; then a charge with `card_id` and
   `cvv` → `COMPLETED`. **Record** whether the Cielo accepts the same charge without `SecurityCode`
   (send one by hand with curl against the sandbox; the gateway always sends it).
10. `GET /1/sales?merchantOrderId=<a payment id of 26 chars>` against the sandbox query host with the
    sandbox headers → **record** that it finds the sale (the recovery depends on it) and what
    `SentOrderId` the sale shows.
11. `GET /1/sales/<a random GUID>` → **record** whether the Cielo answers 404 or 400 with code 307.
12. Ask the Cielo support to register `https://<public-host>/v1/providers/cielo/webhooks/<token>` with the
    header `X-Gateway-Notification-Key: <CIELO_NOTIFICATION_KEY>`; capture a sale and **record** whether
    a ChangeType 1 notification arrives and how long it took.

Write each recorded answer under "Smoke results" below, with the date. Until then these are the open
questions of spec §1.

## Smoke results

Run on 2026-09-30 against the sandbox, gateway at commit `8036bc7` (branch `feat/plano-d-cartao-cielo`),
fresh database, merchant "Cielo Smoke" with a TEST key. The sandbox answers as `Provider: "Simulado"`.

| step | what the sandbox answered | gateway outcome |
|---|---|---|
| 2 | `Status 2`, `ReturnCode "6"`, `ReturnMessage "Operation Successful"`, `CapturedAmount` = amount | 201 `COMPLETED`, `paid_amount` 10000 |
| 3 | declined (ReturnCode 05) | 402 `CARD_DECLINED`, `decline_code: GENERIC`, `payment_id` in the body |
| 4 | **201 with `Status 0` (NotFinished), `ReturnCode "99"`** — a synchronous in-doubt answer, not a slow one; the recovery's `GET /1/sales?merchantOrderId=` answered 200 and the sale was still in doubt | 422 `PROVIDER_TIMEOUT`, payment `FAILED` (spec §6.4), `payment.failed` event. The 402 `TIMEOUT` decline this procedure expected does not happen: 99 arrives as Status 0, and Status 0 is doubt, not a decline |
| 5 | card ending 9, three sales: approved, approved, `Status 0`/99 | 201, 201, 422 `PROVIDER_TIMEOUT` |
| 6 | authorization `Status 1`; `PUT …/capture?amount=5000` → sale shows `Status 2`, `CapturedAmount 5000` | `COMPLETED`, `paid_amount` 5000, `captured_amount` 5000. The second capture was refused by the gateway itself (409 `ALREADY_CAPTURED`) before any call to the Cielo, so **whether the Cielo answers 308 on a second capture is still unrecorded** |
| 7 | `PUT …/void` → sale shows `Status 10`, `VoidedAmount 10000`, `VoidedDate` set | `CANCELED` |
| 8 | two partial voids (1000, 2000) → sale keeps `Status 2` with `VoidedAmount 3000`; both voids were booked `COMPLETED`, so each answered `ReturnCode` 0 or 9 (the ledger keeps no bodies for card calls) | both refunds 201 `COMPLETED` |
| 9 | **the sandbox does not tokenize**: a sale with `SaveCard: true` comes back `Status 2` without `Payment.CreditCard.CardToken` (only `PaymentAccountReference`), and `POST /1/card/` answers 400 `CP900: Merchant was not found` — the tokenization API is enabled per merchant by the Cielo and this sandbox merchant does not have it | 201 `COMPLETED` with `card_id: null`, no warning logged (no token → nothing to save, by design). Charging by `card_id` and the `SecurityCode`-optional question could not be exercised; they need a merchant with tokenization enabled |
| 10 | `GET /1/sales?merchantOrderId=<26-char payment id>` → 200 `{ReasonCode 0, Payments:[{PaymentId, ReceveidDate}]}` (the field is misspelt by the Cielo); `GET /1/sales/{PaymentId}` then shows `MerchantOrderId` equal to our id and no `SentOrderId` | the recovery path works: step 4 used it live |
| 11 | `GET /1/sales/<random GUID>` → **404 with an empty body**, not 400/307 | — |
| 12 | not run: needs a public host registered by the Cielo support | — |

Consequences:

- Step 4 of the procedure above is wrong about the 402: the sandbox's timeout card produces doubt
  (Status 0), which the gateway resolves through the order query and books `FAILED` / 422 when the
  sale stays in doubt. That is spec §6.4 behaviour, kept.
- `card_id` comes back null silently when the acquirer returns no token. Follow-up in DECISOES: whether
  the API should say so (a `card_saved: false` reason) instead of leaving the merchant to notice.
- Sandbox facts to carry into production checks: the second capture's 308, the refund's 10 vs 11, the
  notification (step 12) and everything about tokens remain open until a merchant with tokenization.
