# Payment Gateway

Payment orchestrator (model A: the merchant's own credentials; money never passes through here).
Spec: `docs/superpowers/specs/2026-09-23-payment-gateway-design.md`. Decisions: `docs/superpowers/DECISOES.md`.

## Run

```bash
docker compose up -d
export GATEWAY_ADMIN_KEY=dev-admin GATEWAY_API_KEY_PEPPER=dev-pepper
export GATEWAY_MASTER_KEY=$(openssl rand -base64 32)
./mvnw -pl gateway-app spring-boot:run
```

Without `GATEWAY_MASTER_KEY` the app does not start (the master key encrypts merchant credentials).
Without `GATEWAY_ADMIN_KEY` the admin API answers 403 — closed by default.

If port 5432 is already taken on your machine, map `5433:5432` in `docker-compose.yml` and set
`DB_URL=jdbc:postgresql://localhost:5433/gateway` before starting the app.

## First merchant

```bash
curl -s -XPOST localhost:8080/v1/admin/merchants -H 'X-Admin-Key: dev-admin' -H 'Content-Type: application/json' -d '{"name":"Store"}'
curl -s -XPOST localhost:8080/v1/admin/merchants/<id>/api-keys -H 'X-Admin-Key: dev-admin' -H 'Content-Type: application/json' -d '{"environment":"TEST"}'
curl -s localhost:8080/v1/merchant -H 'Authorization: Bearer gk_test_…'
```

## Modules

`gateway-kernel` (dependency-free types) · `gateway-merchants` (merchant, API keys, encrypted credentials) ·
`gateway-app` (REST, auth, rate limit, outbound webhooks via `webhook-delivery`, observability).
`orders`, `payments` and `providers` arrive with plans B and C. The boundary is enforced by `ArchitectureTest`.

## Payments (Pix / Itaú)

Plan B adds Pix charges through Itaú as the only provider (`ProviderGateway` resolves `ITAU`
unconditionally; see `docs/superpowers/DECISOES.md`). Two environments per merchant, `TEST` and `LIVE`,
each with its own credential and its own idempotency-key scope.

### TEST vs LIVE credentials

`TEST` targets Itaú's real sandbox (there is no fake provider — see DECISOES) and needs no certificate:

```json
{ "client_id": "...", "client_secret": "...", "pix_key": "..." }
```

`LIVE` targets production and needs the full six-field shape, including the mTLS client certificate:

```json
{
  "client_id": "...", "client_secret": "...", "x_itau_apikey": "<uuid>",
  "certificate": "-----BEGIN CERTIFICATE-----...", "private_key": "-----BEGIN PRIVATE KEY-----...",
  "pix_key": "..."
}
```

Sandbox credentials come from the Itaú for Developers portal ("criar credenciais") — no certificate
involved. Production credentials come from the dynamic-certificate flow: generate an RSA key pair, send
the public key to the Itaú operations analyst, receive an encrypted client id and a temporary token by
e-mail, build a CSR (`CN=<client_id>`) and `POST` it with the temporary token to
`https://sts.itau.com.br/seguranca/v1/certificado/solicitacao`; the response carries the signed
certificate and the client secret, shown once. Renewal (the certificate is valid 365 days, renewable
from 30 days before expiry) uses `.../certificado/renovacao`. Full details, including the sandbox's
different (non-mTLS) auth flow, are in `docs/providers/itau/NOTES.md`.

### Registering a credential

```bash
curl -s -XPUT localhost:8080/v1/admin/merchants/<id>/providers/ITAU/credentials \
  -H 'X-Admin-Key: dev-admin' -H 'Content-Type: application/json' \
  -d '{"environment":"TEST","payload":{"client_id":"<client_id>","client_secret":"<client_secret>","pix_key":"<pix_key>"}}'
```

Never put real credential values in a command you keep, log, or paste anywhere other than the running
request — the `<...>` placeholders above stay placeholders.

### Bolecode (boleto with Pix)

`POST /v1/payments` with `"method": "BOLECODE"` issues a registered boleto **and** a Pix QR in one call
(Itaú `POST /boletos-pix`). The payer chooses: the QR settles at once and arrives by the Pix webhook; the
barcode clears in D+1 and is found by a poll of Itaú's boleto query every 6 hours (there is no boleto
webhook in this version — see DECISOES). The response carries both:

```json
{
  "id": "…", "status": "PENDING", "method": "BOLECODE",
  "boleto": {"linha_digitavel": "…47 digits", "codigo_barras": "…44 digits", "due_date": "2026-10-01",
             "payment_limit_date": "2026-10-31", "paid_via": null},
  "pix": {"txid": "BL…", "copia_e_cola": "…", "location": null, "end_to_end_id": null},
  "expires_at": "2026-11-01T02:59:59Z"
}
```

Request: `customer` is required and complete — `name`, `document` (CPF 11 digits or CNPJ 14 digits) and
`address{street, district, city, state (UF), zip (8 digits)}`; a missing field is `422 CUSTOMER_REQUIRED`
naming it. `due_date` defaults to today + 3 days (São Paulo) and must not be in the past; `payment_limit_days`
defaults to 30 (max 3650). `expires_in` does not exist here: a Bolecode expires at the end of its payment
limit date, never at the due date (a late boleto still pays, with the bank's interest rules out of scope).

### The create request is shaped by its method

`method` chooses the body, and the two shapes do not overlap:

```bash
# PIX: expires_in (seconds, default from config); customer optional, only its document is used
curl -s -XPOST localhost:8080/v1/payments -H 'Authorization: Bearer gk_test_…' \
  -H 'Idempotency-Key: order-42' -H 'Content-Type: application/json' \
  -d '{"method":"PIX","amount":15990,"currency":"BRL","reference":"order-42","expires_in":3600}'

# BOLECODE: due_date, payment_limit_days and a complete customer
curl -s -XPOST localhost:8080/v1/payments -H 'Authorization: Bearer gk_test_…' \
  -H 'Idempotency-Key: order-43' -H 'Content-Type: application/json' \
  -d '{"method":"BOLECODE","amount":12990,"currency":"BRL","reference":"order-43",
       "due_date":"2026-10-01","payment_limit_days":30,
       "customer":{"name":"Ana Silva","document":"529.982.247-25",
                   "address":{"street":"Av. Paulista 1000","district":"Bela Vista","city":"São Paulo",
                              "state":"SP","zip":"01310-100"}}}'
```

A field that belongs to the other method — `due_date` on a PIX body, `expires_in` on a BOLECODE one — is
`400 INVALID_REQUEST`, and so is any field this API does not know: a property we silently ignored would be a
misspelled `expires_in` quietly taking the default expiry. An unknown or missing `method` is
`400 INVALID_REQUEST` with `method must be PIX or BOLECODE`.

`payment.completed` says how it was paid: `boleto.paid_via` is `PIX` or `BOLETO`. `POST …/cancel` does the
bank's baixa; if the bank already shows the boleto paid, the payment completes and the cancel answers
`409 ALREADY_PAID`. `POST …/refunds` on a payment settled by boleto is `422 REFUND_NOT_SUPPORTED` (the bank has
no refund for a boleto); one settled by the QR refunds like any Pix.

The credential needs the boleto account. Add to the `ITAU` payload (TEST or LIVE):

```json
{ "client_id": "...", "client_secret": "...", "pix_key": "...",
  "beneficiary_id": "<agencia 4 + conta 7 + DAC 1>", "wallet_code": "109", "species_code": "01" }
```

`wallet_code` and `species_code` default to `109`/`01` when omitted; without `beneficiary_id` a Bolecode is
refused with `422 PROVIDER_CREDENTIALS_MISSING` before anything is written. Nosso número is allocated by the
gateway, sequential per merchant from `00000001`.

Divergences a boleto can open (`reconciliation_divergences`, for a human): `AMOUNT_MISMATCH` (bank paid a
different amount), `DOUBLE_PAYMENT` (paid by QR and by barcode), `BOLETO_REJECTED`, `CANCELED_AT_BANK` (a baixa
done outside the gateway), `NOT_FOUND_AT_BANK` (a second empty query, not necessarily the next one), `PIX_TXID_UNCONFIRMED` (a boleto
adopted from the query whose derived Pix txid the bank does not know).

### Registering the inbound webhook at Itaú

`GET /v1/admin/merchants/<id>` returns `inbound_webhook_url`, built from `gateway.webhooks.mtls.public-host`
and the merchant's own inbound token. Register it against the merchant's Pix key at Itaú:

```
PUT https://pix-pj.api.itau.com/regulatorio-pix/v2/webhook/<pix_key>
{"webhookUrl":"<inbound_webhook_url>"}
```

Itaú appends `/pix` to whatever URL is registered there — do not include it yourself.

### mTLS connector (inbound webhook)

Itaú does not sign webhook payloads; authentication is the TLS client-certificate handshake itself, so
TLS cannot be terminated ahead of the app (see DECISOES). The connector is configured entirely through
env vars, read as `gateway.webhooks.mtls.*`:

- `WEBHOOK_MTLS_PORT` — the connector's port; `0` disables it.
- `WEBHOOK_MTLS_KEYSTORE` / `WEBHOOK_MTLS_KEYSTORE_PASSWORD` — PKCS12 keystore with the server certificate
  for the public webhook host.
- `WEBHOOK_MTLS_TRUSTSTORE` / `WEBHOOK_MTLS_TRUSTSTORE_PASSWORD` — PKCS12 truststore built from Itaú's
  `ca-cert.zip` (portal download).
- `WEBHOOK_MTLS_PUBLIC_HOST` — the host used to build `inbound_webhook_url`.
- `WEBHOOK_MTLS_ALLOWED_SUBJECTS` — comma-separated client-certificate subject DNs allowed to post.
  **Empty means any certificate the trusted CA signed is accepted.** It is unverified whether Itaú's CA
  also signs other customers' certificates — find out the bank's webhook certificate subject and set
  this before go-live.
- `ITAU_CA_PEM` — the same CA chain (from `ca-cert.zip`), used outbound as
  `gateway.providers.itau.trust-store-pem` to trust Itaú's API endpoints.

### Idempotency

Every POST that creates a resource or moves money — payments (and their `/cancel`, `/refunds`,
`/capture`), customers, orders (and their `/payments`, `/cancel`), plans and subscriptions (and their
`/cancel`) — requires an `Idempotency-Key` header, at most 123
characters. A repeated key with the same request body and method replays the stored response
(`Idempotent-Replayed: true`); the same key with a different body is `422 IDEMPOTENCY_KEY_REUSED`; a key
still being processed, or one whose first attempt failed with a 5xx and is held open, is `409 IN_PROGRESS`
— wait and retry with the same key; a different key would start a second operation. The key is scoped per environment
(TEST and LIVE never share a key row), so it is safe to script tests against TEST and LIVE with the same
key values. The stored body hash is an HMAC-SHA256 under `GATEWAY_IDEMPOTENCY_HMAC_KEY` (falling back to
`GATEWAY_API_KEY_PEPPER` when unset), because card request bodies carry PAN and CVV.

### Background jobs

- **Expiration.** A per-payment job expires each charge at its `calendario.expiracao`; a sweep every
  minute catches anything that job missed, and the RECONCILE job promotes a payment stuck in `CREATED`
  (the Itaú call's result never came back) to `PENDING(SYSTEM)` so it enters the normal expiration path.
- **Reconciliation.** Runs every 15 minutes, lists recent Itaú charges and received Pix, and turns any
  mismatch against the gateway's own state — including a charge Itaú shows paid that the gateway already
  marked `FAILED` or `CANCELED` — into a row in `reconciliation_divergences` for a human to resolve.
- **Refund polling.** A refund not yet resolved by the webhook is polled every 5 minutes for up to 288
  attempts (24 h); if it is still unresolved after that, it is marked `FAILED` and raised as a
  reconciliation divergence rather than left open indefinitely.
- **Boleto polling.** A `POLL_BOLETO` job per Bolecode asks Itaú's boleto query every 6 hours
  (`gateway.payments.boleto-poll-every`) until the payment limit date plus 2 days; paid completes the payment
  with `paid_via = BOLETO`, anything the gateway cannot act on becomes a divergence. Reconciliation runs the same
  check for every `PENDING` Bolecode older than `reconciliation-min-age`.

### Card (Cielo)

Credit card, customer present, through the Cielo E-commerce API (`method: "CARD"`). The card number
reaches the gateway and dies in the call to the Cielo: it is never stored, logged or echoed
(`CardDataNeverLeavesTheRequestTest`). Register the merchant's Cielo credential first:

```bash
curl -X PUT localhost:8080/v1/admin/merchants/$MERCHANT/providers/CIELO/credentials \
  -H "X-Admin-Key: $GATEWAY_ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"environment":"TEST","payload":{"merchant_id":"<CIELO_SANDBOX_MERCHANT_ID>","merchant_key":"<CIELO_SANDBOX_MERCHANT_KEY>"}}'
```

```bash
curl -X POST localhost:8080/v1/payments -H "Authorization: Bearer $TEST_KEY" \
  -H 'Idempotency-Key: order-42-1' -H 'Content-Type: application/json' -d '{
    "method": "CARD", "amount": 12990, "currency": "BRL", "reference": "order-42",
    "soft_descriptor": "LOJA42", "installments": 3, "capture": true, "save_card": true,
    "card": {"number": "4024007153763171", "holder": "JOAO DA SILVA", "expiry": "12/2030", "cvv": "123"},
    "customer": {"name": "Joao da Silva", "document": "12345678901", "email": "joao@example.com"}}'
```

- `card` **or** `card_id` (a card saved earlier with `save_card`); with `card_id`, `cvv` is required —
  the Cielo requires the security code with a stored card.
- `brand` is optional when the number identifies it (Visa, Master, Amex, Elo, Aura, JCB, Diners,
  Discover). `installments` 1–12, each installment at least R$ 5,00 (the Cielo's minimum for
  merchant-financed installments). `soft_descriptor` up to 13 letters or digits.
- `capture: false` stops at `AUTHORIZED`; capture with `POST /v1/payments/{id}/capture` (`{"amount": 5000}`
  for a partial capture — one capture per payment, at least 20 cents). `POST /v1/payments/{id}/cancel`
  on an `AUTHORIZED` payment voids it; a captured payment is refunded with `POST …/refunds` (total or
  partial, answered in the same request).
- A decline is `402 CARD_DECLINED` with `decline_code` (`INSUFFICIENT_FUNDS`, `EXPIRED_CARD`,
  `BLOCKED_CARD`, `CANCELED_CARD`, `TIMEOUT`, `DO_NOT_HONOR`, `GENERIC`) and the failed `payment_id`.
  Do not retry a decline automatically: card brands penalize it.
- **Always send an `Idempotency-Key`.** A create repeated without one is a second authorization at the
  Cielo, and a second hold on the payer's limit.
- An authorization nobody captures is never canceled by the gateway; after 5 days
  (`gateway.payments.card-capture-deadline`) reconciliation opens a `CAPTURE_OVERDUE` divergence.
- Saved cards: `GET /v1/cards/{id}` (brand, last four, expiry, holder) and `DELETE /v1/cards/{id}`.

**Cielo notifications.** In the Cielo site, set the notification URL to
`https://<public-host>/v1/providers/cielo/webhooks/<inbound_webhook_token>` (the token is the merchant's,
the same one the Itaú URL uses) and add a fixed header `X-Gateway-Notification-Key` with a value of your
choice; register the same value at the gateway:

```bash
curl -X PUT localhost:8080/v1/admin/merchants/$MERCHANT/providers/CIELO/notification-key \
  -H "X-Admin-Key: $GATEWAY_ADMIN_KEY" -H 'Content-Type: application/json' -d '{"key":"<the header value>"}'
```

The notification is only a hint: the gateway queries the sale and moves the payment on the Cielo's
answer. See `docs/providers/cielo/NOTES.md`.

### Customers, orders, plans and subscriptions

An **order** is what the payer owes; a **payment** is one attempt to pay it. A subscription bills itself by
opening one order per cycle (the invoice), so everything below is the same five resources.

| Route | Success | Errors |
|---|---|---|
| `POST /v1/customers` * | 201 | 409 `CUSTOMER_EXISTS` (+`customer_id`); 422 `CUSTOMER_INVALID` |
| `GET /v1/customers/{id}` | 200 | 404 `NOT_FOUND` |
| `GET /v1/customers?document=` | 200, a list of 0 or 1 | 400 on an invalid document |
| `PATCH /v1/customers/{id}` (`name`, `email`, `address`) | 200 | 400 on an empty body or an unknown field (`document` is immutable) |
| `DELETE /v1/customers/{id}` | 204 | 409 `CUSTOMER_HAS_ACTIVE_SUBSCRIPTION` |
| `GET /v1/customers/{id}/cards` | 200 | |
| `POST /v1/orders` * | 201 `OPEN` | 400 when both or neither of `customer_id`/`customer` are sent |
| `POST /v1/orders/{id}/payments` * | 201 payment | 409 `ORDER_CLOSED`; 409 `ORDER_HAS_ACTIVE_PAYMENT` (+`payment_id`); 402 `CARD_DECLINED` |
| `POST /v1/orders/{id}/cancel` * | 200 `CANCELED` | 409 `ORDER_CLOSED`; 409 `ALREADY_PAID` |
| `GET /v1/orders/{id}`, `GET /v1/orders?reference=&limit=`, `GET /v1/orders/{id}/payments` | 200 | |
| `POST /v1/plans` * | 201 | 400 on a range error |
| `GET /v1/plans/{id}`, `GET /v1/plans?active=` | 200 | |
| `PATCH /v1/plans/{id}` (`name`, `active`) | 200 | 422 `PLAN_IMMUTABLE` naming the field |
| `POST /v1/subscriptions` * | 201 `ACTIVE` | 404; 422 `PLAN_INACTIVE`, `CARD_REQUIRED`, `CARD_NOT_OWNED_BY_CUSTOMER`, `CUSTOMER_ADDRESS_REQUIRED` |
| `GET /v1/subscriptions/{id}`, `GET /v1/subscriptions?customer_id=` | 200 | |
| `POST /v1/subscriptions/{id}/cancel` * `{"at_period_end": true}` (default) | 200 | 409 `SUBSCRIPTION_NOT_ACTIVE` |
| `PATCH /v1/subscriptions/{id}` (`method`, `card_id`) | 200 | the 422s of create; 409 `SUBSCRIPTION_NOT_ACTIVE` |
| `GET /v1/subscriptions/{id}/orders` | 200, newest first | |

`*` requires an `Idempotency-Key` (400 `IDEMPOTENCY_KEY_REQUIRED` otherwise).

```bash
curl -X POST localhost:8080/v1/customers -H "Authorization: Bearer $TEST_KEY" \
  -H 'Idempotency-Key: cust-1' -H 'Content-Type: application/json' \
  -d '{"name": "Ana Silva", "document": "529.982.247-25", "email": "ana@example.com"}'
# 201 {"id": "<customer_id>", "name": "Ana Silva", "email": "ana@example.com", ...}

curl -X POST localhost:8080/v1/orders -H "Authorization: Bearer $TEST_KEY" \
  -H 'Idempotency-Key: order-42' -H 'Content-Type: application/json' \
  -d '{"amount": 4990, "currency": "BRL", "reference": "order-42", "customer_id": "<customer_id>"}'
# 201 {"id": "<order_id>", "status": "OPEN", "amount": 4990, ...}
```

An attempt is the payment body **without** `amount`, `currency` and `customer` — the order owns them:

```bash
curl -X POST localhost:8080/v1/orders/<order_id>/payments -H "Authorization: Bearer $TEST_KEY" \
  -H 'Idempotency-Key: order-42-pix' -H 'Content-Type: application/json' \
  -d '{"method": "PIX", "expires_in": 600}'
# 201 {"id": "<payment_id>", "order_id": "<order_id>", "status": "PENDING", ...}
```

Only one attempt per order can be live (`PENDING`, `AUTHORIZED`, in doubt): a second one is
`409 ORDER_HAS_ACTIVE_PAYMENT` with the live `payment_id` — cancel it first. The order turns `PAID` when an
attempt completes, after the outbox relay ran. A card saved with `save_card` on a customer's order belongs to
that customer (`GET /v1/customers/{id}/cards`).

```bash
curl -X POST localhost:8080/v1/plans ... -d '{"name": "Monthly", "amount": 2990, "currency": "BRL", "interval": "MONTH"}'
curl -X POST localhost:8080/v1/subscriptions ... \
  -d '{"customer_id": "<customer_id>", "plan_id": "<plan_id>", "method": "CARD", "card_id": "<card_id>"}'
# 201 {"id": "<subscription_id>", "status": "ACTIVE", "method": "CARD", "card_id": "<card_id>",
#      "current_period": {"start": "...", "end": "..."}, "next_billing_at": "...",
#      "cancel_at_period_end": false, "latest_order": {...}, "dunning": [], "created_at": "..."}
```

A plan has no environment; a subscription takes the environment of the key that created it. Price and interval
of a plan never change (`422 PLAN_IMMUTABLE`): create a new plan.

**Order and customer events.** `order.created` (also for each invoice a cycle opens), `order.paid`,
`order.canceled` and `order.expired` carry the order: `id`, `status`, `amount`, `currency`, `reference`,
`customer_id`, `paid_payment_id`, `paid_at`, `expires_at`, `subscription_id`, `invoice_number`, `created_at`.
`customer.created` and `customer.updated` carry `id`, `name`, `document` (masked), `email`, `has_address`,
`created_at`.

**Invoice events.** Each cycle emits `invoice.created` with `invoice_id`, `subscription_id`,
`invoice_number`, `amount`, `currency`, `method`, `period` (`start`, `end`), `payment_id`, `charged`,
`decline_code`, `reason`, and per method: `pix.copia_e_cola` for PIX, `boleto.linha_digitavel` and
`boleto.due_date` for BOLECODE, neither for CARD (`charged`/`decline_code` say how it went). A dunning reissue
emits `invoice.updated` with `invoice_id`, `subscription_id`, `attempt`, `payment_id`, `method` and the same
`pix`/`boleto` objects. Subscription events: `subscription.created`, `.past_due`, `.recovered`,
`.dunning_exhausted`, `.canceled`, `.ended`.

**Dunning.** A failed invoice (declined card, expired Pix or boleto) makes the subscription `PAST_DUE` and is
retried on the days in `gateway.billing.dunning-retry-days` (default `1,3,7`, ascending). A card is charged
again; a Pix or boleto is reissued. When the last one fails, `subscription.dunning_exhausted` is emitted and
nothing else happens. **`PAST_DUE` never cancels**: we keep charging, including the next cycle; cutting the
service is yours. A payment of the invoice brings the subscription back to `ACTIVE` once no other open invoice
is still being chased.

- `gateway.billing.card-recurring-enabled` (default `true`): a stored card is charged without CVV only by the
  billing job. Set it to `false` if your Cielo affiliation refuses recurring charges without CVV; card invoices
  then fail with reason `CARD_RECURRING_UNSUPPORTED` and go to dunning.
- `gateway.billing.order-expiry-recheck` (default `PT1H`): how often an order expiry or a dunning retry that
  finds a live attempt looks again, without spending a retry.
- The Itaú sandbox cannot settle a Pix or boleto, so a Pix or boleto invoice stays open in TEST until it
  expires and goes to dunning; only card subscriptions can be seen paid end to end in the sandbox.

### Sandbox

With sandbox credentials from the Itaú for Developers portal stored as a merchant's `TEST` credential,
`POST /v1/payments` with a `gk_test_` API key hits the real Itaú sandbox — there is no local stand-in for
the provider, so a TEST-environment run is a real (if non-production) integration.

### End-to-end against the sandboxes

`python scripts/e2e_sandbox.py` runs one happy path per method (PIX, BOLECODE, CARD) through a gateway
already up, using the sandbox credentials in `.env`, and writes the requests and answers to
`docs/e2e/<date>-sandbox-happy-paths.md` with every secret, key, card number and CVV removed. What each
sandbox can and cannot prove is written at the top of that report and in `docs/providers/*/NOTES.md`.

## Build

`./mvnw verify` (Testcontainers; needs Docker). The `com.barrier:webhook-delivery` library comes from GitHub
Packages: `~/.m2/settings.xml` with server `github-webhook-delivery` and a PAT with `read:packages`. CI uses the
`PACKAGES_READ_TOKEN` repository secret for the same reason.
