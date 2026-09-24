# Pix Gateway — design

Data: 2026-09-23. Status: aprovado em conversa, aguardando revisão do texto.

Um gateway de pagamentos brasileiro no modelo **orquestrador**: uma API para o
merchant, vários provedores por trás, e o dinheiro nunca passa por nós. Este
documento substitui a spec original (`payment_gateway_orchestrator_spec`), que
serviu de ponto de partida; cada seção registra a decisão, a alternativa
rejeitada e o custo de estar errada.

## 1. Decisões que definem o produto

### 1.1 Modelo A: credenciais do merchant

Cada merchant cadastra as próprias credenciais bancárias (certificado,
`client_id`/`secret`). O gateway chama o banco **em nome do merchant**; a
cobrança nasce na conta dele, o dinheiro cai na conta dele.

- **Rejeitado — modelo B (credenciais do gateway, repasse ao merchant).** É
  sub-adquirência: autorização do Banco Central como Instituição de Pagamento,
  PLD/FT, capital mínimo, ledger e settlement obrigatórios. É outra empresa.
- **Custo de estar errado:** se um dia o volume justificar o modelo B, o
  ledger e o settlement entram como módulos novos; nada do modelo A é
  descartado, porque a orquestração continua sendo a base.
- **Consequência:** ledger, settlement, fees e "ser adquirente" são
  **não-objetivos** explícitos. Ninguém reabre isso num sprint.

### 1.2 Pix primeiro; boleto e cartão depois, mas o modelo já sabe

| Fase | Entrega | Provider |
|---|---|---|
| 1 | Pix (cobrança, webhook, expiração, devolução, assinatura por QR) | Itaú |
| 2 | Boleto registrado | Itaú (API Cobrança — mesma auth) |
| 3 | Cartão | Cielo (API E-Commerce 3.0) |
| 4 | Segundo banco Pix | Santander |

- **Rejeitado — três métodos no MVP.** Cartão é outro mundo (adquirente,
  PCI-DSS, `AUTHORIZED→CAPTURED`, chargeback); boleto tem compensação D+1 e
  pagamento após vencimento. Colocar os três atrasa o Pix em meses.
- **Rejeitado — segundo banco na Fase 2.** Valida mais a abstração, entrega
  menos produto. Boleto no mesmo banco reaproveita a auth pronta.
- **O que se faz agora para não pintar a parede errada:** `Payment.method`,
  `payment_details` polimórfico por método (JSONB), estados comuns + estados
  por método, e **uma interface de provider por método** (`PixProvider`,
  `BoletoProvider`, `CardProvider`). Custo no MVP: uma coluna, um JSONB, uma
  interface. Custo de não fazer: renomear `Payment` inteiro na Fase 2.
- **Cielo** porque é a única grande adquirente com sandbox público sem
  contrato, tem tokenização própria (escopo PCI mínimo) e a maior base de
  merchants. Rede é a segunda de cartão. Trocar custa uma `CardProvider`.
- **Cartão nunca guarda PAN.** Token da adquirente, sempre. Regra desde já.

### 1.3 Multi-merchant desde o dia 1

Tabela `merchants`, API keys por merchant e ambiente, credenciais de provider
por `(merchant, provider)` cifradas. `merchant_id` está em toda tabela e na
chave de idempotência.

- **Rejeitado — um merchant no MVP.** Economizaria o cofre de credenciais,
  mas o produto é multi-tenant por definição no modelo A.

### 1.4 Pedidos: referência do merchant + itens informativos (opção c)

O merchant manda `amount` e `reference`; itens são opcionais e **informativos**
(aparecem no recibo, ninguém soma). `Plan` de assinatura existe (valor,
periodicidade); catálogo de produtos não.

- **Rejeitado — (a) só referência:** o `Plan` precisa existir de qualquer
  forma. **Rejeitado — (b) gateway dono do pedido:** vira sistema de pedidos,
  com imposto, desconto e frete — onde isso afunda.

### 1.5 Provider fixo + fallback, não "routing engine"

Cobrança Pix nasce no banco onde está a conta recebedora. `SMART`/`WEIGHTED`
só faz sentido com contas em vários bancos, e fee de Pix é ~zero. O que
sobra é **failover por disponibilidade**: provider primário por
`(merchant, method)`, fallback opcional configurado. `ProviderRegistry`
responde "quem faz `PIX` para o merchant X" — é o routing honesto.

### 1.6 Sem sandbox por enquanto

Nenhum sandbox de banco está disponível hoje. O adapter Itaú é escrito a partir
da documentação pública (OpenAPI do Itaú + `bacen/pix-api`, a referência
normativa) e testado contra WireMock com fixtures **geradas dos exemplos do
OpenAPI**, não inventadas. Os mesmos testes têm um perfil `provider.live` para
o sandbox quando existir — é ali que os quirks aparecem e as fixtures são
corrigidas. Se o portal do Itaú exigir login para o OpenAPI completo, o
usuário o baixa e commita; até lá vale o do Bacen, que o Itaú segue.

## 2. Stack e topologia

- **Java 25, Spring Boot 4, Maven multi-módulo, virtual threads** (servlet
  blocking; sem WebFlux). Casa com o Barrier, o outro produto do mesmo dono, e
  é obrigatório para compartilhar a biblioteca de webhooks (§7).
  - **Rejeitado — Java 21/Boot 3** (pedido inicial): duas toolchains para uma
    pessoa e Jackson 2 vs. 3 na fronteira da biblioteca.
  - **Rejeitado — Python/FastAPI**: transferiria as lições do
    `agent-orchestrator`, mas o mercado de pagamentos e o Barrier são JVM.
- **Monólito modular, um deploy.** Quatro módulos de negócio, fronteira
  cobrada por ArchUnit, um schema Postgres por módulo.
  - **Rejeitado — três serviços (clientes / pedidos / motor de pagamento).**
    A fronteira está certa; o deploy separado no dia 1 compra rede,
    contratos HTTP e transação distribuída para um ganho (times e escala
    independentes) que não existe. As fronteiras desenhadas antes de o
    domínio rodar são as erradas. Separar depois é trocar a implementação da
    interface por um cliente HTTP; o outbox já vira a fila.
- **Postgres como fila** (outbox + jobs, `FOR UPDATE SKIP LOCKED`, lease).
  - **Rejeitado — Kafka no MVP:** dual-write sem outbox, componente
    operacional pesado, nenhum consumidor externo hoje.
  - **Rejeitado — JobRunr/Quartz:** o job não roda na transação do domínio,
    então o outbox continua necessário — dois mecanismos. Entra quando o
    dashboard de jobs virar necessidade.
- PostgreSQL + Flyway; Micrometer/OpenTelemetry; Bucket4j em memória para rate
  limit (Redis só com mais de uma instância).

```
pix-gateway/
  gateway-kernel/     tipos sem dependência: Money (long centavos + BRL), Ids (ULID), Clock, Result,
                      e as interfaces publicadas entre módulos (PaymentGateway, CredentialLookup)
  gateway-merchants/  Merchant, ApiKey (hash), ProviderCredential (cifrada), MerchantWebhook
  gateway-orders/     Order (+ itens informativos), Plan, Subscription, SubscriptionCycle
  gateway-payments/   Payment, PaymentEvent, Refund, IdempotencyKey, WebhookInbox, Outbox, Jobs,
                      Reconciliation, Expiração
  gateway-providers/  PixApiClient (contrato Bacen), AuthStrategy, ItauPixProvider, FakePixProvider
  gateway-app/        Spring Boot: REST, auth por API key, workers, Flyway; ÚNICO deploy
```

Regras cobradas por ArchUnit, cada uma um teste com nome:

1. `kernel` não importa nada. Ninguém importa `app`.
2. `merchants`, `orders`, `payments` não se importam entre si. Conversam por
   interfaces publicadas em `kernel`, implementadas no módulo dono e injetadas
   pelo Spring, e por eventos via outbox. Sem tabela compartilhada.
3. `providers` só é importado por `payments`, e `payments` só conhece as
   interfaces `*Provider` — nunca `ItauPixProvider`.
4. DTOs de banco são `package-private` em `providers`; nenhum sai.

Fluxo nominal: `POST /v1/orders {charge:true}` → `app` autentica API key →
`orders` cria `Order` e chama `PaymentGateway.createCharge` → `payments` grava
`IdempotencyKey(IN_PROGRESS)` + `Payment(CREATED)` + outbox **no mesmo commit**
→ chama `PixProvider.createCharge(txid)` → `PENDING` com `pix_copia_e_cola` →
relay do outbox → entrega `payment.pending` ao webhook do merchant.

## 3. Payments: máquina de estados, idempotência, devolução

### 3.1 Estados comuns e transições (Pix)

| de → para | quem dispara | nota |
|---|---|---|
| `CREATED → PENDING` | gateway, após provider aceitar | grava `txid`, `pix_copia_e_cola`, `expires_at` |
| `CREATED → FAILED` | gateway, provider recusou/indisponível | `IdempotencyKey` guarda o erro; retry com mesma chave devolve o mesmo erro |
| `PENDING → COMPLETED` | webhook ou reconciliation | grava `e2eid`, `paid_at`, `paid_amount` |
| `PENDING → EXPIRED` | job de expiração (`expires_at` + 5 min) | folga porque o banco pode liquidar no último segundo |
| `PENDING → CANCELED` | merchant via `POST …/cancel` → `PATCH /cob/{txid}` `REMOVIDA_PELO_USUARIO_RECEBEDOR` | |
| `EXPIRED → COMPLETED` | **só provider** (webhook/reconciliation) | o banco vence; liquidação atrasada é caso real |

`PARTIALLY_REFUNDED`/`REFUNDED` são **projeção** de `refunded_amount` vs.
`amount`, não transições. Estados por método (`AUTHORIZED` para cartão,
`SETTLEMENT_PENDING` para boleto) entram com o método; a tabela de
transições é por método.

Webhook para estado terminal (`COMPLETED`, `CANCELED`, `FAILED`) é
**registrado em `payment_events` e ignorado** — nunca erro. `payment_events`
tem `sequence` por payment; a versão do payment é o `sequence` do último
evento (optimistic lock). O estado atual é reconstruível do log de eventos
(teste de replay).

### 3.2 Idempotência

Tabela `idempotency_keys(merchant_id, key, request_hash, status,
response_code, response_body, resource_id, created_at)`, PK composta.

1. `INSERT` com `IN_PROGRESS` **antes** de qualquer chamada externa. Conflito:
   `request_hash` diferente → `422 IDEMPOTENCY_KEY_REUSED`; igual e `DONE` →
   resposta gravada; igual e `IN_PROGRESS` → `409 IN_PROGRESS` (o cliente
   tenta de novo; não bloqueamos thread esperando).
2. **`txid` é determinístico**: derivado do `payment_id` (ULID, 26 chars, cabe
   em `[a-zA-Z0-9]{26,35}`). Timeout no `PUT /cob/{txid}` → o retry faz
   `GET /cob/{txid}` antes de recriar; se existe, adota. É isso que garante
   uma cobrança no banco sob qualquer combinação de timeout, retry e
   failover.
3. Chaves expiram em 24 h (job).
4. Toda mutação é idempotente por `(merchant_id, key)`: criar order/payment,
   cancelar, devolver, criar assinatura.

Virtual threads não mudam nada aqui: a serialização é o PK e o optimistic
lock, não a thread.

### 3.3 Devolução (não "refund")

Vocabulário Pix: devolução, `PUT /pix/{e2eid}/devolucao/{id}`, até 90 dias,
múltiplas parciais, soma ≤ valor original. Entidade própria `Refund` com
`REQUESTED → PROCESSING → COMPLETED | FAILED`; `refund_id` também
determinístico a partir do id interno.

## 4. Providers

Interface por método; `payments` só conhece estas.

```java
interface PixProvider {
    ProviderId id();
    Charge createCharge(Credentials c, CreateCharge cmd);                        // PUT /cob/{txid}
    Optional<Charge> findCharge(Credentials c, String txid);                      // GET /cob/{txid}
    void cancelCharge(Credentials c, String txid);                                // PATCH /cob/{txid}
    Refund refund(Credentials c, String e2eid, String refundId, Money amount);    // PUT /pix/{e2eid}/devolucao/{id}
    List<Charge> listCharges(Credentials c, Instant from, Instant to);            // GET /cob — reconciliation
    WebhookEvent parseWebhook(Credentials c, RawWebhook raw);                     // valida origem, extrai pix[]
}
```

Os bancos são mais parecidos do que a spec original supunha: todos
implementam a **API Pix do Bacen** (`/cob`, `/pix`, `/pix/{e2eid}/devolucao`,
webhook com `endToEndId`, status `ATIVA/CONCLUIDA/REMOVIDA_*`). O que varia é
**autenticação e transporte**. Por isso:

- `PixApiClient` — o contrato do Bacen, escrito **uma vez**. `RestClient`
  sobre JDK `HttpClient` (amigo de virtual threads), timeouts explícitos
  (connect 3 s, read 10 s), **sem retry automático** — retry é decisão do
  domínio porque envolve o `GET /cob/{txid}` antes.
- `AuthStrategy` — o que varia. Itaú: OAuth2 client-credentials + mTLS com
  certificado do merchant; token cacheado por `(merchant, provider)` com
  refresh antes de expirar.
- `ItauPixProvider = PixApiClient(base Itaú) + ItauAuth + ItauWebhookVerifier`.
  Santander depois é outro trio com o mesmo `PixApiClient`.
- `FakePixProvider` — sandbox do próprio gateway (ambiente `test` do
  merchant) e o segundo provider que prova a abstração. Também é o que faz a
  suíte não falar com a rede.
- Normalização de erro do provider: `PAYMENT_DECLINED`,
  `PROVIDER_UNAVAILABLE`, `INVALID_PAYMENT`, `TIMEOUT`,
  `UNKNOWN_PROVIDER_ERROR`, sempre com o erro bruto preservado em
  `provider_requests`.

**Rejeitado — adapter por banco com modelo próprio** (a spec original): 4x o
código de tradução para um payload que é o mesmo.

**Pix Automático** (`/rec`, `/cobr`) entra na interface quando o primeiro
provider suportar; até lá assinatura gera `cob` por ciclo. Extensão nomeada,
não método vazio.

## 5. Orders, Plans, Subscriptions

- `order(id, merchant_id, customer_ref, reference, description, amount,
  currency, status, created_at)` + `order_items(order_id, description,
  quantity, unit_amount)` informativos. Status `OPEN → PAID | CANCELED |
  EXPIRED`, projeção do payment vigente (o último não-terminal). Um order tem
  0..n payments.
- `POST /v1/orders {charge:true}` cria order e payment na mesma chamada (o
  caminho de 90% dos merchants); `POST /v1/orders/{id}/payments` é o outro.
- `plan(id, merchant_id, name, amount, interval ∈ {DAY,WEEK,MONTH,YEAR},
  interval_count, allowed_methods, status)`. Sem trial, sem proration no MVP.
- `subscription(id, merchant_id, plan_id, customer_ref, status,
  collection_mode, billing_anchor_day, current_cycle_start,
  current_cycle_end, next_billing_at, grace_days)`; status `ACTIVE →
  PAST_DUE → CANCELED`, mais `PAUSED`. `collection_mode` hoje só `PIX_QR`;
  `PIX_AUTOMATICO`, `BOLETO`, `CARD_TOKEN` vêm com os métodos.
- **Ciclo**: job com `next_run_at = next_billing_at` cria um `Order` do ciclo
  (`reference = sub_x/cycle_n`) e o payment, emite `subscription.cycle_created`
  — o merchant manda o QR ao cliente. `COMPLETED` → `subscription.cycle_paid`,
  avança o ciclo. Vencido além de `grace_days` → `PAST_DUE` +
  `subscription.past_due`; o merchant decide cancelar. Sem retentativa
  automática: com `cob` não há o que retentar.
- **`next_billing_at` vem da âncora** (`billing_anchor_day`), nunca do último
  pagamento: assinatura de dia 31 cobra 28/30 nos meses curtos e volta ao 31.
  Fuso `America/Sao_Paulo`.
- Fronteira: `orders` chama `payments` só via `PaymentGateway.createCharge`
  (kernel) e consome `payment.completed/expired/canceled` do outbox.
  `payments` não sabe o que é order — `order_id` é uma `reference` opaca.

## 6. Webhooks de entrada, reconciliation, expiração

**Entrada (do provider).** `POST /v1/providers/{provider}/webhooks/{merchant_token}`
— o token identifica o merchant (é o que se cadastra no banco ao configurar
o webhook); o provider verifica origem conforme sua `AuthStrategy`. Fluxo:
grava cru em `webhook_inbox` → `202` imediato → job processa → dedup por
`(provider, e2eid)` → transição. **Nunca na thread do request**: o banco tem
timeout curto e reenvia se demorar.

**Reconciliation.** Job a cada 15 min por `(merchant, provider)`: `GET /cob?inicio&fim`
cobrindo `PENDING` com mais de 10 min e `EXPIRED` das últimas 48 h.
`PENDING/EXPIRED` no gateway × `CONCLUIDA` no banco → corrige automático (é
o webhook perdido) e emite `payment.completed` com `source=reconciliation`.
Qualquer outra combinação → `reconciliation_divergences` `OPEN`, métrica,
**nenhuma correção automática** — revisão humana. (Gancho para, um dia, o
agente de conciliação do `agent-orchestrator`.)

**Expiração.** Job a cada minuto: `PENDING` com `expires_at < now − 5 min`.
Antes de marcar, **um `GET /cob/{txid}`** — se `CONCLUIDA`, completa em vez
de expirar. Custa uma chamada por expiração e elimina a maioria das
divergências antes de existirem.

## 7. Webhooks de saída: biblioteca `webhook-delivery` extraída do Barrier

O `services/webhook-api` do Barrier já é a máquina de entrega que esta seção
especificaria: HMAC `t=<ts>,v1=<hex>` com instante dentro da assinatura,
rotação de segredo com janela de sobreposição, ordenação por `partitionKey`
com três travas (`SKIP LOCKED` + filtro no lote + advisory lock),
reivindicação em transação curta com POST fora dela, lease, backoff com
teto, `DEAD`, reconciliação, virtual threads com semáforo como teto. Cada
trava tem o incidente documentado. Reescrever seria repagar.

**Decisão: extrair como biblioteca Maven `webhook-delivery`** (sub-projeto
próprio, antes do gateway), com:

- entrada abstrata `DeliveryIntake.accept(tenantId, eventType, partitionKey,
  eventId, payload)` — o Barrier alimenta do Kafka, o gateway do outbox;
- `WebhookEndpoint` com `id` e `events[]`: N endpoints por tenant, filtro por
  tipo de evento;
- controllers **fora** da lib — cada produto expõe a API que quer (admin no
  Barrier; self-service no gateway: listar entregas, ver erro, reenviar).

- **Rejeitado — serviço compartilhado:** quebra "sem Kafka" e "um deploy", e
  acopla a operação de um KYC e um gateway de pagamento.
- **Rejeitado — copiar:** o próximo incidente é corrigido em uma cópia só.
- **Custo de estar errado:** algo que o gateway precise e a lib não dê vira
  versão nova da lib; o Barrier como consumidor obriga a mudança a ser
  compatível.

No gateway: `partitionKey = payment_id` (o merchant nunca recebe `completed`
antes de `pending`), `tenantId = merchant_id`, `X-Gateway-Signature`,
`X-Gateway-Event-Id`. Eventos: `payment.{pending,completed,expired,canceled,
failed}`, `refund.{completed,failed}`, `subscription.{cycle_created,
cycle_paid,past_due,canceled}`.

## 8. Observabilidade e segurança

- OpenTelemetry via Micrometer; trace com `merchant_id`, `payment_id`,
  `provider`; `correlation_id` da borda ao provider (header) e ao webhook de
  saída. Métricas por provider: latência p50/95/99 por operação, erros por
  código normalizado, tokens renovados, webhooks recebidos/processados/
  duplicados/rejeitados, jobs por status, `reconciliation_divergences_open`.
- Log JSON. CPF, `pix_copia_e_cola`, tokens, certificados **nunca** em log —
  `Masker` central, com teste.
- API key por merchant e ambiente (`live`/`test`), hash SHA-256 + pepper,
  prefixo `gk_live_…`/`gk_test_…`; rotação com duas ativas por até 24 h.
- Credenciais de provider, certificados e CPF do pagador cifrados com
  **AES-256-GCM envelope**: chave de dados por merchant, cifrada por chave
  mestra (variável de ambiente no MVP, KMS na Fase 2). Decifra só na chamada;
  nada em cache além do token OAuth. CPF tem hash separado para busca.
- Rate limit por API key (Bucket4j).
- Sem `merchant_id` em URL; `customer.document` só no corpo.

## 9. Modelo de dados (Fase 1)

Um schema por módulo. Só o essencial; tipos e índices que a §3 exige.

```
merchants.merchants(id, name, status, created_at)
merchants.api_keys(id, merchant_id, env, prefix, key_hash, active, expires_at, created_at)          UNIQUE(prefix)
merchants.provider_credentials(id, merchant_id, provider, env, enc_payload, dek_enc, active, …)   UNIQUE(merchant_id, provider, env)
merchants.merchant_webhooks  → lib webhook-delivery (endpoint, secret, events[])

orders.orders(id, merchant_id, customer_ref, reference, description, amount BIGINT, currency, status, subscription_cycle_id?, created_at)
orders.order_items(id, order_id, description, quantity, unit_amount)
orders.plans(id, merchant_id, name, amount, interval, interval_count, allowed_methods[], status)
orders.subscriptions(id, merchant_id, plan_id, customer_ref, status, collection_mode, billing_anchor_day, current_cycle_start, current_cycle_end, next_billing_at, grace_days)
orders.subscription_cycles(id, subscription_id, n, order_id, period_start, period_end, status)

payments.payments(id, merchant_id, provider, method, status, amount, currency, reference, customer_document_enc, customer_document_hash, details JSONB, expires_at, paid_at, refunded_amount, version, created_at, updated_at)
        UNIQUE(provider, (details->>'txid'))
payments.payment_events(id, payment_id, sequence, type, source, payload, created_at)               UNIQUE(payment_id, sequence)
payments.refunds(id, payment_id, amount, status, provider_refund_id, created_at)
payments.idempotency_keys(merchant_id, key, request_hash, status, response_code, response_body, resource_id, created_at)  PK(merchant_id, key)
payments.webhook_inbox(id, provider, merchant_id, raw_headers, raw_body, signature_ok, status, error, received_at)
payments.provider_requests(id, payment_id, provider, operation, request, response, status, latency_ms, created_at)
payments.outbox(id, aggregate_id, type, payload, status, claimed_at, created_at)
payments.jobs(id, type, ref_id, next_run_at, attempts, status, claimed_at, last_error)
payments.reconciliation_divergences(id, payment_id, gateway_status, provider_status, detail, status, created_at)
```

## 10. API (Fase 1)

```
POST   /v1/orders                         {amount, reference, description?, items?, customer?, charge?: {method: PIX, expires_in?}}
GET    /v1/orders/{id}
GET    /v1/orders?…                       paginado por cursor
POST   /v1/orders/{id}/payments
GET    /v1/payments/{id}
GET    /v1/payments?…
POST   /v1/payments/{id}/cancel
POST   /v1/payments/{id}/refunds          {amount?}      (total se omitido)
GET    /v1/payments/{id}/refunds
POST   /v1/plans · GET /v1/plans/{id}
POST   /v1/subscriptions · GET /v1/subscriptions/{id} · POST …/{id}/cancel · POST …/{id}/pause · POST …/{id}/resume
PUT/GET/DELETE /v1/webhooks/endpoints/{id} · POST …/{id}/rotate-secret
GET    /v1/webhooks/deliveries?… · POST …/{id}/retry
POST   /v1/providers/{provider}/webhooks/{merchant_token}       (entrada; sem API key)
```

Toda mutação aceita `Idempotency-Key`. Resposta de payment Pix: `{id, status,
method, provider, amount, pix: {txid, copia_e_cola, qr_code_base64?},
expires_at, …}`.

## 11. Testes

- **Domínio puro**: tabela de transições percorrida por teste parametrizado
  (todas as permitidas passam; toda combinação fora da tabela é recusada);
  regras de devolução; `next_billing_at` com âncora (31, bissexto, fuso).
- **Módulo com Postgres** (Testcontainers): idempotência sob concorrência (N
  threads, mesma chave → 1 payment); optimistic lock; outbox no mesmo commit
  (rollback do payment = nada no outbox); `SKIP LOCKED`; expiração com `GET`
  antes de marcar.
- **Contrato de provider** (WireMock + OpenAPI): sucesso; timeout no `PUT`
  com `GET` encontrando a cob; 5xx; token expirado no meio; webhook com
  assinatura inválida, duplicado, fora de ordem; `CONCLUIDA` após
  `EXPIRED`. Todo payload enviado validado contra o schema. Perfil
  `provider.live` para o sandbox.
- **Arquitetura** (ArchUnit): as regras da §2, cada uma um teste com nome.
- **Ponta a ponta** (`FakePixProvider`): order com `charge` → `pending` no
  webhook do merchant → pagamento simulado → `completed`; devolução parcial e
  total; ciclo de assinatura → order + payment; webhook de saída verificável
  com o segredo.
- **O teste que protege dinheiro** — caos de retry: cliente repete `POST`
  com mesma chave, provider dá timeout em 30% e 5xx em 10%, expiração e
  reconciliation rodando junto; ao final, cobranças no fake provider ==
  payments `PENDING|COMPLETED`, sem exceção. Roda no CI.
- **Nenhum teste fala com a rede** — `SocketFactory` que recusa hosts externos
  no perfil de teste.

## 12. Critérios de pronto do MVP (Fase 1)

1. Merchant cria Pix via `POST /v1/orders {charge:true}` sem conhecer o Itaú;
   `pix_copia_e_cola` em **p95 < 1,5 s** (WireMock com 200 ms injetados).
2. Caos: **0 cobranças duplicadas** em 10 000 operações.
3. Webhook do provider processado em **p95 < 5 s**; duplicado e fora de ordem
   nunca geram transição inválida.
4. Webhook do merchant entregue em **< 30 s** da transição, assinado, com
   retry e reenvio pela API.
5. Devolução parcial e total, soma ≤ valor, idempotente.
6. Assinatura mensal gera o ciclo no dia certo; `PAST_DUE` após `grace_days`.
7. `payment_events` reconstrói o estado atual (replay).
8. Métricas por provider em `/actuator/prometheus`; CPF e `copia_e_cola`
   ausentes dos logs.
9. ArchUnit verde; `FakePixProvider` prova que `payments` não conhece Itaú.
10. Dump do banco não contém credencial nem CPF em claro.

## 13. Não-escopo (Fase 1)

Boleto, cartão, Pix Automático, routing SMART, ledger/settlement/fees,
dashboard, multi-instância (Redis), KMS, sandbox real do Itaú, trial/proration,
e-mail ao merchant por endpoint desativado. **Ser adquirente ou
sub-adquirente**: não-objetivo do produto, não só da fase.

## 14. Ordem de execução

1. **Sub-projeto 1 — extrair `webhook-delivery`** do Barrier (spec própria,
   curta; trabalho bounded no repo do Barrier).
2. **Sub-projeto 2 — este gateway**, consumindo a lib desde a §7.
