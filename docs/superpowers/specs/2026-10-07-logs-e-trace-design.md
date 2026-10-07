# Logs do fluxo inteiro com trace id e payloads mascarados — design

Data: 2026-10-07. Pedido do usuário (2026-10-07): log do fluxo inteiro com um trace id, com os payloads,
sem nunca mostrar cartão. Retoma a §8 da spec original (`2026-09-23-payment-gateway-design.md`, "correlation
da borda ao provider e ao webhook de saída"), que nunca foi feita. Fora: exportar spans para um coletor
(OpenTelemetry/OTLP), dashboards, retenção de log (é do agregador), o header no webhook de saída (a lib
`webhook-delivery` não aceita headers extras; fica para uma versão dela).

## 1. O que existe
- `CorrelationFilter` aceita/gera `X-Correlation-Id` (ULID), põe no MDC `correlationId` e devolve no
  response — **só na thread do request**. Jobs (`JobScheduler`, `OutboxRelay`, reconciliação, cobrança),
  processamento de webhook do banco e entrega de webhook ao merchant rodam com MDC vazio.
- Nenhuma tabela guarda o id: `outbox`, `jobs`, `webhook_inbox`, `payment_events`, `provider_requests`.
- O banco recebe outro id: Cielo `RequestId` e Itaú `x-itau-correlationID` exigem GUID; o ULID nunca é
  GUID, então cada chamada vai com um UUID aleatório que não é logado (DECISOES 2026-09-28, "Fora").
- Nenhum payload é logado (nem de entrada, nem do banco). Não há log de acesso. Um pagamento feliz não
  gera uma linha INFO sequer.
- `Masker` (regex no encoder) cobre PAN por Luhn, CVV, CPF, `gk_`, `chk_`, `Bearer` e alguns campos
  JSON. Não cobre `CardToken`, validade, `MerchantKey`, CNPJ formatado, PEM, `x-itau-apikey`.
  `CardDataNeverLeavesTheRequestTest` exige que o PAN **nunca chegue a um logger** — não basta o encoder
  apagar.

## 2. Trace id
- **Formato:** 32 hex minúsculos (128 bits), o `trace-id` do W3C. É também um UUID sem hífens: com hífens
  vira o GUID que Cielo e Itaú aceitam, então **o mesmo id vai ao banco** (fecha o "Fora" de 2026-09-28).
- **Entrada:** `traceparent` (W3C) tem precedência; senão `X-Correlation-Id` se for 32 hex ou GUID;
  qualquer outra coisa (inclusive um ULID antigo) é ignorada e um novo é gerado. O response devolve
  `X-Correlation-Id` (o id) e `traceparent`. O filtro sobe para antes de CORS, `PathSanityFilter` e
  `MtlsPortFilter`, para que até um 403 dessas camadas carregue o id.
- **MDC:** `traceId` (substitui `correlationId`; a lib `webhook-delivery` passa a ler `traceId`), mais
  `merchantId`, `orderId`, `paymentId`, `subscriptionId`, `jobType` quando conhecidos. Postos por um
  `LogContext` (kernel, sem Spring: `try (var scope = LogContext.with(...))`), removidos no fim do escopo.
- **Persistido** (`payments V208`): coluna `trace_id CHAR(32)` em `outbox`, `jobs`, `webhook_inbox`,
  `payment_events` e `provider_requests`. Quem grava a linha grava o `traceId` do MDC.
- **Continuado:** quem processa a linha (`JobRunner`, `OutboxRelay`, `ProcessWebhookJob`, a entrega ao
  merchant) abre o `LogContext` com o `trace_id` da linha antes de qualquer log. Um job agendado sem
  origem (reconciliação por cron, varredura) gera o seu e grava nos jobs que criar.
- **Ao banco:** `RequestId` (Cielo) e `x-itau-correlationID` (Itaú) = o trace id em forma de GUID.

## 3. Payloads, sem cartão
**Redação na origem, não no encoder.** `PayloadRedactor` no kernel (puro, sem Jackson: varredura de
JSON por nome de chave, como o `Masker`), aplicado **antes** de chamar o logger. O `Masker` do encoder
continua como segunda camada.

| chave (case-insensitive, qualquer profundidade) | vira |
|---|---|
| `number`, `card_number`, `CardNumber` | `first6******last4` |
| `cvv`, `security_code`, `SecurityCode` | removido (`"***"`) |
| `expiry`, `expiration_date`, `ExpirationDate` | `"**/****"` |
| `CardToken`, `card_token`, `token`, `access_token`, `client_secret`, `secret`, `merchant_key`, `MerchantKey`, `password`, `private_key`, `certificate`, `api_key` | `"***"` |
| `document`, `Identity`, `cpf`, `cnpj`, `TaxId` | `***.***.***-**` com os 2 últimos dígitos |
| `pix_copia_e_cola`, `copia_e_cola`, `pixCopiaECola`, `emv` | primeiros 20 caracteres + `…` |
| `email` | `a***@dominio` |
| `holder`, `Holder` (nome no cartão) | primeira letra de cada palavra + `***` |
| `checkout_url` | o token `chk_` vira `chk_****` |

Além das chaves: qualquer sequência de 13–19 dígitos que passe em Luhn vira `****last4`, `gk_…` e
`chk_…` em qualquer lugar viram `***`. Corpo maior que 8 KB é cortado (`…[truncated N bytes]`); corpo que
não é JSON não é logado (só o tamanho).

**O que se loga:**
- **`http.in`** (filtro, depois da autenticação): método, path com segmentos-segredo trocados
  (`/v1/checkout/{token}`, `/v1/providers/*/webhooks/{token}`), status, duração, merchant, corpo do
  request e do response redigidos. INFO.
- **`bank.call`** (no `ProviderGateway` + clients): provedor, operação, método, path, status, latência,
  `RequestId` enviado, corpo enviado e recebido redigidos. INFO no sucesso, WARN na falha.
- **`webhook.in`**: provedor, headers da whitelist, corpo redigido, quando entra no inbox. INFO.
- **`event`**: uma linha INFO a cada transição de estado (pagamento, ordem, assinatura, reembolso), a cada
  evento no outbox e a cada entrega ao merchant (URL do endpoint, status, tentativa).
- **`job`**: início e fim de cada job com tipo, alvo, duração, resultado.

Chaves: `gateway.logging.http-bodies` (default `true`) e `gateway.logging.bank-bodies` (default `true`)
desligam os corpos sem tirar as linhas. `provider_requests` continua sem corpo (decisão de 2026-09-28):
o lugar do corpo é o log, que tem retenção própria, não o banco.

## 4. Testes
- `PayloadRedactorTest`: cada chave da tabela, aninhada e em lista, Luhn solto no texto, corpo cortado,
  não-JSON, um body real de cada provedor (fixtures de `docs/providers/`).
- `CardDataNeverLeavesTheRequestTest` passa a ligar os corpos e continua exigindo zero PAN/CVV no
  `ListAppender` cru — agora com os payloads de entrada e do banco passando pelo logger.
- Integração: um pagamento Pix do request ao webhook do banco e à entrega ao merchant — **todas** as
  linhas de log têm o mesmo `traceId`; `outbox`, `jobs`, `webhook_inbox`, `payment_events` e
  `provider_requests` gravam o mesmo id; o WireMock recebe `x-itau-correlationID` = o id em GUID.
- `traceparent` válido é adotado; inválido gera novo; `X-Correlation-Id` ULID antigo gera novo.
- Path com token de checkout ou de webhook nunca aparece cru no log.

## 5. Decisões (para o `DECISOES.md`)
1. **Trace id de 128 bits em hex, não ULID.** Rejeitado: manter o ULID (não é GUID, o banco não o aceita,
   e não é W3C). Custo: quem guardava o `X-Correlation-Id` antigo vê um formato novo.
2. **Redação na origem por nome de chave, o encoder como segunda camada.** Rejeitado: só o `Masker` no
   encoder — o teste de cartão exige que o PAN não chegue ao logger. Custo: uma chave nova com dado
   sensível precisa entrar na tabela; o Luhn e o `Masker` pegam o PAN mesmo assim.
3. **Corpo no log, não em `provider_requests`.** Rejeitado: gravar o corpo redigido na tabela. Custo: o
   corpo some com a retenção do log; a tabela continua sendo o registro durável de status e latência.
4. **Sem Micrometer Tracing/OpenTelemetry agora.** Rejeitado: a dependência inteira para ter um id. O
   formato W3C deixa a porta aberta: ligar a exportação depois não muda o id.
