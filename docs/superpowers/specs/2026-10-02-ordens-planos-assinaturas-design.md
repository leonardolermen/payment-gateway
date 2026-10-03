# Ordens, planos e assinaturas — design

Data: 2026-10-02. Plano E. Assume Planos A–D em `main` (Pix, Bolecode e cartão Cielo), a strategy por
método (`PaymentFlows`, `MethodProvider`), o registro de jobs (`JobHandler`/`JobHandlers`), o outbox
transacional relido por `OutboxRelay`, e o padrão de código das Fases 1 e 2.

Decisões do usuário nesta sessão (2026-10-02): (1) cobrança de ciclo que falha entra em dunning e deixa
a assinatura inadimplente, **sem cancelar sozinha**; (2) assinatura aceita **qualquer método** — cartão
guardado, Pix ou Bolecode; (3) ordem tem **uma tentativa ativa por vez**, método trocável entre
tentativas; (4) cliente é **recurso do merchant** (`POST /v1/customers`); (5) abordagem **A**: módulo
novo `gateway-billing` acima de `gateway-payments`, reagindo a pagamentos pelo outbox.

## 1. Escopo

Dentro: clientes; ordens como agrupador de tentativas de pagamento com fechamento ao pagar; planos;
assinaturas em qualquer método com ciclo automático, trial, cancelamento imediato ou ao fim do período;
dunning com retentativas configuráveis e estado `PAST_DUE`; eventos de saída de ordem, fatura e
assinatura; consumidor interno do outbox; E2E de assinatura por cartão contra a Cielo.

Fora (§11): cupons e descontos; proration em troca de plano; cobrança proporcional no cancelamento;
reembolso automático do período; múltiplas tentativas abertas em paralelo na mesma ordem; Pix
Automático e recorrência programada da Cielo (o gateway é quem recorre, não o banco); nota fiscal;
dunning configurável por API (fica em propriedade); exclusão física de cliente (LGPD fica como
follow-up: hoje é `deleted_at`).

## 2. Vocabulário

| termo | o que é |
|---|---|
| customer | pessoa ou empresa do merchant, com documento cifrado; dona de cartões guardados e de assinaturas |
| order | uma venda: valor, cliente (ou pagador inline) e as tentativas de pagamento para liquidá-la; vira `PAID` quando uma tentativa paga |
| attempt | um `Payment` com `order_id`; só um pode estar ativo (`CREATED`, `PENDING`, `AUTHORIZED`) por ordem |
| plan | preço e periodicidade; imutável depois de criado exceto nome e ativo |
| subscription | cliente + plano + método; gera uma order por ciclo (a **fatura** é a order com `invoice_number`) |
| dunning | retentativas de cobrança de uma fatura em aberto, nos dias configurados |

Não existe entidade "invoice": a order de uma assinatura é a fatura. Isso evita duas máquinas de estado
dizendo a mesma coisa.

## 3. Módulo `gateway-billing`

Novo módulo Maven entre `gateway-payments` e `gateway-app`. Pacotes por conceito:

```
com.gateway.billing
  customer/        Customer, CustomerFactory, Customers (port), CustomerService, persistence/
  order/           Order, OrderStatus, OrderTransitions, Orders (port), OrderService,
                   OrderPayments (cria a tentativa via PaymentFlows), OrderSettlement (reage ao outbox),
                   persistence/
  plan/            Plan, PlanInterval, Plans (port), PlanService, persistence/
  subscription/    Subscription, SubscriptionStatus, SubscriptionTransitions, Subscriptions (port),
                   SubscriptionService, BillingCalendar, persistence/
  subscription/billing/   BillSubscriptionJob, DunningRetryJob, DunningSchedule, DunningAttempts
  order/ExpireOrderJob    JobType.EXPIRE_ORDER, agendado em expires_at da ordem (§4.2)
  BillingProperties, BillingConfiguration
```

Dependências: `kernel`, `payments` (só a API pública: `PaymentFlows`, `CreatePaymentCommand`s,
`PaymentQueries`, `PaymentCancellation`, `SavedCards`, `JobHandler`, `Divergences`, `OutboxRepository`
para escrever eventos), `merchants` (`Sealer`). ArchUnit novo: `paymentsDoesNotImportBilling`,
`billingImportsOnlyKernelPaymentsMerchants`, guarda de contagem de classes própria do módulo.

Schema Flyway próprio: `billing`, migrações `V301__customers.sql`, `V302__plans.sql`,
`V303__orders.sql`, `V304__subscriptions.sql`, mais `V205__payments_order_id.sql` em `payments`.

## 4. Modelo de dados

### 4.1 `billing.customers`

```sql
id CHAR(26) PK, merchant_id CHAR(26), environment VARCHAR(10), name VARCHAR(120),
document_ciphertext BYTEA, document_hash CHAR(64), document_kind VARCHAR(4) (CPF|CNPJ),
email VARCHAR(254) NULL, address JSONB NULL, version BIGINT, created_at, updated_at, deleted_at
UNIQUE (merchant_id, environment, document_hash) WHERE deleted_at IS NULL
```

`address` tem a mesma forma do `customer.address` do Bolecode (`street, district, city, state, zip`),
validado pelos mesmos value objects (`Uf`, `ZipCode`). O documento é cifrado com o `Sealer` (AAD
`merchant|customer`), e o hash é o `CustomerDocumentHash` que já existe em `payments`, para que cartões
guardados antes desta fase (que só têm o hash) sejam adotados pelo cliente na criação: ao criar um
cliente, `cards` com o mesmo `customer_document_hash` e `customer_id` nulo passam a apontar para ele.

`payments.cards` ganha `customer_id CHAR(26) NULL` com índice. Um cartão só é cobrado em assinatura se
`customer_id` for o da assinatura (`CARD_NOT_OWNED_BY_CUSTOMER`).

### 4.2 `billing.orders`

```sql
id CHAR(26) PK, merchant_id, environment, customer_id CHAR(26) NULL, payer JSONB NULL,
amount BIGINT, currency CHAR(3), reference VARCHAR(100) NULL, description VARCHAR(200) NULL,
status VARCHAR(10) (OPEN|PAID|CANCELED|EXPIRED), paid_payment_id CHAR(26) NULL, paid_at NULL,
expires_at TIMESTAMPTZ NULL, subscription_id CHAR(26) NULL, invoice_number INT NULL,
period_start DATE NULL, period_end DATE NULL, version BIGINT, created_at, updated_at
INDEX (merchant_id, environment, reference); INDEX (subscription_id, invoice_number) UNIQUE
```

`customer_id` ou `payer` — exatamente um. `payer` é a cópia inline (`name, document, email, address`)
para a ordem avulsa sem cadastro; o documento ali fica **só como hash** mais nome e endereço, porque o
Bolecode precisa deles na emissão e nada mais precisa do número.

Transições (`OrderTransitions`): `OPEN → PAID` (evento `payment.completed` de uma tentativa);
`OPEN → CANCELED` (merchant); `OPEN → EXPIRED` (job `EXPIRE_ORDER` em `expires_at`, só se não há
tentativa ativa: se há, a tentativa expira primeiro pelo seu próprio prazo e a ordem expira na próxima
passada). Estados finais não voltam. Ordem `PAID` com um segundo `payment.completed` abre divergência
`DOUBLE_PAYMENT` em `reconciliation_divergences` com os dois ids; ninguém reembolsa sozinho.

### 4.3 `payments.payments.order_id`

`order_id CHAR(26) NULL` + índice parcial único
`UNIQUE (order_id) WHERE status IN ('CREATED','PENDING','AUTHORIZED')`. É o banco que garante "uma
tentativa ativa": `OrderPayments` tenta criar, e a violação vira `409 ORDER_HAS_ACTIVE_PAYMENT`. A
tentativa herda `amount`, `currency` e o pagador da ordem; o corpo da request não traz esses campos
(`400 INVALID_REQUEST` se trouxer, como já é a regra para campo de outro método).

`CreatePaymentCommand` ganha `Optional<String> orderId` nos três comandos; `payments` não sabe o que é
uma ordem além de guardar o id e aplicá-lo no evento. A resposta de `GET /v1/payments/{id}` mostra
`order_id`.

### 4.4 `billing.plans`

```sql
id, merchant_id, name VARCHAR(80), amount BIGINT, currency CHAR(3),
interval VARCHAR(5) (DAY|WEEK|MONTH|YEAR), interval_count SMALLINT (1..12),
trial_days SMALLINT (0..365), active BOOLEAN, version, created_at, updated_at
```

Plano não tem ambiente: é catálogo. A assinatura é que é `TEST` ou `LIVE`.

### 4.5 `billing.subscriptions`

```sql
id, merchant_id, environment, customer_id, plan_id, method VARCHAR(10), card_id CHAR(26) NULL,
status VARCHAR(10) (ACTIVE|PAST_DUE|CANCELED|ENDED), anchor_day SMALLINT,
current_period_start DATE, current_period_end DATE, next_billing_at TIMESTAMPTZ NULL,
last_invoice_number INT, cancel_at_period_end BOOLEAN, canceled_at NULL, ended_at NULL,
version, created_at, updated_at
INDEX (merchant_id, environment, customer_id); INDEX (next_billing_at) WHERE status IN ('ACTIVE','PAST_DUE')
```

`ENDED` é o fim natural depois de `cancel_at_period_end`; `CANCELED` é o imediato.

### 4.6 `billing.dunning_attempts`

```sql
id, subscription_id, order_id, attempt SMALLINT, scheduled_at, ran_at NULL,
outcome VARCHAR(20) NULL (PAID|DECLINED|ISSUED|EXPIRED|SKIPPED), payment_id CHAR(26) NULL
```

Auditoria: o merchant vê em `GET /v1/subscriptions/{id}` o que o dunning fez e quando.

## 5. API (`gateway-app/api/{customer,order,plan,subscription}`)

Chave do merchant; `Idempotency-Key` obrigatório nos `POST` que criam ou cobram, com o mesmo filtro e
HMAC de hoje. Documento sempre mascarado na resposta (`***.***.247-25`).

### Clientes
- `POST /v1/customers` `{name, document, email?, address?}` → 201. Mesmo documento no merchant/ambiente
  → `409 CUSTOMER_EXISTS` com `customer_id` existente no corpo.
- `GET /v1/customers/{id}`; `GET /v1/customers?document=`; `PATCH /v1/customers/{id}` (`name`, `email`,
  `address`; documento não muda → `422 DOCUMENT_IMMUTABLE`); `DELETE /v1/customers/{id}` lógico, recusado
  com `409 CUSTOMER_HAS_ACTIVE_SUBSCRIPTION` se há assinatura `ACTIVE`/`PAST_DUE`.
- `GET /v1/customers/{id}/cards` — os cartões guardados dele; `DELETE /v1/cards/{id}` já existe.

### Ordens
- `POST /v1/orders` `{amount, currency, reference?, description?, customer_id | customer, expires_at?}`
  → 201 `OPEN`.
- `POST /v1/orders/{id}/payments` — corpo de `POST /v1/payments` **sem** `amount`, `currency`, `customer`;
  `save_card` permitido (o cartão nasce ligado ao `customer_id` da ordem). Respostas: 201 com o
  pagamento (mesmo DTO de hoje, com `order_id`); `409 ORDER_CLOSED`; `409 ORDER_HAS_ACTIVE_PAYMENT`
  com `payment_id`; `402 CARD_DECLINED` como hoje (a ordem segue `OPEN`).
- `POST /v1/orders/{id}/cancel` → cancela a tentativa ativa no banco (via `PaymentCancellation`) e a
  ordem; se o banco diz que a tentativa pagou, a ordem vira `PAID` e a resposta é `409 ALREADY_PAID`.
- `GET /v1/orders/{id}` com `payments: [{id, method, status, created_at}]` resumido e `paid_payment_id`;
  `GET /v1/orders?reference=`; `GET /v1/orders/{id}/payments`.

### Planos
- `POST /v1/plans` `{name, amount, currency, interval, interval_count?, trial_days?}` → 201.
- `GET /v1/plans/{id}`, `GET /v1/plans?active=`; `PATCH /v1/plans/{id}` só `name`/`active`; qualquer
  outro campo → `422 PLAN_IMMUTABLE`.

### Assinaturas
- `POST /v1/subscriptions` `{customer_id, plan_id, method, card_id?, start_at?}` → 201 `ACTIVE` com a
  primeira fatura já criada (e cobrada, se `CARD`) ou com `next_billing_at` no fim do trial / `start_at`.
  Validações: plano ativo (`422 PLAN_INACTIVE`); `CARD` exige `card_id` do cliente
  (`422 CARD_REQUIRED`, `422 CARD_NOT_OWNED_BY_CUSTOMER`); `BOLECODE` exige `address` no cliente
  (`422 CUSTOMER_ADDRESS_REQUIRED`); cliente ativo (`404 CUSTOMER_NOT_FOUND`).
- `POST /v1/subscriptions/{id}/cancel` `{at_period_end: bool}` (default `true`).
- `PATCH /v1/subscriptions/{id}` `{method, card_id?}` — vale a partir da próxima fatura; a fatura
  aberta não muda.
- `GET /v1/subscriptions/{id}` com `current_period`, `next_billing_at`, `latest_order`, `dunning:
  [{attempt, scheduled_at, outcome}]`; `GET /v1/subscriptions?customer_id=`;
  `GET /v1/subscriptions/{id}/orders` (as faturas, mais recente primeiro).

## 6. Ciclo de assinatura (`BillSubscriptionJob`, `JobType.BILL_SUBSCRIPTION`)

Agendado para `next_billing_at` com `refId = subscription_id`. `run`:

1. **Transação 1**: relê a assinatura com lock; se não está `ACTIVE`/`PAST_DUE`, ou `next_billing_at` é
   futuro (job duplicado), encerra `true`. Calcula o período (`BillingCalendar`), cria a order da fatura
   (`invoice_number = last + 1`, `amount` do plano, `expires_at = fim do período 23:59:59 SP`), avança
   `current_period_*`, `last_invoice_number`, `next_billing_at`, grava `invoice.created` **só depois** da
   tentativa (abaixo) e agenda o próximo `BILL_SUBSCRIPTION`. Commit.
2. **Fora de transação**: `OrderPayments.attempt(order, method, card)` cria a tentativa com o fluxo do
   método — é o mesmo caminho de `POST /v1/orders/{id}/payments`, `EventSource.SYSTEM`.
   - `CARD`: `CreateCardPayment` com `CardChoice` por `card_id` **sem CVV** (card on file `Used`,
     `Reason: Recurring`). Hoje `CardChoice` exige CVV com cartão guardado (DECISOES 2026-09-28, "CVV
     obrigatório com card_id"); esta fase abre a exceção **só para `EventSource.SYSTEM`** — a API
     continua exigindo CVV. O smoke da Cielo (NOTES, passo 9) ainda não provou que a Cielo aceita sem
     `SecurityCode`; se recusar, a assinatura por cartão nasce com `422 CARD_RECURRING_UNSUPPORTED`
     até o smoke confirmar, e o resto do plano não depende disso.
   - `PIX`: `expires_in` até `expires_at` da order (máximo que o Itaú aceita, hoje 86400 s em `cob`;
     se o período for maior, o Pix expira antes e o dunning reemite).
   - `BOLECODE`: `due_date = period_end`, `payment_limit_days` = dias até o próximo ciclo + 7.
3. **Transação 2**: grava `invoice.created` no outbox com o resultado (`payment_id`, método, e para
   Pix/Bolecode o `copia_e_cola`/`linha_digitavel`; para cartão `charged: true|false`). Se a tentativa
   falhou na hora (`CARD_DECLINED`), grava `dunning_attempts[1]` agendado para `+retry_days[0]` e a
   assinatura vai a `PAST_DUE` (`subscription.past_due`). Se o banco caiu na dúvida, nada: o pagamento
   `CREATED` é resolvido pela varredura existente e a ordem segue `OPEN` com a vaga ocupada.

Idempotência: a tentativa usa `Idempotency-Key` sintética `sub:<id>:inv:<n>:try:<k>`, então um job
que morre entre a transação 1 e a 2 e reroda não cobra duas vezes.

### 6.1 `BillingCalendar`

`anchor_day` é o dia de início da assinatura (1–31). `MONTH`: `period_end = min(anchor_day, último dia
do mês alvo)`, calculado a partir do mês do início e não do fim anterior, para voltar a 31 em março.
`YEAR`: mesma regra em 29/02. `DAY` e `WEEK`: soma simples. Tudo em `America/Sao_Paulo`, igual ao
Bolecode. `next_billing_at = period_start 03:00 SP` (depois da virada e fora da janela do boleto).
Trial: `period_start = start + trial_days`, sem fatura antes disso.

## 7. Dunning (`DunningRetryJob`, `JobType.DUNNING_RETRY`)

`BillingProperties.dunning.retryDays = [1, 3, 7]` (propriedade, não API). Cada retentativa é uma linha
em `dunning_attempts` com `scheduled_at`. `run`:

1. Relê order e assinatura. Se a order não está `OPEN`, ou a assinatura está `CANCELED`/`ENDED`:
   `outcome = SKIPPED`, encerra.
2. Se há tentativa ativa na order (Pix/Bolecode ainda dentro do prazo): `notYet` — o job volta no
   próximo `JobBackoff`. Um boleto que ainda pode ser pago não é substituído.
3. Senão, `OrderPayments.attempt` de novo com o método atual da assinatura (`PATCH` pode ter trocado):
   - `CARD` aprovado → `outcome = PAID` (a order fecha pelo evento, §8); recusado → `DECLINED`, agenda
     a próxima retentativa; sem próxima → `subscription.dunning_exhausted`, assinatura **fica**
     `PAST_DUE`.
   - `PIX`/`BOLECODE` → `outcome = ISSUED`, nova cobrança com prazo até a próxima retentativa (ou até
     `expires_at` da order na última); `invoice.updated` leva o novo QR/linha. Expirar sem pagar é o
     evento `payment.expired`, que agenda a próxima.

Um ciclo seguinte continua sendo gerado enquanto `PAST_DUE` (a assinatura cobra; cortar serviço é do
merchant). Duas faturas abertas da mesma assinatura são permitidas; cada uma tem seu dunning.

Cancelamento: `at_period_end` só marca; o `BILL_SUBSCRIPTION` seguinte vê a marca, não cria fatura e põe
`ENDED` (`subscription.ended`). Imediato: cancela a tentativa ativa no banco, a order vigente vai a
`CANCELED`, a assinatura a `CANCELED`; faturas pagas não são reembolsadas.

## 8. Consumidor interno do outbox (`OrderSettlement`)

`OutboxRelay` (gateway-app) hoje reclama a linha e a entrega ao `webhook-delivery`. Passa a, **antes**
de entregar ao merchant, chamar os `OutboxListener`s internos registrados (interface nova em
`payments/outbox`: `void on(OutboxMessage message)`), na mesma passada. Se um listener lança, a linha
não é marcada e a passada seguinte repete — por isso todo listener é idempotente pelo `message.id()`
(tabela `billing.processed_events(event_id PK)` escrita na mesma transação da reação).

`OrderSettlement` escuta `payment.completed`, `payment.failed`, `payment.expired`, `payment.canceled`
cujo payload tem `order_id`:

- `completed` → `Order.markPaid(paymentId, paidAt)`; se já `PAID` por outro → divergência
  `DOUBLE_PAYMENT`. Se a order é fatura: assinatura `PAST_DUE → ACTIVE` (`subscription.recovered`),
  `dunning_attempts` pendentes `SKIPPED`, e `order.paid`.
- `failed`/`expired`/`canceled` → só libera a vaga (nada a escrever na order); se é fatura e não está
  em dunning ainda, cria `dunning_attempts[1]` e `PAST_DUE`; se está, o job de dunning já sabe.

Não há cursor por consumidor: a linha é processada uma vez por todos, interno primeiro. Rejeitado:
tabela `outbox_consumers` com cursor por assinante — resolve um problema (reprocessar só um consumidor)
que esta fase não tem.

## 9. Eventos de saída

Mesmo envelope e assinatura dos atuais. Novos: `customer.created`, `customer.updated`,
`order.created`, `order.paid`, `order.canceled`, `order.expired`, `invoice.created`, `invoice.updated`,
`subscription.created`, `subscription.past_due`, `subscription.recovered`,
`subscription.dunning_exhausted`, `subscription.canceled`, `subscription.ended`. Os de pagamento
continuam saindo como hoje, agora com `order_id` no payload quando houver.

## 10. Erros

Código estável, campo na grafia do cliente, no `ErrorHandler` existente: `CUSTOMER_EXISTS` (409),
`CUSTOMER_NOT_FOUND` (404), `CUSTOMER_ADDRESS_REQUIRED` (422, `customer.address`),
`CUSTOMER_HAS_ACTIVE_SUBSCRIPTION` (409), `DOCUMENT_IMMUTABLE` (422), `ORDER_NOT_FOUND` (404),
`ORDER_CLOSED` (409), `ORDER_HAS_ACTIVE_PAYMENT` (409, com `payment_id`), `PLAN_NOT_FOUND` (404),
`PLAN_INACTIVE` (422), `PLAN_IMMUTABLE` (422), `SUBSCRIPTION_NOT_FOUND` (404),
`SUBSCRIPTION_NOT_ACTIVE` (409), `CARD_REQUIRED` (422), `CARD_NOT_OWNED_BY_CUSTOMER` (422),
`CARD_RECURRING_UNSUPPORTED` (422, só até o smoke confirmar o cartão sem CVV).

## 11. Testes

- Unitários (`gateway-billing`): `BillingCalendar` (dia 31, 29/02, trial, WEEK/DAY), `OrderTransitions`
  e `SubscriptionTransitions` (tabela de transições, finais não voltam), `DunningSchedule` (datas a
  partir de `retryDays`), `CustomerFactory` (documento cifra/hash, endereço opcional).
- Integração (Testcontainers, `gateway-billing`): ordem com duas tentativas sequenciais (Pix expira,
  cartão paga) e índice parcial recusando a segunda ativa; `OrderSettlement` com os quatro eventos e o
  pagamento duplo; ciclo completo com `CardMethodProvider` falso que recusa duas vezes e aprova na
  terceira (`PAST_DUE → ACTIVE`, três linhas de dunning); ciclo Bolecode com `invoice.created` trazendo
  linha digitável e `expired` agendando a retentativa; cancelamento nos dois modos; `PATCH method` valendo
  só na fatura seguinte.
- `gateway-app`: controllers com os erros da §10; ArchUnit das novas regras; `CardDataNeverLeavesTheRequestTest`
  continua cobrindo o caminho novo (o PAN nunca entra em `billing`).
- E2E (`scripts/e2e_sandbox.py`): seção "assinatura por cartão" contra a Cielo: cliente, plano mensal,
  assinatura com `card_id` de um `save_card` anterior, fatura 1 paga na hora, `GET` mostrando
  `next_billing_at`; e "ordem avulsa": Pix criado e cancelado, depois cartão pago, ordem `PAID`.
  O segundo ciclo não é esperado no E2E (relógio real); fica no teste de integração com relógio falso.

## 12. Decisões (para o `DECISOES.md`)

1. **Fatura é ordem**, não entidade própria. Rejeitado: `invoices` com estado espelhando `orders`.
   Custo se errado: relatórios por fatura precisam filtrar `subscription_id IS NOT NULL`.
2. **Uma tentativa ativa por ordem, garantida pelo banco** (índice parcial), não pelo serviço.
   Rejeitado: lock na ordem. Custo: zero em concorrência; o 409 nasce de uma exceção de unicidade.
3. **Dunning não cancela.** Rejeitado: cancelar após N falhas. Custo: assinatura `PAST_DUE` eterna se o
   merchant não agir; o próximo ciclo ainda cobra.
4. **Cartão recorrente sem CVV só para `EventSource.SYSTEM`**, sob a bandeira
   `CARD_RECURRING_UNSUPPORTED` até o sandbox provar. Rejeitado: guardar CVV (PCI proíbe).
5. **Listener interno no `OutboxRelay`, sem cursor por consumidor.** Rejeitado: `outbox_consumers`.
   Custo: não dá para reprocessar só o billing sem reentregar webhooks; se precisar, nasce o cursor.
6. **Plano sem ambiente, assinatura com.** Rejeitado: plano por ambiente. Custo: um plano de teste e um
   de produção são o mesmo registro; o merchant distingue pela chave.
7. **`payments` recebe `order_id` como string opaca.** Rejeitado: `payments` consultar `billing`.
   Custo: a regra "valor da tentativa = valor da ordem" vive em `billing`, não no `Payment`.
