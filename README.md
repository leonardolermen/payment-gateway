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

## Build

`./mvnw verify` (Testcontainers; needs Docker). The `com.barrier:webhook-delivery` library comes from GitHub
Packages: `~/.m2/settings.xml` with server `github-webhook-delivery` and a PAT with `read:packages`. CI uses the
`PACKAGES_READ_TOKEN` repository secret for the same reason.
