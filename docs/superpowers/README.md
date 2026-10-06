# Specs, plans and decisions

Specs are the designs (in Portuguese, like DECISOES); plans are how each was built, task by task.
Both are dated by the day they were written.

## Specs

| date | spec | summary |
|---|---|---|
| 2026-09-23 | `specs/2026-09-23-payment-gateway-design.md` | the orchestrator model: one API, providers behind it, money never passes through us |
| 2026-09-25 | `specs/2026-09-25-bolecode-design.md` | one `BOLECODE` method; settled by Pix QR or by polling the barcode |
| 2026-09-25 | `specs/2026-09-25-payment-method-and-provider-strategy-design.md` | one provider interface and a request body per method |
| 2026-09-28 | `specs/2026-09-28-cartao-cielo-design.md` | credit card through Cielo: authorize, capture, void, refund, saved cards |
| 2026-10-02 | `specs/2026-10-02-ordens-planos-assinaturas-design.md` | customers, orders, plans, subscriptions; dunning that never cancels |
| 2026-10-04 | `specs/2026-10-04-webhooks-de-saida-design.md` | plan F: delivery log, redelivery and the outbound webhook contract |
| 2026-10-04 | `specs/2026-10-04-operacao-e-contestacoes-design.md` | plan G: operation through the admin API, merchant disputes, metrics |
| 2026-10-04 | `specs/2026-10-04-seguranca-e-operadores-design.md` | plan H: audit, key scopes and rotation, LGPD, named operators (design only) |

## Plans

| date | plan | summary |
|---|---|---|
| 2026-09-24 | `plans/2026-09-24-plano-a-fundacao-e-merchants.md` | A: modules under ArchUnit, merchants, API keys, encrypted credentials, outbound webhooks |
| 2026-09-24 | `plans/2026-09-24-plano-b-payments-pix-itau.md` | B: Pix with Itaú, mTLS webhook, outbox, jobs, reconciliation |
| 2026-09-25 | `plans/2026-09-25-fase-1-strategy-metodo-e-provider.md` | method strategy and single provider contract |
| 2026-09-25 | `plans/2026-09-25-plano-c-bolecode.md` | C: Bolecode at Itaú, barcode poll every 6 h |
| 2026-09-28 | `plans/2026-09-28-plano-d-cartao-cielo.md` | D: card via Cielo, `AUTHORIZED`, capture, saved cards |
| 2026-10-02 | `plans/2026-10-02-plano-e-ordens-planos-assinaturas.md` | E: module `gateway-billing`, cycles and dunning as jobs |
| 2026-10-04 | `plans/2026-10-04-webhook-deliveries.md` | F: `webhook-delivery` 0.2.0, `/v1/webhooks/deliveries`, event catalog test |
| 2026-10-05 | `plans/2026-10-05-operations-and-disputes.md` | G: divergence lifecycle, merchant disputes, admin job queue, metrics on the management port |

## Decisions

`DECISOES.md` is append-only: each entry records the decision, the alternative rejected and the cost of
being wrong, dated, newest at the end.
