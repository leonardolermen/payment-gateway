# Webhooks de saída: log de entregas, reentrega e contrato — design

Data: 2026-10-04. Plano F (primeiro dos três planos de "bom gateway": webhooks, operação, segurança).
Assume os Planos A–E em `main` (Pix, Bolecode, cartão, ordens/assinaturas) e a biblioteca
`webhook-delivery` (repositório `C:\Dev\webhook-delivery`, `com.barrier:webhook-delivery`, publicada no
GitHub Packages pelo CI a cada merge em `main`, hoje `0.2.0-SNAPSHOT`).

Decisões do usuário (2026-10-04): evoluir a **biblioteca** (opção 1) em vez de ler a tabela da biblioteca
direto do gateway; escopo = borda do merchant (listar, ver, reentregar) + contrato documentado + prova
fim a fim. Fora: retenção/expurgo, painel visual.

## 1. O que já existe (lido na biblioteca em 2026-10-04)

| peça | fato |
|---|---|
| intake | `DeliveryIntake.accept(DeliveryRequest(tenantId, eventType, eventId, aggregateId, partitionKey, payload, correlationId))` cria uma `Delivery` por endpoint ativo inscrito no tipo (`EventTypeMatcher`, `ALL_EVENTS`), `saveIfAbsent` por `(eventId, endpointId)` |
| envio | `HttpWebhookClient` POST do `payload` cru com headers `<prefix>-Signature`, `<prefix>-Signature-Previous` (janela de rotação), `<prefix>-Event-Id`, `<prefix>-Event-Type`; prefixo configurado `X-Gateway` |
| assinatura | `HmacSigner.sign(body, secret, instant)` com esquema versionado `v1=` (formato exato copiado da classe para o README no plano) |
| retry | `claimDue` com lease e `maxPerEndpoint`; backoff `baseBackoff` até `maxAttempts`, depois `DEAD`; `DeliveryRetryScheduler` a cada `retry-delay-ms` |
| estado | `Delivery`: `PENDING, DELIVERED, FAILED, DEAD`, `attempts`, `lastError` (500 chars), `nextAttemptAt`, `deliveredAt`, `claimToken` |
| endpoints | `WebhookEndpointService.register/update/rotateSecret/deactivate/find/listByTenant`; gateway já expõe em `/v1/webhooks/endpoints` |
| gateway | `OutboxRelay` → `MerchantEvents.emitRaw` → intake; `eventId` determinístico (UUID v3 do id do outbox) → at-least-once sem duplicar entrega |

O que falta: ninguém lê `deliveries`; não há reentrega; o README não documenta o contrato de saída.

## 2. Biblioteca `webhook-delivery` 0.2.0

### 2.1 Consulta

```java
// repository/DeliveryRepository
List<Delivery> findByTenant(String tenantId, DeliveryQuery query);
Optional<Delivery> findByTenantAndId(String tenantId, UUID id);

// domain/DeliveryQuery (record, validado no construtor canônico)
record DeliveryQuery(DeliveryStatus status, String eventType, String aggregateId, Instant since,
                     DeliveryCursor after, int limit)  // limit 1..100, default 20
record DeliveryCursor(Instant createdAt, UUID id)        // ordenação createdAt DESC, id DESC
```

Índice novo na migração da biblioteca (`V3__deliveries_tenant_listing.sql`, numeração conforme a
sequência existente em `WebhookDeliveryMigrations`): `(tenant_id, created_at DESC, id DESC)` e
`(tenant_id, status)`.

### 2.2 Reentrega

```java
// service/WebhookDeliveryService
RedeliverResult redeliver(String tenantId, UUID deliveryId);
int redeliverDead(String tenantId, Instant since);   // devolve quantas voltaram a PENDING

enum RedeliverResult { SCHEDULED, NOT_FOUND, NOT_REDELIVERABLE }
```

Regras: só `DEAD` ou `FAILED` reentregam; `PENDING` em voo responde `NOT_REDELIVERABLE`; `DELIVERED`
também (o merchant que quer o payload de novo lê `GET /{id}`). Reentrega põe `status = PENDING`,
`nextAttemptAt = now`, `attempts = 0`, guarda `lastError` em `last_error_before_redelivery` e
`redelivered_at = now` (duas colunas novas); a assinatura na hora do envio usa o segredo **vigente**
do endpoint, nunca o da época. Um endpoint desativado não reentrega (`NOT_REDELIVERABLE`).
`redeliverDead` é um `UPDATE` em lote com as mesmas regras, limitado a 1000 por chamada.

### 2.3 Testes na biblioteca
Testcontainers já existe: listagem com cursor e filtros; isolamento por tenant (id de outro tenant →
vazio/`NOT_FOUND`); reentrega de `DEAD` volta a sair pelo `claimDue`; `PENDING`/`DELIVERED` recusam;
lote limitado. Versão `0.2.0` liberada por merge em `main` (o CI faz o `deploy`); o gateway sobe
`<webhook-delivery.version>0.2.0</webhook-delivery.version>`.

## 3. Gateway

### 3.1 API (chave do merchant; tenant = `merchant_id`, como nos endpoints)

| rota | resposta |
|---|---|
| `GET /v1/webhooks/deliveries?status=&event_type=&aggregate_id=&since=&after=&limit=` | lista de `DeliveryResponse`, header `X-Next-Cursor` quando há mais |
| `GET /v1/webhooks/deliveries/{id}` | `DeliveryResponse` completo, incluindo `payload` |
| `POST /v1/webhooks/deliveries/{id}/redeliver` | 202 `{"status":"PENDING"}`; 409 `DELIVERY_NOT_REDELIVERABLE`; 404 |
| `POST /v1/webhooks/deliveries/redeliver-dead` `{"since": "<instant>"}` | 202 `{"scheduled": n}`; `since` obrigatório e no máximo 30 dias atrás (`422 WINDOW_TOO_WIDE`) |

`DeliveryResponse(id, event_id, event_type, aggregate_id, endpoint_id, target_url, status, attempts,
last_error, next_attempt_at, created_at, delivered_at, redelivered_at)`; `payload` só no `GET /{id}`
(é o JSON do evento, já sem segredos por construção). `after` é o cursor opaco (base64 de
`createdAt|id`). As duas rotas `POST` entram no `IdempotencyFilter`.

Pacote `gateway-app/api/webhook/` (já existe, ganha `WebhookDeliveriesController` e `dto/`). Nada entra
em `payments`/`billing`: é a borda do app sobre a biblioteca, como `MerchantEvents`.

### 3.2 Contrato documentado (README, seção "Outbound webhooks")

1. Envelope: o corpo é o payload do evento (mapa plano, snake_case), **sem** envelope extra; o tipo e
   o id vêm nos headers `X-Gateway-Event-Type` e `X-Gateway-Event-Id`. (É o que a biblioteca envia
   hoje; mudar o corpo seria quebrar quem já integrou.)
2. Assinatura: `X-Gateway-Signature` no formato exato de `HmacSigner` (timestamp + `v1=` HMAC-SHA256
   do corpo com o segredo do endpoint), `X-Gateway-Signature-Previous` durante a janela de rotação
   (`secretRotationOverlap`). Verificação em pseudocódigo + exemplos em Python e Java, incluindo a
   comparação em tempo constante e a tolerância de relógio recomendada (5 min).
3. Semântica: at-least-once (idempotência pelo `X-Gateway-Event-Id`), ordenação por `partition_key`
   (= id do pagamento/ordem/assinatura), retries com backoff até `maxAttempts` → `DEAD`, reentrega
   manual, janela de rotação de segredo, timeouts de conexão/leitura, resposta esperada 2xx.
4. Catálogo de eventos com payload por tipo: `payment.*` (pending, authorized, completed, failed,
   expired, canceled), `refund.*` (requested, completed, failed, unknown), `customer.*`, `order.*`,
   `invoice.*`, `subscription.*`, `dispute.updated` (Plano G). Um exemplo JSON por tipo, gerado a
   partir dos builders reais (`PaymentEvents.paymentJson`, `OrderService.json`, …) por um teste que
   falha quando o README diverge (`EventCatalogTest`: renderiza os exemplos e compara com o bloco do
   README).

### 3.3 Prova fim a fim
`WebhookDeliveryFlowIntegrationTest` no app: WireMock como receptor do merchant; registra endpoint
inscrito em `payment.*`; cria um Pix (Itaú WireMock); o receptor valida `X-Gateway-Signature` com o
segredo devolvido no cadastro; derruba o receptor (500) para forçar `FAILED` → lista mostra
`attempts > 1` e `last_error`; restaura e espera `DELIVERED`; força `DEAD` (`maxAttempts` baixo via
propriedade de teste) e reentrega pela API; rotaciona o segredo e verifica as duas assinaturas na
janela.

## 4. Erros
`DELIVERY_NOT_REDELIVERABLE` (409), `WINDOW_TOO_WIDE` (422), `NOT_FOUND` (404). Sem novos códigos no
`IdempotencyFilter`.

## 5. Decisões (para o `DECISOES.md`)
1. **Evoluir a biblioteca, não ler a tabela dela.** Rejeitado: JPA próprio sobre
   `webhook_delivery.deliveries`. Custo se errado: uma release da lib por mudança de consulta.
2. **Reentrega assina com o segredo vigente.** Rejeitado: guardar a assinatura original. Custo: um
   merchant que rotacionou e reentrega recebe assinatura nova — é o esperado.
3. **Corpo sem envelope** (compatível com o que já sai). Rejeitado: `{"type","id","data"}`. Custo:
   tipo e id vivem em headers; quem loga só o corpo perde o tipo.
4. **Catálogo verificado por teste**, não escrito à mão. Custo: README gerado em parte por código.
