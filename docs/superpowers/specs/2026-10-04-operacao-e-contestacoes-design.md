# Operação e contestações — design

Data: 2026-10-04. Plano G. Assume Planos A–F. Decisões do usuário (2026-10-04): operação pela API
admin com a `X-Admin-Key` (opção 1); o **merchant** vê suas transações (já vê) e pode **contestar** um
pagamento (opção 1 de "quem contesta"); métricas Prometheus para o Grafana do operador. Fora: SLA de
resposta, anexos, chargeback de adquirente, pagador final.

## 1. O que existe
`payments.reconciliation_divergences` (abertas por `Divergences.open(Payment, providerStatus, detail)`
a partir de conciliação Pix/cartão, Bolecode, `OrderSettlement` — `DOUBLE_PAYMENT`, `PAID_AFTER_CLOSE`,
`AMOUNT_MISMATCH`, `CANCELED_AT_BANK`, `NOT_FOUND_AT_BANK`, `PIX_TXID_UNCONFIRMED`, …), `payments.jobs`
(`status PENDING|DONE|DEAD`, `attempts`, `last_error`, `next_run_at`), pagamentos `CREATED` varridos por
`StuckCreatedSweep`, `BillingJobRetry` que repete de hora em hora com log ERROR. Actuator expõe
`health,info,prometheus` mas nenhuma métrica de domínio é registrada. Nenhum endpoint lê nada disso.

## 2. Modelo

`payments.reconciliation_divergences` ganha (migração `V207__divergences_operations.sql`):

```
origin           VARCHAR(10) NOT NULL DEFAULT 'SYSTEM'   -- SYSTEM | MERCHANT
reason           VARCHAR(20)                             -- MERCHANT: AMOUNT_MISMATCH|NOT_SETTLED|DUPLICATE|OTHER
merchant_note    VARCHAR(500)
status           VARCHAR(12) NOT NULL DEFAULT 'OPEN'     -- OPEN | UNDER_REVIEW | RESOLVED | REJECTED
resolution       VARCHAR(16)                             -- SYSTEM: CONFIRMED|FALSE_POSITIVE ; MERCHANT: RESOLVED|REJECTED
resolution_note  VARCHAR(500)
resolved_by      VARCHAR(80)                             -- nome do operador (Plano H) ou 'admin'
resolved_at      TIMESTAMPTZ
```

Índice parcial único já existente de "uma aberta por pagamento" passa a considerar `origin`: uma
divergência `SYSTEM` aberta e uma contestação `MERCHANT` aberta podem coexistir no mesmo pagamento;
duas contestações abertas não (`409 DISPUTE_ALREADY_OPEN`). O domínio `Divergence` ganha
`markUnderReview`, `resolve(resolution, note, by, at)`; tabela de transições
`OPEN → UNDER_REVIEW → RESOLVED|REJECTED`, `OPEN → RESOLVED|REJECTED`, finais não voltam.

## 3. API admin (`X-Admin-Key`, pacote `gateway-app/api/admin/`)

| rota | comportamento |
|---|---|
| `GET /v1/admin/divergences?status=&origin=&kind=&merchant_id=&since=&limit=&after=` | lista com cursor; `kind` = `provider_status` atual |
| `GET /v1/admin/divergences/{id}` | detalhe + resumo do pagamento (`id, status, amount, method, provider, paid_at`) |
| `POST /v1/admin/divergences/{id}/review` | → `UNDER_REVIEW` |
| `POST /v1/admin/divergences/{id}/resolve` `{"resolution": …, "note": …}` | `SYSTEM`: `CONFIRMED|FALSE_POSITIVE`; `MERCHANT`: `RESOLVED|REJECTED`; grava `resolved_by` (nome do operador quando o Plano H existir; até lá `"admin"`), emite `dispute.updated` se `MERCHANT` |
| `GET /v1/admin/jobs?status=&type=&merchant_id=` | `id, type, ref_id, status, attempts, last_error, next_run_at, created_at`; `DEAD` e `attempts >= max` primeiro |
| `POST /v1/admin/jobs/{id}/run-now` | `next_run_at = now`, `status = PENDING` (também para `DEAD`), `attempts` mantido |
| `POST /v1/admin/jobs/{id}/give-up` `{"note": …}` | `status = DEAD`, `last_error = note` — só para `PENDING`; um job `DEAD` já desistiu |
| `GET /v1/admin/payments/stuck` | `CREATED` há mais que `stuckCreatedAfter` e `PENDING` com `expires_at` vencido há mais que `expirationGrace` |

Resolver nunca move dinheiro: reembolso, cancelamento e captura continuam nas APIs próprias, para a
auditoria (Plano H) mostrar duas ações distintas. `jobs` precisa de `JobRepository.findByFilter`,
`forceDue(id)`, `giveUp(id, note)` em `payments`.

## 4. API do merchant (chave de API)

| rota | comportamento |
|---|---|
| `POST /v1/payments/{id}/disputes` `{"reason": "AMOUNT_MISMATCH|NOT_SETTLED|DUPLICATE|OTHER", "note": "…"}` | 201 `OPEN`; abre `Divergence(origin=MERCHANT)`; `409 DISPUTE_ALREADY_OPEN`; só sobre pagamento do merchant (`404` senão); `Idempotency-Key` obrigatório |
| `GET /v1/disputes?status=&since=&limit=&after=` | as do merchant |
| `GET /v1/disputes/{id}` | `id, payment_id, reason, note, status, resolution, resolution_note, created_at, resolved_at` |

Evento `dispute.updated` `{id, payment_id, reason, status, resolution, resolution_note, updated_at}`
no outbox, partição = `payment_id`, a cada transição (inclusive a abertura, como `status: OPEN`).
Pacote `payments/dispute/` (`Dispute` é a visão `MERCHANT` da divergência; `DisputeService`
abre/lista; a resolução vem do admin via `Divergences`).

## 5. Métricas (Micrometer → `/actuator/prometheus`)

Porta de gestão separada (`management.server.port=${GATEWAY_MANAGEMENT_PORT:9090}`), sem filtro de
chave de API e **sem** exposição pública (README avisa). Registradas por um `OperationsMetrics`
(gauges por consulta agregada a cada 30 s, para não contar linha a linha):

| métrica | labels |
|---|---|
| `gateway_payments_total` (gauge por status) | `status, method, provider, environment` |
| `gateway_payments_stuck` | `kind` = `created_too_long`, `pending_past_expiry` |
| `gateway_divergences_open` | `origin, kind` |
| `gateway_jobs` | `status, type` ; `gateway_jobs_overdue` (`next_run_at < now - 5min`) |
| `gateway_provider_call_seconds` (timer registrado em `ProviderGateway.call`, o mesmo ponto que grava `provider_requests`) | `provider, operation, outcome` |
| `gateway_webhook_deliveries` | `status` (via `DeliveryRepository.countByStatus`, lib 0.2) |
| `gateway_outbox_pending` | — |

README: tabela das métricas e limiares sugeridos para alertas (divergência aberta > 24 h, job
`DEAD` > 0, `created_too_long` > 0, `overdue` > 10, p95 de provider > 5 s). Alertas vivem no Grafana
do operador, fora do repo.

## 6. Testes
Integração: abrir contestação, 409 na segunda, `dispute.updated` no outbox a cada transição, admin
resolve e o merchant vê `RESOLVED` com a nota; divergência `SYSTEM` listada e resolvida com
`FALSE_POSITIVE`; `run-now` em job `DEAD` faz o `JobRunner` executá-lo; `give-up` em `DEAD` → 409;
`stuck` lista um `CREATED` envelhecido pelo `MutableClock`; `/actuator/prometheus` na porta de gestão
contém `gateway_divergences_open` com o label certo após abrir uma. Unitários: transições de
`Divergence`; `OperationsMetrics` com repositórios falsos.

## 7. Decisões (para o `DECISOES.md`)
1. **Contestação é uma divergência com `origin = MERCHANT`**, mesma fila. Rejeitado: tabela própria.
   Custo: a tabela de divergências ganha colunas que só um lado usa.
2. **Resolver não move dinheiro.** Rejeitado: `resolve` com `refund: true`. Custo: dois cliques
   para o caso comum "duplicado → reembolsa".
3. **Métricas por gauge agregado a cada 30 s**, não por contador em cada transição. Rejeitado:
   instrumentar cada `transition`. Custo: 30 s de atraso e uma consulta periódica.
4. **Porta de gestão separada sem autenticação própria.** Rejeitado: proteger `/actuator` com a chave
   admin. Custo: depende da rede; o README diz para não expor a porta.
