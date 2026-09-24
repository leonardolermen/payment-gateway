# Itaú — Recebimentos Pix (API regulatória v2) — integration notes

Source: Itaú for Developers portal, product `itau-ep9-api-regulatorio-pix-v2-externo`, docs version 2.27.2 (read on 2026-09-24, logged in). The OpenAPI next to this file was downloaded from the portal's public asset URL. The Bacen normative spec (`../bacen-pix-api-2.10.0.yaml`) is what Itaú implements; where they differ, this file wins.

The older product `itau-ep9-gtw-pix-recebimentos-ext-v2` is being decommissioned (new URLs, new CA, refund flow became asynchronous). Do not target it.

## Base URLs

| environment | base URL |
|---|---|
| production | `https://pix-pj.api.itau.com/regulatorio-pix/v2` |
| sandbox (portal-hosted) | `https://sandbox.devportal.itau.com.br/itau-ep9-api-regulatorio-pix-v2-externo/v2` (needs "criar credenciais" in the portal; not enabled on our account yet) |

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

Credential issuance (done by the merchant, out of band): generate RSA key pair → send public key to the Itaú operations analyst → receive encrypted client id / temporary token / session key by e-mail → decrypt, build CSR (`CN=<client_id>`), `POST https://sts.itau.com.br/seguranca/v1/certificado/solicitacao` with the temporary token → response carries the signed certificate and the **client_secret shown once**. Renewal: `POST https://sts.itau.com.br/seguranca/v1/certificado/renovacao`.

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

## What this means for the gateway (Plan B)

1. No fake provider. `TEST` environment = Itaú sandbox base URL with the merchant's sandbox credentials; without credentials the call fails with `PROVIDER_CREDENTIALS_MISSING`.
2. Tests never hit the network: WireMock serves fixtures copied from this OpenAPI's examples; every request we build is validated against the OpenAPI schema.
3. Refund is asynchronous: `Refund` goes `REQUESTED → PROCESSING → COMPLETED | FAILED`, closed by the refund-status webhook or by polling `GET …/devolucao/{id}`.
4. Inbound webhook needs mTLS client-cert validation against Itaú's CA at the edge.
5. Token cache per merchant credential, refreshed before the 300 s expiry.
