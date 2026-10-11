# Provider credentials in self-service (B4 part 1) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An owner configures Itaú and Cielo credentials per environment from the panel, tests the connection, sets the Cielo notification key and sees the webhook URL to register at the bank — without an operator.

**Architecture:** New merchant routes under `/v1/merchant/providers` (session + OWNER only). The credential JSON keeps going through `ProviderCredentialService` (encrypted); a `SecretMerge` keeps omitted secrets from the stored payload. A kernel port `CredentialProbe` is implemented in `gateway-providers` (Itaú token fetch, Cielo auth-only query) and indexed in the app (`CredentialProbes`). Probe results live on the credential row (V104).

**Tech Stack:** Java 25, Spring Boot 4, JPA, Flyway, WireMock, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-09-provedores-self-service-design.md` — base branch `feat/users-and-sessions` (B3).

## Global Constraints

- English code/comments/commits; 100 columns; `./mvnw spotless:apply`; `./mvnw verify` green (JDK 25: `export JAVA_HOME="C:\\Users\\leona\\.jdks\\corretto-25.0.4.1"`). `spotless:apply` reflows `gateway-payments/.../payment/create/CardPaymentFlow.java` on this branch — revert it, it is not ours.
- ArchUnit: `kernel` imports nothing; `providers` only `kernel`; `merchants` imports neither `payments`/`billing`/`app`; nothing outside `payments`/`providers`/`app` imports `providers`; no "Itau"/"Cielo" in class names outside `providers`; outside `..persistence..` only `*Service|*Gateway|*Properties|*Configuration|*Events|*Runner|*Relay` may import Spring.
- Secrets never in logs, error messages, `provider_requests`, or responses: `PUT` answers 204; `GET` exposes `secrets_set` booleans and an 8-hex fingerprint prefix only; `last_test_detail` is one of the fixed phrases below, never a bank body.
- Error codes are contract: `PROVIDER_CREDENTIALS_INVALID` 422 (+`field`), `PROVIDER_CREDENTIALS_MISSING` 404, `USER_SESSION_REQUIRED` 403, `FORBIDDEN_FOR_ROLE` 403.
- `/v1/providers/**` is the banks' inbound surface — the merchant routes are `/v1/merchant/providers/**`, which `RoleRoutes` makes OWNER (prefix `/v1/merchant`) and this plan makes `userOnly`.
- Secret fields: ITAU `client_secret`, `x_itau_apikey`, `private_key_pem`; CIELO `merchant_key`. Omitted in a `PUT` → kept from the stored payload; `""` → removed.
- Probe phrases (pt-BR, fixed): ok → `"Conectado"`; 401/403 → `"Credencial recusada pelo banco"`; bad PEM → `"Certificado ou chave privada inválidos"`; timeout/unavailable → `"O banco não respondeu"`; incomplete shape → `"Credencial incompleta: <field>"`.

## Review Focus

1. A `PUT` with `client_secret` omitted must keep the stored secret byte-for-byte (decrypt proves it) and a `PUT` with `""` must drop it — Task 2 (`SecretMergeTest`, `ProvidersApiIntegrationTest.anOmittedSecretIsKept`).
2. The LIVE shape rule must apply from the `X-Environment` header, never the body — Task 3 (`ProvidersApiIntegrationTest.liveNeedsTheCertificateEvenIfTheBodySaysTest`).
3. A `gk_` API key on any `/v1/merchant/providers` route gets `403 USER_SESSION_REQUIRED`; FINANCE gets `403 FORBIDDEN_FOR_ROLE` — Task 3.
4. The probe must never write to the bank: WireMock sees only the token call (Itaú) / one GET (Cielo) — Task 4.
5. `GET` must never contain a secret value even after a `PUT`, and the audit line never carries one — Task 3 (`ProvidersApiIntegrationTest.theGetNeverEchoesASecret`).

---

### Task 1: Migration V104, probe result on the credential, kernel `CredentialProbe` port

**Files:**
- Create: `gateway-merchants/src/main/resources/db/migration/merchants/V104__provider_credential_test.sql`
- Modify: `gateway-merchants/.../credential/ProviderCredential.java` (+ `ProbeOutcome lastTest` record: `ok`, `detail`, `checkedAt`; `withLastTest(…)`), `credential/persistence/{ProviderCredentialEntity,ProviderCredentialRepositoryImpl}.java`, `ProviderCredentialService.java` (+ `recordTest(MerchantId, Provider, ApiKeyEnvironment, ProbeOutcome)`, `Optional<ProviderCredential> find(…)`)
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/provider/{CredentialProbe,ProbeResult}.java`
- Test: `gateway-merchants/src/test/.../credential/ProviderCredentialTestIntegrationTest.java` (store → recordTest → find shows it)

```sql
-- V104: the last "test connection" outcome lives on the credential, so the panel shows
-- "conectado em …" without calling the bank on every open. detail is one of our fixed phrases.
ALTER TABLE merchants.provider_credentials
    ADD COLUMN last_test_ok BOOLEAN,
    ADD COLUMN last_test_detail VARCHAR(120),
    ADD COLUMN last_test_at TIMESTAMPTZ;
```
```java
// kernel/provider/CredentialProbe.java
/** Asks the bank whether a credential authenticates, without issuing or changing anything. */
public interface CredentialProbe {
  String providerId();

  ProbeResult probe(ProviderCredentials credentials);
}
// kernel/provider/ProbeResult.java
public record ProbeResult(boolean ok, String detail) {}
```
Commit: `feat(credentials): the last connection test lives on the credential; a kernel probe port`.

---

### Task 2: `SecretMerge` and the merchant-facing store with validation

**Files:**
- Create: `gateway-merchants/.../credential/SecretMerge.java` (+ `SecretMergeTest`): `static byte[] merge(byte[] submitted, Optional<byte[]> stored, Set<String> secretFields)` on JSON objects — for each secret field: absent in `submitted` → copy from `stored` if present; `""` → remove; otherwise keep submitted. Non-secret fields: submitted wins, absent → absent.
- Create: `gateway-app/.../api/provider/{ProviderCatalog,CredentialShape}.java`: `ProviderCatalog` maps `Provider` → methods and secret fields (`ITAU → [PIX, BOLECODE], {client_secret, x_itau_apikey, private_key_pem}`; `CIELO → [CARD], {merchant_key}`; `FAKE` is not listed → 400). `CredentialShape.validate(Provider, ProviderEnvironment, byte[] json)` calls `ItauCredentials.parse(json)` (+ `requireProductionShape()` when LIVE) / `CieloCredentials.parse(json)` and maps their `IllegalArgumentException` message to `DomainException("PROVIDER_CREDENTIALS_INVALID", message)` with `field` = the first token of the message (the parsers already name the field, e.g. `merchant_key is required`) — add `field` to `DomainException`? No: `ErrorHandler` already serialises `field` from `FieldedDomainException`? Check `api/support/ErrorHandler` for how `field` reaches the problem; if nothing exists, add `ProviderCredentialsInvalid extends DomainException` carrying `field` and a handler mapping it to 422 with `field`.
- Create: `gateway-app/.../api/provider/MerchantProviderService.java` (`@Service`, `@Transactional`): `store(MerchantId, Provider, ApiKeyEnvironment, byte[] submitted)` = merge → validate → `credentials.store`; `audit`.
- Test: `SecretMergeTest` (kept / replaced / removed / non-secret absent stays absent), `CredentialShapeTest` (Itaú TEST ok; LIVE missing `private_key_pem` → code + field; Cielo 39-char key → field `merchant_key`).

Commit: `feat(providers): merchant-side credential store — secrets merge, shape validated per environment`.

---

### Task 3: Routes — `GET`, `PUT credentials`, `PUT notification-key`; `RoleRoutes` user-only; audit

**Files:**
- Create: `gateway-app/.../api/provider/ProvidersController.java` + `dto/{ProvidersResponse,ProviderStatusResponse,ProbeOutcomeResponse,CredentialsRequest,NotificationKeyRequest}.java`
- Modify: `security/RoleRoutes.java` (`userOnly` += `/v1/merchant/providers`; remove `/v1/providers` from `OWNER_PREFIXES`; `RoleRoutesTest` cases), `api/auth/AuthEvents.java` (+ `providerCredentialsSet`, `providerTested`, `notificationKeySet`), `README.md` (route table rows)
- Test: `gateway-app/src/test/java/com/gateway/app/ProvidersApiIntegrationTest.java` — header like `AuthApiIntegrationTest` (signup owner, verify via GreenMail link, `X-Environment`), plus a FINANCE invitee and an API key from the admin route:
  - `anOwnerStoresItauTestCredentialsAndTheGetShowsStateNotSecrets`
  - `anOmittedSecretIsKept` (PUT without `client_secret`; then `ProviderCredentialService.decrypt` via `@Autowired` shows the old secret)
  - `anEmptySecretIsRemoved`
  - `liveNeedsTheCertificateEvenIfTheBodySaysTest` (`X-Environment: LIVE`, body with `"environment":"TEST"` ignored → 422 field `certificate_pem`)
  - `theGetNeverEchoesASecret` (dump the GET body as text; `doesNotContain` each secret)
  - `financeIsForbiddenAndAnApiKeyNeedsASession` (403 codes)
  - `notificationKeyIsStoredAndTooLongIsRefused`
  - `GET` includes `inbound_webhook_url` (null when mTLS port is 0 in tests — assert the key exists).
- `GET` assembly: for each provider in the catalog, `credentials.find(merchant, provider, env)` → `configured`, `updated_at`, `fingerprint` = first 8 of `Sha256.hex(decrypted json)`? No — never decrypt for a GET; store the fingerprint: add `fingerprint CHAR(64)` to V104 (Task 1 — amend the migration there: `ADD COLUMN fingerprint CHAR(64)`) computed at `store` time from the merged plaintext; `secrets_set` computed at store time too → `secrets_set JSONB` column. Ruling for the executor: **Task 1's V104 adds `fingerprint CHAR(64)` and `secrets_set JSONB` as well**, and `ProviderCredentialService.store` receives them (`store(…, byte[] plaintext, String fingerprint, Map<String,Boolean> secretsSet)`); the admin route passes computed values too.

Commit: `feat(api): /v1/merchant/providers — status, credentials and the Cielo notification key, owner-only`.

---

### Task 4: Probes — Itaú and Cielo implementations, `CredentialProbes`, `POST …/test`

**Files:**
- Create: `gateway-providers/.../itau/auth/ItauCredentialProbe.java` (`CredentialProbe`; `providerId() = "ITAU"`; `probe`: `ItauCredentials.parse` + `requireProductionShape` when LIVE → on `IllegalArgumentException` → `ProbeResult(false, "Credencial incompleta: <field>")`; `tokens.tokenFor(creds, ItauEndpoints.forEnvironment(env), trustStore)` → ok `"Conectado"`; `ProviderException` `UNAUTHORIZED`/4xx → `"Credencial recusada pelo banco"`; PEM/keystore failure → `"Certificado ou chave privada inválidos"`; `TIMEOUT`/`UNAVAILABLE` → `"O banco não respondeu"`), `cielo/auth/CieloCredentialProbe.java` (`GET {apiQuery}/1/sales/00000000-0000-0000-0000-000000000000` via `CieloHttp.send`; 404 → ok; 401 → recusada; timeout → não respondeu; other → `"O banco respondeu de forma inesperada"`), beans in `ProvidersConfiguration`.
- Create: `gateway-app/.../api/provider/CredentialProbes.java` (indexes `List<CredentialProbe>` by `providerId`, refuses duplicates — same shape as `JobHandlers`), `ProvidersController.test(...)`: find credential (404 `PROVIDER_CREDENTIALS_MISSING`) → decrypt → `probe` → `credentials.recordTest` → audit → `200 {ok, detail, checked_at}`.
- Test: unit `CredentialProbesTest`; integration in `ProvidersApiIntegrationTest`: Itaú token stub 200 → ok + `last_test` in GET; stub 401 → false + phrase; Cielo sales stub 404 → ok, 401 → false; WireMock `verify` that only the token / the one GET was called; no credential → 404.

Commit: `feat(providers): test connection — Itaú token, Cielo auth-only query, result on the credential`.

---

### Task 5: Docs, DECISOES, full verify

- README: "Provedores" section (fields per provider, secrets merge rule, test phrases, the inbound URL, who may); route table rows.
- `DECISOES.md`: the five entries of spec §6.
- `ROADMAP-PAINEL.md` is not on this branch (skip; say so).
- `./mvnw verify` green; commit `docs: provider self-service in the README and DECISOES`.

## Self-review notes
- Spec coverage: §2 routes (T3, T4), §2.1 fields/merge (T2), §3 probe (T1 port, T4), §4 security (T3 tests, audit), §5 tests spread, §6 decisions (T5). Executor ruling pre-recorded: V104 also carries `fingerprint` and `secrets_set` so the GET never decrypts.
- Interfaces: `ProbeOutcome`/`ProbeResult` (merchants record vs kernel record — the controller converts), `ProviderCatalog`, `SecretMerge.merge`, `CredentialProbes.forProvider(String)` named consistently.
