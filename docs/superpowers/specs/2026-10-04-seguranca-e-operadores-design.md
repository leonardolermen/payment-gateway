# Segurança e operadores — design

Data: 2026-10-04. Plano H. Assume Planos A–G. Decisões do usuário (2026-10-04): opção 2 — tudo
auditado, escopos e rotação com convivência nas chaves do merchant, rate limit persistido, LGPD, **e
operadores nomeados** com papel em vez de uma chave admin única. Fora: OAuth/SSO (não há painel),
MFA, IP allowlist.

## 1. O que existe
`merchants.api_keys` (`gk_test_`/`gk_live_`, hash com pepper, `active`, `expires_at` usado na rotação
imediata, `revoke`), `ApiKeyAuthFilter` → `MerchantContext.Current(merchantId, environment, apiKeyId)`,
`AdminKeyFilter` com a única `GATEWAY_ADMIN_KEY`, `RateLimitFilter` com Bucket4j em memória por chave,
`PathSanityFilter`, `ProtectedRoutes`. Clientes com `deleted_at` lógico e documento selado.

## 2. Operadores (`merchants/operator/`, schema `merchants`, `V103__operators.sql`)

```
operators(id CHAR(26) PK, name VARCHAR(80), role VARCHAR(10) ops|finance|readonly,
          key_hash CHAR(64), key_prefix CHAR(12), active BOOLEAN, created_at, last_used_at, revoked_at)
```

- Chave `gk_admin_<ulid-ish>` mostrada uma vez, hash com o mesmo pepper; a `GATEWAY_ADMIN_KEY` do
  ambiente continua válida como **bootstrap** (`role = ops`, nome `"bootstrap"`) e o README diz para
  revogá-la depois de criar o primeiro operador: `POST /v1/admin/operators` só com `ops`.
- `AdminKeyFilter` passa a resolver `OperatorContext.Current(operatorId, name, role)`; `ProtectedRoutes`
  ganha a tabela rota → papel mínimo: `readonly` só `GET`; `finance` + resolver divergências e
  contestações, reembolsos admin; `ops` tudo, inclusive operadores e credenciais de provider.
  Papel insuficiente → `403 FORBIDDEN_FOR_ROLE`.
- API: `POST /v1/admin/operators {name, role}` → 201 com a chave; `GET`, `POST /{id}/rotate-key`,
  `DELETE /{id}` (revoga). Um operador não revoga a si mesmo nem o último `ops` ativo
  (`409 LAST_OPS_OPERATOR`).

## 3. Chaves do merchant: escopos e rotação com convivência

- `api_keys.scopes VARCHAR(60)` (`V104`): conjunto de `read`, `write`, `refunds`; default das chaves
  existentes = os três. `ApiKey` ganha `scopes()`; `MerchantContext.Current` ganha `scopes`.
- `ProtectedRoutes` tabela rota → escopo: `GET` = `read`; `POST/PATCH/DELETE` = `write`;
  `POST /v1/payments/{id}/refunds` = `refunds` (cancelamento é `write`; só reembolso devolve
  dinheiro). Faltando → `403 INSUFFICIENT_SCOPE` com o escopo exigido no corpo.
- `POST /v1/admin/merchants/{id}/api-keys {"environment", "scopes"?}` e
  `POST …/api-keys/rotate {"environment", "overlap": "PT24H"?}`: a antiga continua válida até
  `expires_at = now + overlap` (máximo 7 dias, default 24 h) e aparece como `expiring` na listagem;
  `POST …/api-keys/{keyId}/revoke` encerra antes. Hoje `rotate` mata na hora; isso vira o caso
  `overlap = PT0S`.
- `GET /v1/merchant/api-keys`: o merchant lista as próprias (prefixo, escopos, `expires_at`,
  `last_used_at`), sem poder criar — criar é admin, como hoje.

## 4. Rate limit persistido
Bucket4j com `bucket4j-postgresql` (tabela `merchants.rate_limit_buckets`, `V105`), chave =
`merchant_id|environment`, limite por merchant (`merchants.rate_limit_per_minute`, nulo = global
`gateway.rate-limit.requests-per-minute`). Resposta 429 mantém `Retry-After`. Rejeitado: Redis (nova
dependência de infra para um processo só). Teste: duas instâncias do filtro sobre o mesmo banco
compartilham o balde.

## 5. Auditoria (`merchants/audit/`, `V106__audit_log.sql`)

```
audit_log(id CHAR(26) PK, at TIMESTAMPTZ, actor_kind VARCHAR(10) OPERATOR|MERCHANT|SYSTEM,
          actor_id CHAR(26), actor_name VARCHAR(80), merchant_id CHAR(26), action VARCHAR(60),
          resource_kind VARCHAR(30), resource_id VARCHAR(40), ip VARCHAR(45), correlation_id VARCHAR(64),
          before JSONB, after JSONB)
```

- Gravada por um port `AuditTrail` em `merchants/audit` (o kernel não conhece auditoria). Não é um
  filtro HTTP genérico: um filtro não sabe o `before` e auditaria o que falhou. Decisão: cada
  serviço de mutação do admin e do merchant chama `audit.record(AuditEntry)` **na mesma transação**
  da mudança, com `before`/`after` montados pelos mesmos builders JSON dos eventos (nunca o agregado
  serializado; nunca documento, PAN, segredo ou chave — `after` de uma chave criada guarda só o
  prefixo).
- O que entra: operadores (criar/rotacionar/revogar), chaves de API (criar/rotacionar/revogar),
  credenciais de provider (`PUT`, sem o payload), endpoints de webhook (registrar/alterar/rotacionar
  segredo/desativar), resolução de divergências e contestações, `run-now`/`give-up` de jobs,
  cancelamentos e reembolsos iniciados pela API (actor `MERCHANT`), purga de cliente. Não entra:
  leituras, criação de pagamento (já é evento), jobs do sistema (já são linhas em `jobs`).
- `GET /v1/admin/audit?actor=&merchant_id=&action=&since=&after=&limit=` (`readonly` pode ler).
  Retenção fora desta fase; a tabela é append-only (sem `UPDATE`/`DELETE` concedidos ao usuário da
  aplicação — grant no `V106`).

## 6. LGPD: purga de cliente
`DELETE /v1/customers/{id}?purge=true`: além do `deleted_at`, agenda `PURGE_CUSTOMER`
(`JobType` novo, handler em billing) para `now + gateway.billing.purge-after` (default 30 d); o job
só executa se não há assinatura `ACTIVE|PAST_DUE` e nenhuma ordem `OPEN` do cliente, e então
zera `document_ciphertext` (bytes vazios), troca `name` por `"[purged]"`, `email` e `address` por
nulo, mantém `document_hash` (conciliação de cartões salvos e dedupe) e `purged_at`. Cartões salvos
do cliente são marcados `deleted_at`. Pagamentos e ordens ficam (obrigação fiscal), mas os `payer`
inline de ordens do cliente purgado não existem (ordem com cliente não tem `payer`). Auditado.
Resposta do `GET` de um cliente purgado: `410 GONE` com `purged_at`.

## 7. Testes
Operadores: papel insuficiente → 403; último `ops` não revoga; bootstrap continua válido; rotação
mostra a chave uma vez. Escopos: chave `read` em `POST` → 403 `INSUFFICIENT_SCOPE`; chave sem
`refunds` em reembolso → 403; rotação com convivência: antiga autentica dentro da janela e não depois;
revogação antecipada. Rate limit: balde compartilhado entre duas instâncias do filtro; limite por
merchant. Auditoria: cada ação da lista gera uma linha com `before`/`after` sem segredos
(`AuditNeverHoldsSecretsTest` varre a tabela como o teste PCI faz); leituras não geram. Purga: cliente
com assinatura ativa não purga; após o prazo purga e o `GET` responde 410; o hash sobrevive.

## 8. Decisões (para o `DECISOES.md`)
1. **Operadores com papel, sem OAuth.** Rejeitado: SSO. Custo: chaves de operador são segredos a
   guardar como as de merchant.
2. **Auditoria na transação do serviço, não em filtro.** Rejeitado: filtro HTTP genérico (não sabe o
   `before`, e audita o que falhou). Custo: cada serviço de mutação chama o port.
3. **Bucket4j em Postgres, não Redis.** Custo: uma escrita por request na tabela de baldes.
4. **Purga mantém o hash do documento.** Rejeitado: apagar tudo. Custo: o hash permite saber que "um
   documento X já foi cliente", que é o necessário para dedupe e conciliação e nada mais.
5. **Chave admin do ambiente vira bootstrap.** Rejeitado: migração dura. Custo: quem nunca criar um
   operador segue com uma chave sem nome — o README e um WARN no boot cobram.
