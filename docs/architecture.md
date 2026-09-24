# Architecture

One deployable, four business modules, one library. The boundary between modules is enforced by `ArchitectureTest` (ArchUnit) — a diagram that lied would fail the build.

## Modules and who may import whom

```mermaid
flowchart TB
  subgraph app["gateway-app — the only deployable"]
    REST["REST API<br/>/v1/payments, /v1/webhooks, /v1/admin, /v1/me"]
    SEC["security<br/>PathSanityFilter · AdminKeyFilter · ApiKeyAuthFilter · RateLimitFilter"]
    MTLS["mTLS connector :8443<br/>/v1/providers/itau/webhooks/{token}/pix"]
    RELAY["OutboxRelay + JobScheduler<br/>(@Scheduled)"]
    WIRING["ProviderWiring<br/>CredentialLookup adapter · Clock"]
  end

  subgraph payments["gateway-payments (schema: payments)"]
    PS["PaymentService · RefundService<br/>IdempotencyService · ProviderGateway"]
    WI["WebhookInboxService<br/>ExpirationService · ReconciliationService · RefundPollingService"]
    JR["JobRunner"]
    PD["domain: Payment (state table) · PaymentEvent · Refund · IdempotencyKey"]
    PR["repositories: payments · payment_events · refunds · idempotency_keys<br/>outbox · jobs · webhook_inbox · provider_requests · reconciliation_divergences"]
  end

  subgraph merchants["gateway-merchants (schema: merchants)"]
    MS["MerchantService · ApiKeyService · ProviderCredentialService"]
    ENV["EnvelopeCipher (AES-256-GCM, DEK per row, master key)"]
    MR["repositories: merchants · api_keys · provider_credentials"]
  end

  subgraph providers["gateway-providers"]
    IP["ItauPixProvider"]
    PAC["PixApiClient (Bacen/Itaú v2 contract)"]
    TOK["ItauTokenClient (OAuth2 client credentials; mTLS in LIVE, plain in sandbox)"]
  end

  subgraph kernel["gateway-kernel — imports nothing"]
    K1["Money · Ulid · MerchantId · Secret · DomainException"]
    K2["provider contracts: PixProvider · CredentialLookup · Charge · RefundResult · ProviderException"]
  end

  WD[["com.barrier:webhook-delivery<br/>(signed outbound webhooks, retry, ordering)"]]
  PG[("PostgreSQL<br/>one schema per module + webhook_delivery")]
  ITAU(["Itaú Pix API<br/>sandbox / production"])

  app --> payments
  app --> merchants
  app --> providers
  app --> WD
  payments --> kernel
  merchants --> kernel
  providers --> kernel
  payments -. "only the interfaces<br/>PixProvider · CredentialLookup" .-> K2
  PAC --> ITAU
  TOK --> ITAU
  PR --> PG
  MR --> PG
  WD --> PG
```

Rules the test enforces: `kernel` imports nothing; nobody imports `app`; `merchants`, `payments` (and later `orders`) do not import each other; only `app` imports `providers`; `payments` sees the bank only through `PixProvider`; Itaú vocabulary (`cob`, `txid`, `devolucao`) never leaves `gateway-providers`.

## Creating a Pix charge

```mermaid
sequenceDiagram
  autonumber
  participant M as Merchant
  participant API as app: PaymentsController + IdempotencyFilter
  participant PS as payments: PaymentService
  participant DB as PostgreSQL (payments)
  participant PG as ProviderGateway
  participant IT as providers: ItauPixProvider
  participant BANK as Itaú

  M->>API: POST /v1/payments {amount, reference} + Idempotency-Key
  API->>DB: INSERT idempotency_keys (IN_PROGRESS) ON CONFLICT DO NOTHING
  alt key already DONE with same body hash
    API-->>M: replay of the stored 201 (Idempotent-Replayed: true)
  else key IN_PROGRESS / different hash
    API-->>M: 409 IN_PROGRESS / 422 IDEMPOTENCY_KEY_REUSED
  end
  API->>PS: createCharge(merchant, env, amount, …)
  PS->>PG: resolve(merchant, env, ITAU) — credentials decrypted for this call only
  PS->>DB: tx: Payment CREATED (id = txid) + event 'created'
  PS->>IT: createCharge(credentials, txid, amount)  (outside any transaction)
  IT->>BANK: PUT /cob/{txid}  (Bearer token, x-itau-apikey)
  alt 201
    BANK-->>IT: ATIVA + pixCopiaECola
  else timeout / unavailable
    IT->>BANK: GET /cob/{txid} — did the PUT land?
    BANK-->>IT: found (adopt) / not found (FAILED, best-effort cancel)
  end
  PS->>DB: tx: PENDING + event + outbox 'payment.pending' + job EXPIRE_PAYMENT
  API->>DB: idempotency key DONE with the response body
  API-->>M: 201 {id, status: PENDING, pix.copia_e_cola, expires_at}
  Note over DB,M: OutboxRelay → MerchantEvents → webhook-delivery → signed POST to the merchant
```

## Getting paid

```mermaid
sequenceDiagram
  autonumber
  participant BANK as Itaú
  participant MT as app: mTLS connector + ItauWebhookController
  participant IN as payments: WebhookInboxService
  participant DB as PostgreSQL
  participant JR as JobRunner
  participant M as Merchant

  BANK->>MT: POST /v1/providers/itau/webhooks/{token}/pix (client certificate from Itaú's CA)
  MT->>IN: accept(raw headers + body)
  IN->>DB: tx: webhook_inbox RECEIVED + job PROCESS_WEBHOOK
  MT-->>BANK: 202 (well inside the bank's 5 s)
  JR->>IN: process(inboxId)
  IN->>DB: per Pix, in its own tx: payment PENDING|EXPIRED → COMPLETED (source PROVIDER_WEBHOOK), event, outbox 'payment.completed'
  Note over IN,DB: duplicate e2eid → 'ignored' event · paid while FAILED/CANCELED → reconciliation divergence, nothing sent
  DB-->>M: (relay) payment.completed, ordered after payment.pending by partition key
```

Backstops that do not depend on the webhook:

- **Expiration job** (`expires_at` + 5 min): asks the bank first (`GET /cob/{txid}`); only an unpaid charge becomes `EXPIRED`.
- **Reconciliation job** (every 15 min): lists the bank's charges for the window; `PENDING|EXPIRED × CONCLUIDA` completes the payment; anything else contradictory opens a `reconciliation_divergences` row for a human.
- **Refund polling** (every 5 min, up to 24 h): Itaú's refund is asynchronous; `EM_PROCESSAMENTO` is polled until `DEVOLVIDO`/`NAO_REALIZADO`.

## Payment states

```mermaid
stateDiagram-v2
  [*] --> CREATED: POST /v1/payments
  CREATED --> PENDING: bank accepted (API) / adopted by sweeper (SYSTEM)
  CREATED --> FAILED: bank refused, or timeout and charge not found
  PENDING --> COMPLETED: webhook or reconciliation
  PENDING --> EXPIRED: expiration job (after asking the bank)
  PENDING --> CANCELED: POST /cancel
  EXPIRED --> COMPLETED: late settlement — the bank wins
  COMPLETED --> COMPLETED: refunds are a projection (refunded_amount), not a transition
  COMPLETED --> [*]
  CANCELED --> [*]
  FAILED --> [*]
```

Every transition is a row in `PaymentTransitions` with the sources allowed to trigger it; a test walks the whole cross product. Every change appends a `payment_events` row whose `sequence` is the aggregate's version (optimistic lock) — the current state is reconstructible from the log.

## Environments and credentials

| environment (from the API key) | provider endpoint | credential shape |
|---|---|---|
| `TEST` (`gk_test_…`) | Itaú sandbox, token at `/api/oauth/jwt`, no mTLS | `{client_id, client_secret, pix_key}` |
| `LIVE` (`gk_live_…`) | Itaú production, STS token over mTLS, `x-itau-apikey` | `{client_id, client_secret, x_itau_apikey, certificate_pem, private_key_pem, pix_key}` |

Credentials are stored per `(merchant, provider, environment)` in an AES-256-GCM envelope (fresh DEK per row, AAD = `merchant|provider|environment`, master key from `GATEWAY_MASTER_KEY`) and decrypted only for the duration of a bank call.

## Where things live

- Specs and decisions: `docs/superpowers/specs/`, `docs/superpowers/DECISOES.md`
- Bank facts: `docs/providers/itau/NOTES.md` (+ the official OpenAPI next to it)
- Plans executed: `docs/superpowers/plans/`
