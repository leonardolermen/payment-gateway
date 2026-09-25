# Itaú — Recebimentos Pix (API regulatória v2) — integration notes

Source: Itaú for Developers portal, product `itau-ep9-api-regulatorio-pix-v2-externo`, docs version 2.27.2 (read on 2026-09-24, logged in). The OpenAPI next to this file was downloaded from the portal's public asset URL. The Bacen normative spec (`../bacen-pix-api-2.10.0.yaml`) is what Itaú implements; where they differ, this file wins.

The older product `itau-ep9-gtw-pix-recebimentos-ext-v2` is being decommissioned (new URLs, new CA, refund flow became asynchronous). Do not target it.

## Base URLs

| environment | base URL |
|---|---|
| production | `https://pix-pj.api.itau.com/regulatorio-pix/v2` |
| sandbox (portal-hosted) | `https://sandbox.devportal.itau.com.br/itau-ep9-api-regulatorio-pix-v2-externo/v2` - token from `https://sandbox.devportal.itau.com.br/api/oauth/jwt` (see below); credentials created in the portal on 2026-09-24 |

Certificate chain and IP ranges are being rotated until 2026-09-15 (portal notice `certificados-apis-expiracao-2026`): the truststore must carry Itaú's new Root/Intermediate CA. Leaf certs need not be pinned.

## Authentication

OAuth 2.0 client credentials over mTLS with a **dynamic certificate** issued by Itaú (valid 365 days; renewable from 30 days before expiry).

```
POST https://sts.itau.com.br/as/token.oauth2
--cert certificado.crt --key ARQUIVO_CHAVE_PRIVADA.key
Content-Type: application/x-www-form-urlencoded
grant_type=client_credentials&client_id=<client_id>&client_secret=<client_secret>
→ { "access_token": "...", ... }   token lifetime 300 s (5 min)
```

Every API call: `Authorization: Bearer <access_token>` + header `x-itau-apikey: <uuid>` (required; regex `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`) + optional `x-itau-correlationID: <uuid>` (audit trail; we send our correlation id as a UUID). The security scheme in the OpenAPI is `APIGatewaySTSAuthorizer` with scopes `cob.write`, `cob.read`, `pix.read`, `pix.write`, `webhook.read`, `webhook.write`, …

### Sandbox authentication (different from production)

The portal-hosted sandbox does **not** use STS/mTLS. The portal's own page (JS chunk of the API reference tab, read 2026-09-24) does:

```
POST https://sandbox.devportal.itau.com.br/api/oauth/jwt
Content-Type: application/x-www-form-urlencoded
grant_type=client_credentials&client_id=<sandbox client_id>&client_secret=<sandbox client_secret>
-> { "access_token": "<jwt>", "expires_in": <seconds> }
```

The sandbox `client_id`/`client_secret` are created per account in the portal ("criar credenciais"), then the API calls go to the sandbox base URL with `Authorization: Bearer <access_token>`. No certificate; no `x-itau-apikey` requirement is documented (send it when present). Consequence for the gateway: the `TEST` credential is `{client_id, client_secret, pix_key}` (+ optional `x_itau_apikey`); certificate and private key are required only for `LIVE`.

Credential issuance for production (done by the merchant, out of band): generate RSA key pair → send public key to the Itaú operations analyst → receive encrypted client id / temporary token / session key by e-mail → decrypt, build CSR (`CN=<client_id>`), `POST https://sts.itau.com.br/seguranca/v1/certificado/solicitacao` with the temporary token → response carries the signed certificate and the **client_secret shown once**. Renewal: `POST https://sts.itau.com.br/seguranca/v1/certificado/renovacao`.

So a merchant's Itaú credential = `{ client_id, client_secret, x_itau_apikey, certificate (PEM), private_key (PEM) }` — five values, all stored encrypted in `provider_credentials`.

## Endpoints we use (Pix immediate charge)

| operation | endpoint | notes |
|---|---|---|
| create charge | `PUT /cob/{txid}` | txid ours, `[a-zA-Z0-9]{26,35}` (a ULID fits); body requires `valor.original` (string `\d{1,10}\.\d{2}`), `chave` (the merchant's Pix key), `calendario.expiracao` (seconds); 201 returns `status=ATIVA`, `pixCopiaECola` (≤512 chars), `location`, `revisao` |
| read charge | `GET /cob/{txid}` | `status ∈ ATIVA, CONCLUIDA, REMOVIDA_PELO_USUARIO_RECEBEDOR, REMOVIDA_PELO_PSP`; when paid, `pix[]` carries `endToEndId`, `valor`, `horario` |
| cancel charge | `PATCH /cob/{txid}` `{"status":"REMOVIDA_PELO_USUARIO_RECEBEDOR"}` | only while `ATIVA`; 400 `CobOperacaoInvalida` otherwise |
| list charges | `GET /cob?inicio&fim[&paginacao.paginaAtual&paginacao.itensPorPagina]` | reconciliation window; history available for 2 years |
| read received pix | `GET /pix/{e2eid}` | |
| list received pix | `GET /pix?inicio&fim` | |
| refund (devolução) | `PUT /pix/{e2eid}/devolucao/{id}` `{"valor":"100.00"}` | id ours (`[a-zA-Z0-9]{26,35}`), **asynchronous**: 201 with `status=EM_PROCESSAMENTO`; final status via `GET /pix/{e2eid}/devolucao/{id}` or webhook. Window 90 days; sum of refunds ≤ original. Recommended client timeout 30 s |
| read refund | `GET /pix/{e2eid}/devolucao/{id}` | `status ∈ EM_PROCESSAMENTO, DEVOLVIDO, NAO_REALIZADO`, `motivo` explains failures |
| webhook register | `PUT /webhook/{chave}` `{"webhookUrl":"https://…"}` | per Pix key; Itaú appends `/pix` to the URL |

Errors are RFC 7807 (`type`, `title`, `status`, `detail`, `violacoes[]`), e.g. `type=https://pix.bcb.gov.br/api/v2/error/CobOperacaoInvalida`. Statuses: 400 invalid, 401 auth (cert/token/apikey), 403, 404 `CobNaoEncontrado`/`PayloadPixNaoEncontrado`, 410 removed, 422 semantic, 503 unavailable, 504 timeout.

## Inbound webhook (Itaú → us)

- Itaú POSTs to `<registered url>/pix` immediately on each received Pix, one by one. Refund status notifications are optional (must be enabled with the account manager).
- **Transport security is mTLS**: Itaú presents a client certificate issued by Itaú's CA (download `ca-cert.zip` from the portal) and our endpoint must be HTTPS and validate that chain. There is no HMAC signature. Our edge must terminate TLS with client-cert verification for the webhook path.
- Must answer within **5 s**; process asynchronously (persist, 200, then work) — exactly the gateway's `webhook_inbox` design.
- Payload: `{"pix":[{"endToEndId","txid","valor","horario","infoPagador","chave","componentesValor"?,"pagador"?,"devolucoes"?}]}`. Dedup key: `endToEndId`. `txid` may be absent (static QR / key transfer) — those are not ours.
- "Webhook exclusivo" (optional) adds `pagador{documento,nome,instituicao,ispb}`.

## Domains

Charge status: `ATIVA`, `CONCLUIDA`, `REMOVIDA_PELO_USUARIO_RECEBEDOR`, `REMOVIDA_PELO_PSP` (portal prose shows `REMOVIDO_…`; the OpenAPI enum uses `REMOVIDA_…` — trust the enum, accept both when parsing). Refund status: `EM_PROCESSAMENTO`, `DEVOLVIDO`, `NAO_REALIZADO`. Refund nature: `ORIGINAL` (normal), `RETIRADA` (saque/troco), `MED_*` (set by the bank).

## Sandbox verification (2026-09-25)

Ran the gateway locally against the portal-hosted sandbox with credentials created in the portal (never committed; they live in the encrypted `TEST` credential of a local merchant):

| call | result |
|---|---|
| `POST /api/oauth/jwt` (client credentials, no mTLS) | token issued |
| `PUT /cob/{txid}` with `valor.original=1.00` | 201, `status=ATIVA`, `pixCopiaECola` and `location` under `spi-h.itau.com.br` |
| `GET /cob/{txid}` | 200 |
| `PATCH /cob/{txid}` `REMOVIDA_PELO_USUARIO_RECEBEDOR` | 200 |

What it proves: the auth flow and the request/response contract are compatible with the real endpoint.

What it does **not** prove: the sandbox is a static mock. It answered with the documentation's own example (`txid=bbba96ad…`, receiver "PMD BASHAR RIO") instead of echoing the txid we sent, and it accepts any `chave`. Paying the QR, receiving the inbound webhook, refunds and reconciliation can only be exercised in production or a fuller sandbox.

Consequence noticed: since commit `548df4b`, the gateway stores its **own** txid (`payment.id()`) for a Pix charge
(`PaymentService.adoptPending`) rather than whatever the bank echoes back; against this mock the bank's example txid differs
from ours, and a mismatch only logs a WARN ("bank echoed txid … keeping ours") instead of being adopted. This closes the
follow-up noted earlier — no longer pending. (Bolecode is different: see the Bolecode section below, where the bank's txid
is the one stored because it derives from the account.)

## What this means for the gateway (Plan B)

1. No fake provider. `TEST` environment = Itaú sandbox base URL with the merchant's sandbox credentials (plain OAuth at `/api/oauth/jwt`, no mTLS); without credentials the call fails with `PROVIDER_CREDENTIALS_MISSING`.
2. Tests never hit the network: WireMock serves fixtures copied from this OpenAPI's examples; every request we build is validated against the OpenAPI schema.
3. Refund is asynchronous: `Refund` goes `REQUESTED → PROCESSING → COMPLETED | FAILED`, closed by the refund-status webhook or by polling `GET …/devolucao/{id}`.
4. Inbound webhook needs mTLS client-cert validation against Itaú's CA at the edge.
5. Token cache per merchant credential, refreshed before the 300 s expiry.

## Bolecode (boleto with Pix) — read 2026-09-25

Four products on the portal; the OpenAPIs are next to this file.

| role | product | version | operation |
|---|---|---|---|
| issue | `itau-ep9-api-recebimentos-v1-externo` | 1.0.7 | `POST /boletos-pix` |
| query | `itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws` | 1.2.9 | `GET /boletos?id_beneficiario&codigo_carteira&nosso_numero` |
| baixa | `itau-ep9-gtw-cash-management-ext-v2` | 2.75.147 | `PATCH /boletos/{id_boleto}/baixa` |
| webhook | `itau-ep9-gtw-boletos-boletos-v3-ext-aws` | 1.34.1 | out of scope (needs an OAuth2 server on our side; payload not in the OpenAPI) |

Base URLs: production `https://pix-pj.api.itau.com/recebimentos-pix/v1`, `https://secure.api.cloud.itau.com.br/boletoscash/v2`,
`https://api.gateway.itau.com.br/cash_management/v2`; sandbox `https://sandbox.devportal.itau.com.br/<product>/v1|v1|v2`.
Auth as Pix (STS + mTLS + `x-itau-apikey`; sandbox `/api/oauth/jwt` without mTLS), except that cash_management's OpenAPI
declares `tokenUrl: https://sts.itau.com.br/api/oauth/token` — configured per API in `application.yml` until production says which.

Facts that shaped the code (all from the JSON, not the prose):

- Errors are `{codigo, mensagem, campos[{campo, mensagem, valor}]}`, not RFC 7807. `campos[].valor` echoes what we sent (the
  payer's document included) and never reaches a log line.
- The baixa's `id_boleto` is `agência(4)+conta(7)+DAC(1)+carteira(3)+nosso número(8)` (23 chars), not the boleto UUID.
- The Pix `txid` of a Bolecode is `BL` + agência(4) + conta(7) + carteira(3) + nosso número left-padded to 15 (`^BL[0-9]{31}$`,
  2 + 29 digits; to be confirmed in the smoke). Unlike a pure-Pix payment, the payment record stores the **bank's** txid
  (`IssuedBoleto.pixTxid()`, from the issue response) — the bank derives it from the account, so the gateway cannot pick its
  own. The formula (`BoletoProvider.pixTxidFor`) is used only to *recover* it when the issue's response was lost (202/timeout):
  the reconstructed txid is checked with `GET /cob/{txid}`. An empty answer still ADOPTS the payment (PENDING, with the
  query's own `qrcode_pix.emv`) and flags it with divergence `PIX_TXID_UNCONFIRMED`: refusing would leave a boleto the bank
  issued stuck in `CREATED`, and the poll settles it by nosso número regardless of the txid. Only a confirmation that could
  not be made at all (the bank unreachable) leaves `CREATED` for the sweeper (`adoptBolecodeFromStatus`).
- The unique-txid index (`uq_payments_provider_txid`, migration V203) is scoped `(merchant_id, provider, txid)`, per tenant —
  the bank derives Bolecode txids from the account, and the shared sandbox returns canned txids from its own examples, so a
  global unique index would collide across merchants testing against the same sandbox account.
- `situacao_geral_boleto` ∈ `Em Aberto | Pago | Liquidado | Pagamento Rejeitado | Aguardando Crédito | Creditado | Baixado`;
  the payment record is the list `pagamentos_cobranca[]` (`valor_pago_total_cobranca`, `data_inclusao_pagamento`, …).
- `settleBoleto` decides "double payment" by the bank's paid channel, not by our own state alone: a Pix channel on an
  already-Pix-completed payment is ignored (expected — the QR paid first); any other channel is `DOUBLE_PAYMENT`; an unknown
  channel is ignored plus a WARN log rather than guessed at. The sandbox smoke below must record which channel codes and
  descriptions the query actually returns, since the OpenAPI enum was not exhaustive.
- Cancel refuses with `409 ALREADY_PAID` when the bank's query shows the boleto paid; the response message says the payment
  is "now COMPLETED" only when the adoption actually completed it, and says "under review" when it instead opened an
  `AMOUNT_MISMATCH` divergence (the bank's paid amount did not match ours).
- Reconciliation's Bolecode sweep is a method-scoped repository query (`findByMethodAndStatusIn`), not a generic PENDING
  scan, so it never touches Pix-only rows.
- `JobRunner.last_error` is prefixed with the `ProviderException` code for every job type (not just polling), so a stuck job
  row's failure reason is visible without joining `provider_requests`.
- Forbidden anywhere in the issue payload: `[ : < > & ; ' " ` ( ) # * / | ü` and the words `http`, `javascript`, `alert`; each text
  field also has its own character class — the gateway filters by the class (`BoletoText`).
- `etapa_processo_boleto = "simulacao"` validates without issuing. Used only in the smoke below.

### Sandbox smoke (to run after the plan; record the outcome here)

Never paste credential values anywhere but the running request. `<...>` stays `<...>`.

1. Portal: subscribe the app to the three products above; the sandbox `client_id`/`client_secret` are the same as for Pix.
   `<beneficiary_id>` for the sandbox is whatever the portal shows for the sandbox account (the docs' example is `150000052061`).
2. Token: `curl -s -XPOST https://sandbox.devportal.itau.com.br/api/oauth/jwt -H 'Content-Type: application/x-www-form-urlencoded' -d 'grant_type=client_credentials&client_id=<client_id>&client_secret=<client_secret>'`
3. Simulação (validates, does not issue): take `gateway-providers/src/test/resources/itau/boleto/fixtures/post_boletos_pix_request_min.json`,
   set `etapa_processo_boleto` to `simulacao` and `beneficiario.id_beneficiario` to `<beneficiary_id>`, then
   `curl -s -XPOST https://sandbox.devportal.itau.com.br/itau-ep9-api-recebimentos-v1-externo/v1/boletos-pix -H 'Authorization: Bearer <token>' -H 'x-itau-correlationID: <uuid>' -H 'Content-Type: application/json' -d @body.json`.
   Expected: 200 with `dados_individuais_boleto[0]` and `dados_qrcode`, or a `{codigo, mensagem, campos}` body — either way, record status and field names.
4. Efetivação through the gateway: register the credential with `beneficiary_id`, `wallet_code`, `species_code`
   (`PUT /v1/admin/merchants/<id>/providers/ITAU/credentials`, `README.md`), then `POST /v1/payments` with `method: BOLECODE` and a
   complete `customer` using a `gk_test_` key; `GET /v1/payments/<id>`; `POST /v1/payments/<id>/cancel`.
5. Query directly: `curl -s 'https://sandbox.devportal.itau.com.br/itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws/v1/boletos?id_beneficiario=<beneficiary_id>&codigo_carteira=109&nosso_numero=<nosso_numero>' -H 'Authorization: Bearer <token>' -H 'x-itau-correlationid: <uuid>'`.
6. Record here, like the Pix table above: which calls answered what, whether the sandbox echoed our nosso número and txid or its
   example's (the Pix sandbox did not echo; if this one does not either, a second Bolecode in the same database will hit
   `uq_payments_provider_txid` on the example's fixed `BL…` txid — expected, note it), which channel codes/descriptions the query
   returned for a paid boleto (needed by `settleBoleto`'s double-payment check), and whether `x-itau-apikey` was required
   (sent when present per the query/instruction OpenAPIs, but the sandbox issues none as of this writing).

**Not yet run.** This smoke needs real sandbox credentials and has not been executed as part of this task; the steps above
are the procedure, not a result. Run it before relying on Bolecode against production.

### Open follow-up (outside this plan)

Nosso número is allocated sequentially per merchant (`boleto_numbers`, starting at `00000001`), but Itaú requires uniqueness
per **account**, not per merchant. Two merchants sharing one Itaú beneficiary account would allocate colliding nosso números
and one would be rejected by the bank at issue time. Not fixed here because it needs a product decision (per-account
counter shared across merchants, or a documented one-merchant-per-account constraint) rather than a code change; tracked in
`DECISOES.md`.
