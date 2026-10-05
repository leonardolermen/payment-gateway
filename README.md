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

### Outbound webhooks

Register an endpoint with `POST /v1/webhooks/endpoints`; the response carries its signing secret once.

```
POST /v1/webhooks/endpoints
{"url": "https://merchant.example.com/hooks", "events": ["payment.*"]}

201 → {"id": "...", "url": "...", "events": ["payment.*"], "active": true, "secret": "..."}
```

`POST /v1/webhooks/endpoints/{id}/rotate-secret` issues a new one. Every state change listed in the
event catalog below is written to an outbox in the same transaction as the change, and delivered by
`webhook-delivery` to each active endpoint as an HTTP `POST` whose body is the event JSON.

**Delivery semantics.**

- **At least once.** A delivery can arrive twice (a timeout after your server already processed it, a
  redelivery). `X-Gateway-Event-Id` is the same on every attempt of the same event: store it and ignore
  repeats.
- **Ordered per partition key.** Events for one payment (including its refunds), one order, one customer
  or one subscription (including its invoices) are delivered in the order they happened; a failing event
  holds back the ones behind it on that key. There is no order across keys.
- **Success is any 2xx.** Anything else, a connection error or a timeout (2 s to connect, 10 s to read)
  is a failed attempt. Answer fast and do the work afterwards.
- **Retries.** Up to `webhook-delivery.max-attempts` (5) attempts, waiting `base-backoff` (30 s) × 2^n,
  capped at 64×, between them. After the last one the delivery is `DEAD` and stays so until redelivered.
- **Private targets are refused.** A URL resolving to a loopback, private or link-local address is
  rejected unless `webhook-delivery.allow-private-targets` is true (only for local development).

**Headers.**

```
X-Gateway-Event-Id: <uuid, the same on every attempt>
X-Gateway-Event-Type: payment.completed
X-Gateway-Signature: t=<epoch-seconds>,v1=<hex HMAC-SHA256(secret, t + "." + body)>
X-Gateway-Signature-Previous: t=<epoch-seconds>,v1=<same, under the previous secret>
```

`X-Gateway-Signature-Previous` is only sent during the 24 h after a secret rotation, so a receiver that
has not switched to the new secret yet keeps validating.

**Verifying the signature.** Compute the HMAC over the **raw request body bytes**, before any JSON
parsing — a re-serialized body (different spacing, key order or escaping) will not match. Compare in
constant time, and reject a `t` more than 5 minutes away from your clock, which stops a captured request
from being replayed later. During a rotation, accept the request if either header validates under the
secret you hold.

```
signed   = t + "." + raw_body
expected = hex(HMAC-SHA256(secret, signed))
valid    = constant_time_equals(expected, v1) and |now - t| <= 300
```

Python:

```python
import hashlib
import hmac
import time

TOLERANCE_SECONDS = 300


def signature_valid(header, raw_body: bytes, secret: str) -> bool:
    if not header:
        return False
    parts = dict(item.split("=", 1) for item in header.split(",") if "=" in item)
    timestamp, received = parts.get("t"), parts.get("v1")
    if timestamp is None or received is None:
        return False
    if abs(time.time() - int(timestamp)) > TOLERANCE_SECONDS:
        return False
    signed = timestamp.encode() + b"." + raw_body
    expected = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, received)


def verified(headers, raw_body: bytes, secret: str) -> bool:
    # Either header: during a rotation one of them is signed with the secret you still hold.
    return signature_valid(headers.get("X-Gateway-Signature"), raw_body, secret) or signature_valid(
        headers.get("X-Gateway-Signature-Previous"), raw_body, secret
    )
```

Java:

```java
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

final class GatewaySignature {
  private static final long TOLERANCE_SECONDS = 300;

  // Either header: during a rotation one of them is signed with the secret you still hold.
  static boolean verified(String signature, String previousSignature, byte[] rawBody, String secret)
      throws Exception {
    return valid(signature, rawBody, secret) || valid(previousSignature, rawBody, secret);
  }

  static boolean valid(String header, byte[] rawBody, String secret) throws Exception {
    if (header == null) {
      return false;
    }

    String timestamp = null;
    String received = null;
    for (String part : header.split(",")) {
      if (part.startsWith("t=")) {
        timestamp = part.substring(2);
      } else if (part.startsWith("v1=")) {
        received = part.substring(3);
      }
    }
    if (timestamp == null || received == null) {
      return false;
    }
    if (Math.abs(Instant.now().getEpochSecond() - Long.parseLong(timestamp)) > TOLERANCE_SECONDS) {
      return false;
    }

    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    mac.update((timestamp + ".").getBytes(StandardCharsets.UTF_8));
    byte[] expected = mac.doFinal(rawBody);

    return MessageDigest.isEqual(
        HexFormat.of().formatHex(expected).getBytes(StandardCharsets.UTF_8),
        received.getBytes(StandardCharsets.UTF_8));
  }
}
```

**Listing and redelivering.** Every delivery to your endpoints is visible to your API key, newest first:

```
GET /v1/webhooks/deliveries?status=DEAD&event_type=payment.completed&aggregate_id=<id>&since=<ISO-8601>&limit=20
```

```json
[
  {
    "id": "<uuid>",
    "event_id": "<uuid>",
    "event_type": "payment.completed",
    "aggregate_id": "<payment id>",
    "endpoint_id": "<uuid>",
    "target_url": "https://<your-host>/webhooks/gateway",
    "status": "DEAD",
    "attempts": 5,
    "last_error": "HTTP 500",
    "last_error_before_redelivery": null,
    "next_attempt_at": null,
    "created_at": "2026-10-05T12:00:00.123456Z",
    "delivered_at": null,
    "redelivered_at": null
  }
]
```

`status` is one of `PENDING`, `DELIVERED`, `FAILED` (will retry) and `DEAD`; `limit` defaults to 20, at
most 100. A full page comes with an `X-Next-Cursor` header: pass its value back as `?after=` for the
next page (which may be empty). `GET /v1/webhooks/deliveries/{id}` returns the same object plus
`payload`, the exact JSON body that was sent.

`POST /v1/webhooks/deliveries/{id}/redeliver` schedules one delivery again: `202 {"status":"PENDING"}`,
`404` for an unknown id, `409 DELIVERY_NOT_REDELIVERABLE` when it is not in a state that can be resent.
`POST /v1/webhooks/deliveries/redeliver-dead` with `{"since":"<ISO-8601>"}` reschedules every `DEAD`
delivery created since then: `202 {"scheduled": <n>}`, or `422 WINDOW_TOO_WIDE` when `since` is more
than 30 days back. Both POSTs require an `Idempotency-Key`.

**Event catalog.** One section per `X-Gateway-Event-Type`. Values are illustrative; the keys are the
contract (a null value still has its key), and a test (`EventCatalogTest`) fails the build when a
payload and its example here disagree. In payment events, `pix` is set for `PIX` and `BOLECODE`,
`boleto` for `BOLECODE`, `card` for `CARD`; the others are `null`.

#### payment.pending

The charge exists at the bank and waits for the payer (Pix QR issued, boleto registered). Example: a Pix payment.

```json
{
  "id": "01M46BTW1B92DANHVWJWFB518D",
  "status": "PENDING",
  "method": "PIX",
  "provider": "itau",
  "environment": "TEST",
  "amount": 15000,
  "currency": "BRL",
  "reference": "order-1234",
  "order_id": null,
  "description": "Order 1234",
  "pix": {
    "txid": "01M46BTW1B92DANHVWJWFB518D",
    "copia_e_cola": "00020101021226...6304ABCD",
    "location": null,
    "end_to_end_id": null
  },
  "boleto": null,
  "card": null,
  "expires_at": "2026-10-05T13:00:00Z",
  "paid_at": null,
  "paid_amount": null,
  "refunded_amount": 0,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### payment.authorized

A card was authorized and not yet captured. Example: a card payment.

```json
{
  "id": "01M46BTW2RK3BFEYNCD7Q98X70",
  "status": "AUTHORIZED",
  "method": "CARD",
  "provider": "cielo",
  "environment": "TEST",
  "amount": 15000,
  "currency": "BRL",
  "reference": "order-1234",
  "order_id": null,
  "description": "Order 1234",
  "pix": null,
  "boleto": null,
  "card": {
    "brand": "Visa",
    "last4": "0004",
    "installments": 1,
    "authorization_code": "123456",
    "tid": "1006993069000A1B2C3D",
    "captured_amount": null,
    "card_id": null
  },
  "expires_at": null,
  "paid_at": null,
  "paid_amount": null,
  "refunded_amount": 0,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### payment.completed

Money arrived: Pix paid, boleto or bolecode paid, card captured. Example: a bolecode paid through its QR.

```json
{
  "id": "01M46BTW2VQYSJA2N886B43CRR",
  "status": "COMPLETED",
  "method": "BOLECODE",
  "provider": "itau",
  "environment": "TEST",
  "amount": 4990,
  "currency": "BRL",
  "reference": null,
  "order_id": null,
  "description": null,
  "pix": {
    "txid": "txid-1",
    "copia_e_cola": "00020101021226...6304ABCD",
    "location": null,
    "end_to_end_id": "E00000000202610051203abcdef12345"
  },
  "boleto": {
    "linha_digitavel": "34191.09008 00012.300000 00000.000000 1 00000000004990",
    "codigo_barras": "34191000000000049901090000012300000000000000",
    "due_date": "2026-10-08",
    "payment_limit_date": "2026-11-07",
    "paid_via": "PIX"
  },
  "card": null,
  "expires_at": "2026-10-08T12:00:00Z",
  "paid_at": "2026-10-05T12:03:10Z",
  "paid_amount": 4990,
  "refunded_amount": 0,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### payment.failed

The payment will not complete (provider refusal, card declined).

```json
{
  "id": "01M46BTW2YG23ZWNQQD3ET9717",
  "status": "FAILED",
  "method": "CARD",
  "provider": "cielo",
  "environment": "TEST",
  "amount": 15000,
  "currency": "BRL",
  "reference": "order-1234",
  "order_id": null,
  "description": "Order 1234",
  "pix": null,
  "boleto": null,
  "card": {
    "brand": "Visa",
    "last4": "0004",
    "installments": 1,
    "authorization_code": null,
    "tid": null,
    "captured_amount": null,
    "card_id": null
  },
  "expires_at": null,
  "paid_at": null,
  "paid_amount": null,
  "refunded_amount": 0,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### payment.expired

The Pix or boleto passed `expires_at` unpaid.

```json
{
  "id": "01M46BTW2YG23ZWNQQD3ET9719",
  "status": "EXPIRED",
  "method": "PIX",
  "provider": "itau",
  "environment": "TEST",
  "amount": 15000,
  "currency": "BRL",
  "reference": "order-1234",
  "order_id": null,
  "description": "Order 1234",
  "pix": {
    "txid": "01M46BTW2YG23ZWNQQD3ET9719",
    "copia_e_cola": "00020101021226...6304ABCD",
    "location": null,
    "end_to_end_id": null
  },
  "boleto": null,
  "card": null,
  "expires_at": "2026-10-05T13:00:00Z",
  "paid_at": null,
  "paid_amount": null,
  "refunded_amount": 0,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### payment.canceled

The merchant canceled it (`POST /v1/payments/{id}/cancel`).

```json
{
  "id": "01M46BTW2ZEETQW1M14JV6W5G5",
  "status": "CANCELED",
  "method": "BOLECODE",
  "provider": "itau",
  "environment": "TEST",
  "amount": 4990,
  "currency": "BRL",
  "reference": null,
  "order_id": null,
  "description": null,
  "pix": {
    "txid": "txid-1",
    "copia_e_cola": "00020101021226...6304ABCD",
    "location": null,
    "end_to_end_id": null
  },
  "boleto": {
    "linha_digitavel": "34191.09008 00012.300000 00000.000000 1 00000000004990",
    "codigo_barras": "34191000000000049901090000012300000000000000",
    "due_date": "2026-10-08",
    "payment_limit_date": "2026-11-07",
    "paid_via": null
  },
  "card": null,
  "expires_at": "2026-10-08T12:00:00Z",
  "paid_at": null,
  "paid_amount": null,
  "refunded_amount": 0,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### refund.requested

A refund was accepted and reserved against the payment. Same partition key as the payment.

```json
{
  "id": "01M46BTW2ZEETQW1M14JV6W5GB",
  "payment_id": "01M46BTW2ZEETQW1M14JV6W5G8",
  "amount": 5000,
  "state": "REQUESTED",
  "reason": null,
  "requested_at": "2026-10-05T12:00:00Z",
  "settled_at": null
}
```

#### refund.completed

The bank settled the refund.

```json
{
  "id": "01M46BTW30SVMZG4DD3R2A3DBS",
  "payment_id": "01M46BTW30SVMZG4DD3R2A3DBP",
  "amount": 5000,
  "state": "COMPLETED",
  "reason": null,
  "requested_at": "2026-10-05T12:00:00Z",
  "settled_at": "2026-10-05T12:05:00Z"
}
```

#### refund.failed

The bank refused the refund; `reason` says why and the amount is released.

```json
{
  "id": "01M46BTW30SVMZG4DD3R2A3DBX",
  "payment_id": "01M46BTW30SVMZG4DD3R2A3DBT",
  "amount": 5000,
  "state": "FAILED",
  "reason": "<bank reason>",
  "requested_at": "2026-10-05T12:00:00Z",
  "settled_at": "2026-10-05T12:05:00Z"
}
```

#### refund.unknown

The bank never answered within the polling budget. The amount stays reserved, and a `refund.completed` or `refund.failed` may still follow.

```json
{
  "id": "01M46BTW30SVMZG4DD3R2A3DC1",
  "payment_id": "01M46BTW30SVMZG4DD3R2A3DBY",
  "amount": 5000,
  "state": "UNKNOWN",
  "reason": null,
  "requested_at": "2026-10-05T12:00:00Z",
  "settled_at": null
}
```

#### customer.created

A customer was created. `document` is always masked.

```json
{
  "id": "01M46BTW36VZY0MRJAY3QCC00B",
  "name": "Maria Silva",
  "document": "***.982.***-25",
  "email": "maria@example.com",
  "has_address": true,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### customer.updated

A customer changed.

```json
{
  "id": "01M46BTW3AH1Q9F80JPXTMYM90",
  "name": "Maria Silva",
  "document": "***.982.***-25",
  "email": "maria.silva@example.com",
  "has_address": true,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### order.created

An order was created (by the API, or as a subscription invoice — then `subscription_id` and `invoice_number` are set).

```json
{
  "id": "01M46BTW3BEX7GFVGWV0XED5HT",
  "status": "OPEN",
  "amount": 15000,
  "currency": "BRL",
  "reference": "order-1234",
  "customer_id": "01M46BTW3AH1Q9F80JPXTMYM91",
  "paid_payment_id": null,
  "paid_at": null,
  "expires_at": "2026-10-06T12:00:00Z",
  "subscription_id": null,
  "invoice_number": null,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### order.paid

A payment attempt on the order completed.

```json
{
  "id": "01M46BTW3D70Y28P8Y68JE95VM",
  "status": "PAID",
  "amount": 15000,
  "currency": "BRL",
  "reference": "order-1234",
  "customer_id": "01M46BTW3D70Y28P8Y68JE95VK",
  "paid_payment_id": "01M46BSGXDBWG32PFFS15HYYNR",
  "paid_at": "2026-10-05T12:03:10Z",
  "expires_at": "2026-10-06T12:00:00Z",
  "subscription_id": null,
  "invoice_number": null,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### order.canceled

The order was canceled.

```json
{
  "id": "01M46BTW3D70Y28P8Y68JE95VP",
  "status": "CANCELED",
  "amount": 15000,
  "currency": "BRL",
  "reference": "order-1234",
  "customer_id": "01M46BTW3D70Y28P8Y68JE95VN",
  "paid_payment_id": null,
  "paid_at": null,
  "expires_at": "2026-10-06T12:00:00Z",
  "subscription_id": null,
  "invoice_number": null,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### order.expired

The order passed `expires_at` unpaid.

```json
{
  "id": "01M46BTW3D70Y28P8Y68JE95VR",
  "status": "EXPIRED",
  "amount": 15000,
  "currency": "BRL",
  "reference": "order-1234",
  "customer_id": "01M46BTW3D70Y28P8Y68JE95VQ",
  "paid_payment_id": null,
  "paid_at": null,
  "expires_at": "2026-10-06T12:00:00Z",
  "subscription_id": null,
  "invoice_number": null,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### invoice.created

A subscription cycle opened and its invoice was charged or issued. `charged` is true only for a captured card; `reason` and `decline_code` say why it was not.

```json
{
  "invoice_id": "01M46BTW3KFJH530ZFTMYR6VTJ",
  "subscription_id": "01M46BTW3H47WGKMSNGSSE217F",
  "invoice_number": 1,
  "amount": 4990,
  "currency": "BRL",
  "method": "BOLECODE",
  "period": {
    "start": "2026-10-05",
    "end": "2026-11-04"
  },
  "payment_id": "01M46BTW3JXBXY3Q1WZW4H74Q5",
  "charged": false,
  "decline_code": null,
  "reason": null,
  "pix": {
    "copia_e_cola": "00020101021226...6304ABCD"
  },
  "boleto": {
    "linha_digitavel": "34191.09008 00012.300000 00000.000000 1 00000000004990",
    "due_date": "2026-10-08"
  }
}
```

#### invoice.updated

A dunning retry issued a new payment for the same invoice. `attempt` counts the retries.

```json
{
  "invoice_id": "01M46BTW3MQGA2WBBRE7SYGW1K",
  "subscription_id": "01M46BTW3MQGA2WBBRE7SYGW1J",
  "attempt": 2,
  "payment_id": "01M46BTW3MQGA2WBBRE7SYGW1M",
  "method": "BOLECODE",
  "pix": {
    "copia_e_cola": "00020101021226...6304ABCD"
  },
  "boleto": {
    "linha_digitavel": "34191.09008 00012.300000 00000.000000 1 00000000004990",
    "due_date": "2026-10-08"
  }
}
```

#### subscription.created

A subscription was created.

```json
{
  "id": "01M46BTW3NHQJ5PAP4SXCH68KV",
  "status": "ACTIVE",
  "customer_id": "01M46BTW3NHQJ5PAP4SXCH68KT",
  "plan_id": "01M46BTW3NHQJ5PAP4SXCH68KS",
  "method": "BOLECODE",
  "card_id": null,
  "current_period": {
    "start": "2026-10-05",
    "end": "2026-11-04"
  },
  "next_billing_at": "2026-11-05T12:00:00Z",
  "cancel_at_period_end": false,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### subscription.past_due

An invoice failed and dunning started.

```json
{
  "id": "01M46BTW3P1F7TNYP5E0TV4CD5",
  "status": "PAST_DUE",
  "customer_id": "01M46BTW3P1F7TNYP5E0TV4CD4",
  "plan_id": "01M46BTW3P1F7TNYP5E0TV4CD3",
  "method": "BOLECODE",
  "card_id": null,
  "current_period": {
    "start": "2026-10-05",
    "end": "2026-11-04"
  },
  "next_billing_at": "2026-11-05T12:00:00Z",
  "cancel_at_period_end": false,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### subscription.recovered

A past-due subscription paid its invoice and is active again.

```json
{
  "id": "01M46BTW3P1F7TNYP5E0TV4CD8",
  "status": "ACTIVE",
  "customer_id": "01M46BTW3P1F7TNYP5E0TV4CD7",
  "plan_id": "01M46BTW3P1F7TNYP5E0TV4CD6",
  "method": "BOLECODE",
  "card_id": null,
  "current_period": {
    "start": "2026-10-05",
    "end": "2026-11-04"
  },
  "next_billing_at": "2026-11-05T12:00:00Z",
  "cancel_at_period_end": false,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### subscription.dunning_exhausted

Every retry failed. Carries the subscription plus the `invoice_id` that could not be collected.

```json
{
  "id": "01M46BTW3Q56QCNKDYFNQD739S",
  "status": "PAST_DUE",
  "customer_id": "01M46BTW3Q56QCNKDYFNQD739R",
  "plan_id": "01M46BTW3Q56QCNKDYFNQD739Q",
  "method": "BOLECODE",
  "card_id": null,
  "current_period": {
    "start": "2026-10-05",
    "end": "2026-11-04"
  },
  "next_billing_at": "2026-11-05T12:00:00Z",
  "cancel_at_period_end": false,
  "created_at": "2026-10-05T12:00:00Z",
  "invoice_id": "01M46BTW3Q56QCNKDYFNQD739T"
}
```

#### subscription.canceled

The subscription was canceled.

```json
{
  "id": "01M46BTW3Q56QCNKDYFNQD739X",
  "status": "CANCELED",
  "customer_id": "01M46BTW3Q56QCNKDYFNQD739W",
  "plan_id": "01M46BTW3Q56QCNKDYFNQD739V",
  "method": "BOLECODE",
  "card_id": null,
  "current_period": {
    "start": "2026-10-05",
    "end": "2026-11-04"
  },
  "next_billing_at": null,
  "cancel_at_period_end": false,
  "created_at": "2026-10-05T12:00:00Z"
}
```

#### subscription.ended

A subscription set to cancel at period end reached it.

```json
{
  "id": "01M46BTW3Q56QCNKDYFNQD73A0",
  "status": "ENDED",
  "customer_id": "01M46BTW3Q56QCNKDYFNQD739Z",
  "plan_id": "01M46BTW3Q56QCNKDYFNQD739Y",
  "method": "BOLECODE",
  "card_id": null,
  "current_period": {
    "start": "2026-10-05",
    "end": "2026-11-04"
  },
  "next_billing_at": null,
  "cancel_at_period_end": false,
  "created_at": "2026-10-05T12:00:00Z"
}
```

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
The sandboxes cannot call back into a gateway, so outbound webhooks are proved by
`WebhookDeliveryFlowIntegrationTest` (signed delivery, retries into `DEAD`, redelivery, secret rotation).

## Build

`./mvnw verify` (Testcontainers; needs Docker). The `com.barrier:webhook-delivery` library comes from GitHub
Packages: `~/.m2/settings.xml` with server `github-webhook-delivery` and a PAT with `read:packages`. CI uses the
`PACKAGES_READ_TOKEN` repository secret for the same reason.
