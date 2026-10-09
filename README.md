# Payment Gateway

Payment orchestrator (model A: the merchant's own credentials; money never passes through here).
Three methods through two providers: Pix and Bolecode (boleto with Pix) through Itaú, credit card through
Cielo; customers, orders, plans and subscriptions on top. Architecture: `docs/architecture.md`.
Spec: `docs/superpowers/specs/2026-09-23-payment-gateway-design.md`. Decisions: `docs/superpowers/DECISOES.md`.

## Run

```bash
docker compose up -d
./mvnw -DskipTests install          # first run, and after changing a sibling module
cp .env.example .env                # then fill in the values below
./mvnw -pl gateway-app spring-boot:run
```

The app reads the repo root's `.env` on startup, so the IDE's run configuration needs no environment
variables. The minimum for a local run:

```properties
GATEWAY_ADMIN_KEY=dev-admin
GATEWAY_API_KEY_PEPPER=dev-pepper
GATEWAY_MASTER_KEY=<output of: openssl rand -base64 32>
WEBHOOK_MTLS_PORT=0
spring.profiles.active=local
```

Outside the `local` and `test` profiles the app refuses to start with `GATEWAY_MAIL_HOST` empty or
`GATEWAY_PANEL_BASE_URL` blank or on `http://localhost`: a production box would otherwise log the
reset and invite links nobody reads. Hence `local` in the minimum above.

Without `GATEWAY_MASTER_KEY` the app does not start (the master key encrypts merchant credentials).
Keep the same key across runs: a new one cannot decrypt the credentials saved under the previous one.
Without `GATEWAY_ADMIN_KEY` the admin API answers 403 — closed by default (the header is `X-Admin-Key`).

`-pl gateway-app` resolves the sibling modules from `~/.m2`, hence the `install` first. Do not add `-am`
to the `spring-boot:run` line: the goal then runs on the parent pom too and fails with "Unable to find a
suitable main class".

### Without bank sandbox credentials

The checkout offers a method only when the merchant has an active credential for its bank (Itaú:
Pix and Bolecode; Cielo: card). With no sandbox credentials, `scripts/mock-providers` stands in for
both banks: a WireMock built from the integration tests' fixtures, echoing the txid, amount and a
fresh `PaymentId` per request. Card `4024007153760052` is denied. Any other card answers like the Cielo
would for that request: `Capture: true` is paid (`Status 2`, captured amount = amount), otherwise
authorized (`Status 1`); the brand, holder, expiry and installments are echoed and the number comes back
masked with its own first six and last four digits; `SaveCard: true` returns a fresh `CardToken`, and a
sale by `CardToken` (a subscription cycle) is answered for that same token.

```bash
java -jar ~/.m2/repository/org/wiremock/wiremock-standalone/3.13.0/wiremock-standalone-3.13.0.jar \
  --port 8099 --root-dir scripts/mock-providers
```

Point the TEST URLs at it in `.env` (properties keys, read through the `.env` import):

```properties
gateway.providers.itau.test-api-base=http://localhost:8099/itau/pix
gateway.providers.itau.test-token-url=http://localhost:8099/itau/oauth
gateway.providers.itau.boleto.test-issue-api-base=http://localhost:8099/itau/issue
gateway.providers.itau.boleto.test-issue-token-url=http://localhost:8099/itau/oauth
gateway.providers.itau.boleto.test-query-api-base=http://localhost:8099/itau/query
gateway.providers.itau.boleto.test-query-token-url=http://localhost:8099/itau/oauth
gateway.providers.itau.boleto.test-instruction-api-base=http://localhost:8099/itau/instruction
gateway.providers.itau.boleto.test-instruction-token-url=http://localhost:8099/itau/oauth
gateway.providers.cielo.test-api-base=http://localhost:8099/cielo/api
gateway.providers.cielo.test-query-api-base=http://localhost:8099/cielo/query
```

Then register any TEST credentials on the merchant (`PUT /v1/admin/merchants/{id}/providers/{ITAU|CIELO}/credentials`);
they are not checked against the bank on save. The mock covers creating a Pix charge, a Bolecode, a card
sale, capture, void and Pix refund; bank-side events (a Pix paid, a boleto settled) still have to be
simulated by posting the webhook.

Without `WEBHOOK_MTLS_PORT=0` the app refuses to start unless `WEBHOOK_MTLS_KEYSTORE` and
`WEBHOOK_MTLS_TRUSTSTORE` are set: the inbound connector (default `8443`) needs the key material for the
bank's mTLS webhooks.

`com.barrier:webhook-delivery` comes from GitHub Packages, which asks for a token even to read
public packages. Either put a PAT (classic, `read:packages`) in `~/.m2/settings.xml` under the server id
`github-webhook-delivery`, or install it from source — the repo is public:

```bash
git clone --depth 1 --branch v0.2.0 https://github.com/leonardolermen/webhook-delivery.git
cd webhook-delivery && ./mvnw -DskipTests install
```

Keep the tag in step with `webhook-delivery.version` in the root `pom.xml`. With the artifact in
`~/.m2`, the remaining 401 warnings for `maven-metadata.xml` during the build are harmless.

Behind a reverse proxy, the edge proxy must append the connecting address to (or overwrite) `X-Forwarded-For`: the gateway rate-limits `/v1/checkout` per IP using the last entry of that header, and only when the connection comes from a private or loopback address. `GATEWAY_CORS_ORIGINS` (comma-separated exact origins) enables CORS; empty means off.

`/actuator/health` and `/actuator/prometheus` are served on the management port (`GATEWAY_MANAGEMENT_PORT`,
default `9090`), not on `8080`: point health checks there, and keep that port off the public network.

If port 5432 is already taken on your machine, map `5433:5432` in `docker-compose.yml` and set
`DB_URL=jdbc:postgresql://localhost:5433/gateway` before starting the app.

## First merchant

```bash
curl -s -XPOST localhost:8080/v1/admin/merchants -H 'X-Admin-Key: dev-admin' -H 'Content-Type: application/json' -d '{"name":"Store"}'
curl -s -XPOST localhost:8080/v1/admin/merchants/<id>/api-keys -H 'X-Admin-Key: dev-admin' -H 'Content-Type: application/json' -d '{"environment":"TEST"}'
curl -s localhost:8080/v1/merchant -H 'Authorization: Bearer gk_test_…'
```

## Panel login

The merchant panel signs people in; API keys (`gk_…`) keep working everywhere, unchanged. Spec:
`docs/superpowers/specs/2026-10-08-usuarios-e-sessao-design.md`.

- **Signup** (`POST /v1/auth/signup`) creates a merchant and its first user, an `OWNER`, and opens a
  session. TEST works at once; LIVE answers `403 EMAIL_NOT_VERIFIED` until the link sent by e-mail is used.
- **Session**: the response carries a `gs_…` access token (15 min), sent as `Authorization: Bearer`; keep
  it in memory. The refresh token is the `gw_refresh` cookie (`HttpOnly`, `Secure`, `SameSite=None`,
  `Path=/v1/auth`, `gateway.auth.refresh-ttl`, 30 days by default), rotated on every `POST /v1/auth/refresh`; replaying an old cookie ends the
  session. Only hashes are stored.
- **`X-Environment: TEST|LIVE`** picks the environment of a user session (an API key carries its own).
  Missing or invalid means `TEST`.
- **Roles** (`RoleRoutes`), the least role a route needs:

| Role | May |
|---|---|
| `READONLY` | every `GET`; its own account under `/v1/me` |
| `FINANCE` | the above, plus writes that move money or data: payments, refunds, orders, customers, plans, subscriptions, disputes |
| `OWNER` | everything: webhook endpoints, `/v1/merchant`, providers, installment settings, the team and invites, deleting customers |

- **E-mail** goes out through SMTP as a `SEND_EMAIL` job. With `GATEWAY_MAIL_HOST` empty the message is
  logged instead (the link too, so only use that in dev; outside the `local` and `test` profiles an empty
  host fails startup). STARTTLS (required, not just offered) and authentication are on only when
  `GATEWAY_MAIL_USERNAME` is set. Connect, read and write time out after 10 s. Links (verify, reset,
  invite) start at `GATEWAY_PANEL_BASE_URL`.
- **Invites** need a verified e-mail: an owner who has not used the verification link gets
  `403 EMAIL_NOT_VERIFIED` from `POST /v1/invites`.
- **Audit**: every login (success or failure), logout, password change or reset, verification, invite sent
  or accepted, role change, removal and session revoke is one INFO line on the `gateway.audit.account`
  logger, `key=value`, with ids and the client IP only (never an e-mail, token or password).

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_MAIL_HOST` / `GATEWAY_MAIL_PORT` | empty / `587` | SMTP relay |
| `GATEWAY_MAIL_USERNAME` / `GATEWAY_MAIL_PASSWORD` | empty | set to turn on STARTTLS and auth |
| `GATEWAY_MAIL_FROM` | `no-reply@localhost` | sender address |
| `GATEWAY_PANEL_BASE_URL` | `http://localhost:5173` | where the panel is served (must not be localhost outside `local`/`test`) |
| `GATEWAY_AUTH_MAX_CONCURRENT_HASHES` (`gateway.auth.max-concurrent-hashes`) | `8` | Argon2 hashes running at once (64 MB each); past it, `503 AUTH_BUSY` |

| Route | Success | Errors |
|---|---|---|
| `POST /v1/auth/signup` | 201 session | 409 `EMAIL_TAKEN`; 400/422 on a weak password |
| `POST /v1/auth/login` | 200 session | 401 `INVALID_CREDENTIALS` (same answer for a wrong password and an unknown e-mail); 503 `AUTH_BUSY` |
| `POST /v1/auth/refresh` (cookie) | 200 session | 401 `SESSION_EXPIRED` |
| `POST /v1/auth/logout` (cookie) | 204 | |
| `POST /v1/auth/password/forgot` | 202, always | |
| `POST /v1/auth/password/reset` | 204; every session is closed | 410 `TOKEN_EXPIRED`; 400/422 on a weak password |
| `POST /v1/auth/email/verify` | 204 | 410 `TOKEN_EXPIRED` |
| `POST /v1/auth/invite/accept` | 201 session | 410 `TOKEN_EXPIRED`; 409 `EMAIL_TAKEN` |
| `GET /v1/me`, `PATCH /v1/me` | 200 | |
| `POST /v1/me/password` | 204; other sessions are closed | 401 `INVALID_CREDENTIALS` |
| `POST /v1/me/email/resend` | 202 | 409 `ALREADY_VERIFIED`; 429 `RESEND_TOO_SOON` |
| `GET /v1/me/sessions`, `DELETE /v1/me/sessions/others` | 200 / 204 | |
| `GET /v1/merchant/users` | 200 users and open invites | |
| `POST /v1/invites` (`OWNER`) | 202 | 409 `EMAIL_TAKEN`; 403 `EMAIL_NOT_VERIFIED` (the inviter's own e-mail) |
| `PATCH /v1/merchant/users/{id}` (`OWNER`) | 200 | 404 `NOT_FOUND`; 400 `OWN_ACCOUNT` |
| `DELETE /v1/merchant/users/{id}` (`OWNER`) | 204 | 404 `NOT_FOUND`; 400 `OWN_ACCOUNT` |

Any route that hashes a password (signup, login, reset, invite accept, password change) can answer
`503 AUTH_BUSY` when `gateway.auth.max-concurrent-hashes` are already running; retry in a moment.

`/v1/auth/*` is rate limited per client IP (`gateway.auth.rate-limit-per-minute`, default 10; an IPv6
client is counted by its /64). With `GATEWAY_CORS_ORIGINS` set, a request to `/v1/auth/*` whose `Origin`
is not in the list is refused with `403 ORIGIN_NOT_ALLOWED`; no `Origin` (curl, a server) passes. The `/v1/me`,
`/v1/merchant/users` and `/v1/invites` routes need a user session: an API key has no person behind it.

## Modules

- `gateway-kernel` — dependency-free shared types and the provider contracts.
- `gateway-merchants` — merchants, API keys, provider credentials encrypted per merchant and environment.
- `gateway-providers` — the Itaú (Pix, Bolecode) and Cielo (card) clients.
- `gateway-payments` — payments, refunds, idempotency, outbox, jobs, reconciliation.
- `gateway-billing` — customers, orders, plans, subscriptions, cycles and dunning (schema `billing`).
- `gateway-app` — the deployable: REST, auth, rate limit, inbound webhooks, outbox relay, observability.
- `webhook-delivery` (library, `com.barrier`) — signed outbound webhooks, retries, listing and redelivery.

The boundary is enforced by `ArchitectureTest`; the rules and diagrams are in `docs/architecture.md`.

## Payments (Pix / Itaú)

Pix and Bolecode go through Itaú; card goes through Cielo (see "Card (Cielo)" below). `ProviderGateway`
resolves the provider per method. Two environments per merchant, `TEST` and `LIVE`,
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
- **Ordered per partition key.** Events for one payment (including its refunds), one order, one customer,
  one subscription (including its invoices) or one merchant's installment settings are delivered in the
  order they happened while they are being retried: a failing event holds back the ones behind it on that
  key. There is no order across keys.
  **Once an event goes `DEAD`, the newer ones on that key are delivered**, so a later redelivery (manual or
  bulk) arrives OUT of order. Do not infer state from arrival order: use the payload (`status`,
  timestamps) and the event id.
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
    try:
        parts = dict(item.split("=", 1) for item in header.split(","))
        timestamp, received = parts["t"], parts["v1"]
        age = abs(time.time() - int(timestamp))
    except (ValueError, KeyError):  # a malformed header is invalid, never an exception
        return False
    if age > TOLERANCE_SECONDS:
        return False
    signed = timestamp.encode() + b"." + raw_body
    expected = hmac.new(secret.encode(), signed, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected.encode(), received.encode())


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
    long signedAt;
    try {
      signedAt = Long.parseLong(timestamp);
    } catch (NumberFormatException e) { // a malformed header is invalid, never an exception
      return false;
    }
    if (Math.abs(Instant.now().getEpochSecond() - signedAt) > TOLERANCE_SECONDS) {
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
`POST /v1/webhooks/deliveries/redeliver-dead` with `{"since":"<ISO-8601>"}` reschedules at most 1000 `DEAD`
deliveries created since then per call, oldest first: `202 {"scheduled": <n>}`. Call again until
`scheduled < 1000`. A missing `since` is `400 INVALID_REQUEST`; `since` more than 30 days back is
`422 WINDOW_TOO_WIDE`. Both POSTs require an `Idempotency-Key`.

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
    "card_id": null,
    "interest_amount": 0
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
    "card_id": null,
    "interest_amount": 0
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

#### dispute.updated

A merchant's dispute was opened (`status: OPEN`) or the operator moved it: `UNDER_REVIEW`, then
`RESOLVED` or `REJECTED` with a `resolution` and `resolution_note`. Same partition as the payment's own
events, so it arrives in order with them.

```json
{
  "id": "01M46BTW3Q56QCNKDYFNQD73A1",
  "payment_id": "01M46BTW3Q56QCNKDYFNQD73A2",
  "reason": "DUPLICATE",
  "status": "OPEN",
  "resolution": null,
  "resolution_note": null,
  "updated_at": "2026-10-05T12:00:00Z"
}
```

#### installment_settings.updated

The merchant replaced its installment settings (`PUT /v1/installment-settings`) in one `environment`.
`api_key_id` is the key that made the change. Partitioned per merchant, so changes arrive in order.

```json
{
  "environment": "TEST",
  "max_installments": 10,
  "interest_free_up_to": 3,
  "monthly_rate_bps": 299,
  "api_key_id": "01M46BTW3K4Q2X9S7D5F8G6H1J",
  "updated_at": "2026-10-05T12:00:00Z"
}
```

### Idempotency

Every POST that creates a resource or moves money — payments (and their `/cancel`, `/refunds`,
`/capture`, `/disputes`), customers, orders (and their `/payments`, `/cancel`), plans and subscriptions (and their
`/cancel`) — requires an `Idempotency-Key` header, at most 123
characters. A repeated key with the same request body and method replays the stored response
(`Idempotent-Replayed: true`); the same key with a different body is `422 IDEMPOTENCY_KEY_REUSED`; a key
still being processed, or one whose first attempt failed with a 5xx and is held open, is `409 IN_PROGRESS`
— wait and retry with the same key; a different key would start a second operation. The key is scoped per environment
(TEST and LIVE never share a key row), so it is safe to script tests against TEST and LIVE with the same
key values. The stored body hash is an HMAC-SHA256 under `GATEWAY_IDEMPOTENCY_HMAC_KEY` (falling back to
`GATEWAY_API_KEY_PEPPER` when unset), because card request bodies carry PAN and CVV.

### Disputes

A merchant who disagrees with a payment opens a dispute; an operator reviews and decides it. Opening
never moves money: a confirmed double charge is refunded through `/refunds` as a separate act.

- `POST /v1/payments/{id}/disputes` (`Idempotency-Key` required) with `{"reason", "note"}` → `201`.
  `reason` is `AMOUNT_MISMATCH`, `NOT_SETTLED`, `DUPLICATE` or `OTHER` (anything else is `400`); `note` is
  optional, up to 500 characters. A payment holds one open dispute at a time: a second one while the first
  is `OPEN` or `UNDER_REVIEW` is `409 DISPUTE_ALREADY_OPEN`. Another merchant's payment is `404`.
- `GET /v1/disputes?status=&since=&after=&limit=` lists the merchant's disputes, newest first; a full page
  carries `X-Next-Cursor`, which goes back as `after`.
- `GET /v1/disputes/{id}`.

The response is `id, payment_id, reason, note, status, resolution, resolution_note, created_at,
resolved_at`. `status` goes `OPEN` → `UNDER_REVIEW` → `RESOLVED` or `REJECTED` (the operator may also
decide straight from `OPEN`), through `/v1/admin/divergences/{id}/review` and `/resolve`: the dispute id is
the divergence id. Each step, the opening included, emits [`dispute.updated`](#disputeupdated).

### Operations

Admin routes (header `X-Admin-Key`):

- `GET /v1/admin/divergences?status=&origin=&kind=&merchant_id=&since=&after=&limit=` — the divergence queue
  (reconciliation and disputes); `GET /v1/admin/divergences/{id}`; `POST /v1/admin/divergences/{id}/review`;
  `POST /v1/admin/divergences/{id}/resolve`.
- `GET /v1/admin/jobs?status=&type=&limit=` — `DEAD` first; `POST /v1/admin/jobs/{id}/run-now` makes a
  `PENDING` or `DEAD` job due now (a `DONE` job is `409 JOB_NOT_RERUNNABLE`: not every handler is idempotent);
  `POST /v1/admin/jobs/{id}/give-up` moves a `PENDING` job to `DEAD` with the operator's note.
- `GET /v1/admin/payments/stuck` — payments left in `CREATED` too long and `PENDING` payments past their expiry.

Metrics are served at `/actuator/prometheus` on the **management port** (`GATEWAY_MANAGEMENT_PORT`, default
`9090`), not on the API port. That endpoint answers without a key: **never publish the management port** —
scrape it from inside the network. The gauges are recounted every `gateway.metrics.refresh-ms` (default 30 s),
not on scrape.

| Metric | Labels | Suggested alert |
|---|---|---|
| `gateway_payments` (gauge) | `status`, `method`, `provider`, `environment` | — (volume dashboards) |
| `gateway_payments_stuck` | `kind` = `created_too_long`, `pending_past_expiry` | `created_too_long` > 0 |
| `gateway_divergences_open` | `origin` (`SYSTEM`, `MERCHANT`), `kind` | > 0 for 24 h (a divergence open over a day) |
| `gateway_jobs` | `status` = `PENDING`, `DEAD`; `type` = every `JobType` (`DONE` rows are history and not counted) | `DEAD` > 0 |
| `gateway_jobs_overdue` | — | > 10 (`PENDING` jobs due for over 5 minutes: the runner is behind) |
| `gateway_webhook_deliveries` | `status` | — |
| `gateway_outbox_pending` | — | — (growing steadily means the relay stopped) |
| `gateway_provider_call_seconds` (timer: `_count`, `_sum`, `_max`, `_bucket`) | `provider`, `operation`, `outcome` = `ok`, `provider_error`, `timeout`, `unexpected` | p95 > 5 s (`histogram_quantile` over `_bucket`) |

A label set that stops appearing in the database keeps reporting `0` rather than disappearing.

#### Runbook: divergence kinds

A divergence never moves money or a payment's state by itself: the operator checks the bank or the Cielo, acts
through the normal API (a refund, a capture, a cancel) when money has to move, and then closes the row with
`resolve` — `CONFIRMED` or `FALSE_POSITIVE` for a `SYSTEM` row, `RESOLVED` or `REJECTED` for a dispute.

| Kind | What happened | What the operator does |
|---|---|---|
| `AMOUNT_MISMATCH` | A Pix or a paid boleto arrived with an amount different from the charge; the payment was not completed. | Agree the amount with the merchant; refund the payer or settle the difference outside the gateway. |
| `BOLETO_PAID` | A boleto was paid at the bank while the payment is `FAILED` or `CANCELED`. | Tell the merchant the money arrived; refund the payer if the sale is not going ahead. |
| `BOLETO_REJECTED` | The bank refused one payment attempt of a boleto; the boleto is still open for the payer. | Usually nothing: watch for the next attempt; `FALSE_POSITIVE` once paid or expired. |
| `CANCELED_AT_BANK` | The boleto was written off (baixa) at the bank outside the gateway; the gateway did not move the payment. | Find out who wrote it off; cancel the payment through the API if that is what the merchant wants. |
| `CARD_ACTIVE_AT_PROVIDER` | The Cielo shows the sale authorized or paid while the gateway has it `FAILED` or `CANCELED`. | Void or refund the sale at the Cielo so the cardholder is not charged. |
| `CAPTURE_OVERDUE` | A card authorization older than the capture deadline is still only authorized at the Cielo. | Ask the merchant to capture or void before the authorization lapses. |
| `DOUBLE_PAYMENT` | Two payments settled one order, or a Bolecode was paid both by Pix and by boleto. | Confirm both credits at the bank and refund one of them. |
| `FRAUD_ALERT` | The Cielo notified a fraud alert (ChangeType 8) on the sale. | Review the sale with the merchant; refund or void it if it is fraudulent. |
| `NOT_FOUND_AT_BANK` | Two or more boleto polls found the boleto unknown to the bank. | Check the registration at Itaú; cancel the payment if the boleto never existed. |
| `NOT_FOUND_AT_PROVIDER` | The Cielo returns nothing for the sale's PaymentId inside the reconciliation window. | Look the sale up in the Cielo backoffice; cancel the payment if it was never created. |
| `PAID_AFTER_CLOSE` | A payment attempt was paid after its order was canceled or expired. | Refund the payer, or reopen the sale with the merchant. |
| `PARTIAL_REFUND_AT_PROVIDER` | The Cielo notified a partial refund that no refund made through the gateway explains. | Compare with the Cielo backoffice; record what happened with the merchant. |
| `PIX_RECEIVED` | A Pix landed on a `FAILED` or `CANCELED` payment, or a second, different Pix on a `COMPLETED` one. | Refund the payer, or reopen the order with the merchant. |
| `PIX_TXID_UNCONFIRMED` | A Bolecode was adopted as pending, but the bank did not confirm its Pix txid. | Check the charge at Itaú; if the Pix part does not exist, the payer can only pay the barcode. |
| `REFUNDED_AT_PROVIDER` | The Cielo shows the sale refunded or voided beyond what the gateway refunded. | Confirm at the Cielo and tell the merchant; the gateway's refunds no longer match the money. |
| `REFUND_UNKNOWN` | A refund's outcome is unknown: a card refund ended in an error that may have landed, or a Pix refund outlived its polling budget. | Check the refund at the bank or the Cielo; never retry it before knowing. |
| `UNCONFIRMED_REFUND_WEBHOOK` | A refund webhook did not match its merchant or the payment's endToEndId; it was ignored. | Check the webhook registration at Itaú; nothing changed in the gateway. |
| `UNCONFIRMED_WEBHOOK` | A Pix webhook said paid, but the bank's own query does not confirm it; the payment was not completed. | Check the charge at Itaú; reconciliation completes it if the bank later shows it paid. |
| `VOID_DENIED` | The Cielo denied a void (ChangeType 5). | Retry the void or refund at the Cielo; the cardholder may still be charged. |
| `DISPUTE` | A merchant opened a dispute (`origin = MERCHANT`). | `review`, investigate, and `resolve` as `RESOLVED` or `REJECTED`; a refund, if due, goes through `/refunds`. |

Pix reconciliation also opens a row named after the bank's charge status (e.g. `CONCLUIDA`) when the amount the
bank shows paid differs from the gateway's `paid_amount`; treat it as `AMOUNT_MISMATCH`.

#### Runbook: job statuses

| Status | Meaning | Operator |
|---|---|---|
| `PENDING` | Waiting for `next_run_at`, or held by a worker inside its lease. | `run-now` makes it due now (`409 JOB_IN_FLIGHT` while a worker holds it); `give-up` moves it to `DEAD` with a note. |
| `DEAD` | Its retries ran out (`last_error` says why), or an operator gave it up. | `run-now` puts it back to `PENDING`, due now, keeping `attempts` and `last_error`. |
| `DONE` | Finished; kept as history. | Nothing: `run-now` is `409 JOB_NOT_RERUNNABLE`, because not every handler is idempotent. |

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
- **Billing jobs.** `EXPIRE_ORDER`, `BILL_SUBSCRIPTION` (one cycle) and `DUNNING_RETRY` run in the same job
  runner but never go `DEAD`: once the backoff is spent they retry every `gateway.billing.order-expiry-recheck`
  and log an error for an operator, because a dead row would stop billing or chasing an invoice silently.

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
- `card.interest_amount` (cents) is the installment interest inside `amount`: always 0 here, where you
  choose the amount; an order's card attempt sets it (see
  [Installments with interest](#installments-with-interest)).

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
| `GET /v1/customers?limit=&cursor=` | 200, active customers of the key's environment, newest first | 400 on a `limit` outside 1–100 |
| `GET /v1/customers?document=` | 200, a list of 0 or 1 | 400 on an invalid document, or with `cursor` |
| `PATCH /v1/customers/{id}` (`name`, `email`, `address`) | 200 | 400 on an empty body or an unknown field (`document` is immutable) |
| `DELETE /v1/customers/{id}` | 204 | 409 `CUSTOMER_HAS_ACTIVE_SUBSCRIPTION` |
| `GET /v1/customers/{id}/cards` | 200 | |
| `POST /v1/orders` * | 201 `OPEN` | 400 when both or neither of `customer_id`/`customer` are sent |
| `POST /v1/orders/{id}/payments` * | 201 payment | 409 `ORDER_CLOSED`; 409 `ORDER_HAS_ACTIVE_PAYMENT` (+`payment_id`); 402 `CARD_DECLINED`; 422 `INVALID_INSTALLMENTS` |
| `POST /v1/orders/{id}/cancel` * | 200 `CANCELED` | 409 `ORDER_CLOSED`; 409 `ALREADY_PAID` |
| `POST /v1/orders/{id}/checkout-token/rotate` * | 200 with a new `checkout_url` | 409 `ORDER_CLOSED` |
| `GET /v1/orders?limit=&cursor=&status=` | 200, orders of the key's environment, newest first | 400 on an unknown `status` |
| `GET /v1/orders/{id}`, `GET /v1/orders?reference=&limit=`, `GET /v1/orders/{id}/payments` | 200 | 400 when `reference` comes with `cursor` or `status` |
| `POST /v1/plans` * | 201 | 400 on a range error |
| `GET /v1/plans/{id}`, `GET /v1/plans?active=` | 200 | |
| `PATCH /v1/plans/{id}` (`name`, `active`) | 200 | 422 `PLAN_IMMUTABLE` naming the field |
| `POST /v1/subscriptions` * | 201 `ACTIVE` | 404; 422 `PLAN_INACTIVE`, `CARD_REQUIRED`, `CARD_NOT_OWNED_BY_CUSTOMER`, `CUSTOMER_ADDRESS_REQUIRED` |
| `GET /v1/subscriptions/{id}`, `GET /v1/subscriptions?customer_id=` | 200 | |
| `POST /v1/subscriptions/{id}/cancel` * `{"at_period_end": true}` (default) | 200 | 409 `SUBSCRIPTION_NOT_ACTIVE` |
| `PATCH /v1/subscriptions/{id}` (`method`, `card_id`) | 200 | the 422s of create; 409 `SUBSCRIPTION_NOT_ACTIVE` |
| `GET /v1/subscriptions/{id}/orders` | 200, newest first | |
| `GET /v1/installment-settings` | 200, the key's environment (the default when never set) | |
| `PUT /v1/installment-settings` | 200 | 400 on a missing field or a range error |

`*` requires an `Idempotency-Key` (400 `IDEMPOTENCY_KEY_REQUIRED` otherwise).

Lists page by `cursor`, the `id` of the last item of the previous page (ids are time-ordered), like
`GET /v1/payments`. A TEST key lists only TEST orders and customers, a LIVE key only LIVE ones. Every
order carries `customer_name`: the customer's name, or the inline payer's on an order created without
a customer; null once the customer was deleted.

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

#### Installments with interest

How an order's card attempt is split is the merchant's, per environment of the key (a TEST rate never applies
in LIVE): up to `max_installments` (1–12), the first `interest_free_up_to` (1–`max_installments`) without
interest, the rest at `monthly_rate_bps` a month (0–1000; 2,99% is `299`). Never set, it is 12 installments,
all interest-free. The `PUT` replaces the whole settings (every field required, no `Idempotency-Key`) and
emits `installment_settings.updated`.

```bash
curl -X PUT localhost:8080/v1/installment-settings -H "Authorization: Bearer $TEST_KEY" \
  -H 'Content-Type: application/json' \
  -d '{"max_installments": 10, "interest_free_up_to": 3, "monthly_rate_bps": 299}'
# 200 {"environment": "TEST", "max_installments": 10, "interest_free_up_to": 3, "monthly_rate_bps": 299,
#      "updated_at": "2026-10-07T12:00:00.123456Z"}
```

The gateway prices every option and the attempt charges the total of the one chosen in `installments`,
recomputed from the settings (no total is ever taken from the body):

- up to `interest_free_up_to`: total = the order's amount; `installment_amount` = amount / n, truncated to the
  cent (the Cielo spreads the cents left over);
- above it: Price table, `installment = amount × i / (1 − (1 + i)^−n)` with `i` the monthly rate, **rounded up
  to the cent**, and total = installment × n, so the Cielo's own division gives the same installment;
- an option whose installment is under R$ 5,00 is not offered (1x always is). A count not offered is
  `422 INVALID_INSTALLMENTS`.

R$ 100,00 at 2,99% in 6x: 10000 × 0.0299 / (1 − 1.0299^−6) = 1845.36 → 1846 a month, 11076 in all. The
attempt's payment then has `amount: 11076` and `card.interest_amount: 1076` (total − order amount, also in the
`payment.*` events); the order keeps `amount: 10000`, and a total refund returns the 11076 charged. A
subscription cycle is always 1x at the plan's price; `POST /v1/payments` is not priced (`interest_amount: 0`).

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

### Public checkout

Every order is born with a link for the payer: `checkout_url` in the `POST /v1/orders` response,
`<GATEWAY_CHECKOUT_BASE_URL><token>` (default base `http://localhost:5173/pay/`, the front's `/pay/` route).
The token is shown **once**: the row keeps only its hash, like an API key. `GET /v1/orders/{id}` returns
`checkout_url: null`. Lost it? `POST /v1/orders/{id}/checkout-token/rotate` (needs an `Idempotency-Key`; `409
ORDER_CLOSED` on a closed order) issues a new one and the old link stops working. Orders created before this
feature have no link until rotated. Logs mask tokens as `chk_****`.

The payer's routes need no key and no `Idempotency-Key` — the token is the authorization — and are limited per
client IP (`gateway.checkout.rate-limit-per-minute`, 60; see the proxy note under [Run](#run)):

| Route | Success | Errors |
|---|---|---|
| `GET /v1/checkout/{token}` | 200 `{order_id, merchant_name, amount, currency, description, status, expires_at, methods, installment_options, active_payment}` | 404 `NOT_FOUND` |
| `POST /v1/checkout/{token}/payments` (same body as `POST /v1/orders/{id}/payments`) | 201 payment | 410 `CHECKOUT_ORDER_CLOSED`; 409 `ORDER_HAS_ACTIVE_PAYMENT`; 402 `CARD_DECLINED`; 422 `PROVIDER_CREDENTIALS_MISSING`, `INVALID_INSTALLMENTS` |
| `GET /v1/checkout/{token}/payments/{id}` | 200 payment | 404 `NOT_FOUND` |
| `POST /v1/checkout/{token}/payments/{id}/cancel` | 200 payment `CANCELED` (Pix and boleto) | 410 `CHECKOUT_ORDER_CLOSED`; 422 `CHECKOUT_CANNOT_CANCEL_CARD` |

The payment object is `{id, method, status, pix: {copia_e_cola, expires_at}, boleto: {linha_digitavel,
due_date, payment_limit_date}, card: {brand, last4, installments, interest_amount}, paid_at, created_at}`, with
only the block of its method filled. `methods` lists the methods the merchant has a credential for.
`installment_options` is `[{count, installment_amount, total, interest_free}]`, ascending, priced by the
merchant's [installment settings](#installments-with-interest), and empty when `CARD` is not in `methods`;
the page shows these values as they are and sends the chosen `count` as `installments`. A closed order still
answers `GET` with 200 and its `status`; only the `POST`s are `410`. Error bodies never echo the token.
Responses carry no payer data, no provider ids and no merchant reference.

Payment events of an attempt made through the link currently carry `source: API`, like the merchant's own
attempts; telling payer-initiated attempts apart in events is not available yet.

A browser front on another origin needs `GATEWAY_CORS_ORIGINS` (comma-separated exact origins; empty = CORS
off). Allowed request headers are `Content-Type`, `Authorization` and `Idempotency-Key`; `X-Next-Cursor` and
`Retry-After` are exposed; credentials are not allowed. The merchant key travels as `Authorization: Bearer`.

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

## Documentation map

- `docs/architecture.md` — modules, import rules, state machines, jobs, with diagrams (`docs/diagrams/`).
- `docs/superpowers/specs/` — designs, by date:
  - 2026-09-23 payment gateway — the orchestrator model, modules, payments, webhooks.
  - 2026-09-25 bolecode — one `BOLECODE` method, settled by QR or by barcode poll.
  - 2026-09-25 payment method and provider strategy — one flow per method, one provider contract.
  - 2026-09-28 card via Cielo — authorization, capture, void, refund, saved cards.
  - 2026-10-02 orders, plans and subscriptions — billing, cycles, dunning without cancel.
  - 2026-10-04 outbound webhooks — delivery log, redelivery, the documented contract.
  - 2026-10-04 operations and disputes — design only (plan G).
  - 2026-10-04 security and operators — design only (plan H).
- `docs/superpowers/plans/` — how each spec was built; indexed in `docs/superpowers/README.md`.
- `docs/superpowers/DECISOES.md` — append-only decisions with the rejected alternative and the cost of being wrong.
- `docs/providers/itau/NOTES.md`, `docs/providers/cielo/NOTES.md` — provider facts and sandbox limits.
- `docs/e2e/` — sandbox happy-path reports (2026-09-30, 2026-10-02).

## Build

`./mvnw verify` (Testcontainers; needs Docker). The `com.barrier:webhook-delivery` library comes from GitHub
Packages: `~/.m2/settings.xml` with server `github-webhook-delivery` and a PAT with `read:packages`. CI uses the
`PACKAGES_READ_TOKEN` repository secret for the same reason.
