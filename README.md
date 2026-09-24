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
curl -s localhost:8080/v1/me -H 'Authorization: Bearer gk_test_…'
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

`POST /v1/payments`, `/cancel` and `/refunds` require an `Idempotency-Key` header, at most 123
characters. A repeated key with the same request body and method replays the stored response
(`Idempotent-Replayed: true`); the same key with a different body is `422 IDEMPOTENCY_KEY_REUSED`; a key
still being processed, or one whose first attempt failed with a 5xx and is held open, is `409 IN_PROGRESS`
— retry with a new key, or `GET` the resource to see what happened. The key is scoped per environment
(TEST and LIVE never share a key row), so it is safe to script tests against TEST and LIVE with the same
key values.

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

### Sandbox

With sandbox credentials from the Itaú for Developers portal stored as a merchant's `TEST` credential,
`POST /v1/payments` with a `gk_test_` API key hits the real Itaú sandbox — there is no local stand-in for
the provider, so a TEST-environment run is a real (if non-production) integration.

## Build

`./mvnw verify` (Testcontainers; needs Docker). The `com.barrier:webhook-delivery` library comes from GitHub
Packages: `~/.m2/settings.xml` with server `github-webhook-delivery` and a PAT with `read:packages`. CI uses the
`PACKAGES_READ_TOKEN` repository secret for the same reason.
