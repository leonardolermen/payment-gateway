# Cartão de crédito pela Cielo — design

Data: 2026-09-28. Fase 3 da spec original (`2026-09-23-payment-gateway-design.md`, §1.2). Assume a
Fase 1 da strategy por método (`2026-09-25-payment-method-and-provider-strategy-design.md`) e a
Fase 2 do padrão de código (PR #11) como base: um `MethodProvider` por método, um `PaymentFlow` por
método, comando e request selados, `PaymentService` dividido por conceito.

Decisões do usuário nesta sessão (2026-09-28): (1) o número do cartão **chega pelo gateway** em
`POST /v1/payments`; (2) captura automática por padrão, autorização separada com `capture: false`;
(3) 1 a 12 parcelas, sempre `ByMerchant`; (4) cartão guardado (`save_card` → `card_id`) já nesta fase.

## 1. A Cielo, nas palavras da doc

Fonte: docs.cielo.com.br/ecommerce-cielo (API E-commerce Cielo, lida em 2026-09-28; índice em
`/ecommerce-cielo/llms.txt`). O manual antigo em developercielo.github.io foi descontinuado em
2024-08-14 e não é fonte. A Cielo não publica OpenAPI: os exemplos de request e response das páginas
de referência são a fonte dos fixtures, copiados verbatim e anotados com a URL.

| item | fato |
|---|---|
| base | transacional `https://api.cieloecommerce.cielo.com.br`, consulta `https://apiquery.cieloecommerce.cielo.com.br`; sandbox `https://apisandbox.cieloecommerce.cielo.com.br` e `https://apiquerysandbox.cieloecommerce.cielo.com.br` |
| auth | headers `MerchantId` (GUID) e `MerchantKey` (40 chars), `RequestId` opcional (GUID). Sem mTLS, sem OAuth. Sandbox: credenciais criadas em cadastrosandbox.cieloecommerce.cielo.com.br, sem contrato |
| autorizar | `POST /1/sales` com `MerchantOrderId` (alfanumérico; acima de 20 chars a Cielo regenera e devolve `SentOrderId`), `Customer{Name, Identity?, IdentityType?, Email?}`, `Payment{Type: "CreditCard", Amount (centavos), Installments, Capture, SoftDescriptor (13), Interest: "ByMerchant", CreditCard{CardNumber, Holder (25, sem acento), ExpirationDate "MM/AAAA", SecurityCode, Brand, SaveCard}}`. 201 com `Payment{PaymentId, Status, ReturnCode, ReturnMessage, Tid, AuthorizationCode, ProofOfSale, Amount, CapturedAmount, ReceivedDate, CapturedDate, CreditCard{CardNumber mascarado, Brand, CardToken?}, Links}` |
| status | `0` NotFinished, `1` Authorized, `2` PaymentConfirmed (capturado), `3` Denied, `10` Voided, `12` Pending, `13` Aborted, `14` Processing, `15` Refunded (a lista antiga trazia `11` Refunded; a atual, `15` — o mapeamento aceita os dois) |
| capturar | `PUT /1/sales/{PaymentId}/capture?amount=` — sem `amount` captura tudo; **uma captura parcial no máximo** e nenhuma captura depois dela; mínimo 20 centavos; ~50 tentativas antes do erro 841 |
| cancelar / devolver | `PUT /1/sales/{PaymentId}/void?amount=` — antes da captura só total (`Status 10`); depois da captura é estorno (`Status 15`/`11`), parcial permitido e repetível até o total; corpo opcional `{"Reason":"HighRisk"}` (obrigatório para fraude a partir de 2026-04-17). Síncrono |
| consultar | `GET /1/sales/{PaymentId}` (apiquery); `GET /1/sales?merchantOrderId=` devolve a lista de `Payments` do pedido |
| token | `POST /1/card {CustomerName, CardNumber, Holder, ExpirationDate, Brand}` → `CardToken` (GUID, preso ao `MerchantId`); ou `SaveCard: true` na autorização. Pagar com token: `Payment.CreditCard.CardToken` (+ `Brand`; `SecurityCode` opcional) |
| card on file | com token: `CreditCard.CardOnFile{Usage: "First" ou "Used", Reason: "Recurring" / "Unscheduled" / "Installments"}`; Mastercard exige `Payment.InitiatedTransactionIndicator{Category: "C1" / "M1" / "M2", Subcategory}` |
| erro HTTP | 400 com lista `[{Code, Message}]`; 401 credencial; 500. Negativa **não é erro HTTP**: é 201 com `Status 3` e `ReturnCode`/`ReturnMessage` do emissor |
| notificação | "Post de Notificação": URL HTTPS configurada no site da Cielo (uma por loja), corpo `{PaymentId, ChangeType, RecurrentPaymentId?}`; `ChangeType` 1 status, 2 recorrência criada, 3 antifraude, 4 status de recorrência, 5 cancelamento negado, 8 alerta de fraude, 25 cancelamento parcial. Até 3 headers fixos configuráveis (é a autenticação: não há assinatura). Reenvio a cada 30 min, 3 tentativas, espera 200 |
| sandbox | cartão de teste: o último dígito decide — 0/1/4 autoriza (`ReturnCode` 4 ou 6), 2 negado (05), 3 vencido (57), 5 bloqueado (78), 6 timeout (99), 7 cancelado (77), 8 problemas (70), 9 aleatório. CVV e validade livres. Códigos de retorno do sandbox diferem dos de produção |

O que fica para confirmar no smoke (registrado em `docs/providers/cielo/NOTES.md`): o `SentOrderId`
quando `MerchantOrderId` tem mais de 20 chars; se o void depois de captura responde 15 ou 11; se o
sandbox dispara notificação; se `SecurityCode` é aceito como ausente com `CardToken`.

## 2. Escopo

Dentro: cartão de crédito com cliente presente, à vista ou parcelado, captura automática ou
posterior, cancelamento antes da captura, estorno total e parcial depois, cartão guardado por token e
cobrança com `card_id` (cliente presente), notificação da Cielo como gatilho, reconciliação de
autorizações sem captura, provider `CIELO` para o método `CARD`.

Fora (§9): débito e 3DS (na Cielo um exige o outro), Silent Order Post, antifraude, `ByIssuer`,
recorrência programada da Cielo, tratamento de chargeback além do alerta, Zero Auth, conversão de
moeda, roteamento de provider por merchant (o método `CARD` resolve para `CIELO` como `PIX` e
`BOLECODE` resolvem para `ITAU`).

## 3. Kernel

```java
// kernel/payment/PaymentMethod.java
public enum PaymentMethod { PIX, BOLECODE, CARD }

// kernel/provider/card/CardMethodProvider.java
public interface CardMethodProvider extends MethodProvider<CardIssueRequest, CardAuthorization, CardStatus> {
  CardAuthorization capture(ProviderCredentials credentials, String bankReference, Optional<Money> amount);
  CardRefundResult refund(ProviderCredentials credentials, String bankReference, Optional<Money> amount);
  StoredCard tokenize(ProviderCredentials credentials, CardData card, String customerName);
  /** Notification body → the bank reference it is about; no credential, like the Pix webhook. */
  ProviderWebhookEvent parseWebhook(byte[] body);
}

// kernel/provider/card/CardIssueRequest.java
public record CardIssueRequest(
    String merchantOrderId,           // bank reference we send: the payment id
    Money amount, int installments, boolean capture, boolean saveCard, String softDescriptor,
    CardSource source,                // sealed: CardData | CardToken
    Payer payer) {}                   // kernel/party Payer (name required, document optional)

public sealed interface CardSource permits CardData, CardToken {}
public record CardData(String number, String holder, YearMonth expiry, Secret securityCode, CardBrand brand) implements CardSource {
  // canonical constructor: Luhn, expiry >= current month, holder ASCII ≤ 25, brand consistent with the BIN when known
  // toString prints brand + last4 only; number never leaves this record except through CieloSalesClient
}
public record CardToken(String value, CardBrand brand, CardOnFileUsage usage) implements CardSource {}

public record CardAuthorization(
    String paymentId, CardStatus status, String returnCode, String returnMessage,
    String tid, String authorizationCode, String proofOfSale,
    Money amount, Money capturedAmount, CardBrand brand, String last4,
    Optional<String> cardToken, Instant receivedAt, Optional<Instant> capturedAt) {}

public enum CardStatus { NOT_FINISHED, AUTHORIZED, PAID, DENIED, VOIDED, PENDING, ABORTED, PROCESSING, REFUNDED }
public record CardRefundResult(CardStatus status, Money refundedAmount, String returnCode, String returnMessage) {}
public record StoredCard(String token, CardBrand brand, String last4, YearMonth expiry) {}
public enum CardBrand { VISA, MASTER, AMEX, ELO, HIPERCARD, DINERS, DISCOVER, JCB, AURA }
```

`find(credentials, bankReference)` consulta por `PaymentId` (o `bankReference` do cartão é o
`PaymentId` da Cielo, não o `MerchantOrderId`: é ele que captura, cancela e consulta usam). A busca
por `MerchantOrderId` existe só na recuperação após timeout (`findByOrder` na extensão).

`ProviderGateway` ganha `resolveCard(merchantId, environment)` com provider fixo `CIELO`.
`PaymentService.PROVIDER = "ITAU"` deixa de ser uma constante única: cada flow declara o seu
(`PixPaymentFlow` e `BolecodePaymentFlow` "ITAU", `CardPaymentFlow` "CIELO"). Roteamento por
merchant continua fora.

## 4. Domínio (`gateway-payments`)

Estado novo `AUTHORIZED`. Transições de cartão (fontes entre parênteses):

| de → para | quando |
|---|---|
| `CREATED → COMPLETED` (API) | `capture: true`, Cielo `Status 2` |
| `CREATED → AUTHORIZED` (API) | `capture: false`, Cielo `Status 1` |
| `CREATED → FAILED` (API, SYSTEM) | `Status 3/13`, ou timeout sem transação encontrada por `MerchantOrderId` |
| `AUTHORIZED → COMPLETED` (API, PROVIDER_WEBHOOK, RECONCILIATION) | captura pelo merchant, ou a Cielo mostra `Status 2` (capturado por fora) |
| `AUTHORIZED → CANCELED` (API, PROVIDER_WEBHOOK, RECONCILIATION) | void, ou a Cielo mostra `Status 10` |

Cartão não passa por `PENDING` nem `EXPIRED`. `Payment.markAuthorized(details)`,
`markCaptured(capturedAmount, at)`, e o `markCompleted` existente para a captura automática.
`paid_amount` é o `CapturedAmount`. `details.card = {paymentId, tid, authorizationCode, proofOfSale,
brand, last4, installments, capturedAmount, cardId?}`; nunca número, validade ou CVV.

Devolução de cartão reusa `Refund`: `RefundService` resolve o provider pelo método do pagamento; para
`CARD` chama `refund` e fecha na mesma transação (`REQUESTED → COMPLETED | FAILED`), sem job de
polling; parcial permitido (só após captura, o que já é verdade: só `COMPLETED` devolve); soma ≤
`paid_amount`. `PENDING → …` do Pix não muda.

Cartão guardado: `payments.cards(id CHAR(26), merchant_id, provider, environment, token_ciphertext,
brand, last4, expiry_month, expiry_year, holder, customer_document_hash, created_at, deleted_at)`.
`token_ciphertext` usa o envelope AES-256-GCM dos merchants (`EnvelopeCipher`, AAD =
`merchant|provider|environment|card`). `DELETE /v1/cards/{id}` marca `deleted_at`; a Cielo não tem
exclusão de token. `card_id` de outro merchant é `NOT_FOUND`, nunca 403.

## 5. Providers (`gateway-providers/cielo/`)

- `CieloCredentials {merchant_id, merchant_key}` (mesmo formato nos dois ambientes), validação de
  formato (GUID; 40 chars), `fingerprint` como no Itaú, `toString` mascarado.
- `CieloEndpoints` (api, apiquery) por ambiente; overrides em `application.yml`.
- `CieloSalesClient`: `authorize`, `capture`, `void`, `findByPaymentId`, `findByMerchantOrderId`.
  Headers `MerchantId`, `MerchantKey`, `RequestId` = correlation id. Timeout de leitura 30 s (a
  autorização pode demorar; o `Status 0` cobre o resto).
- `CieloCardClient`: `POST /1/card`.
- `CieloCardProvider implements CardMethodProvider`.
- `CieloErrors`: 400 `[{Code, Message}]` → `INVALID` (com `Code` no `providerType`); 401 →
  `UNAUTHENTICATED`; 5xx → `UNAVAILABLE`; timeout → `TIMEOUT`; `Status 3` **não** é exceção: é uma
  `CardAuthorization` com `DENIED` — negativa é resultado de negócio, e o flow decide.
- `CieloDeclines`: `ReturnCode` → `decline_code` nosso (`INSUFFICIENT_FUNDS`, `EXPIRED_CARD`,
  `BLOCKED_CARD`, `CANCELED_CARD`, `TIMEOUT`, `DO_NOT_HONOR`, `GENERIC`), tabela medida contra a doc de
  códigos de negócio; `ReturnMessage` nunca vai para o merchant.
- Texto: `Holder` sem acento (transliterado), `SoftDescriptor` até 13 chars alfanuméricos,
  `Customer.Name` só letras.
- Fixtures em `src/test/resources/cielo/fixtures/*.json` com a URL da página de origem no README;
  WireMock para todos os contratos; o teste de mascaramento (§7) roda por cima do `provider_requests`.

## 6. Fluxo (`gateway-payments/payment/create/CardPaymentFlow`)

1. `CreateCardPayment` (comando selado) chega validado: `CardData` já construído (Luhn, validade,
   bandeira) ou `card_id` resolvido em `CardToken` (linha em `cards` do merchant, não excluída).
2. `CREATED` gravado sem nenhum dado de cartão (`PaymentDraftFactory`).
3. `authorize` fora de transação, `MerchantOrderId = payment.id()`.
4. Resposta: `Status 2` → `COMPLETED` (`paid_at = CapturedDate`, `paid_amount = CapturedAmount`),
   outbox `payment.completed`; `Status 1` → `AUTHORIZED`, outbox `payment.authorized`; `Status 3/13`
   → `FAILED` com `decline_code`, outbox `payment.failed`; `Status 0/14` ou timeout → consulta por
   `MerchantOrderId`: achou, adota pelo status; não achou, `FAILED` (na Cielo não há 202 "em
   andamento" que justifique esperar, e a "Garantia de Cancelamento" estorna sozinha o que ficou
   autorizado em `Status 0`).
5. `save_card`: o `CardToken` da resposta vira linha em `cards` na mesma transação da adoção; o
   `card_id` vai em `details.card.cardId` e na resposta. Se a Cielo não devolveu token, o pagamento
   segue e o merchant recebe `card_id: null` (nunca falha um pagamento aprovado por causa do token).
6. Cobrança com `card_id`: `CardToken` + `CardOnFile{Usage: "Used", Reason: "Unscheduled"}` +
   `InitiatedTransactionIndicator{Category: "C1", Subcategory: "CredentialsOnFile"}` (cliente
   presente). A primeira cobrança com `save_card` vai com `Usage: "First"`. A recorrência
   (`M1/Recurring`) entra na fase de assinaturas.

`POST /v1/payments/{id}/capture {amount?}` (`CardCapture`): só `AUTHORIZED`; valor ≤ autorizado e ≥
20 centavos; a Cielo aceita uma parcial só, então a segunda chamada é `ALREADY_CAPTURED`;
`capture` → `COMPLETED`, outbox `payment.completed`. `cancel` em `AUTHORIZED` → `void` → `CANCELED`.
`cancel` em `COMPLETED` → `INVALID_STATE` (use `refunds`), como hoje.

Idempotência: a chave cobre `create`, `capture` e `refunds`, escopo por ambiente, como hoje. Uma
autorização repetida sem chave cria outra transação na Cielo: é o comportamento documentado, e o
`README` avisa.

## 7. PCI: uma regra, um teste

O número completo e o CVV existem em memória, dentro de `CardData`, entre a desserialização do
request e a chamada HTTP à Cielo, e em nenhum outro lugar:

- `CardData` não é serializável pelo Jackson (sem getters públicos expostos, `@JsonIgnoreType`),
  `toString` = `brand ****last4`, o CVV é `Secret`.
- `provider_requests` recebe o corpo com `CardNumber` → `first6******last4` e `SecurityCode` → `***`
  antes de gravar; a resposta da Cielo já vem mascarada.
- `Masker` (logs) ganha o padrão de PAN: sequências de 13–19 dígitos que passam em Luhn viram
  `****last4`; e `"SecurityCode"\s*:\s*"\d+"`.
- `PaymentEvents`, outbox, `payment_events.payload`, `ProviderException.message`, `DomainException`
  nunca recebem `CardData`.
- Teste `CardDataNeverLeavesTheRequestTest` (app): roda um fluxo inteiro com um cartão de teste e
  depois varre `provider_requests`, `payment_events`, `outbox`, `webhook_inbox` e o log capturado
  procurando o número completo e o CVV; falha se achar. Esse teste é a definição de pronto da fase.

Resposta ao merchant e `GET /v1/cards/{id}`: `brand`, `last4`, `expiry` (`MM/YYYY`), `holder`.

## 8. Notificação e reconciliação

- `POST /v1/providers/cielo/webhooks/{token}`: sem mTLS (a Cielo não oferece). Autenticação = o
  token da URL (por merchant, como o do Itaú) **mais** o header `X-Gateway-Notification-Key` com o
  valor que o merchant configura no site da Cielo (guardado como `inbound_webhook_secret` do
  merchant, comparado em tempo constante). Sem os dois, 404. Corpo `{PaymentId, ChangeType}`: vai para
  a `webhook_inbox` e o job consulta `GET /1/sales/{PaymentId}`; a verdade é a consulta.
- `ChangeType 1` e `25`: aplica o status (`AUTHORIZED → COMPLETED/CANCELED`, `COMPLETED` + estorno
  fora do gateway → divergência `REFUNDED_AT_PROVIDER`); `5` → divergência `VOID_DENIED`; `8` →
  divergência `FRAUD_ALERT`; `2/3/4/6` → evento `ignored`.
- Reconciliação: `AUTHORIZED` há mais de `cardCaptureDeadline` (padrão 5 dias) → divergência
  `CAPTURE_OVERDUE` (a Cielo não expira a autorização sozinha e o limite fica preso no cartão do
  cliente); `AUTHORIZED`/`COMPLETED` dos últimos `reconciliationLookback` conferidos por
  `GET /1/sales/{PaymentId}` (custa uma chamada por pagamento; janela e cap por método como o boleto).

## 9. API (`gateway-app/api/payment`)

`CardPaymentRequest` (`method: "CARD"`, variante selada):

```json
{
  "amount": 12990, "currency": "BRL", "method": "CARD", "reference": "order-42",
  "description": "Pedido 42", "soft_descriptor": "LOJA42",
  "card": {"number": "4024007153763191", "holder": "JOAO DA SILVA", "expiry": "12/2030", "cvv": "123", "brand": "VISA"},
  "installments": 3, "capture": true, "save_card": true,
  "customer": {"name": "Joao da Silva", "document": "12345678901", "email": "joao@example.com"}
}
```

`card` **ou** `card_id` (nunca os dois; `cvv` opcional com `card_id`). `brand` opcional quando o BIN
identifica. `installments` 1–12, padrão 1; `capture` padrão `true`; `save_card` padrão `false`;
`soft_descriptor` ≤ 13 alfanuméricos; `customer.name` obrigatório. 422 `CARD_INVALID` com o campo
(`card.number`, `card.expiry`…); 402 `CARD_DECLINED` com `decline_code`; 422 `CARD_NOT_FOUND` para
`card_id` inexistente ou excluído.

Resposta 201: `status` `COMPLETED` ou `AUTHORIZED`, e `card{brand, last4, installments,
authorization_code, tid, captured_amount, card_id}`; `pix` e `boleto` nulos.

Endpoints novos: `POST /v1/payments/{id}/capture {amount?}` → 200 com o pagamento; 409
`CAPTURE_NOT_ALLOWED` (não está `AUTHORIZED`), 409 `ALREADY_CAPTURED`, 422 `CAPTURE_AMOUNT_INVALID`.
`GET /v1/cards/{id}`, `DELETE /v1/cards/{id}` → 204. Evento novo ao merchant: `payment.authorized`.

`GET /v1/payments` e o JSON de evento ganham o bloco `card` (nulo nos outros métodos).

## 10. Testes

- kernel: `CardDataTest` (Luhn, validade, `toString` mascarado, bandeira × BIN), `CardStatus`.
- providers: contratos WireMock por operação com os exemplos da doc (autorização aprovada, negada
  `Status 3`, `Status 0`, 400 com lista, 401, timeout; captura total e parcial; void antes e depois da
  captura; consulta por `PaymentId` e por `MerchantOrderId`; `POST /1/card`), `CieloDeclinesTest`
  (tabela), `CieloCredentialsTest`, mascaramento do corpo em `provider_requests`.
- payments: `PaymentTransitionsTest` com `AUTHORIZED`; `CardPaymentFlow` (aprovado com captura,
  autorizado, negado, timeout adota, timeout sem transação falha, `save_card` com e sem token na
  resposta, `card_id` de outro merchant); `CardCapture` (parcial, segunda captura, valor inválido);
  `cancel` em `AUTHORIZED`; devolução síncrona total e parcial e a soma; inbox da Cielo por
  `ChangeType`; reconciliação `CAPTURE_OVERDUE`.
- app: fluxo ponta a ponta com WireMock (`POST` → `capture` → `refunds` → `GET /v1/cards`), contrato
  JSON, o teste PCI do §7, o webhook com e sem o header.
- smoke no sandbox (documentado em `docs/providers/cielo/NOTES.md`, rodado depois com as credenciais
  que o usuário cria): cartões terminados em 1, 2, 6 e 9; captura parcial; void; estorno parcial;
  `save_card` e cobrança com `card_id`; consulta por `MerchantOrderId` com id de 26 chars.

## 11. Decisões (para o `DECISOES.md`)

- **Cartão pelo gateway, e o PAN morre na chamada.** Rejeitado: Silent Order Post (o merchant
  precisaria de credencial SOP e de um passo no front). Custo: o gateway entra no escopo PCI de dados
  em trânsito, pago com a regra e o teste do §7. Custo se errado: um log com PAN é incidente de
  segurança.
- **Negativa é resultado, não exceção.** `Status 3` vira `FAILED` + `decline_code`; exceção é só o
  que impediu a Cielo de responder. Custo se errado: retentativa automática de uma negativa, o que
  as bandeiras penalizam.
- **Timeout sem transação é `FAILED`**, ao contrário do boleto. A Cielo não tem "em andamento" que
  dure, e a Garantia de Cancelamento estorna o `Status 0`. Custo se errado: uma autorização que a
  consulta não achou e depois apareceu fica órfã até a reconciliação apontar.
- **`AUTHORIZED` sem prazo próprio; a reconciliação avisa.** Rejeitado: expirar em N dias como o Pix
  — a Cielo não expira, e cancelar por conta própria libera um limite que o merchant pode querer
  capturar. Custo se errado: limite preso no cartão do cliente até alguém olhar a divergência.
- **`card_id` é nosso, o token é da Cielo, cifrado.** Rejeitado: expor o `CardToken` da Cielo ao
  merchant — prende o merchant à Cielo e o token vira dado sensível na mão dele.

## 12. Emendas ao escrever o plano (2026-09-28, lidas na doc da Cielo e no código)

O plano (`docs/superpowers/plans/2026-09-28-plano-d-cartao-cielo.md`) foi escrito contra as páginas
`.md` da doc e o código da Fase 2; onde discordam desta spec, vale o que está aqui:

1. **Status**: a tabela atual tem `11` Refunded e `20` Scheduled, sem `14`/`15`. Void no mesmo dia
   da venda responde `10` mesmo depois da captura.
2. **CVV com cartão guardado é obrigatório** (`required: [CardToken, SecurityCode]`), não opcional.
3. **Bandeiras**: sem Hipercard. `CardBrand` = Visa, Master, Amex, Elo, Diners, Discover, JCB, Aura.
4. **Parcelas**: `ByMerchant` exige parcela mínima de R$ 5,00 → `INVALID_INSTALLMENTS` abaixo disso.
5. **Card On File** só para Visa, Master e Elo; `InitiatedTransactionIndicator` só Mastercard.
6. **Códigos de negativa** medidos contra a tabela ABECS (`api-codes` não tem os do emissor); no
   sandbox `57` é "vencido", em produção é "não permitido para o cartão" — a tabela segue produção.
7. **Notificação**: responder `200` (não 202); `ChangeType 6` é boleto e `7` é chargeback, ambos
   ignorados; o sandbox envia notificações depois que a URL é cadastrada por e-mail no suporte.
8. **Consultas**: a consulta por pedido escreve `ReceveidDate` (sic); a resposta da captura não traz
   valor nem data capturados, então a captura consulta a venda em seguida; não há 404 documentado
   para `PaymentId` desconhecido (há o código 307).
9. **Exemplos da doc**: o 201 Elo é JSON inválido; 400/401 são strings. O cartão de teste da doc
   (`4024007153763191`) não passa em Luhn; testes e smoke usam números válidos com o último dígito
   certo.
10. **`@JsonIgnoreType` no kernel** não existe (sem Jackson): mixin no app.
11. **`provider_requests` não guarda corpo** (`request = null`): a regra vira "toda mensagem de
    exceção da Cielo passa pelo masker", e o teste PCI continua varrendo a tabela.
12. **`inbound_webhook_secret`** não existe: tabela nova em merchants (V102) com o SHA-256 do header.
13. **`MtlsPortFilter`** hoje devolve 404 para todo `/v1/providers/**` fora da porta mTLS: a cerca
    passa a valer só para os caminhos do Itaú.
14. **Timeout**: `FAILED` só quando a consulta por `MerchantOrderId` responde vazio; se a própria
    consulta falha, fica `CREATED` para o sweeper (que ganha um ramo de cartão).
15. **Cifra do token**: porta `Sealer` no kernel, adaptador `EnvelopeSealer` em merchants, fornecido
    pelo app — payments continua sem importar merchants.
16. `tokenize` fica no contrato e no provider, sem chamador em payments nesta fase.
