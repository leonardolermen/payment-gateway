# Payment Gateway — Plano D: cartão de crédito pela Cielo

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Um merchant cria um pagamento `CARD` em `POST /v1/payments` com o número do cartão (ou um `card_id` guardado); o gateway autoriza na API E-commerce Cielo (`POST /1/sales`), com captura automática ou posterior (`POST /v1/payments/{id}/capture`), cancela antes da captura (void), devolve total ou parcial depois (void com `amount`, síncrono), guarda o cartão como token cifrado (`save_card` → `card_id`), escuta o Post de Notificação da Cielo e reconcilia autorizações esquecidas — sem que o PAN ou o CVV toquem banco, log, outbox ou evento.

**Architecture:** Um terceiro contrato no `kernel` (`provider/card/CardMethodProvider`) com os value objects do cartão (`CardNumber`, `CardExpiry`, `CardHolder`, `CardData`), implementado em `gateway-providers/cielo/` por dois clientes HTTP (`CieloSalesClient`, `CieloCardClient`) compostos em `CieloCardProvider`. Em `gateway-payments` nasce o estado `AUTHORIZED`, `details.card`, o `CardPaymentFlow` (terceiro `PaymentFlow`), `CardCapture`, o void no cancelamento, `CardRefunds` síncrono, `SavedCards` (tabela `payments.cards`, token cifrado por uma porta `kernel/security/Sealer` que `merchants` implementa com o `EnvelopeCipher`), `CardNotifications` na inbox e `CardReconciliation`. O `app` ganha a variante selada `CardPaymentRequest`, `/capture`, `/v1/cards/{id}`, o endpoint de notificação sem mTLS, o 402 e o teste PCI de ponta a ponta.

**Tech Stack:** o de sempre (Java 25, Spring Boot 4, JDK `HttpClient`, Jackson 3 — `tools.jackson.*` com anotações `com.fasterxml.jackson.annotation.*` —, WireMock 3, Testcontainers/Postgres 17, Flyway, ArchUnit, Logback). Nada novo.

**Spec:** `docs/superpowers/specs/2026-09-28-cartao-cielo-design.md`. A fonte da Cielo são as páginas `.md` de `https://docs.cielo.com.br/ecommerce-cielo/` (lidas em 2026-09-28); os exemplos que viram fixture estão copiados abaixo, cada um com a URL. Executores leem a spec, este plano e, a partir da Task 13, `docs/providers/cielo/NOTES.md`.

## Global Constraints

- **Todo o código em inglês**: identificadores, colunas, comentários, mensagens, log, commits, README. Prosa de `docs/superpowers/` em português. O vocabulário da Cielo (`MerchantOrderId`, `PaymentId`, `ReturnCode`, `ChangeType`, `CardOnFile`) aparece **só** em `gateway-providers` e nos JSONs; no domínio o id da Cielo é `CardDetails.paymentId` e o nosso id de cartão é `cardId`.
- Fronteiras (ArchUnit cobra): `kernel` sem Spring, sem JPA e **sem Jackson**; `payments` conhece só `com.gateway.kernel.provider.card.*` e `com.gateway.kernel.security.Sealer`; `payments` e `merchants` não se importam; nenhuma classe fora de `providers` tem `Itau` **nem `Cielo`** no nome (a regra `cieloVocabularyStaysInProviders` entra na Task 12); entidade JPA package-private e só em `..persistence..`.
- **PCI, uma regra:** o número completo e o CVV existem em memória dentro de `CardData`/`CardToken` entre a desserialização do request e `SaleRequestFactory`/`CardTokenRequestFactory` (as duas únicas classes de produção que chamam `CardNumber.reveal()`; só `SaleRequestFactory` revela o CVV). Nunca em `toString`, exceção, `DomainException`, `ProviderException.message`, `payment_events`, `outbox`, `provider_requests`, `webhook_inbox`, `idempotency_keys.response_body` nem log. `CardDataNeverLeavesTheRequestTest` (Task 12) é a definição de pronto.
- Fora do escopo (spec §2/§9) e **nenhuma task implementa**: débito, 3DS, Silent Order Post, antifraude, `ByIssuer`, recorrência programada da Cielo, `M1/Recurring`, chargeback além do alerta, Zero Auth, conversão de moeda, roteamento de provider por merchant (`CARD` resolve sempre para `CIELO`), `ServiceTaxAmount`, `Reason: "HighRisk"`, `TransactionLinkId`.
- 1 a 12 parcelas, sempre `Interest: "ByMerchant"`; captura automática por padrão; `save_card` padrão `false`.
- **Nenhum teste fala com a rede.** WireMock em `localhost`; fixtures copiados verbatim das páginas da Cielo (URL no `README.md` dos fixtures); os derivados dizem de qual exemplo vieram e o que mudou.
- Credenciais da Cielo (sandbox ou produção) **nunca** entram em teste, commit, log ou neste plano: testes usam `11111111-2222-3333-4444-555555555555` / `"A".repeat(40)`; o smoke (Task 13) usa placeholders `<CIELO_SANDBOX_MERCHANT_ID>`/`<CIELO_SANDBOX_MERCHANT_KEY>`. Os valores default que a doc da Cielo imprime no OpenAPI também não são copiados.
- Dinheiro em `Money` (centavos); a Cielo também fala centavos inteiros — nenhuma conversão.
- Datas da Cielo (`"2025-11-24 17:50:00"`, sem fuso) são horário de Brasília: um único conversor (`CieloDates`) para `Instant` em `America/Sao_Paulo`.
- Código e mensagem de erro são contrato: `CARD_INVALID` (422, a mensagem nomeia `card.number`, `card.expiry`, `card.holder`, `card.cvv`, `card.brand`, `cvv`), `CARD_DECLINED` (402, propriedade `decline_code`), `CARD_NOT_FOUND` (422), `INVALID_INSTALLMENTS` (422), `INVALID_SOFT_DESCRIPTOR` (422), `CAPTURE_NOT_ALLOWED` (409), `ALREADY_CAPTURED` (409), `CAPTURE_AMOUNT_INVALID` (422). A mensagem de `unreadableBody` passa de `method must be PIX or BOLECODE` para `method must be PIX, BOLECODE or CARD` — mudança de contrato **declarada** aqui e no commit da Task 11.
- Comentários registram POR QUÊ com evidência (a URL da doc da Cielo, o exemplo, o número). Linha de 100 colunas (`spotless`/google-java-format). Commits `type(scope): lowercase subject`, corpo em inglês com o porquê, última linha `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. Renome/refactor sem mudança de comportamento viaja em commit próprio. `git commit` **só** nos steps de commit; sem `--amend`.
- Ambiente local: **todo** comando Maven é prefixado `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH &&`. Teste único: `./mvnw -q -B -o -pl <module> -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false test`; com módulo a montante alterado na mesma task, `-am`. Antes de cada commit: `./mvnw -q -B spotless:apply` (sem `-o`: o jar do formatter pode não estar em cache). Testes com Postgres precisam de Docker (Testcontainers), que está rodando.

## Review Focus

As cinco entradas que a spec implica, nenhum teste dela cobriria, e que mais provavelmente mordem quem usa o gateway (cada uma ganhou teste na task dona):

1. **PAN digitado como gente digita** — `"4024 0071 5376 3171"` ou `"4024-0071-5376-3171"`: aceito e normalizado para dígitos; o `last4` é `3171` (Task 1, `CardNumberTest.acceptsSpacesAndDashes`).
2. **Amex: 15 dígitos e CVV de 4** — `378282246310005` + `1234` é aceito; o mesmo número com CVV de 3 é `card.cvv`, e um Visa com CVV de 4 também (Task 1, `CardDataTest.amexTakesFourDigitCvvAndVisaThree`).
3. **Validade no mês corrente** — `09/2026` em 2026-09-28 é válido até o fim do mês; `08/2026` não (Task 1, `CardExpiryTest.theCurrentMonthIsStillValid`).
4. **Parcela abaixo do mínimo da Cielo** — `ByMerchant` exige parcela ≥ R$ 5,00 (doc de criação, campo `Interest`): 1000 centavos em 3x é `INVALID_INSTALLMENTS` antes de qualquer linha; 1600 em 3x (divisão não exata, 533,33) é aceito — a Cielo divide, não nós (Task 8, `CardPaymentFlowIntegrationTest.installmentsBelowTheMinimumAreRefusedBeforeARowExists`).
5. **Notificação de um `PaymentId` que o gateway não conhece** (outro sistema na mesma loja Cielo, ou um pagamento de antes do gateway): a entrada da inbox termina `IGNORED`, sem exceção e sem retentativa infinita (Task 10, `CardNotificationsIntegrationTest.anUnknownPaymentIdIsIgnored`).

---

## O que a doc da Cielo diz (lida em 2026-09-28) e onde a spec precisou de ajuste

A doc ganha. Cada linha tem a página (`https://docs.cielo.com.br/ecommerce-cielo/<página>.md`) e o que o plano faz.

| # | spec dizia | a doc diz (página) | o plano faz |
|---|---|---|---|
| D1 | status `15` Refunded ("a lista atual") e `14` Processing | `reference/payment-status`: 0, 1, 2, 3, 10, **11 Refunded** ("cancelado após 23h59 do dia de autorização"), 12, 13, **20 Scheduled**; não existe 14 nem 15. `reference/api-codes`: "Estorno aprovado — Status 11 — ReturnCode 9" | `CieloStatuses`: 11 → `REFUNDED`; 15 → `REFUNDED` e 14 → `PROCESSING` ficam como tolerância documentada (spec §1); 20 e qualquer inteiro desconhecido → `PROCESSING` (em dúvida → consulta, nunca adoção) |
| D2 | void depois da captura é estorno `Status 15/11` | `reference/payment-status`: 10 Voided é "Pagamento cancelado"; 11 só "após 23h59 do dia de autorização". Um void no mesmo dia de uma venda capturada volta `10` | `CieloCardProvider.refund` aceita `VOIDED` **e** `REFUNDED` como devolução concluída |
| D3 | `SecurityCode` opcional com `CardToken` | `reference/cartao-tokenizado-api`: schema de `Payment.CreditCard` com `"required": ["CardToken", "SecurityCode"]` | `cvv` **obrigatório** com `card_id` (422 `CARD_INVALID` "cvv is required with card_id"); `CardToken` carrega `Secret securityCode`. O smoke confirma se a Cielo aceita sem (NOTES.md) |
| D4 | `CardBrand` com `HIPERCARD` | `reference/criar-pagamento-credito`, `Brand`: "Visa / Master / Amex / Elo / Aura / JCB / Diners / Discover"; `reference/criar-cardtoken` idem | `CardBrand` sem `HIPERCARD`; um BIN Hipercard (606282) sem `brand` é `card.brand is required` |
| D5 | parcelas 1–12 | mesma página, `Interest`: "No caso de parcelamento pela loja (ByMerchant), o valor mínimo da parcela precisa ser de R$5,00" | `InstallmentPlan.of(amount, n)`: `amount / n ≥ 500` centavos, senão `INVALID_INSTALLMENTS` (Review Focus 4) |
| D6 | `CardOnFile{Usage, Reason}` em toda cobrança com token | `docs/card-on-file`: "Bandeiras Suportadas: Mastercard, Visa, Elo" e "Não envie a marcação de Card On File para transações sem o cartão armazenado"; `InitiatedTransactionIndicator` "Obrigatório apenas para bandeira Mastercard" | `CardOnFile` só para VISA/MASTER/ELO; `InitiatedTransactionIndicator{C1, CredentialsOnFile}` só para MASTER |
| D7 | `CieloDeclines` medido contra `reference/api-codes` | `reference/api-codes` só tem os códigos da API (100–841); as negativas do emissor estão em `page/abecs` ("Códigos de Retorno padrão ABECS", por bandeira) | `CieloDeclines` medido contra `page/abecs` (Visa: 51 saldo, 54 vencido, 78/62 bloqueado, 41/43/46 perdido/roubado/encerrado, 91/96 emissor fora/falha, 05 genérica) |
| D8 | sandbox 3 → `57` "vencido" | `reference/credito-sandbox`: "Os códigos de retorno em Sandbox **não são os mesmos** disponíveis em produção"; em produção (ABECS Visa) 57 é "transação não permitida para o cartão" | a tabela segue produção: 57 → `DO_NOT_HONOR`. O smoke espera `DO_NOT_HONOR` no cartão final 3 e registra a diferença |
| D9 | 400 com lista `[{Code, Message}]` | `reference/api-errors-code-message` confirma a lista (exemplo `[{"Code":322,...}]`); mas o OpenAPI de criação traz `"Bad request"` e `"Unauthorized"` como corpo de 400/401 (string, não JSON) | `CieloErrors` lê a lista quando é JSON e cai no status quando não é |
| D10 | consulta por `PaymentId` inexistente → vazio | nenhuma página documenta 404; `reference/api-codes` tem `307 Transaction not found` e `303 Sent OrderId does not exist` | `find` devolve vazio em 404 **ou** em 400 cujo `Code` é 307; o smoke registra o que a Cielo responde |
| D11 | `GET /1/sales?merchantOrderId=` devolve `Payments` | `reference/consulta-merchantorderid-api`: `{ReasonCode, ReasonMessage, Payments[{PaymentId, ReceveidDate}]}` — o campo é **`ReceveidDate`** (grafia da Cielo); "Apenas as transações dentro dos últimos três meses podem ser consultadas" | DTO com `@JsonProperty("ReceveidDate")`; a recuperação pega o `PaymentId` mais recente |
| D12 | `MerchantOrderId` "alfanumérico; acima de 20 chars a Cielo regenera" | `reference/criar-pagamento-credito`: tamanho **50**, só `a-z A-Z 0-9`; `SentOrderId` difere se > 20 chars **ou** se o id já foi usado nas últimas 24 h; os exemplos do próprio sandbox mostram `SentOrderId` diferente para `MerchantOrderId` de 10 chars | `MerchantOrderId = payment.id()` (ULID, 26 alfanuméricos) continua; `SentOrderId` é ignorado; a recuperação consulta pelo nosso id |
| D13 | Post de Notificação responde qualquer 2xx | `docs/webhook`: "A loja deverá retornar como resposta à notificação: HTTP Status Code 200 OK"; URL HTTPS na porta 443, estática, ≤ 255 chars; "disparado a cada 30 minutos; em caso de falha, três novas tentativas" | o endpoint responde **200**, não 202 como o do Itaú |
| D14 | `ChangeType` 2/3/4/6 ignorados | `docs/webhook`: 6 = "Boleto registrado pago a menor", **7 = "Notificação de chargeback"** (Risk Notification, legado) | 2, 3, 4, 6, 7 → `IGNORED` (chargeback está fora, spec §2) |
| D15 | "se o sandbox dispara notificação" (a confirmar) | `reference/como-usar-o-sandbox`: "É possível testar o Post de Notificação no ambiente Sandbox" mediante cadastro da URL por e-mail ao atendimento | NOTES.md registra o procedimento; o smoke inclui o pedido |
| D16 | exemplos da doc como fixture verbatim | o exemplo 201 "Elo via Link de Pagamento" é **JSON inválido** (falta vírgula depois de `"SolutionType": "ExternalLinkPay"`); vários 201 trazem `"Interest": 0` (inteiro) enquanto o request manda `"ByMerchant"` | fixtures só dos exemplos válidos ("Cartão de crédito simplificado", "Card On File", tokenizado); o DTO de resposta não modela `Interest` |
| D17 | captura devolve a venda | `reference/capturar-apos-autorizacao`: resposta 200 só `{Status, Tid, ProofOfSale, AuthorizationCode, ReturnCode, ReturnMessage, Links}` — sem `CapturedAmount` nem `CapturedDate`; "Após uma captura, não é possível realizar capturas adicionais" | `CieloCardProvider.capture` faz `PUT …/capture` e depois `GET /1/sales/{PaymentId}` para devolver a `CardAuthorization` completa (uma chamada a mais, documentada) |
| D18 | `MerchantKey` de 40 chars (GUID) | os parâmetros dizem "Tamanho: 40. Formato: GUID", mas o exemplo é 40 letras maiúsculas sem hífen | `CieloCredentials` valida `[A-Za-z0-9]{40}`, não GUID |
| D19 | `CardNumber` 13–19 | `CardNumber` "Tamanho: 19" na criação, "Tamanho: 16" no `POST /1/card/`, e o código 128 diz "Numero do cartão superior a 16 digitos" | o kernel aceita 13–19 (Luhn decide); um 400 `128` da Cielo vira `PROVIDER_DECLINED` como qualquer 400. NOTES.md registra |
| D20 | o exemplo da spec §9 e da doc usa `4024007153763191` como cartão de teste | `reference/credito-sandbox`: "O cartão de teste **4024.0071.5376.3191** … irá simular o status autorizado", e a mesma página: Luhn "é empregada nos ambientes Sandbox e de Produção" — mas esse número **não passa em Luhn** (conferido: soma mod 10 ≠ 0) | testes e smoke usam números Luhn-válidos com o último dígito que o sandbox lê: `4024007153763171` (1, aprova), `4024007153760052` (2), `4024007153760086` (6), `4024007153760029` (9); o gateway recusaria o da doc com `card.number must pass the Luhn check` antes da Cielo |

Onde o **código** atual discorda da spec (o código ganha, a decisão fica aqui):

| # | spec | código | o plano faz |
|---|---|---|---|
| C1 | `CardData` com `@JsonIgnoreType` | `gateway-kernel/pom.xml` não tem Jackson ("No dependencies on purpose") e o ArchUnit `kernelImportsNothing` | o kernel fica sem Jackson; o `app` registra um mixin `@JsonIgnoreType` para `CardData`, `CardToken`, `CardNumber` no `JsonMapper` (Task 11, com teste); `payments` nunca serializa o comando |
| C2 | `provider_requests` recebe o corpo mascarado | `ProviderGateway.record` grava `request = null` e só a mensagem da exceção em `response` ("Request and response bodies are not recorded here") | nenhum corpo passa a ser gravado; a regra vira: toda mensagem de `ProviderException` da Cielo passa por `CieloPayloadMasker` (Task 3), e o teste PCI varre `provider_requests` mesmo assim |
| C3 | `find` devolve `CardStatus`; notificação vira `ProviderWebhookEvent` | `MethodProvider<ISSUE, ISSUED, STATUS>` exige um tipo para `find`; `ProviderWebhookEvent` é o formato do Pix (`received`, `refundUpdates`) | `CardMethodProvider extends MethodProvider<CardIssueRequest, CardAuthorization, CardAuthorization>`; `parseWebhook` devolve `CardNotification(paymentId, kind, changeType)` |
| C4 | `CardIssueRequest.payer` (`kernel/party/Payer`) | `Payer` exige `Address` (boleto); cartão não tem endereço | `CardCustomer(PersonName name, Document document, String email)` com documento e e-mail opcionais |
| C5 | decline_code sai de `ReturnCode` "no flow" | `payments` não pode importar `providers` (`CieloDeclines` mora lá) | `CardAuthorization.declineCode()` (enum `CardDeclineCode` no kernel), preenchido pelo provider |
| C6 | `inbound_webhook_secret` do merchant | não existe; `Merchant` é um record de 6 componentes usado em todo lugar | conceito novo `merchants/notification/` com tabela própria `merchants.inbound_notification_keys` (V102), guardando **SHA-256** da chave e comparando em tempo constante; admin `PUT /v1/admin/merchants/{id}/notification-key` |
| C7 | a rota da Cielo em `/v1/providers/cielo/...` | `MtlsPortFilter` responde 404 a **todo** `/v1/providers/**` fora da porta mTLS | o filtro passa a reservar só `/v1/providers/itau/**` para a porta mTLS (Task 12, teste) |
| C8 | `EnvelopeCipher` reusado por payments | `payments` e `merchants` não se importam (ArchUnit) | porta `kernel/security/Sealer` (`seal`/`open` de bytes com contexto); `merchants/crypto/EnvelopeSealer` implementa empacotando `Encrypted`; o app não precisa de cola: o bean vem de `MerchantsConfiguration` |
| C9 | `RefundService` resolve o provider pelo método | `RefundService.request` resolve Pix antes de tudo e já tem 368 linhas | o despacho fica no topo de `request` (um `if` de método, único ponto) e o cartão vai para `refund/CardRefunds` |
| C10 | reconciliação por método | `ReconciliationService` já tem 8 dependências e o laço de escopos Pix lê **todos** os métodos (um merchant só-cartão sem credencial Itaú logaria falha a cada 15 min); `StuckCreatedSweep` trata todo não-BOLECODE como Pix | `CardReconciliation` separado, chamado por `ReconcileJob`; o laço Pix pula `CARD`; o sweep ganha o ramo `CARD` |
| C11 | `timeout` na devolução síncrona | a spec não diz; "Timeout não é falha" (CLAUDE.md) | timeout/503 no void de devolução deixa o `Refund` em `PROCESSING` (valor reservado), abre divergência `REFUND_UNKNOWN` e responde `PROVIDER_TIMEOUT`; a notificação `ChangeType 25`/`1` ou a reconciliação fecham |
| C12 | `markCompleted` existente para a captura automática | `markCompleted(endToEndId, …)` é do Pix (grava `paidVia: PIX`) | `markCompletedByCard(CardDetails, Money, Instant, by)` e `markCaptured(Money, Instant, by)` |
| C13 | `tokenize` no contrato | o fluxo da spec só usa `SaveCard: true` na autorização | `tokenize`/`POST /1/card/` implementado e testado no provider (spec §5), **não chamado** por `payments` nesta fase |

---

## Estrutura de arquivos

```
gateway-kernel/src/main/java/com/gateway/kernel/
  payment/PaymentMethod.java                     + CARD
  security/Sealer.java                           porta de cifra (merchants implementa, payments consome)
  provider/card/
    CardMethodProvider.java  CardIssueRequest.java  CardCustomer.java  CardSource.java
    CardData.java  CardToken.java  CardOnFileUsage.java  CardNumber.java  CardExpiry.java
    CardHolder.java  CardBrand.java  InvalidCardValue.java  Installments.java  SoftDescriptor.java
    CardAuthorization.java  CardStatus.java  CardDeclineCode.java  CardRefundResult.java
    StoredCard.java  CardNotification.java  CardNotificationKind.java
gateway-providers/src/main/java/com/gateway/providers/
  ProvidersConfiguration.java                    + CieloProperties, @Bean CardMethodProvider
  cielo/CieloCardProvider.java  CieloErrors.java  CieloHttp.java  CieloText.java  CieloPayloadMasker.java
  cielo/auth/CieloCredentials.java  CieloEndpoints.java
  cielo/sale/CieloSalesClient.java  SaleRequestFactory.java  CieloStatuses.java  CieloDeclines.java  CieloDates.java
  cielo/sale/dto/SaleRequest.java  SaleResponse.java  CaptureResponse.java  SalesByOrderResponse.java  CieloError.java
  cielo/card/CieloCardClient.java  CardTokenRequestFactory.java  cielo/card/dto/CardTokenRequest.java  CardTokenResponse.java
  cielo/notification/NotificationBody.java
gateway-providers/src/test/resources/cielo/fixtures/*.json + README.md
gateway-merchants/src/main/java/com/gateway/merchants/
  credential/Provider.java                       + CIELO
  crypto/EnvelopeSealer.java                     Sealer sobre EnvelopeCipher
  notification/InboundNotificationKeyService.java  + persistence/{Entity, JpaRepository, Repository, RepositoryImpl}
  MerchantsConfiguration.java                    + beans
  resources/db/migration/merchants/V102__inbound_notification_keys.sql
gateway-payments/src/main/java/com/gateway/payments/
  PaymentsConfiguration.java  PaymentsProperties.java (+ cardCaptureDeadline, cardReconciliationLookback, cardReconciliationCap)
  payment/PaymentStatus.java (+AUTHORIZED)  PaymentTransitions.java  Payment.java  PaymentDetailsJson.java
  payment/PaymentEvents.java (+card)  PaymentCancellation.java (+void)  StuckCreatedSweep.java (+CARD)
  payment/card/CardDetails.java  CardDetailsJson.java  CardCapture.java  CardAdoption.java  CardStatusSync.java
  payment/card/CardDeclinedException.java
  payment/create/CreateCardPayment.java  CardChoice.java  CardDataFactory.java  InstallmentPlan.java
  payment/create/CardPaymentFlow.java  CardAuthorizationRecovery.java  PaymentDraftFactory.java (+card)
  payment/persistence/*                          + card no details, findByMerchantAndCardPaymentId
  card/SavedCard.java  SavedCards.java  card/persistence/{SavedCardEntity, SavedCardJpaRepository, SavedCardRepository, SavedCardRepositoryImpl}.java
  refund/CardRefunds.java  RefundService.java (+despacho)  payment/Payment.applyRefund (refundable = paid)
  inbox/CardNotifications.java  WebhookInboxService.java (+despacho)
  reconciliation/CardReconciliation.java  ReconciliationService.java (pula CARD)
  jobs/ReconcileJob.java                          + CardReconciliation
  provider/ProviderGateway.java                   + resolveCard, cardProvider
  resources/db/migration/payments/V204__cards.sql
gateway-payments/src/test/java/com/gateway/payments/support/{RecordingCardProvider, TestSealer, ServiceTestConfig, InMemoryCredentialLookup, ServiceIntegrationTestBase}.java
gateway-app/src/main/java/com/gateway/app/
  api/payment/dto/CardPaymentRequest.java  CardFields.java  CardCustomer.java  CaptureRequestBody.java  CreatePaymentRequest.java  PaymentResponse.java
  api/payment/PaymentsController.java (+capture)  api/card/CardsController.java + dto/CardResponse.java
  api/support/ErrorHandler.java (+tabela de status, 402)  api/support/IdempotencyFilter.java (+capture)
  api/admin/MerchantsAdminController.java (+notification-key) + dto/NotificationKeyRequest.java
  inbound/card/CardNotificationController.java  inbound/mtls/MtlsPortFilter.java (só itau)
  observability/Masker.java (+PAN, SecurityCode)  observability/CardJsonMixins.java
  resources/application.yml                       + gateway.providers.cielo.*, gateway.payments.card-*
gateway-app/src/test/java/com/gateway/app/{CardFlowIntegrationTest, CardDataNeverLeavesTheRequestTest, ...}
README.md  .env.example  docs/providers/cielo/NOTES.md  docs/superpowers/DECISOES.md
```

---

### Task 1: Cartão no `kernel` — value objects, `CardData` e o vocabulário da autorização

**Files:**
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/provider/card/{InvalidCardValue,CardNumber,CardExpiry,CardHolder,CardBrand,CardSource,CardData,CardToken,CardOnFileUsage,Installments,SoftDescriptor,CardCustomer,CardStatus,CardDeclineCode,CardAuthorization,CardRefundResult,StoredCard,CardNotification,CardNotificationKind,CardIssueRequest}.java`
- Test: `gateway-kernel/src/test/java/com/gateway/kernel/provider/card/{CardNumberTest,CardExpiryTest,CardHolderTest,CardBrandTest,CardDataTest,CardStatusTest}.java`

**Interfaces:**
- Consumes: `kernel.errors.InvalidValue`, `kernel.security.Secret`, `kernel.money.Money`, `kernel.party.PersonName`, `kernel.party.Document`.
- Produces (todas em `com.gateway.kernel.provider.card`):
  ```java
  class InvalidCardValue extends InvalidValue { InvalidCardValue(String field, String reason); String field(); } // field: "number"|"holder"|"expiry"|"cvv"|"brand"
  final class CardNumber { static CardNumber of(String raw); String reveal(); String last4(); String first6(); String masked(); /* toString = "****" + last4 */ }
  record CardExpiry(YearMonth value) { static CardExpiry of(String mmYyyy, YearMonth currentMonth); String formatted(); /* "MM/YYYY" */ }
  record CardHolder(String value) { static CardHolder of(String raw); }
  enum CardBrand { VISA, MASTER, AMEX, ELO, AURA, JCB, DINERS, DISCOVER; static Optional<CardBrand> fromBin(String digits); static CardBrand of(String raw); int cvvLength(); }
  sealed interface CardSource permits CardData, CardToken { CardBrand brand(); }
  record CardData(CardNumber number, CardHolder holder, CardExpiry expiry, Secret securityCode, CardBrand brand) implements CardSource
      { static CardData of(String number, String holder, String expiry, String cvv, String brand, YearMonth currentMonth); String last4(); }
  enum CardOnFileUsage { FIRST, USED }
  record CardToken(String value, CardBrand brand, CardOnFileUsage usage, Secret securityCode) implements CardSource
  record Installments(int count) { static Installments of(Integer raw); }               // null → 1; 1..12
  record SoftDescriptor(String value) { static SoftDescriptor ofNullable(String raw); } // null in, null out; InvalidValue otherwise
  record CardCustomer(PersonName name, Document document, String email) {}             // document, email nullable
  enum CardStatus { NOT_FINISHED, AUTHORIZED, PAID, DENIED, VOIDED, PENDING, ABORTED, PROCESSING, REFUNDED; boolean inDoubt(); }
  enum CardDeclineCode { INSUFFICIENT_FUNDS, EXPIRED_CARD, BLOCKED_CARD, CANCELED_CARD, TIMEOUT, DO_NOT_HONOR, GENERIC }
  record CardAuthorization(String paymentId, CardStatus status, String returnCode, String returnMessage, CardDeclineCode declineCode,
      String tid, String authorizationCode, String proofOfSale, Money amount, Money capturedAmount, CardBrand brand, String last4,
      Optional<String> cardToken, Instant receivedAt, Optional<Instant> capturedAt)
  record CardRefundResult(CardStatus status, Money refundedAmount, String returnCode, String returnMessage) { boolean completed(); }
  record StoredCard(String token, CardBrand brand, String last4, YearMonth expiry)      // toString masks token
  enum CardNotificationKind { STATUS_CHANGED, PARTIAL_REFUND, VOID_DENIED, FRAUD_ALERT, IGNORED }
  record CardNotification(String paymentId, CardNotificationKind kind, int changeType) {}
  record CardIssueRequest(String merchantOrderId, Money amount, Installments installments, boolean capture, boolean saveCard,
      SoftDescriptor softDescriptor, CardSource source, CardCustomer customer)
  ```

- [ ] **Step 1: Os testes dos value objects**

`gateway-kernel/src/test/java/com/gateway/kernel/provider/card/CardNumberTest.java`:
```java
package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CardNumberTest {

  /**
   * Luhn-valid and ending in 1, which the sandbox approves (reference/credito-sandbox decides by the
   * last digit). The page's own example, 4024.0071.5376.3191, fails the Luhn check (plan D20).
   */
  static final String SANDBOX_VISA = "4024007153763171";

  @Test
  void keepsTheDigitsAndMasksEverythingElse() {
    CardNumber number = CardNumber.of(SANDBOX_VISA);

    assertThat(number.reveal()).isEqualTo(SANDBOX_VISA);
    assertThat(number.last4()).isEqualTo("3171");
    assertThat(number.first6()).isEqualTo("402400");
    assertThat(number.masked()).isEqualTo("402400******3171");
    assertThat(number.toString()).isEqualTo("****3171").doesNotContain("402400");
  }

  /** Review Focus 1: a checkout form hands over what the payer typed, grouping and all. */
  @Test
  void acceptsSpacesAndDashes() {
    assertThat(CardNumber.of("4024 0071 5376 3171").reveal()).isEqualTo(SANDBOX_VISA);
    assertThat(CardNumber.of("4024-0071-5376-3171").reveal()).isEqualTo(SANDBOX_VISA);
    assertThat(CardNumber.of(" 4024 0071-5376 3171 ").last4()).isEqualTo("3171");
  }

  @Test
  void refusesAFailedLuhnCheck() {
    assertThatThrownBy(() -> CardNumber.of("4024007153763192"))
        .isInstanceOf(InvalidCardValue.class)
        .satisfies(
            thrown -> {
              InvalidCardValue invalid = (InvalidCardValue) thrown;
              assertThat(invalid.field()).isEqualTo("number");
              assertThat(invalid.reason()).isEqualTo("must pass the Luhn check");
              assertThat(invalid.getMessage()).doesNotContain("4024007153763192");
            });
  }

  @Test
  void refusesWrongLengthsLettersAndNothing() {
    for (String invalid :
        new String[] {null, "", "   ", "411111111111", "41111111111111111111", "4111x11111111111"}) {
      assertThatThrownBy(() -> CardNumber.of(invalid))
          .as("number %s", invalid)
          .isInstanceOf(InvalidCardValue.class)
          .extracting(thrown -> ((InvalidCardValue) thrown).reason())
          .isEqualTo("must be 13 to 19 digits");
    }
  }

  @Test
  void acceptsShortAndLongNumbersThatPassLuhn() {
    assertThat(CardNumber.of("4222222222222").last4()).isEqualTo("2222");
    assertThat(CardNumber.of("6011000990139424").last4()).isEqualTo("9424");
  }
}
```

`gateway-kernel/src/test/java/com/gateway/kernel/provider/card/CardExpiryTest.java`:
```java
package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class CardExpiryTest {
  static final YearMonth SEPTEMBER_2026 = YearMonth.of(2026, 9);

  @Test
  void parsesMonthSlashYearAndFormatsItBack() {
    CardExpiry expiry = CardExpiry.of("12/2030", SEPTEMBER_2026);

    assertThat(expiry.value()).isEqualTo(YearMonth.of(2030, 12));
    assertThat(expiry.formatted()).isEqualTo("12/2030");
  }

  /** Review Focus 3: a card is good through the last day of its printed month. */
  @Test
  void theCurrentMonthIsStillValid() {
    assertThat(CardExpiry.of("09/2026", SEPTEMBER_2026).value()).isEqualTo(SEPTEMBER_2026);
    assertThatThrownBy(() -> CardExpiry.of("08/2026", SEPTEMBER_2026))
        .isInstanceOf(InvalidCardValue.class)
        .extracting(thrown -> ((InvalidCardValue) thrown).reason())
        .isEqualTo("must not be in the past");
  }

  @Test
  void refusesAnythingButMonthSlashFourDigitYear() {
    for (String invalid : new String[] {null, "", "12/30", "13/2030", "00/2030", "2030-12", "1/2030"}) {
      assertThatThrownBy(() -> CardExpiry.of(invalid, SEPTEMBER_2026))
          .as("expiry %s", invalid)
          .isInstanceOf(InvalidCardValue.class)
          .extracting(thrown -> ((InvalidCardValue) thrown).field())
          .isEqualTo("expiry");
    }
  }
}
```

`gateway-kernel/src/test/java/com/gateway/kernel/provider/card/CardHolderTest.java`:
```java
package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CardHolderTest {

  /**
   * Accents are kept here: the name is the payer's. Removing them is the Cielo text rule ("Não
   * aceita caracteres especiais ou acentuação"), applied by the provider (CieloText.holder).
   */
  @Test
  void keepsLettersAndSingleSpacesTrimmed() {
    assertThat(CardHolder.of("  JOÃO  DA SILVA ").value()).isEqualTo("JOÃO DA SILVA");
  }

  @Test
  void refusesDigitsSymbolsAndMoreThanTwentyFiveCharacters() {
    for (String invalid : new String[] {null, "", "  ", "JOAO 2", "JOAO@SILVA", "A".repeat(26)}) {
      assertThatThrownBy(() -> CardHolder.of(invalid))
          .as("holder %s", invalid)
          .isInstanceOf(InvalidCardValue.class)
          .extracting(thrown -> ((InvalidCardValue) thrown).field())
          .isEqualTo("holder");
    }
  }
}
```

`gateway-kernel/src/test/java/com/gateway/kernel/provider/card/CardBrandTest.java`:
```java
package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class CardBrandTest {

  @Test
  void recognisesTheBinsItKnows() {
    assertThat(CardBrand.fromBin("4024007153763171")).contains(CardBrand.VISA);
    assertThat(CardBrand.fromBin("5371542802050634")).contains(CardBrand.MASTER);
    assertThat(CardBrand.fromBin("2221000000000009")).contains(CardBrand.MASTER);
    assertThat(CardBrand.fromBin("378282246310005")).contains(CardBrand.AMEX);
    assertThat(CardBrand.fromBin("6362970000457013")).contains(CardBrand.ELO);
    assertThat(CardBrand.fromBin("4389350000000000")).contains(CardBrand.ELO);
    assertThat(CardBrand.fromBin("6011000990139424")).contains(CardBrand.DISCOVER);
    assertThat(CardBrand.fromBin("3530111333300000")).contains(CardBrand.JCB);
    assertThat(CardBrand.fromBin("30569309025904")).contains(CardBrand.DINERS);
    assertThat(CardBrand.fromBin("5078601870000127985")).contains(CardBrand.AURA);
  }

  /** Hipercard is not in the Cielo Brand list (plan table D4), so its BIN is simply unknown. */
  @Test
  void anUnknownBinIsEmptyNotAGuess() {
    assertThat(CardBrand.fromBin("6062825624254001")).isEqualTo(Optional.empty());
  }

  @Test
  void parsesTheApiSpellingCaseInsensitively() {
    assertThat(CardBrand.of("visa")).isEqualTo(CardBrand.VISA);
    assertThat(CardBrand.of("Master")).isEqualTo(CardBrand.MASTER);
    assertThatThrownBy(() -> CardBrand.of("HIPERCARD"))
        .isInstanceOf(InvalidCardValue.class)
        .extracting(thrown -> ((InvalidCardValue) thrown).reason())
        .isEqualTo("must be one of VISA, MASTER, AMEX, ELO, AURA, JCB, DINERS, DISCOVER");
  }

  @Test
  void amexIsTheOnlyFourDigitCvv() {
    for (CardBrand brand : CardBrand.values()) {
      assertThat(brand.cvvLength()).as("%s", brand).isEqualTo(brand == CardBrand.AMEX ? 4 : 3);
    }
  }
}
```

`gateway-kernel/src/test/java/com/gateway/kernel/provider/card/CardDataTest.java`:
```java
package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class CardDataTest {
  static final YearMonth NOW = YearMonth.of(2026, 9);

  @Test
  void buildsFromWhatTheMerchantSentAndPrintsOnlyBrandAndLastFour() {
    CardData card = CardData.of("4024007153763171", "JOAO DA SILVA", "12/2030", "123", null, NOW);

    assertThat(card.brand()).isEqualTo(CardBrand.VISA);
    assertThat(card.last4()).isEqualTo("3171");
    assertThat(card.toString())
        .isEqualTo("VISA ****3171")
        .doesNotContain("4024007153763171")
        .doesNotContain("123")
        .doesNotContain("JOAO");
  }

  @Test
  void aBrandThatContradictsTheBinIsRefused() {
    assertThatThrownBy(
            () -> CardData.of("4024007153763171", "JOAO DA SILVA", "12/2030", "123", "MASTER", NOW))
        .isInstanceOf(InvalidCardValue.class)
        .satisfies(
            thrown -> {
              assertThat(((InvalidCardValue) thrown).field()).isEqualTo("brand");
              assertThat(((InvalidCardValue) thrown).reason())
                  .isEqualTo("does not match the card number (VISA)");
            });
  }

  @Test
  void anUnknownBinNeedsTheBrand() {
    assertThatThrownBy(
            () -> CardData.of("6062825624254001", "JOAO DA SILVA", "12/2030", "123", null, NOW))
        .isInstanceOf(InvalidCardValue.class)
        .extracting(thrown -> ((InvalidCardValue) thrown).reason())
        .isEqualTo("is required when the card number does not identify it");
  }

  /** Review Focus 2. */
  @Test
  void amexTakesFourDigitCvvAndVisaThree() {
    CardData amex = CardData.of("378282246310005", "JOAO DA SILVA", "12/2030", "1234", null, NOW);
    assertThat(amex.brand()).isEqualTo(CardBrand.AMEX);
    assertThat(amex.last4()).isEqualTo("0005");

    assertThatThrownBy(
            () -> CardData.of("378282246310005", "JOAO DA SILVA", "12/2030", "123", null, NOW))
        .isInstanceOf(InvalidCardValue.class)
        .satisfies(
            thrown -> {
              assertThat(((InvalidCardValue) thrown).field()).isEqualTo("cvv");
              assertThat(((InvalidCardValue) thrown).reason()).isEqualTo("must be 4 digits");
            });
    assertThatThrownBy(
            () -> CardData.of("4024007153763171", "JOAO DA SILVA", "12/2030", "1234", null, NOW))
        .isInstanceOf(InvalidCardValue.class)
        .extracting(thrown -> ((InvalidCardValue) thrown).reason())
        .isEqualTo("must be 3 digits");
  }

  @Test
  void theCvvIsASecret() {
    CardData card = CardData.of("4024007153763171", "JOAO DA SILVA", "12/2030", "123", null, NOW);

    assertThat(card.securityCode().toString()).isEqualTo("***");
    assertThat(card.securityCode().reveal()).isEqualTo("123");
  }

  @Test
  void aTokenNeverPrintsItsValueNorItsCvv() {
    CardToken token =
        new CardToken(
            "6e1bf77a-b28b-4660-b14f-455e2a1c95e9",
            CardBrand.VISA,
            CardOnFileUsage.USED,
            com.gateway.kernel.security.Secret.of("262"));

    assertThat(token.toString())
        .doesNotContain("6e1bf77a")
        .doesNotContain("262")
        .isEqualTo("CardToken[VISA, USED]");
  }
}
```

`gateway-kernel/src/test/java/com/gateway/kernel/provider/card/CardStatusTest.java`:
```java
package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class CardStatusTest {

  /**
   * In doubt = the acquirer has not decided. The flow asks again by MerchantOrderId instead of
   * adopting (spec §6.4); Status 12 Pending is one of them (Review Focus: a 201 that is not an
   * answer).
   */
  @Test
  void inDoubtIsNotFinishedPendingAndProcessing() {
    EnumSet<CardStatus> inDoubt =
        EnumSet.of(CardStatus.NOT_FINISHED, CardStatus.PENDING, CardStatus.PROCESSING);

    for (CardStatus status : CardStatus.values()) {
      assertThat(status.inDoubt()).as("%s", status).isEqualTo(inDoubt.contains(status));
    }
  }

  @Test
  void aRefundIsCompletedWhenTheSaleIsVoidedOrRefunded() {
    for (CardStatus status : CardStatus.values()) {
      boolean expected = status == CardStatus.VOIDED || status == CardStatus.REFUNDED;
      assertThat(
              new CardRefundResult(status, com.gateway.kernel.money.Money.brl(100), "9", "ok")
                  .completed())
          .as("%s", status)
          .isEqualTo(expected);
    }
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-kernel test`
Expected: FAIL — compilation errors, `CardNumber`, `CardData`, `CardBrand` … not found.

- [ ] **Step 3: `InvalidCardValue`, `CardNumber`, `CardExpiry`, `CardHolder`**

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/InvalidCardValue.java`:
```java
package com.gateway.kernel.provider.card;

import com.gateway.kernel.errors.InvalidValue;

/**
 * A card field with the wrong shape. Unlike its parent it knows which field — "number", "expiry",
 * "cvv" — because a card is validated as a whole (the CVV length depends on the brand, the brand on
 * the number) and the caller could not tell which part failed. The API prefix ({@code card.}) is
 * still the caller's: this type does not know it is called {@code card.number} on the wire.
 *
 * <p>The message never carries the value: a rejected card number is still a card number.
 */
public class InvalidCardValue extends InvalidValue {
  private final String field;

  public InvalidCardValue(String field, String reason) {
    super(reason);
    this.field = field;
  }

  public String field() {
    return field;
  }
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardNumber.java`:
```java
package com.gateway.kernel.provider.card;

import java.util.regex.Pattern;

/**
 * A primary account number, digits only. Spaces and dashes are removed because that is how a payer
 * types it (Review Focus 1); anything else is refused. Not a record on purpose: a record's
 * generated toString would print the digits, and so would every exception built from one.
 *
 * <p>{@link #reveal()} is the one way out, named like {@code Secret.reveal()} so the call site reads
 * as the decision it is. The only production callers are the two Cielo request factories.
 */
public final class CardNumber {
  private static final Pattern GROUPING = Pattern.compile("[\\s-]");
  private static final Pattern DIGITS_13_TO_19 = Pattern.compile("\\d{13,19}");

  private final String digits;

  private CardNumber(String digits) {
    this.digits = digits;
  }

  public static CardNumber of(String raw) {
    String digits = raw == null ? "" : GROUPING.matcher(raw).replaceAll("");

    if (!DIGITS_13_TO_19.matcher(digits).matches()) {
      throw new InvalidCardValue("number", "must be 13 to 19 digits");
    }
    if (!passesLuhn(digits)) {
      throw new InvalidCardValue("number", "must pass the Luhn check");
    }

    return new CardNumber(digits);
  }

  public String reveal() {
    return digits;
  }

  public String last4() {
    return digits.substring(digits.length() - 4);
  }

  public String first6() {
    return digits.substring(0, 6);
  }

  /** The PCI display form: BIN and last four, the middle starred. */
  public String masked() {
    return first6() + "*".repeat(digits.length() - 10) + last4();
  }

  @Override
  public String toString() {
    return "****" + last4();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof CardNumber number && number.digits.equals(digits);
  }

  @Override
  public int hashCode() {
    return digits.hashCode();
  }

  /**
   * Mod 10. The sandbox and production both apply it before anything else (reference/credito-sandbox:
   * "regra do mod10 (Algoritimo de Luhn), que é empregada nos ambientes Sandbox e de Produção"), so
   * refusing here saves a round trip that could only end in a 400.
   */
  private static boolean passesLuhn(String digits) {
    int sum = 0;
    boolean doubleIt = false;

    for (int i = digits.length() - 1; i >= 0; i--) {
      int digit = digits.charAt(i) - '0';
      if (doubleIt) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubleIt = !doubleIt;
    }

    return sum % 10 == 0;
  }
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardExpiry.java`:
```java
package com.gateway.kernel.provider.card;

import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The month printed on the card, in the {@code MM/YYYY} both the API and the Cielo use. Valid
 * through the last day of that month, so the current month is accepted (Review Focus 3).
 *
 * <p>{@code currentMonth} is a parameter, not {@code YearMonth.now()}: the kernel has no clock, and
 * "the current month" must be the caller's (São Paulo) month, not the JVM's.
 */
public record CardExpiry(YearMonth value) {
  private static final Pattern MONTH_SLASH_YEAR = Pattern.compile("(0[1-9]|1[0-2])/(\\d{4})");

  public static CardExpiry of(String raw, YearMonth currentMonth) {
    Matcher matcher = MONTH_SLASH_YEAR.matcher(raw == null ? "" : raw.trim());

    if (!matcher.matches()) {
      throw new InvalidCardValue("expiry", "must be MM/YYYY");
    }

    YearMonth value =
        YearMonth.of(Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(1)));
    if (value.isBefore(currentMonth)) {
      throw new InvalidCardValue("expiry", "must not be in the past");
    }

    return new CardExpiry(value);
  }

  public String formatted() {
    return String.format("%02d/%04d", value.getMonthValue(), value.getYear());
  }
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardHolder.java`:
```java
package com.gateway.kernel.provider.card;

import java.util.regex.Pattern;

/**
 * The name embossed on the card: letters and spaces, at most 25 (the Cielo's {@code Holder} size,
 * reference/criar-pagamento-credito). Accented letters are kept — the transliteration the Cielo
 * needs is the provider's text rule, not the payer's name.
 */
public record CardHolder(String value) {
  private static final Pattern LETTERS_AND_SPACES = Pattern.compile("[\\p{L} ]+");
  private static final Pattern SPACES = Pattern.compile("\\s+");
  private static final int MAX = 25;

  public static CardHolder of(String raw) {
    String trimmed = raw == null ? "" : SPACES.matcher(raw.trim()).replaceAll(" ");

    if (trimmed.isEmpty() || !LETTERS_AND_SPACES.matcher(trimmed).matches()) {
      throw new InvalidCardValue("holder", "must contain only letters and spaces");
    }
    if (trimmed.length() > MAX) {
      throw new InvalidCardValue("holder", "must be at most 25 characters");
    }

    return new CardHolder(trimmed);
  }
}
```

- [ ] **Step 4: `CardBrand`**

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardBrand.java`:
```java
package com.gateway.kernel.provider.card;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The brands the Cielo accepts, exactly its {@code Brand} list (reference/criar-pagamento-credito:
 * "Visa / Master / Amex / Elo / Aura / JCB / Diners / Discover"). Hipercard is not in it (plan D4).
 *
 * <p>The BIN table is deliberately small: it only has to catch a merchant sending MASTER for a
 * Visa number, and to spare the merchant the {@code brand} field for the common cards. Unknown BIN
 * means "ask the merchant", never a guess. Elo is checked first because its ranges sit inside
 * Visa's 4 and Discover's 65.
 */
public enum CardBrand {
  VISA,
  MASTER,
  AMEX,
  ELO,
  AURA,
  JCB,
  DINERS,
  DISCOVER;

  private static final List<String> ELO_PREFIXES =
      List.of(
          "401178", "401179", "431274", "438935", "451416", "457393", "457631", "457632",
          "504175", "506699", "5067", "509", "627780", "636297", "636368", "650031", "650032",
          "650033", "65004", "65005", "6504", "6505", "6507", "6509", "6516", "6550");

  public static Optional<CardBrand> fromBin(String digits) {
    if (ELO_PREFIXES.stream().anyMatch(digits::startsWith)) {
      return Optional.of(ELO);
    }

    int two = Integer.parseInt(digits.substring(0, 2));
    int four = Integer.parseInt(digits.substring(0, 4));

    if (digits.startsWith("4")) {
      return Optional.of(VISA);
    }
    if ((two >= 51 && two <= 55) || (four >= 2221 && four <= 2720)) {
      return Optional.of(MASTER);
    }
    if (two == 34 || two == 37) {
      return Optional.of(AMEX);
    }
    if (four >= 3528 && four <= 3589) {
      return Optional.of(JCB);
    }
    if (two == 36 || two == 38 || (four >= 3000 && four <= 3059)) {
      return Optional.of(DINERS);
    }
    if (digits.startsWith("6011") || two == 65) {
      return Optional.of(DISCOVER);
    }
    if (two == 50) {
      return Optional.of(AURA);
    }

    return Optional.empty();
  }

  public static CardBrand of(String raw) {
    String upper = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);

    return Arrays.stream(values())
        .filter(brand -> brand.name().equals(upper))
        .findFirst()
        .orElseThrow(
            () ->
                new InvalidCardValue(
                    "brand",
                    "must be one of "
                        + Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "))));
  }

  /** American Express prints four digits on the front; every other brand three on the back. */
  public int cvvLength() {
    return this == AMEX ? 4 : 3;
  }
}
```

- [ ] **Step 5: `CardSource`, `CardData`, `CardToken`, `CardOnFileUsage`**

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardSource.java`:
```java
package com.gateway.kernel.provider.card;

/**
 * What pays: the card itself, or the acquirer's token for one stored earlier. Sealed so the
 * request factory at the provider handles both and nothing else.
 */
public sealed interface CardSource permits CardData, CardToken {
  CardBrand brand();
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardData.java`:
```java
package com.gateway.kernel.provider.card;

import com.gateway.kernel.security.Secret;
import java.time.YearMonth;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A card as the payer gave it. The full number and the CVV exist in memory inside this record,
 * between the request being read and the Cielo being called, and nowhere else (spec §7).
 *
 * <p>{@code toString} is brand and last four only — the generated one would print every component.
 * Jackson must not serialize it either; the kernel has no Jackson, so the app registers an ignore
 * mixin for this type (plan C1).
 *
 * <p>The canonical constructor carries the rules that need more than one field: the brand must
 * match the BIN when the BIN is known, and the CVV length depends on the brand.
 */
public record CardData(
    CardNumber number, CardHolder holder, CardExpiry expiry, Secret securityCode, CardBrand brand)
    implements CardSource {
  private static final Pattern DIGITS = Pattern.compile("\\d+");

  public CardData {
    Optional<CardBrand> fromBin = CardBrand.fromBin(number.reveal());
    if (fromBin.isPresent() && brand != fromBin.get()) {
      throw new InvalidCardValue(
          "brand", "does not match the card number (" + fromBin.get() + ")");
    }

    String cvv = securityCode.reveal();
    if (!DIGITS.matcher(cvv).matches() || cvv.length() != brand.cvvLength()) {
      throw new InvalidCardValue("cvv", "must be " + brand.cvvLength() + " digits");
    }
  }

  /**
   * From the strings the merchant sent. {@code brand} may be null when the BIN identifies it; the
   * order of the checks is the order a payer would fix them in.
   */
  public static CardData of(
      String number,
      String holder,
      String expiry,
      String cvv,
      String brand,
      YearMonth currentMonth) {
    CardNumber cardNumber = CardNumber.of(number);
    CardHolder cardHolder = CardHolder.of(holder);
    CardExpiry cardExpiry = CardExpiry.of(expiry, currentMonth);

    if (cvv == null || cvv.isBlank()) {
      throw new InvalidCardValue("cvv", "is required");
    }

    CardBrand cardBrand = brandOf(cardNumber, brand);

    return new CardData(cardNumber, cardHolder, cardExpiry, Secret.of(cvv.trim()), cardBrand);
  }

  private static CardBrand brandOf(CardNumber number, String brand) {
    if (brand != null && !brand.isBlank()) {
      return CardBrand.of(brand);
    }

    return CardBrand.fromBin(number.reveal())
        .orElseThrow(
            () ->
                new InvalidCardValue(
                    "brand", "is required when the card number does not identify it"));
  }

  public String last4() {
    return number.last4();
  }

  @Override
  public String toString() {
    return brand + " " + number;
  }
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardOnFileUsage.java`:
```java
package com.gateway.kernel.provider.card;

/**
 * The Card On File marker (docs/card-on-file): FIRST on the charge that stores the card, USED on
 * every charge with the stored credential after it.
 */
public enum CardOnFileUsage {
  FIRST,
  USED
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardToken.java`:
```java
package com.gateway.kernel.provider.card;

import com.gateway.kernel.security.Secret;

/**
 * A card stored at the acquirer. {@code securityCode} is required: the Cielo's schema for a
 * tokenized charge lists {@code "required": ["CardToken", "SecurityCode"]}
 * (reference/cartao-tokenizado-api), against the spec's "optional" (plan D3).
 *
 * <p>The token itself is a credential for this merchant's account at the Cielo: never printed.
 */
public record CardToken(
    String value, CardBrand brand, CardOnFileUsage usage, Secret securityCode)
    implements CardSource {

  @Override
  public String toString() {
    return "CardToken[" + brand + ", " + usage + "]";
  }
}
```

- [ ] **Step 6: Os demais tipos**

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/Installments.java`:
```java
package com.gateway.kernel.provider.card;

import com.gateway.kernel.errors.InvalidValue;

/**
 * 1 to 12. Twelve is the Cielo's default ceiling (reference/criar-pagamento-credito: "Por padrão, a
 * API E-commerce aceita até 12 parcelas"); more needs the merchant to ask the Cielo, and is out of
 * this phase. The per-installment minimum needs the amount, so it lives with the amount
 * ({@code InstallmentPlan} in payments).
 */
public record Installments(int count) {

  public static Installments of(Integer raw) {
    int count = raw == null ? 1 : raw;

    if (count < 1 || count > 12) {
      throw new InvalidValue("must be between 1 and 12");
    }

    return new Installments(count);
  }
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/SoftDescriptor.java`:
```java
package com.gateway.kernel.provider.card;

import com.gateway.kernel.errors.InvalidValue;
import java.util.regex.Pattern;

/**
 * The text after the store name on the payer's statement: up to 13 characters, no special ones
 * (reference/criar-pagamento-credito, {@code SoftDescriptor}: "Não permite caracteres especiais.
 * Tamanho: 13"). Refused rather than truncated: the merchant chose the words and should see them
 * printed as chosen.
 */
public record SoftDescriptor(String value) {
  private static final Pattern ALPHANUMERIC_UP_TO_13 = Pattern.compile("[A-Za-z0-9]{1,13}");

  /** Null stays null: the Cielo then prints the store name alone. */
  public static SoftDescriptor ofNullable(String raw) {
    if (raw == null) {
      return null;
    }
    if (!ALPHANUMERIC_UP_TO_13.matcher(raw).matches()) {
      throw new InvalidValue("must be 1 to 13 letters or digits");
    }

    return new SoftDescriptor(raw);
  }
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardCustomer.java`:
```java
package com.gateway.kernel.provider.card;

import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;

/**
 * Who pays with the card, as the acquirer takes it: the name is required (the Cielo refuses a sale
 * without {@code Customer.Name}, code 105), document and e-mail are optional. Not {@code
 * kernel/party/Payer}: that one requires an address, which a card charge does not have (plan C4).
 */
public record CardCustomer(PersonName name, Document document, String email) {}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardStatus.java`:
```java
package com.gateway.kernel.provider.card;

import java.util.EnumSet;
import java.util.Set;

/**
 * The acquirer's view of a sale, normalized. PAID is the Cielo's PaymentConfirmed (captured).
 * Mapping from the Cielo's integers is the provider's (CieloStatuses, plan D1).
 */
public enum CardStatus {
  NOT_FINISHED,
  AUTHORIZED,
  PAID,
  DENIED,
  VOIDED,
  PENDING,
  ABORTED,
  PROCESSING,
  REFUNDED;

  private static final Set<CardStatus> IN_DOUBT = EnumSet.of(NOT_FINISHED, PENDING, PROCESSING);

  /** The acquirer has not decided: ask again, never adopt. */
  public boolean inDoubt() {
    return IN_DOUBT.contains(this);
  }
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardDeclineCode.java`:
```java
package com.gateway.kernel.provider.card;

/**
 * The gateway's decline vocabulary (spec §5). What a merchant's checkout can act on: ask for
 * another card (EXPIRED, BLOCKED, CANCELED, DO_NOT_HONOR), wait (INSUFFICIENT_FUNDS), retry later
 * (TIMEOUT), or nothing specific (GENERIC). The issuer's own text never reaches the merchant.
 */
public enum CardDeclineCode {
  INSUFFICIENT_FUNDS,
  EXPIRED_CARD,
  BLOCKED_CARD,
  CANCELED_CARD,
  TIMEOUT,
  DO_NOT_HONOR,
  GENERIC
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardAuthorization.java`:
```java
package com.gateway.kernel.provider.card;

import com.gateway.kernel.money.Money;
import java.time.Instant;
import java.util.Optional;

/**
 * A sale as the acquirer reports it, after an authorization, a capture or a query. {@code
 * paymentId} is the acquirer's id (the bank reference every later call uses), not ours. {@code
 * declineCode} is set only for DENIED and ABORTED; {@code returnMessage} is the issuer's text, kept
 * for the log and never shown to the merchant. {@code last4} is what the acquirer echoes from its
 * masked number.
 */
public record CardAuthorization(
    String paymentId,
    CardStatus status,
    String returnCode,
    String returnMessage,
    CardDeclineCode declineCode,
    String tid,
    String authorizationCode,
    String proofOfSale,
    Money amount,
    Money capturedAmount,
    CardBrand brand,
    String last4,
    Optional<String> cardToken,
    Instant receivedAt,
    Optional<Instant> capturedAt) {

  @Override
  public String toString() {
    return "CardAuthorization[paymentId="
        + paymentId
        + ", status="
        + status
        + ", returnCode="
        + returnCode
        + ", cardToken="
        + (cardToken.isPresent() ? "***" : "none")
        + "]";
  }
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardRefundResult.java`:
```java
package com.gateway.kernel.provider.card;

import com.gateway.kernel.money.Money;

/**
 * The answer to a void with an amount. Synchronous at the Cielo. Both VOIDED and REFUNDED mean the
 * money went back: a void on the day of the sale answers 10 even after capture, and only after 23h59
 * does it answer 11 (reference/payment-status; plan D2).
 */
public record CardRefundResult(
    CardStatus status, Money refundedAmount, String returnCode, String returnMessage) {

  public boolean completed() {
    return status == CardStatus.VOIDED || status == CardStatus.REFUNDED;
  }
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/StoredCard.java`:
```java
package com.gateway.kernel.provider.card;

import java.time.YearMonth;

/** A card stored at the acquirer by {@code POST /1/card/}. The token is never printed. */
public record StoredCard(String token, CardBrand brand, String last4, YearMonth expiry) {

  @Override
  public String toString() {
    return "StoredCard[" + brand + " ****" + last4 + ", token=***]";
  }
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardNotificationKind.java`:
```java
package com.gateway.kernel.provider.card;

/**
 * What an acquirer notification is about, normalized from the Cielo's ChangeType (docs/webhook):
 * 1 status changed, 25 partial cancel or refund, 5 cancel denied, 8 fraud alert; the rest (2, 3, 4,
 * 6, 7 — recurrence, antifraud, boleto, legacy chargeback) is not this phase's business.
 */
public enum CardNotificationKind {
  STATUS_CHANGED,
  PARTIAL_REFUND,
  VOID_DENIED,
  FRAUD_ALERT,
  IGNORED
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardNotification.java`:
```java
package com.gateway.kernel.provider.card;

/**
 * A parsed acquirer notification. Only a hint: the payment moves on what a query of {@code
 * paymentId} answers, never on the body. {@code changeType} is kept raw for the ignored event.
 */
public record CardNotification(String paymentId, CardNotificationKind kind, int changeType) {}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardIssueRequest.java`:
```java
package com.gateway.kernel.provider.card;

import com.gateway.kernel.money.Money;

/**
 * One authorization. {@code merchantOrderId} is ours — the payment id — so a timeout can be
 * followed by a query for it. {@code capture} and {@code saveCard} are what the Cielo's own request
 * carries as booleans; they are fields of a request, not flags steering a method.
 */
public record CardIssueRequest(
    String merchantOrderId,
    Money amount,
    Installments installments,
    boolean capture,
    boolean saveCard,
    SoftDescriptor softDescriptor,
    CardSource source,
    CardCustomer customer) {

  @Override
  public String toString() {
    return "CardIssueRequest[" + merchantOrderId + ", " + amount.cents() + ", " + source + "]";
  }
}
```

- [ ] **Step 7: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-kernel test`
Expected: PASS (todos os testes do kernel, os antigos inclusive).

- [ ] **Step 8: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-kernel/src/main/java/com/gateway/kernel/provider/card gateway-kernel/src/test/java/com/gateway/kernel/provider/card
git commit -m "feat(kernel): card value objects and the authorization vocabulary

CardNumber (digits, Luhn, masked toString), CardExpiry (current month still
valid), CardHolder, CardBrand (the Cielo's Brand list, no Hipercard) and
CardData, whose constructor carries the rules that span fields: brand against
BIN, CVV length by brand. CardToken requires the CVV because the Cielo's
tokenized-charge schema lists SecurityCode as required.

Nothing here prints a number, a CVV or a token: that is the first line of the
PCI rule in spec 2026-09-28 section 7.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: `CardMethodProvider`, `PaymentMethod.CARD`, `ProviderGateway.resolveCard` e o provider por flow

**Files:**
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardMethodProvider.java`
- Modify: `gateway-kernel/src/main/java/com/gateway/kernel/payment/PaymentMethod.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/provider/ProviderGateway.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/PaymentsConfiguration.java` (bean `providerGateway`, bean `cardPaymentFlow`)
- Modify: `gateway-payments/src/main/java/com/gateway/payments/payment/PaymentService.java` (remove `PROVIDER`)
- Modify: `gateway-payments/src/main/java/com/gateway/payments/payment/create/{PixPaymentFlow,BolecodePaymentFlow}.java`, `payment/PixSettlement.java`, `reconciliation/ReconciliationService.java`, test `payment/BolecodeServiceIntegrationTest.java:422`
- Create: `gateway-payments/src/main/java/com/gateway/payments/payment/create/CardPaymentFlow.java` (primeira versão: recusa)
- Test: `gateway-payments/src/test/java/com/gateway/payments/provider/ProviderGatewayTest.java`, `gateway-payments/src/test/java/com/gateway/payments/payment/create/PaymentFlowsTest.java`

**Interfaces:**
- Consumes: Task 1.
- Produces:
  ```java
  // kernel
  enum PaymentMethod { PIX, BOLECODE, CARD }
  interface CardMethodProvider extends MethodProvider<CardIssueRequest, CardAuthorization, CardAuthorization> {
    CardAuthorization capture(ProviderCredentials credentials, String bankReference, Optional<Money> amount);
    CardRefundResult refund(ProviderCredentials credentials, String bankReference, Optional<Money> amount);
    Optional<CardAuthorization> findByOrder(ProviderCredentials credentials, String merchantOrderId);
    StoredCard tokenize(ProviderCredentials credentials, CardData card, String customerName);
    CardNotification parseWebhook(byte[] body);
  }
  // payments
  ProviderGateway(List<PixMethodProvider>, List<BoletoMethodProvider>, List<CardMethodProvider>, CredentialLookup, ProviderRequestRepository)
  ResolvedProvider<CardMethodProvider> ProviderGateway.resolveCard(MerchantId, ProviderEnvironment, String providerId)
  CardMethodProvider ProviderGateway.cardProvider(String providerId)          // no credential: parsing a notification
  boolean ProviderGateway.hasCardProvider(String providerId)
  static final String PixPaymentFlow.PROVIDER = "ITAU"; BolecodePaymentFlow.PROVIDER = "ITAU"; CardPaymentFlow.PROVIDER = "CIELO"
  ```

- [ ] **Step 1: Refactor sem mudança de comportamento — o provider mora em cada flow**

A spec §3: "`PaymentService.PROVIDER = "ITAU"` deixa de ser uma constante única: cada flow declara o seu". Em `PixPaymentFlow` e `BolecodePaymentFlow` acrescente, logo abaixo da declaração da classe:
```java
  /**
   * The one bank this method goes to until per-merchant routing exists (spec 2026-09-28 §2). Here
   * rather than on PaymentService: each method's provider is that method's decision.
   */
  public static final String PROVIDER = "ITAU";
```
e troque cada `PaymentService.PROVIDER` do próprio arquivo por `PROVIDER` (Pix: linhas 62 e 68; Bolecode: 82, 90, 130). Em `PixSettlement.java:167` e `ReconciliationService.java:151,161` troque por `PixPaymentFlow.PROVIDER` (import `com.gateway.payments.payment.create.PixPaymentFlow`). Em `BolecodeServiceIntegrationTest.java:422` troque por `BolecodePaymentFlow.PROVIDER`. Apague de `PaymentService`:
```java
  /** Plan B has one bank. The provider is resolved by name so a second one is a config change. */
  public static final String PROVIDER = "ITAU";
```
e os imports que sobrarem sem uso em `PixPaymentFlow`/`BolecodePaymentFlow` (`PaymentService`).

- [ ] **Step 2: A suíte de payments continua verde**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments test`
Expected: PASS, nenhum teste alterado além do import na linha 422.

- [ ] **Step 3: Commit do refactor**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-payments
git commit -m "refactor(payments): each flow declares its provider

PaymentService.PROVIDER was one constant for every method; the card method
goes to another acquirer (spec 2026-09-28 section 3). PixPaymentFlow and
BolecodePaymentFlow now own \"ITAU\"; settlement and reconciliation read the
Pix one. No behaviour change.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

- [ ] **Step 4: Os testes do gateway e das flows**

Acrescente a `gateway-payments/src/test/java/com/gateway/payments/provider/ProviderGatewayTest.java` (e troque cada `new ProviderGateway(X, List.of(), credential, requests)` existente por `new ProviderGateway(X, List.of(), List.of(), credential, requests)`):
```java
  @Test
  void cardResolvesToTheNamedAcquirerWithItsCredential() {
    CardMethodProvider cielo = new NamedCardProvider("CIELO");
    ProviderGateway gateway =
        new ProviderGateway(List.of(), List.of(), List.of(cielo), oneCredential, requests);

    ResolvedProvider<CardMethodProvider> resolved =
        gateway.resolveCard(MERCHANT, ProviderEnvironment.TEST, "CIELO");

    assertThat(resolved.provider()).isSameAs(cielo);
    assertThat(gateway.hasCardProvider("cielo")).isTrue();
    assertThat(gateway.hasCardProvider("ITAU")).isFalse();
  }

  @Test
  void anAcquirerWithoutCardIsMethodNotSupported() {
    ProviderGateway gateway =
        new ProviderGateway(List.of(), List.of(), List.of(), oneCredential, requests);

    assertThatThrownBy(() -> gateway.resolveCard(MERCHANT, ProviderEnvironment.TEST, "CIELO"))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("METHOD_NOT_SUPPORTED");
  }

  /** Only what resolveCard needs; every operation is outside this test. */
  static final class NamedCardProvider implements CardMethodProvider {
    private final String id;

    NamedCardProvider(String id) {
      this.id = id;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public PaymentMethod method() {
      return PaymentMethod.CARD;
    }

    @Override
    public void requireIssueCredentials(ProviderCredentials credentials) {}

    @Override
    public CardAuthorization issue(ProviderCredentials credentials, CardIssueRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CardAuthorization> find(ProviderCredentials credentials, String reference) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void cancel(ProviderCredentials credentials, String reference) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CardAuthorization capture(
        ProviderCredentials credentials, String reference, Optional<Money> amount) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CardRefundResult refund(
        ProviderCredentials credentials, String reference, Optional<Money> amount) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CardAuthorization> findByOrder(
        ProviderCredentials credentials, String merchantOrderId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StoredCard tokenize(
        ProviderCredentials credentials, CardData card, String customerName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CardNotification parseWebhook(byte[] body) {
      throw new UnsupportedOperationException();
    }
  }
```
(imports: `com.gateway.kernel.provider.card.*`, `com.gateway.kernel.money.Money`, `com.gateway.kernel.payment.PaymentMethod`, `java.util.Optional`; `MERCHANT`, `oneCredential` e `requests` são os campos que o arquivo já tem.)

Em `gateway-payments/src/test/java/com/gateway/payments/payment/create/PaymentFlowsTest.java`, um teste novo e um ajuste. O ajuste não é refactor escondido: `resolvesTheFlowOfTheMethodAsked` monta um registro **completo**, e completo passou a incluir `CARD` — sem o ajuste ele afirma que PIX+BOLECODE bastam, o que deixou de ser verdade (e é exatamente o que o guarda existe para recusar). O commit diz isso.
```java
  /**
   * CARD exists from this task on, so a registry without its flow no longer starts: the guard that
   * turns a forgotten method into a startup failure instead of a merchant's 500.
   */
  @Test
  void aRegistryWithoutTheCardFlowFailsAtConstruction() {
    assertThatThrownBy(
            () ->
                new PaymentFlows(
                    List.of(flowFor(PaymentMethod.PIX), flowFor(PaymentMethod.BOLECODE))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("no payment flow for CARD");
  }
```
e, em `resolvesTheFlowOfTheMethodAsked`, o registro completo:
```java
    PaymentFlow pix = flowFor(PaymentMethod.PIX);
    PaymentFlow bolecode = flowFor(PaymentMethod.BOLECODE);
    PaymentFlow card = flowFor(PaymentMethod.CARD);

    PaymentFlows flows = new PaymentFlows(List.of(pix, bolecode, card));

    assertThat(flows.forMethod(PaymentMethod.PIX)).isSameAs(pix);
    assertThat(flows.forMethod(PaymentMethod.BOLECODE)).isSameAs(bolecode);
    assertThat(flows.forMethod(PaymentMethod.CARD)).isSameAs(card);
```

- [ ] **Step 5: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -am -Dtest='ProviderGatewayTest,PaymentFlowsTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `CardMethodProvider`, `PaymentMethod.CARD`, `resolveCard`, `CardPaymentFlow` não existem.

- [ ] **Step 6: Kernel**

`gateway-kernel/src/main/java/com/gateway/kernel/payment/PaymentMethod.java` — o enum vira:
```java
public enum PaymentMethod {
  PIX,
  BOLECODE,
  /** Credit card, customer present (spec 2026-09-28). Debit and 3DS are out of this phase. */
  CARD
}
```

`gateway-kernel/src/main/java/com/gateway/kernel/provider/card/CardMethodProvider.java`:
```java
package com.gateway.kernel.provider.card;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.MethodProvider;
import com.gateway.kernel.provider.ProviderCredentials;
import java.util.Optional;

/**
 * The card side: the spine ({@code issue} = authorize, {@code find} by the acquirer's payment id,
 * {@code cancel} = void of an authorization) plus what only a card has. Implemented per acquirer in
 * {@code gateway-providers} (CieloCardProvider); consumed by payments.
 *
 * <p>{@code bankReference} is the acquirer's PaymentId, which capture, void and query all take.
 * The MerchantOrderId (ours, the payment id) is only for {@link #findByOrder}, the recovery after a
 * lost answer (spec §3). {@code find}'s type is the whole authorization, not just a status: a
 * recovery must adopt tid, codes and amounts from it (plan C3).
 */
public interface CardMethodProvider
    extends MethodProvider<CardIssueRequest, CardAuthorization, CardAuthorization> {

  /** Empty amount captures everything authorized. The acquirer allows one capture per sale. */
  CardAuthorization capture(
      ProviderCredentials credentials, String bankReference, Optional<Money> amount);

  /** A void with an amount after capture: synchronous at the acquirer. */
  CardRefundResult refund(
      ProviderCredentials credentials, String bankReference, Optional<Money> amount);

  /** The most recent sale sent with this MerchantOrderId, or empty when the acquirer has none. */
  Optional<CardAuthorization> findByOrder(ProviderCredentials credentials, String merchantOrderId);

  /** Stores a card without charging it (spec §5). Not called by payments in this phase (C13). */
  StoredCard tokenize(ProviderCredentials credentials, CardData card, String customerName);

  /** Parsing needs no credential, like the Pix webhook: a rotated key must not lose the inbox. */
  CardNotification parseWebhook(byte[] body);
}
```

- [ ] **Step 7: `ProviderGateway`**

Em `gateway-payments/src/main/java/com/gateway/payments/provider/ProviderGateway.java`: acrescente o campo e o parâmetro do construtor
```java
  private final List<CardMethodProvider> cardProviders;

  public ProviderGateway(
      List<PixMethodProvider> pixProviders,
      List<BoletoMethodProvider> boletoProviders,
      List<CardMethodProvider> cardProviders,
      CredentialLookup credentials,
      ProviderRequestRepository requests) {
    this.pixProviders = pixProviders;
    this.boletoProviders = boletoProviders;
    this.cardProviders = cardProviders;
    this.credentials = credentials;
    this.requests = requests;
  }
```
e, depois de `resolveBoleto`:
```java
  /** Same door as the boleto: an acquirer without a card product is METHOD_NOT_SUPPORTED, once. */
  public ResolvedProvider<CardMethodProvider> resolveCard(
      MerchantId merchantId, ProviderEnvironment environment, String providerId) {
    CardMethodProvider provider =
        cardProviders.stream()
            .filter(candidate -> candidate.id().equalsIgnoreCase(providerId))
            .findFirst()
            .orElseThrow(
                () ->
                    new DomainException(
                        "METHOD_NOT_SUPPORTED", providerId + " has no card product"));

    return new ResolvedProvider<>(provider, credential(merchantId, environment, providerId));
  }

  /** Parsing a notification needs no credential, like {@link #pixProvider}. */
  public CardMethodProvider cardProvider(String providerId) {
    return cardProviders.stream()
        .filter(candidate -> candidate.id().equalsIgnoreCase(providerId))
        .findFirst()
        .orElseThrow(
            () -> new DomainException("PROVIDER_UNKNOWN", "no card provider named " + providerId));
  }

  /** The inbox asks this to route a stored notification to the card side. */
  public boolean hasCardProvider(String providerId) {
    return cardProviders.stream().anyMatch(candidate -> candidate.id().equalsIgnoreCase(providerId));
  }
```
e troque `CREATING` para:
```java
  /**
   * The operations that create a resource at the bank answer 201 (PUT /cob, PUT /devolucao, POST
   * /1/sales).
   */
  private static final Set<String> CREATING =
      Set.of("createCharge", "requestRefund", "authorizeCard");
```
(import `com.gateway.kernel.provider.card.CardMethodProvider`.)

- [ ] **Step 8: `CardPaymentFlow` recusando, e o wiring**

`gateway-payments/src/main/java/com/gateway/payments/payment/create/CardPaymentFlow.java`:
```java
package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.payment.Payment;

/**
 * Creating a card payment. Until the flow lands (Task 8) a CARD create is refused as a method not
 * supported: CARD must exist in the enum now, for the provider module to declare it, and
 * PaymentFlows refuses to start with a method that has no flow — which is the guard worth keeping.
 */
public class CardPaymentFlow implements PaymentFlow {
  public static final String PROVIDER = "CIELO";

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  @Override
  public Payment create(CreatePaymentCommand command) {
    throw new DomainException("METHOD_NOT_SUPPORTED", "CARD payments are not enabled yet");
  }
}
```

Em `PaymentsConfiguration`:
```java
  /**
   * ObjectProvider: a context with no BoletoMethodProvider or CardMethodProvider at all (some
   * payments tests) must still start.
   */
  @Bean
  ProviderGateway providerGateway(
      List<PixMethodProvider> providers,
      ObjectProvider<BoletoMethodProvider> boletoProviders,
      ObjectProvider<CardMethodProvider> cardProviders,
      CredentialLookup credentials,
      ProviderRequestRepository requests) {
    return new ProviderGateway(
        providers,
        boletoProviders.orderedStream().toList(),
        cardProviders.orderedStream().toList(),
        credentials,
        requests);
  }

  @Bean
  CardPaymentFlow cardPaymentFlow() {
    return new CardPaymentFlow();
  }
```
(imports `com.gateway.kernel.provider.card.CardMethodProvider`, `com.gateway.payments.payment.create.CardPaymentFlow`.) O `CreatePaymentCommand` selado **não** muda nesta task: nada cria um comando `CARD` ainda.

- [ ] **Step 9: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -am test`
Expected: PASS. Se algum `switch` sobre `PaymentMethod` em `main` deixar de compilar por não ser exaustivo, acrescente o ramo `CARD -> throw new IllegalStateException("CARD has no " + <o que o switch decide>)` e liste o arquivo no corpo do commit — `grep -rn "switch (.*method" gateway-*/src/main` hoje não acha nenhum.

- [ ] **Step 10: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-kernel gateway-payments
git commit -m "feat(payments): card method, acquirer contract and resolveCard

CardMethodProvider is the third MethodProvider; find returns the whole
CardAuthorization because a recovery adopts tid, codes and amounts from it,
and parseWebhook returns a CardNotification because ProviderWebhookEvent is
the Pix shape. ProviderGateway gets the typed card door. CardPaymentFlow
refuses CARD until the flow lands, so PaymentFlows keeps failing the startup
for a method with no flow at all.

PaymentFlowsTest.resolvesTheFlowOfTheMethodAsked now builds a registry with
the CARD flow too: a complete registry includes it, and without it the test
asserted what the guard exists to refuse.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---
### Task 3: A Cielo em `gateway-providers` — credencial, endpoints, erros, negativas, texto, status, datas e o mascaramento

**Files:**
- Create: `gateway-providers/src/main/java/com/gateway/providers/cielo/auth/{CieloCredentials,CieloEndpoints}.java`
- Create: `gateway-providers/src/main/java/com/gateway/providers/cielo/{CieloErrors,CieloText,CieloPayloadMasker}.java`
- Create: `gateway-providers/src/main/java/com/gateway/providers/cielo/sale/{CieloStatuses,CieloDeclines,CieloDates,CieloBrands}.java`
- Create: `gateway-providers/src/main/java/com/gateway/providers/cielo/sale/dto/CieloError.java`
- Test: `gateway-providers/src/test/java/com/gateway/providers/cielo/auth/{CieloCredentialsTest,CieloEndpointsTest}.java`, `gateway-providers/src/test/java/com/gateway/providers/cielo/{CieloErrorsTest,CieloTextTest,CieloPayloadMaskerTest}.java`, `gateway-providers/src/test/java/com/gateway/providers/cielo/sale/{CieloDeclinesTest,CieloStatusesTest,CieloDatesTest,CieloBrandsTest}.java`

**Interfaces:**
- Consumes: Task 1 (`CardStatus`, `CardDeclineCode`, `CardBrand`, `CardHolder`), `kernel.party.PersonName`, `kernel.provider.{ProviderException, ProviderEnvironment}`, `kernel.security.Secret`.
- Produces:
  ```java
  record CieloCredentials(String merchantId, Secret merchantKey, String fingerprint) { static CieloCredentials parse(byte[] json); }
      // IllegalArgumentException whose message STARTS with the field name:
      // "merchant_id is required", "merchant_id must be a GUID", "merchant_key is required", "merchant_key must be 40 letters or digits"
  record CieloEndpoints(URI api, URI apiQuery) { static CieloEndpoints forEnvironment(ProviderEnvironment environment); }
  final class CieloErrors { static ProviderException from(int status, String body); static boolean isTransactionNotFound(ProviderException e); }
  final class CieloText { static String holder(CardHolder holder); static String customerName(PersonName name); }
  final class CieloPayloadMasker { static String mask(String text); }
  final class CieloStatuses { static CardStatus of(Integer status); }
  final class CieloDeclines { static CardDeclineCode of(String returnCode); }
  final class CieloDates { static Instant parse(String text); }                          // null/blank → null
  final class CieloBrands { static String nameOf(CardBrand brand); static CardBrand of(String cieloName); }   // unknown → null
  record CieloError(@JsonProperty("Code") String code, @JsonProperty("Message") String message)
  ```

- [ ] **Step 1: Testes da credencial e dos endpoints**

`gateway-providers/src/test/java/com/gateway/providers/cielo/auth/CieloCredentialsTest.java`:
```java
package com.gateway.providers.cielo.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CieloCredentialsTest {
  static final String MERCHANT_ID = "11111111-2222-3333-4444-555555555555";
  static final String MERCHANT_KEY = "A".repeat(40);

  static byte[] json(String id, String key) {
    return ("{\"merchant_id\":\"" + id + "\",\"merchant_key\":\"" + key + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void parsesBothFieldsAndNeverPrintsTheKey() {
    CieloCredentials credentials = CieloCredentials.parse(json(MERCHANT_ID, MERCHANT_KEY));

    assertThat(credentials.merchantId()).isEqualTo(MERCHANT_ID);
    assertThat(credentials.merchantKey().reveal()).isEqualTo(MERCHANT_KEY);
    assertThat(credentials.fingerprint()).hasSize(64);
    assertThat(credentials.toString()).doesNotContain(MERCHANT_KEY).contains("merchantKey=***");
  }

  @Test
  void theIdMustBeAGuid() {
    assertThatThrownBy(() -> CieloCredentials.parse(json("not-a-guid", MERCHANT_KEY)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("merchant_id must be a GUID");
  }

  /**
   * The docs call the key a GUID of 40 characters; their own example is 40 upper-case letters with
   * no hyphen (plan D18). The rule is the example's: 40 letters or digits.
   */
  @Test
  void theKeyIsFortyLettersOrDigitsAndIsNotEchoed() {
    assertThat(CieloCredentials.parse(json(MERCHANT_ID, "aB3".repeat(13) + "x")).merchantKey())
        .isNotNull();
    assertThatThrownBy(() -> CieloCredentials.parse(json(MERCHANT_ID, "SHORT-KEY")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("merchant_key must be 40 letters or digits")
        .hasMessageNotContaining("SHORT-KEY");
  }

  @Test
  void aMissingFieldIsNamedFirst() {
    assertThatThrownBy(() -> CieloCredentials.parse("{}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("merchant_id is required");
    assertThatThrownBy(
            () ->
                CieloCredentials.parse(
                    ("{\"merchant_id\":\"" + MERCHANT_ID + "\"}").getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("merchant_key is required");
  }
}
```

`gateway-providers/src/test/java/com/gateway/providers/cielo/auth/CieloEndpointsTest.java`:
```java
package com.gateway.providers.cielo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.ProviderEnvironment;
import org.junit.jupiter.api.Test;

/** The four hosts of reference/como-usar-o-sandbox and the "Produção" row of each page. */
class CieloEndpointsTest {

  @Test
  void productionAndSandboxEachHaveATransactionalAndAQueryHost() {
    CieloEndpoints live = CieloEndpoints.forEnvironment(ProviderEnvironment.LIVE);
    CieloEndpoints test = CieloEndpoints.forEnvironment(ProviderEnvironment.TEST);

    assertThat(live.api()).hasToString("https://api.cieloecommerce.cielo.com.br");
    assertThat(live.apiQuery()).hasToString("https://apiquery.cieloecommerce.cielo.com.br");
    assertThat(test.api()).hasToString("https://apisandbox.cieloecommerce.cielo.com.br");
    assertThat(test.apiQuery()).hasToString("https://apiquerysandbox.cieloecommerce.cielo.com.br");
  }
}
```

- [ ] **Step 2: Testes de erros, texto e mascaramento**

`gateway-providers/src/test/java/com/gateway/providers/cielo/CieloErrorsTest.java`:
```java
package com.gateway.providers.cielo;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderException.Code;
import org.junit.jupiter.api.Test;

class CieloErrorsTest {

  /** reference/api-errors-code-message, the page's own example body. */
  @Test
  void aFourHundredListIsInvalidWithItsCodes() {
    ProviderException e =
        CieloErrors.from(400, "[{\"Code\":322,\"Message\":\"Zero Dollar Auth is not enabled\"}]");

    assertThat(e.code()).isEqualTo(Code.INVALID);
    assertThat(e.httpStatus()).isEqualTo(400);
    assertThat(e.providerType()).isEqualTo("322");
    assertThat(e.getMessage()).isEqualTo("322 Zero Dollar Auth is not enabled");
  }

  @Test
  void severalErrorsKeepEveryCode() {
    ProviderException e =
        CieloErrors.from(
            400,
            "[{\"Code\":126,\"Message\":\"Credit Card Expiration Date is invalid\"},"
                + "{\"Code\":182,\"Message\":\"Brand is required\"}]");

    assertThat(e.providerType()).isEqualTo("126,182");
  }

  /** The creation page's OpenAPI documents 400/401 bodies as bare strings (plan D9). */
  @Test
  void aBodyThatIsNotJsonFallsBackToTheStatus() {
    assertThat(CieloErrors.from(400, "Bad request").code()).isEqualTo(Code.INVALID);
    assertThat(CieloErrors.from(401, "Unauthorized").code()).isEqualTo(Code.UNAUTHENTICATED);
    assertThat(CieloErrors.from(404, "").code()).isEqualTo(Code.NOT_FOUND);
    assertThat(CieloErrors.from(500, null).code()).isEqualTo(Code.UNAVAILABLE);
    assertThat(CieloErrors.from(503, "<html>").code()).isEqualTo(Code.UNAVAILABLE);
    assertThat(CieloErrors.from(504, "").code()).isEqualTo(Code.TIMEOUT);
    assertThat(CieloErrors.from(302, "").code()).isEqualTo(Code.UNKNOWN);
  }

  /** api-codes: 307 "Transaction not found" is the only documented not-found answer (plan D10). */
  @Test
  void transactionNotFoundIsA404OrCode307() {
    assertThat(CieloErrors.isTransactionNotFound(CieloErrors.from(404, ""))).isTrue();
    assertThat(
            CieloErrors.isTransactionNotFound(
                CieloErrors.from(400, "[{\"Code\":307,\"Message\":\"Transaction not found\"}]")))
        .isTrue();
    assertThat(
            CieloErrors.isTransactionNotFound(
                CieloErrors.from(400, "[{\"Code\":3070,\"Message\":\"x\"}]")))
        .isFalse();
  }

  @Test
  void aCardNumberInARawBodyIsMaskedBeforeItBecomesTheMessage() {
    ProviderException e = CieloErrors.from(502, "{\"CardNumber\":\"4024007153763171\"}");

    assertThat(e.getMessage()).doesNotContain("4024007153763171").contains("402400******3171");
  }
}
```

`gateway-providers/src/test/java/com/gateway/providers/cielo/CieloTextTest.java`:
```java
package com.gateway.providers.cielo;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.card.CardHolder;
import org.junit.jupiter.api.Test;

/**
 * Holder: "Não aceita caracteres especiais ou acentuação" (reference/criar-pagamento-credito) and
 * code 214 "Credit Card Holder Must Have Only Letters". Customer.Name: "apenas a-z, A-Z".
 */
class CieloTextTest {

  @Test
  void theHolderIsTransliteratedNotStripped() {
    assertThat(CieloText.holder(CardHolder.of("JOÃO DA CONCEIÇÃO"))).isEqualTo("JOAO DA CONCEICAO");
    assertThat(CieloText.holder(CardHolder.of("Zoë Ñúñez"))).isEqualTo("Zoe Nunez");
  }

  @Test
  void theCustomerNameKeepsOnlyLettersAndSpaces() {
    assertThat(CieloText.customerName(PersonName.of("  Joana D'Arc-Silva 3 ")))
        .isEqualTo("Joana DArcSilva");
  }
}
```

`gateway-providers/src/test/java/com/gateway/providers/cielo/CieloPayloadMaskerTest.java`:
```java
package com.gateway.providers.cielo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Spec §7: CardNumber becomes first6******last4 and SecurityCode ***. The token and the merchant
 * key are credentials of the merchant's Cielo account, so they go too.
 */
class CieloPayloadMaskerTest {

  @Test
  void masksTheFourSensitiveFields() {
    String body =
        "{\"CardNumber\": \"4024007153763171\",\"SecurityCode\":\"123\","
            + "\"CardToken\":\"6e1bf77a-b28b-4660-b14f-455e2a1c95e9\",\"MerchantKey\":\"KEY\"}";

    assertThat(CieloPayloadMasker.mask(body))
        .isEqualTo(
            "{\"CardNumber\":\"402400******3171\",\"SecurityCode\":\"***\","
                + "\"CardToken\":\"***\",\"MerchantKey\":\"***\"}");
  }

  @Test
  void anAlreadyMaskedNumberAndNullAreLeftAlone() {
    assertThat(CieloPayloadMasker.mask("{\"CardNumber\":\"409168******7641\"}"))
        .isEqualTo("{\"CardNumber\":\"409168******7641\"}");
    assertThat(CieloPayloadMasker.mask(null)).isNull();
  }
}
```

- [ ] **Step 3: Testes das tabelas da venda**

`gateway-providers/src/test/java/com/gateway/providers/cielo/sale/CieloStatusesTest.java`:
```java
package com.gateway.providers.cielo.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.card.CardStatus;
import org.junit.jupiter.api.Test;

/** reference/payment-status (read 2026-09-28), plus the two codes the spec listed (plan D1). */
class CieloStatusesTest {

  @Test
  void theDocumentedCodes() {
    assertThat(CieloStatuses.of(0)).isEqualTo(CardStatus.NOT_FINISHED);
    assertThat(CieloStatuses.of(1)).isEqualTo(CardStatus.AUTHORIZED);
    assertThat(CieloStatuses.of(2)).isEqualTo(CardStatus.PAID);
    assertThat(CieloStatuses.of(3)).isEqualTo(CardStatus.DENIED);
    assertThat(CieloStatuses.of(10)).isEqualTo(CardStatus.VOIDED);
    assertThat(CieloStatuses.of(11)).isEqualTo(CardStatus.REFUNDED);
    assertThat(CieloStatuses.of(12)).isEqualTo(CardStatus.PENDING);
    assertThat(CieloStatuses.of(13)).isEqualTo(CardStatus.ABORTED);
  }

  @Test
  void theSpecsCodesAreTolerated() {
    assertThat(CieloStatuses.of(14)).isEqualTo(CardStatus.PROCESSING);
    assertThat(CieloStatuses.of(15)).isEqualTo(CardStatus.REFUNDED);
  }

  /** 20 Scheduled is recurrence (out of scope); anything unknown is doubt, never an adoption. */
  @Test
  void anythingElseIsInDoubt() {
    assertThat(CieloStatuses.of(20)).isEqualTo(CardStatus.PROCESSING);
    assertThat(CieloStatuses.of(99)).isEqualTo(CardStatus.PROCESSING);
    assertThat(CieloStatuses.of(null)).isEqualTo(CardStatus.PROCESSING);
  }
}
```

`gateway-providers/src/test/java/com/gateway/providers/cielo/sale/CieloDeclinesTest.java`:
```java
package com.gateway.providers.cielo.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.card.CardDeclineCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Measured against page/abecs (read 2026-09-28, Visa table; Mastercard, Elo and Amex agree on these
 * codes) and, for the codes that exist only in the sandbox, reference/credito-sandbox (plan D7, D8).
 */
class CieloDeclinesTest {

  @Test
  void theTable() {
    Map<String, CardDeclineCode> expected =
        Map.ofEntries(
            Map.entry("51", CardDeclineCode.INSUFFICIENT_FUNDS),
            Map.entry("54", CardDeclineCode.EXPIRED_CARD),
            Map.entry("78", CardDeclineCode.BLOCKED_CARD),
            Map.entry("62", CardDeclineCode.BLOCKED_CARD),
            Map.entry("41", CardDeclineCode.CANCELED_CARD),
            Map.entry("43", CardDeclineCode.CANCELED_CARD),
            Map.entry("46", CardDeclineCode.CANCELED_CARD),
            Map.entry("91", CardDeclineCode.TIMEOUT),
            Map.entry("96", CardDeclineCode.TIMEOUT),
            Map.entry("57", CardDeclineCode.DO_NOT_HONOR),
            Map.entry("14", CardDeclineCode.DO_NOT_HONOR),
            Map.entry("59", CardDeclineCode.DO_NOT_HONOR),
            Map.entry("83", CardDeclineCode.DO_NOT_HONOR),
            Map.entry("N7", CardDeclineCode.DO_NOT_HONOR),
            Map.entry("05", CardDeclineCode.GENERIC),
            Map.entry("99", CardDeclineCode.TIMEOUT),
            Map.entry("77", CardDeclineCode.CANCELED_CARD),
            Map.entry("70", CardDeclineCode.DO_NOT_HONOR));

    expected.forEach(
        (returnCode, declineCode) ->
            assertThat(CieloDeclines.of(returnCode)).as(returnCode).isEqualTo(declineCode));
  }

  @Test
  void unknownOrMissingIsGeneric() {
    assertThat(CieloDeclines.of("BP171")).isEqualTo(CardDeclineCode.GENERIC);
    assertThat(CieloDeclines.of(null)).isEqualTo(CardDeclineCode.GENERIC);
    assertThat(CieloDeclines.of(" 51 ")).isEqualTo(CardDeclineCode.INSUFFICIENT_FUNDS);
  }
}
```

`gateway-providers/src/test/java/com/gateway/providers/cielo/sale/CieloDatesTest.java`:
```java
package com.gateway.providers.cielo.sale;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CieloDatesTest {

  /** "ReceivedDate": "2025-11-24 18:04:07" in the creation example: Brasília, UTC-3. */
  @Test
  void aSaleDateIsSaoPauloTime() {
    assertThat(CieloDates.parse("2025-11-24 18:04:07"))
        .isEqualTo(Instant.parse("2025-11-24T21:04:07Z"));
  }

  /** "ReceveidDate": "2024-11-29T13:36:04.033" in the by-order query example. */
  @Test
  void theQueryShapeWithFractionAlsoParses() {
    assertThat(CieloDates.parse("2024-11-29T13:36:04.033"))
        .isEqualTo(Instant.parse("2024-11-29T16:36:04.033Z"));
    assertThat(CieloDates.parse("2025-02-18T14:10:10.61"))
        .isEqualTo(Instant.parse("2025-02-18T17:10:10.610Z"));
  }

  @Test
  void blankIsNull() {
    assertThat(CieloDates.parse(null)).isNull();
    assertThat(CieloDates.parse(" ")).isNull();
  }
}
```

`gateway-providers/src/test/java/com/gateway/providers/cielo/sale/CieloBrandsTest.java`:
```java
package com.gateway.providers.cielo.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.card.CardBrand;
import org.junit.jupiter.api.Test;

/** The spelling of the Brand field (reference/criar-pagamento-credito). */
class CieloBrandsTest {

  @Test
  void everyBrandRoundTrips() {
    for (CardBrand brand : CardBrand.values()) {
      assertThat(CieloBrands.of(CieloBrands.nameOf(brand))).isEqualTo(brand);
    }
    assertThat(CieloBrands.nameOf(CardBrand.MASTER)).isEqualTo("Master");
    assertThat(CieloBrands.nameOf(CardBrand.JCB)).isEqualTo("JCB");
    assertThat(CieloBrands.of("VISA")).isEqualTo(CardBrand.VISA);
    assertThat(CieloBrands.of("Hipercard")).isNull();
  }
}
```

- [ ] **Step 4: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-providers -am -Dtest='Cielo*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — o pacote `com.gateway.providers.cielo` não existe.

- [ ] **Step 5: `CieloCredentials` e `CieloEndpoints`**

`gateway-providers/src/main/java/com/gateway/providers/cielo/auth/CieloCredentials.java`:
```java
package com.gateway.providers.cielo.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.kernel.security.Secret;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * A merchant's Cielo E-commerce credential: the two headers every call carries
 * (reference/gerenciamento-de-credenciais). Same shape in sandbox and production — only the host
 * differs — so there is no environment rule here.
 *
 * <p>Every validation message starts with the field name: the provider turns it into the
 * CREDENTIALS_INCOMPLETE {@code providerType}, and the admin API's 422 names the field. The value
 * itself is never echoed: a key with a typo is still the merchant's key.
 */
public record CieloCredentials(String merchantId, Secret merchantKey, String fingerprint) {
  private static final Pattern GUID =
      Pattern.compile(
          "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
  private static final Pattern FORTY_ALPHANUMERIC = Pattern.compile("^[A-Za-z0-9]{40}$");

  public static CieloCredentials parse(byte[] json) {
    Raw raw = new ObjectMapper().readValue(json, Raw.class);

    if (raw.merchantId() == null || raw.merchantId().isBlank()) {
      throw new IllegalArgumentException("merchant_id is required");
    }
    if (!GUID.matcher(raw.merchantId()).matches()) {
      throw new IllegalArgumentException("merchant_id must be a GUID");
    }
    if (raw.merchantKey() == null || raw.merchantKey().isBlank()) {
      throw new IllegalArgumentException("merchant_key is required");
    }
    if (!FORTY_ALPHANUMERIC.matcher(raw.merchantKey()).matches()) {
      throw new IllegalArgumentException("merchant_key must be 40 letters or digits");
    }

    return new CieloCredentials(raw.merchantId(), Secret.of(raw.merchantKey()), sha256Hex(json));
  }

  /**
   * Hash of the whole payload, as ItauCredentials does: not a secret by itself but derived from
   * one, so {@link #toString} leaves it out.
   */
  @Override
  public String fingerprint() {
    return fingerprint;
  }

  @Override
  public String toString() {
    return "CieloCredentials[merchantId=" + merchantId + ", merchantKey=***]";
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private record Raw(
      @JsonProperty("merchant_id") String merchantId,
      @JsonProperty("merchant_key") String merchantKey) {}
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/auth/CieloEndpoints.java`:
```java
package com.gateway.providers.cielo.auth;

import com.gateway.kernel.provider.ProviderEnvironment;
import java.net.URI;

/**
 * Two hosts per environment: transactional (authorize, capture, void, tokenize) and query (GET).
 * reference/como-usar-o-sandbox for the sandbox pair; each reference page's "Produção" row for the
 * production pair (read 2026-09-28).
 */
public record CieloEndpoints(URI api, URI apiQuery) {
  private static final URI LIVE_API = URI.create("https://api.cieloecommerce.cielo.com.br");
  private static final URI LIVE_QUERY = URI.create("https://apiquery.cieloecommerce.cielo.com.br");
  private static final URI TEST_API = URI.create("https://apisandbox.cieloecommerce.cielo.com.br");
  private static final URI TEST_QUERY =
      URI.create("https://apiquerysandbox.cieloecommerce.cielo.com.br");

  public static CieloEndpoints forEnvironment(ProviderEnvironment environment) {
    return switch (environment) {
      case LIVE -> new CieloEndpoints(LIVE_API, LIVE_QUERY);
      case TEST -> new CieloEndpoints(TEST_API, TEST_QUERY);
    };
  }
}
```

- [ ] **Step 6: `CieloPayloadMasker`, `CieloError`, `CieloErrors`, `CieloText`**

`gateway-providers/src/main/java/com/gateway/providers/cielo/CieloPayloadMasker.java`:
```java
package com.gateway.providers.cielo;

import java.util.regex.Pattern;

/**
 * Masks what must never leave the Cielo client as text (spec §7): every exception message the
 * client builds from a body goes through here before it can reach provider_requests or a log. The
 * gateway does not record request bodies at all (ProviderGateway, plan C2); this is the rule for
 * everything else.
 */
public final class CieloPayloadMasker {
  private static final Pattern CARD_NUMBER =
      Pattern.compile("\"CardNumber\"\\s*:\\s*\"(\\d{6})\\d{3,9}(\\d{4})\"");
  private static final Pattern SECRET_FIELDS =
      Pattern.compile("\"(SecurityCode|CardToken|MerchantKey)\"\\s*:\\s*\"[^\"]*\"");

  private CieloPayloadMasker() {}

  public static String mask(String text) {
    if (text == null) {
      return null;
    }

    String masked = CARD_NUMBER.matcher(text).replaceAll("\"CardNumber\":\"$1******$2\"");
    return SECRET_FIELDS.matcher(masked).replaceAll("\"$1\":\"***\"");
  }
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/dto/CieloError.java`:
```java
package com.gateway.providers.cielo.sale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One item of a 4xx body: {@code [{"Code": 322, "Message": "..."}]}
 * (reference/api-errors-code-message). {@code Code} is a number in the example and a string ("00")
 * in the table; read as text either way.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CieloError(
    @JsonProperty("Code") String code, @JsonProperty("Message") String message) {}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/CieloErrors.java`:
```java
package com.gateway.providers.cielo;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderException.Code;
import com.gateway.providers.cielo.sale.dto.CieloError;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Non-2xx from the Cielo → {@link ProviderException}. The status decides the code; the list's
 * {@code Code}s travel in {@code providerType} (comma-separated) so support reads what the Cielo
 * said. A decline is NOT here: it is a 201 with Status 3 (reference/api-codes), a business answer
 * the flow handles.
 *
 * <p>Cielo messages are fixed English sentences ("Credit Card Expiration Date is invalid"), so the
 * message keeps them; a raw body that is not a list goes through the masker first.
 */
public final class CieloErrors {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_RAW = 300;
  private static final String TRANSACTION_NOT_FOUND = "307";

  private CieloErrors() {}

  public static ProviderException from(int status, String body) {
    List<CieloError> errors = parse(body);

    if (errors.isEmpty()) {
      String raw = body == null || body.isBlank() ? "" : ": " + truncate(body);
      return new ProviderException(
          code(status), status, null, CieloPayloadMasker.mask("Cielo HTTP " + status + raw));
    }

    String codes = errors.stream().map(CieloError::code).collect(Collectors.joining(","));
    String message =
        errors.stream()
            .map(error -> error.code() + " " + error.message())
            .collect(Collectors.joining("; "));

    return new ProviderException(code(status), status, codes, CieloPayloadMasker.mask(message));
  }

  /**
   * No page documents a 404 for an unknown PaymentId; api-codes documents 307 "Transaction not
   * found" (plan D10). Both mean "the Cielo does not know it".
   */
  public static boolean isTransactionNotFound(ProviderException e) {
    if (e.code() == Code.NOT_FOUND) {
      return true;
    }

    return e.code() == Code.INVALID
        && e.providerType() != null
        && Arrays.asList(e.providerType().split(",")).contains(TRANSACTION_NOT_FOUND);
  }

  static Code code(int status) {
    if (status == 400 || status == 422) {
      return Code.INVALID;
    }
    if (status == 401 || status == 403) {
      return Code.UNAUTHENTICATED;
    }
    if (status == 404) {
      return Code.NOT_FOUND;
    }
    if (status == 504) {
      return Code.TIMEOUT;
    }
    if (status >= 500) {
      return Code.UNAVAILABLE;
    }

    return Code.UNKNOWN;
  }

  private static List<CieloError> parse(String body) {
    if (body == null || !body.trim().startsWith("[")) {
      return List.of();
    }

    try {
      return MAPPER.readValue(body, new TypeReference<List<CieloError>>() {});
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  private static String truncate(String text) {
    return text.length() <= MAX_RAW ? text : text.substring(0, MAX_RAW) + "…";
  }
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/CieloText.java`:
```java
package com.gateway.providers.cielo;

import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardHolder;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * The Cielo's text rules, applied on the way out. Holder: "Não aceita caracteres especiais ou
 * acentuação. Tamanho: 25"; Customer.Name: "apenas a-z, A-Z", 255
 * (reference/criar-pagamento-credito). Transliterated (Ã → A), not dropped: "JOÃO" must reach the
 * issuer as "JOAO", not "JO".
 */
public final class CieloText {
  private static final Pattern MARKS = Pattern.compile("\\p{M}");
  private static final Pattern NOT_ASCII_LETTER_OR_SPACE = Pattern.compile("[^A-Za-z ]");
  private static final Pattern SPACES = Pattern.compile(" +");
  private static final int HOLDER_MAX = 25;
  private static final int NAME_MAX = 255;

  private CieloText() {}

  public static String holder(CardHolder holder) {
    return clean(holder.value(), HOLDER_MAX);
  }

  public static String customerName(PersonName name) {
    return clean(name.value(), NAME_MAX);
  }

  private static String clean(String text, int max) {
    String plain = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
    String letters = NOT_ASCII_LETTER_OR_SPACE.matcher(plain).replaceAll("");
    String single = SPACES.matcher(letters.trim()).replaceAll(" ");

    if (single.isEmpty()) {
      // INVALID, not DECLINED: the Cielo never saw it; our input has nothing it accepts.
      throw new ProviderException(
          ProviderException.Code.INVALID, 0, null, "name has no letters the Cielo accepts");
    }

    return single.length() <= max ? single : single.substring(0, max).trim();
  }
}
```

- [ ] **Step 7: As tabelas da venda**

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/CieloStatuses.java`:
```java
package com.gateway.providers.cielo.sale;

import com.gateway.kernel.provider.card.CardStatus;
import java.util.Map;

/**
 * Payment.Status → {@link CardStatus}. The table is reference/payment-status (read 2026-09-28): 0,
 * 1, 2, 3, 10, 11, 12, 13, 20. The spec also listed 14 Processing and 15 Refunded from an older
 * list; both are tolerated so a Cielo that still sends them is understood (plan D1).
 *
 * <p>Everything unknown — 20 Scheduled (recurrence, out of scope) included — is PROCESSING: in
 * doubt, so the flow asks again instead of adopting something it does not understand.
 */
public final class CieloStatuses {
  private static final Map<Integer, CardStatus> BY_CODE =
      Map.ofEntries(
          Map.entry(0, CardStatus.NOT_FINISHED),
          Map.entry(1, CardStatus.AUTHORIZED),
          Map.entry(2, CardStatus.PAID),
          Map.entry(3, CardStatus.DENIED),
          Map.entry(10, CardStatus.VOIDED),
          Map.entry(11, CardStatus.REFUNDED),
          Map.entry(12, CardStatus.PENDING),
          Map.entry(13, CardStatus.ABORTED),
          Map.entry(14, CardStatus.PROCESSING),
          Map.entry(15, CardStatus.REFUNDED));

  private CieloStatuses() {}

  public static CardStatus of(Integer status) {
    if (status == null) {
      return CardStatus.PROCESSING;
    }

    return BY_CODE.getOrDefault(status, CardStatus.PROCESSING);
  }
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/CieloDeclines.java`:
```java
package com.gateway.providers.cielo.sale;

import com.gateway.kernel.provider.card.CardDeclineCode;
import java.util.Map;

/**
 * ReturnCode of a declined sale → the gateway's {@link CardDeclineCode}. Measured against
 * page/abecs ("Códigos de Retorno padrão ABECS", read 2026-09-28), not reference/api-codes: that
 * page only lists the API's own codes (100–841) and points to ABECS for issuer declines (plan D7).
 *
 * <p>Three codes exist only in the sandbox (reference/credito-sandbox: 99 timeout, 77 canceled, 70
 * card problems) and none is an ABECS code, so mapping them shadows no production answer. 57
 * follows production ("transação não permitida para o cartão"), not the sandbox's "cartão
 * expirado" (plan D8). ReturnMessage is never read here: the issuer's text never reaches a
 * merchant.
 */
public final class CieloDeclines {
  private static final Map<String, CardDeclineCode> BY_RETURN_CODE =
      Map.ofEntries(
          Map.entry("51", CardDeclineCode.INSUFFICIENT_FUNDS),
          Map.entry("54", CardDeclineCode.EXPIRED_CARD),
          Map.entry("78", CardDeclineCode.BLOCKED_CARD),
          Map.entry("62", CardDeclineCode.BLOCKED_CARD),
          Map.entry("41", CardDeclineCode.CANCELED_CARD),
          Map.entry("43", CardDeclineCode.CANCELED_CARD),
          Map.entry("46", CardDeclineCode.CANCELED_CARD),
          Map.entry("91", CardDeclineCode.TIMEOUT),
          Map.entry("96", CardDeclineCode.TIMEOUT),
          Map.entry("57", CardDeclineCode.DO_NOT_HONOR),
          Map.entry("14", CardDeclineCode.DO_NOT_HONOR),
          Map.entry("59", CardDeclineCode.DO_NOT_HONOR),
          Map.entry("83", CardDeclineCode.DO_NOT_HONOR),
          Map.entry("N7", CardDeclineCode.DO_NOT_HONOR),
          Map.entry("05", CardDeclineCode.GENERIC),
          Map.entry("99", CardDeclineCode.TIMEOUT),
          Map.entry("77", CardDeclineCode.CANCELED_CARD),
          Map.entry("70", CardDeclineCode.DO_NOT_HONOR));

  private CieloDeclines() {}

  public static CardDeclineCode of(String returnCode) {
    if (returnCode == null) {
      return CardDeclineCode.GENERIC;
    }

    return BY_RETURN_CODE.getOrDefault(returnCode.trim(), CardDeclineCode.GENERIC);
  }
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/CieloDates.java`:
```java
package com.gateway.providers.cielo.sale;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * The Cielo writes dates without a zone: "2025-11-24 18:04:07" in a sale, "2024-11-29T13:36:04.033"
 * in the by-order query. Both are Brasília time; one converter so the zone is decided once.
 */
public final class CieloDates {
  private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");
  private static final DateTimeFormatter SPACE_OR_T =
      new DateTimeFormatterBuilder()
          .appendPattern("yyyy-MM-dd")
          .optionalStart()
          .appendLiteral(' ')
          .optionalEnd()
          .optionalStart()
          .appendLiteral('T')
          .optionalEnd()
          .appendPattern("HH:mm:ss")
          .optionalStart()
          .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
          .optionalEnd()
          .toFormatter();

  private CieloDates() {}

  public static Instant parse(String text) {
    if (text == null || text.isBlank()) {
      return null;
    }

    return LocalDateTime.parse(text.trim(), SPACE_OR_T).atZone(SAO_PAULO).toInstant();
  }
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/CieloBrands.java`:
```java
package com.gateway.providers.cielo.sale;

import com.gateway.kernel.provider.card.CardBrand;
import java.util.Arrays;

/**
 * The Brand spelling: "Visa / Master / Amex / Elo / Aura / JCB / Diners / Discover"
 * (reference/criar-pagamento-credito). Read case-insensitively: an echo in another case must not
 * fail a sale that was approved.
 */
public final class CieloBrands {

  private CieloBrands() {}

  public static String nameOf(CardBrand brand) {
    return switch (brand) {
      case VISA -> "Visa";
      case MASTER -> "Master";
      case AMEX -> "Amex";
      case ELO -> "Elo";
      case AURA -> "Aura";
      case JCB -> "JCB";
      case DINERS -> "Diners";
      case DISCOVER -> "Discover";
    };
  }

  /** Null for a name outside the list: an echo the gateway cannot place is not a failure. */
  public static CardBrand of(String cieloName) {
    if (cieloName == null) {
      return null;
    }

    return Arrays.stream(CardBrand.values())
        .filter(brand -> nameOf(brand).equalsIgnoreCase(cieloName.trim()))
        .findFirst()
        .orElse(null);
  }
}
```

- [ ] **Step 8: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-providers -am -Dtest='Cielo*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-providers/src/main/java/com/gateway/providers/cielo gateway-providers/src/test/java/com/gateway/providers/cielo
git commit -m "feat(providers): cielo credential, endpoints and the tables of a sale

CieloCredentials validates the key as the docs' example shows it (40 letters
or digits, not a GUID). CieloErrors reads the [{Code, Message}] list and falls
back to the status for the bare-string bodies the OpenAPI documents; 307
counts as not found. CieloStatuses follows reference/payment-status (11 is
Refunded, unknown is in doubt); CieloDeclines is measured against the ABECS
page, since api-codes has no issuer codes. CieloPayloadMasker keeps a card
number out of every exception message this client builds.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---
### Task 4: `CieloSalesClient.authorize`, o request da venda e os fixtures

**Files:**
- Create: `gateway-providers/src/main/java/com/gateway/providers/cielo/CieloHttp.java`
- Create: `gateway-providers/src/main/java/com/gateway/providers/cielo/sale/{CieloSalesClient,SaleRequestFactory,SaleResponses}.java`
- Create: `gateway-providers/src/main/java/com/gateway/providers/cielo/sale/dto/{SaleRequest,SaleResponse}.java`
- Create: `gateway-providers/src/test/resources/cielo/fixtures/{README.md,post_sales_request_simplified.json,post_sales_request_token.json,post_sales_201_captured.json,post_sales_201_card_on_file.json,post_sales_201_token_authorized.json,post_sales_201_authorized_saved.json,post_sales_201_denied.json,post_sales_201_not_finished.json,error_400_list.json,error_400_expiration.json}`
- Test: `gateway-providers/src/test/java/com/gateway/providers/cielo/sale/{CieloFixtures,SaleRequestFactoryTest,CieloSalesClientAuthorizeContractTest}.java`

**Interfaces:**
- Consumes: Tasks 1, 3.
- Produces:
  ```java
  public final class CieloHttp {
    CieloHttp(Duration connectTimeout, Duration readTimeout);
    HttpRequest.Builder request(URI base, String pathAndQuery);
    HttpRequest.BodyPublisher json(Object body);
    HttpResponse<String> send(CieloCredentials credentials, HttpRequest.Builder builder);  // TIMEOUT / UNAVAILABLE on transport failure
    <T> T read(HttpResponse<String> response, Class<T> type);                               // UNKNOWN when unreadable
  }
  public class CieloSalesClient {
    CieloSalesClient(CieloHttp http, CieloEndpoints endpoints);
    SaleResponse authorize(CieloCredentials credentials, SaleRequest request);                // POST {api}/1/sales → 201
  }
  public final class SaleRequestFactory { static SaleRequest from(CardIssueRequest request); }
  public final class SaleResponses { static CardAuthorization toAuthorization(SaleResponse response, CardSource source); } // source may be null
  record SaleRequest(merchantOrderId, Customer, Payment)   record SaleResponse(String merchantOrderId, Payment payment)   // Cielo names via @JsonProperty
  test: CieloFixtures.read(String name); SaleRequestFactoryTest.request(CardSource, boolean saveCard); SaleRequestFactoryTest.visa()
  ```

- [ ] **Step 1: Os fixtures**

Cada `.json` vai em `gateway-providers/src/test/resources/cielo/fixtures/`. Os "verbatim" são o exemplo da página byte a byte, depois de desfazer o escape `\n`/`\"` do OpenAPI embutido; os "derivados" dizem de onde vieram e o que mudou.

`post_sales_request_simplified.json` (verbatim — `reference/criar-pagamento-credito`, request "Cartão de crédito simplificado"):
```json
{
  "Customer": {
    "Name": "Aline de Souza",
    "Identity": "12345678909",
    "IdentityType": "CPF",
    "Email": "aline@email.com",
    "Birthdate": "1990-01-01"
  },
  "Payment": {
    "Type": "CreditCard",
    "IsCryptocurrencyNegociation": false,
    "IssuerTransactionId": "580027442382078",
    "CreditCard": {
      "CardNumber": "4091688625337641",
      "Holder": "Aline de Souza",
      "ExpirationDate": "12/2035",
      "SecurityCode": "333",
      "Brand": "Visa",
      "SaveCard": false
    },
    "Amount": 10000,
    "Currency": "BRL",
    "Country": "BRA",
    "SoftDescriptor": "LojaTeste",
    "Installments": 1,
    "Interest": "ByMerchant",
    "Capture": true,
    "Authenticate": false,
    "Recurrent": false
  },
  "MerchantOrderId": "2017051001"
}
```

`post_sales_request_token.json` (verbatim — `reference/cartao-tokenizado-api`, "Request Example"):
```json
{
  "MerchantOrderId": "Loja123456",
  "Customer": {
    "Name": "Comprador Teste"
  },
  "Payment": {
    "Type": "CreditCard",
    "Amount": 100,
    "Installments": 1,
    "Capture": false,
    "SoftDescriptor": "123456789ABCD",
    "CreditCard": {
      "CardToken": "6e1bf77a-b28b-4660-b14f-455e2a1c95e9",
      "SecurityCode": "262",
      "Brand": "Visa"
    }
  }
}
```

`post_sales_201_captured.json` (verbatim — `reference/criar-pagamento-credito`, 201 "Cartão de crédito simplificado"):
```json
{
  "MerchantOrderId": "2017051001",
  "Customer": {
    "Name": "Aline de Souza",
    "Identity": "12345678909",
    "IdentityType": "CPF",
    "Email": "aline@email.com",
    "Birthdate": "1990-01-01"
  },
  "Payment": {
    "ServiceTaxAmount": 0,
    "Installments": 1,
    "Interest": 0,
    "Capture": true,
    "Authenticate": false,
    "Recurrent": false,
    "IssuerTransactionId": "580027442382078",
    "CreditCard": {
      "CardNumber": "409168******7641",
      "Holder": "Aline de Souza",
      "ExpirationDate": "12/2035",
      "SaveCard": false,
      "Brand": "Visa",
      "PaymentAccountReference": "4ZI0GII5L9RV8O1MU7DQ9WNOC17IE"
    },
    "Tid": "1124060407175",
    "ProofOfSale": "182738",
    "AuthorizationCode": "663864",
    "SoftDescriptor": "LojaTeste",
    "Provider": "Simulado",
    "IsQrCode": false,
    "SentOrderId": "20251124180407BDDA31",
    "Amount": 10000,
    "ReceivedDate": "2025-11-24 18:04:07",
    "CapturedAmount": 10000,
    "CapturedDate": "2025-11-24 18:04:07",
    "Status": 2,
    "IsSplitted": false,
    "ReturnMessage": "Operation Successful",
    "ReturnCode": "6",
    "PaymentId": "6f8d1753-86bb-4dc0-9ebb-09a29093e1fb",
    "Type": "CreditCard",
    "Currency": "BRL",
    "Country": "BRA",
    "Links": [
      {
        "Method": "GET",
        "Rel": "self",
        "Href": "https://apiquerysandbox.cieloecommerce.cielo.com.br/1/sales/6f8d1753-86bb-4dc0-9ebb-09a29093e1fb"
      },
      {
        "Method": "PUT",
        "Rel": "void",
        "Href": "https://apisandbox.cieloecommerce.cielo.com.br/1/sales/6f8d1753-86bb-4dc0-9ebb-09a29093e1fb/void"
      }
    ]
  }
}
```

`post_sales_201_card_on_file.json` (verbatim — mesma página, 201 "Cartão de crédito com Card On File"):
```json
{
  "MerchantOrderId": "2017051001",
  "Customer": {
    "Name": "Aline de Souza",
    "Identity": "12345678909",
    "IdentityType": "CPF",
    "Email": "aline@email.com",
    "Birthdate": "1990-01-01"
  },
  "Payment": {
    "ServiceTaxAmount": 0,
    "Installments": 1,
    "Interest": 0,
    "Capture": true,
    "Authenticate": false,
    "Recurrent": false,
    "CreditCard": {
      "CardNumber": "409168******7641",
      "Holder": "Aline de Souza",
      "ExpirationDate": "12/2035",
      "SaveCard": false,
      "Brand": "Visa",
      "CardOnFile": {
        "Usage": "Used",
        "Reason": "Unscheduled"
      },
      "PaymentAccountReference": "970UZI3LF5Y8SGFJ7QR6DBHWS86P6"
    },
    "Tid": "1124055001076",
    "ProofOfSale": "803707",
    "AuthorizationCode": "024169",
    "SoftDescriptor": "LojaTeste",
    "Provider": "Simulado",
    "IsQrCode": false,
    "SentOrderId": "2025112417500059AF2E",
    "Amount": 10000,
    "ReceivedDate": "2025-11-24 17:50:00",
    "CapturedAmount": 10000,
    "CapturedDate": "2025-11-24 17:50:01",
    "Status": 2,
    "IsSplitted": false,
    "ReturnMessage": "Operation Successful",
    "ReturnCode": "6",
    "PaymentId": "5cd9ccaf-e3b2-430c-b5dd-2b674cfd656a",
    "Type": "CreditCard",
    "Currency": "BRL",
    "Country": "BRA",
    "Links": [
      {
        "Method": "GET",
        "Rel": "self",
        "Href": "https://apiquerysandbox.cieloecommerce.cielo.com.br/1/sales/5cd9ccaf-e3b2-430c-b5dd-2b674cfd656a"
      },
      {
        "Method": "PUT",
        "Rel": "void",
        "Href": "https://apisandbox.cieloecommerce.cielo.com.br/1/sales/5cd9ccaf-e3b2-430c-b5dd-2b674cfd656a/void"
      }
    ]
  }
}
```

`post_sales_201_token_authorized.json` (verbatim — `reference/cartao-tokenizado-api`, 201 "Result"):
```json
{
  "MerchantOrderId": "Loja123456",
  "Customer": {
    "Name": "Comprador Teste"
  },
  "Payment": {
    "ServiceTaxAmount": 0,
    "Installments": 1,
    "Interest": "ByMerchant",
    "Capture": false,
    "Authenticate": false,
    "CreditCard": {
      "SaveCard": false,
      "CardToken": "6e1bf77a-b28b-4660-b14f-455e2a1c95e9",
      "Brand": "Visa"
    },
    "ProofOfSale": "5036294",
    "Tid": "0310025036294",
    "AuthorizationCode": "319285",
    "SoftDescriptor": "123456789ABCD",
    "PaymentId": "c3ec8ec4-1ed5-4f8d-afc3-19b18e5962a8",
    "Type": "CreditCard",
    "Amount": 100,
    "Currency": "BRL",
    "Country": "BRA",
    "ExtraDataCollection": [],
    "Status": 1,
    "Links": [
      {
        "Method": "GET",
        "Rel": "self",
        "Href": "https://apiquerysandbox.cieloecommerce.cielo.com.br/1/sales/{PaymentId}"
      },
      {
        "Method": "PUT",
        "Rel": "capture",
        "Href": "https://apisandbox.cieloecommerce.cielo.com.br/1/sales/{PaymentId}/capture"
      },
      {
        "Method": "PUT",
        "Rel": "void",
        "Href": "https://apisandbox.cieloecommerce.cielo.com.br/1/sales/{PaymentId}/void"
      }
    ]
  }
}
```

`post_sales_201_authorized_saved.json` (derivado de `post_sales_201_captured.json`: `Capture: false`, `Status: 1`, `ReturnCode: "4"` — "transação autorizada (apta a ser capturada)", `reference/api-codes` —, sem `CapturedAmount`/`CapturedDate`/`Links`, `SaveCard: true` e `CardToken` = o token do exemplo de `reference/criar-cardtoken`):
```json
{
  "MerchantOrderId": "2017051001",
  "Customer": {
    "Name": "Aline de Souza",
    "Identity": "12345678909",
    "IdentityType": "CPF",
    "Email": "aline@email.com",
    "Birthdate": "1990-01-01"
  },
  "Payment": {
    "ServiceTaxAmount": 0,
    "Installments": 1,
    "Interest": 0,
    "Capture": false,
    "Authenticate": false,
    "Recurrent": false,
    "CreditCard": {
      "CardNumber": "409168******7641",
      "Holder": "Aline de Souza",
      "ExpirationDate": "12/2035",
      "SaveCard": true,
      "CardToken": "db62dc71-d07b-4745-9969-42697b988ccb",
      "Brand": "Visa"
    },
    "Tid": "1124060407175",
    "ProofOfSale": "182738",
    "AuthorizationCode": "663864",
    "SoftDescriptor": "LojaTeste",
    "Amount": 10000,
    "ReceivedDate": "2025-11-24 18:04:07",
    "Status": 1,
    "ReturnMessage": "Operation Successful",
    "ReturnCode": "4",
    "PaymentId": "6f8d1753-86bb-4dc0-9ebb-09a29093e1fb",
    "Type": "CreditCard",
    "Currency": "BRL",
    "Country": "BRA"
  }
}
```

`post_sales_201_denied.json` (derivado de `post_sales_201_captured.json`: `Status: 3`, `ReturnCode: "51"` — ABECS "saldo/limite insuficiente" —, sem `AuthorizationCode`, `CapturedAmount`, `CapturedDate`, `Links`):
```json
{
  "MerchantOrderId": "2017051001",
  "Customer": {
    "Name": "Aline de Souza"
  },
  "Payment": {
    "Installments": 1,
    "Capture": true,
    "CreditCard": {
      "CardNumber": "409168******7641",
      "Holder": "Aline de Souza",
      "ExpirationDate": "12/2035",
      "SaveCard": false,
      "Brand": "Visa"
    },
    "Tid": "1124060407175",
    "ProofOfSale": "182738",
    "Amount": 10000,
    "ReceivedDate": "2025-11-24 18:04:07",
    "Status": 3,
    "ReturnMessage": "Nao Autorizada",
    "ReturnCode": "51",
    "PaymentId": "6f8d1753-86bb-4dc0-9ebb-09a29093e1fb",
    "Type": "CreditCard",
    "Currency": "BRL",
    "Country": "BRA"
  }
}
```

`post_sales_201_not_finished.json` (derivado: `Status: 0`, `ReturnCode: "001"`, `ReturnMessage: "XML invalido"` — linha "Crédito - não finalizado" de `reference/api-codes`):
```json
{
  "MerchantOrderId": "2017051001",
  "Customer": {
    "Name": "Aline de Souza"
  },
  "Payment": {
    "Installments": 1,
    "Capture": true,
    "CreditCard": {
      "CardNumber": "409168******7641",
      "Holder": "Aline de Souza",
      "ExpirationDate": "12/2035",
      "SaveCard": false,
      "Brand": "Visa"
    },
    "Amount": 10000,
    "ReceivedDate": "2025-11-24 18:04:07",
    "Status": 0,
    "ReturnMessage": "XML invalido",
    "ReturnCode": "001",
    "PaymentId": "6f8d1753-86bb-4dc0-9ebb-09a29093e1fb",
    "Type": "CreditCard",
    "Currency": "BRL",
    "Country": "BRA"
  }
}
```

`error_400_list.json` (verbatim — `reference/api-errors-code-message`, "Exemplo"):
```json
[
  {
    "Code": 322,
    "Message": "Zero Dollar Auth is not enabled"
  }
]
```

`error_400_expiration.json` (derivado: a linha 126 da tabela da mesma página, no formato do exemplo):
```json
[
  {
    "Code": 126,
    "Message": "Credit Card Expiration Date is invalid"
  }
]
```

`README.md` (a lista inteira, inclusive os arquivos que a Task 5 cria):
```markdown
# Cielo fixtures

Source: the Cielo E-commerce API reference, the `.md` pages under
https://docs.cielo.com.br/ecommerce-cielo/ (read 2026-09-28). The Cielo publishes no OpenAPI file;
every example is copied from the OpenAPI block embedded in each page, with the `\n`/`\"` escaping
undone.

| file | origin |
|---|---|
| post_sales_request_simplified.json | verbatim — reference/criar-pagamento-credito, request "Cartão de crédito simplificado" |
| post_sales_request_token.json | verbatim — reference/cartao-tokenizado-api, "Request Example" |
| post_sales_201_captured.json | verbatim — reference/criar-pagamento-credito, 201 "Cartão de crédito simplificado" |
| post_sales_201_card_on_file.json | verbatim — reference/criar-pagamento-credito, 201 "Cartão de crédito com Card On File" |
| post_sales_201_token_authorized.json | verbatim — reference/cartao-tokenizado-api, 201 "Result" |
| post_sales_201_authorized_saved.json | derived from post_sales_201_captured: Capture false, Status 1, ReturnCode "4", no captured fields, SaveCard true, CardToken from reference/criar-cardtoken |
| post_sales_201_denied.json | derived: Status 3, ReturnCode "51" (page/abecs), no AuthorizationCode |
| post_sales_201_not_finished.json | derived: Status 0, ReturnCode "001" (reference/api-codes, "Crédito - não finalizado") |
| error_400_list.json | verbatim — reference/api-errors-code-message, "Exemplo" |
| error_400_expiration.json | derived: row 126 of the same page's table, in the example's shape |
| put_capture_200.json | verbatim — reference/capturar-apos-autorizacao, 200 "Result" |
| put_void_200.json | verbatim — reference/cancelamento-paymentid, 200 "Result" |
| put_void_200_refunded.json | derived from put_void_200: Status 11 (reference/payment-status, "Refunded") |
| get_sale_200_credit.json | verbatim — reference/consulta-paymentid-api, 200 "Transação de crédito" |
| get_sale_200_authorized.json | derived from get_sale_200_credit: Capture false, Status 1, no captured fields |
| get_sales_by_order_200.json | verbatim — reference/consulta-merchantorderid-api, 200 "Result" (note `ReceveidDate`, the Cielo's spelling) |
| post_card_request.json | verbatim — reference/criar-cardtoken, "Request Example" |
| post_card_201.json | verbatim — reference/criar-cardtoken, 201 "Result" |
| notification_change_type_2.json | verbatim — docs/webhook, the notification example |
| notification_status_changed.json | derived: ChangeType 1, PaymentId of get_sale_200_credit, no RecurrentPaymentId (only for ChangeType 2/4) |

Not used, on purpose: the 201 "Elo via Link de Pagamento" is not valid JSON (a comma is missing
after `"SolutionType": "ExternalLinkPay"`), and the 3DS, airline and IDX examples are out of scope.
The public sandbox MerchantId/MerchantKey the pages print as defaults are not copied anywhere.
```

- [ ] **Step 2: Os testes**

`gateway-providers/src/test/java/com/gateway/providers/cielo/sale/CieloFixtures.java`:
```java
package com.gateway.providers.cielo.sale;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Reads a file of src/test/resources/cielo/fixtures (see its README for each file's origin). */
public final class CieloFixtures {
  private CieloFixtures() {}

  public static String read(String name) {
    try {
      return Files.readString(
          Path.of("src/test/resources/cielo/fixtures/" + name), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
```

`gateway-providers/src/test/java/com/gateway/providers/cielo/sale/SaleRequestFactoryTest.java`:
```java
package com.gateway.providers.cielo.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardCustomer;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardIssueRequest;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardSource;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.provider.card.Installments;
import com.gateway.kernel.provider.card.SoftDescriptor;
import com.gateway.kernel.security.Secret;
import com.gateway.providers.cielo.sale.dto.SaleRequest;
import java.time.YearMonth;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class SaleRequestFactoryTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final YearMonth NOW = YearMonth.of(2026, 9);
  static final CardCustomer CUSTOMER =
      new CardCustomer(PersonName.of("João da Silva"), Document.of("123.456.789-09"), "j@x.com");

  public static CardIssueRequest request(CardSource source, boolean saveCard) {
    return new CardIssueRequest(
        "01K0PAYMENTIDULID000000000",
        Money.brl(12990),
        Installments.of(3),
        true,
        saveCard,
        SoftDescriptor.ofNullable("LOJA42"),
        source,
        CUSTOMER);
  }

  public static CardData visa() {
    return CardData.of("4024007153763171", "JOÃO DA SILVA", "12/2030", "123", null, NOW);
  }

  static CardToken masterToken() {
    return new CardToken(
        "6e1bf77a-b28b-4660-b14f-455e2a1c95e9",
        CardBrand.MASTER,
        CardOnFileUsage.USED,
        Secret.of("262"));
  }

  static JsonNode json(SaleRequest request) {
    return JSON.readTree(JSON.writeValueAsString(request));
  }

  @Test
  void aNewCardCarriesTheDocumentedFields() {
    JsonNode body = json(SaleRequestFactory.from(request(visa(), false)));

    assertThat(body.get("MerchantOrderId").asText()).isEqualTo("01K0PAYMENTIDULID000000000");
    assertThat(body.at("/Customer/Name").asText()).isEqualTo("Joao da Silva");
    assertThat(body.at("/Customer/Identity").asText()).isEqualTo("12345678909");
    assertThat(body.at("/Customer/IdentityType").asText()).isEqualTo("CPF");
    assertThat(body.at("/Payment/Type").asText()).isEqualTo("CreditCard");
    assertThat(body.at("/Payment/Amount").asLong()).isEqualTo(12990);
    assertThat(body.at("/Payment/Installments").asInt()).isEqualTo(3);
    assertThat(body.at("/Payment/Interest").asText()).isEqualTo("ByMerchant");
    assertThat(body.at("/Payment/Capture").asBoolean()).isTrue();
    assertThat(body.at("/Payment/SoftDescriptor").asText()).isEqualTo("LOJA42");
    assertThat(body.at("/Payment/CreditCard/CardNumber").asText()).isEqualTo("4024007153763171");
    assertThat(body.at("/Payment/CreditCard/Holder").asText()).isEqualTo("JOAO DA SILVA");
    assertThat(body.at("/Payment/CreditCard/ExpirationDate").asText()).isEqualTo("12/2030");
    assertThat(body.at("/Payment/CreditCard/SecurityCode").asText()).isEqualTo("123");
    assertThat(body.at("/Payment/CreditCard/Brand").asText()).isEqualTo("Visa");
    assertThat(body.at("/Payment/CreditCard/SaveCard").asBoolean()).isFalse();
    assertThat(body.at("/Payment/CreditCard").has("CardOnFile")).isFalse();
    assertThat(body.at("/Payment").has("InitiatedTransactionIndicator")).isFalse();
  }

  /**
   * Every key we send exists in one of the Cielo's own request examples, so a typo in a
   * {@code @JsonProperty} fails here instead of at the sandbox. CardOnFile and
   * InitiatedTransactionIndicator come from the "Cartão de crédito completo" example of the same
   * page, whose Customer block is not copied as a fixture.
   */
  @Test
  void everyKeyWeSendIsADocumentedKey() {
    Set<String> documented = new TreeSet<>();
    paths("", JSON.readTree(CieloFixtures.read("post_sales_request_simplified.json")), documented);
    paths("", JSON.readTree(CieloFixtures.read("post_sales_request_token.json")), documented);
    documented.addAll(
        Set.of(
            "/Payment/CreditCard/CardOnFile",
            "/Payment/CreditCard/CardOnFile/Usage",
            "/Payment/CreditCard/CardOnFile/Reason",
            "/Payment/InitiatedTransactionIndicator",
            "/Payment/InitiatedTransactionIndicator/Category",
            "/Payment/InitiatedTransactionIndicator/Subcategory"));

    Set<String> sent = new TreeSet<>();
    paths("", json(SaleRequestFactory.from(request(visa(), true))), sent);
    paths("", json(SaleRequestFactory.from(request(masterToken(), false))), sent);

    assertThat(documented).containsAll(sent);
  }

  /** docs/card-on-file: First on the charge that stores the card; Reason only with Used. */
  @Test
  void savingAVisaMarksTheFirstUse() {
    JsonNode body = json(SaleRequestFactory.from(request(visa(), true)));

    assertThat(body.at("/Payment/CreditCard/SaveCard").asBoolean()).isTrue();
    assertThat(body.at("/Payment/CreditCard/CardOnFile/Usage").asText()).isEqualTo("First");
    assertThat(body.at("/Payment/CreditCard/CardOnFile").has("Reason")).isFalse();
  }

  /** Spec §6.6, and the indicator only for Mastercard (plan D6). */
  @Test
  void aStoredMastercardIsUsedUnscheduledCustomerInitiated() {
    JsonNode body = json(SaleRequestFactory.from(request(masterToken(), false)));

    assertThat(body.at("/Payment/CreditCard/CardToken").asText())
        .isEqualTo("6e1bf77a-b28b-4660-b14f-455e2a1c95e9");
    assertThat(body.at("/Payment/CreditCard/SecurityCode").asText()).isEqualTo("262");
    assertThat(body.at("/Payment/CreditCard").has("CardNumber")).isFalse();
    assertThat(body.at("/Payment/CreditCard/CardOnFile/Usage").asText()).isEqualTo("Used");
    assertThat(body.at("/Payment/CreditCard/CardOnFile/Reason").asText()).isEqualTo("Unscheduled");
    assertThat(body.at("/Payment/InitiatedTransactionIndicator/Category").asText()).isEqualTo("C1");
    assertThat(body.at("/Payment/InitiatedTransactionIndicator/Subcategory").asText())
        .isEqualTo("CredentialsOnFile");
  }

  /** docs/card-on-file: "Bandeiras Suportadas: Mastercard, Visa, Elo". */
  @Test
  void anAmexTokenCarriesNoCardOnFileMarker() {
    CardToken amex = new CardToken("tok", CardBrand.AMEX, CardOnFileUsage.USED, Secret.of("1234"));

    JsonNode body = json(SaleRequestFactory.from(request(amex, false)));

    assertThat(body.at("/Payment/CreditCard").has("CardOnFile")).isFalse();
    assertThat(body.at("/Payment").has("InitiatedTransactionIndicator")).isFalse();
  }

  @Test
  void theRequestNeverPrintsTheCard() {
    SaleRequest request = SaleRequestFactory.from(request(visa(), false));

    assertThat(request.toString())
        .doesNotContain("4024007153763171")
        .isEqualTo("SaleRequest[01K0PAYMENTIDULID000000000]");
    assertThat(request.payment().creditCard().toString()).isEqualTo("CreditCard[***]");
  }

  static void paths(String prefix, JsonNode node, Set<String> into) {
    if (!node.isObject()) {
      return;
    }
    for (String name : node.propertyNames()) {
      into.add(prefix + "/" + name);
      paths(prefix + "/" + name, node.get(name), into);
    }
  }
}
```

`gateway-providers/src/test/java/com/gateway/providers/cielo/sale/CieloSalesClientAuthorizeContractTest.java`:
```java
package com.gateway.providers.cielo.sale;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardDeclineCode;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.providers.cielo.CieloHttp;
import com.gateway.providers.cielo.auth.CieloCredentials;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.sale.dto.SaleResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** POST /1/sales against the Cielo's own examples (fixtures README) and states derived from them. */
public class CieloSalesClientAuthorizeContractTest {
  static final String MERCHANT_ID = "11111111-2222-3333-4444-555555555555";
  static final String MERCHANT_KEY = "A".repeat(40);
  static WireMockServer server;

  @BeforeAll
  static void start() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @BeforeEach
  void reset() {
    server.resetAll();
  }

  public static CieloCredentials credentials() {
    return CieloCredentials.parse(
        ("{\"merchant_id\":\"" + MERCHANT_ID + "\",\"merchant_key\":\"" + MERCHANT_KEY + "\"}")
            .getBytes(StandardCharsets.UTF_8));
  }

  static CieloSalesClient client(WireMockServer server, Duration readTimeout) {
    URI base = URI.create(server.baseUrl());
    return new CieloSalesClient(
        new CieloHttp(Duration.ofSeconds(2), readTimeout), new CieloEndpoints(base, base));
  }

  public static CardData visa() {
    return SaleRequestFactoryTest.visa();
  }

  static SaleResponse authorize(Duration readTimeout) {
    return client(server, readTimeout)
        .authorize(
            credentials(),
            SaleRequestFactory.from(SaleRequestFactoryTest.request(visa(), false)));
  }

  static void answer(int status, String fixture) {
    server.stubFor(
        post(urlEqualTo("/1/sales"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(CieloFixtures.read(fixture))));
  }

  @Test
  void anApprovedCapturedSaleWithTheRequiredHeaders() {
    answer(201, "post_sales_201_captured.json");

    CardAuthorization authorization =
        SaleResponses.toAuthorization(authorize(Duration.ofSeconds(5)), visa());

    server.verify(
        postRequestedFor(urlEqualTo("/1/sales"))
            .withHeader("MerchantId", equalTo(MERCHANT_ID))
            .withHeader("MerchantKey", equalTo(MERCHANT_KEY))
            .withHeader("RequestId", matching("[0-9a-f-]{36}"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(
                matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo("4024007153763171"))));
    assertThat(authorization.paymentId()).isEqualTo("6f8d1753-86bb-4dc0-9ebb-09a29093e1fb");
    assertThat(authorization.status()).isEqualTo(CardStatus.PAID);
    assertThat(authorization.returnCode()).isEqualTo("6");
    assertThat(authorization.declineCode()).isNull();
    assertThat(authorization.tid()).isEqualTo("1124060407175");
    assertThat(authorization.authorizationCode()).isEqualTo("663864");
    assertThat(authorization.proofOfSale()).isEqualTo("182738");
    assertThat(authorization.amount()).isEqualTo(Money.brl(10000));
    assertThat(authorization.capturedAmount()).isEqualTo(Money.brl(10000));
    assertThat(authorization.brand()).isEqualTo(CardBrand.VISA);
    assertThat(authorization.last4()).isEqualTo("7641");
    assertThat(authorization.cardToken()).isEmpty();
    assertThat(authorization.receivedAt()).isEqualTo(Instant.parse("2025-11-24T21:04:07Z"));
    assertThat(authorization.capturedAt()).contains(Instant.parse("2025-11-24T21:04:07Z"));
  }

  @Test
  void anAuthorizationThatSavedTheCardCarriesTheToken() {
    answer(201, "post_sales_201_authorized_saved.json");

    CardAuthorization authorization =
        SaleResponses.toAuthorization(authorize(Duration.ofSeconds(5)), visa());

    assertThat(authorization.status()).isEqualTo(CardStatus.AUTHORIZED);
    assertThat(authorization.capturedAmount()).isNull();
    assertThat(authorization.capturedAt()).isEmpty();
    assertThat(authorization.cardToken()).contains("db62dc71-d07b-4745-9969-42697b988ccb");
    assertThat(authorization.toString()).doesNotContain("db62dc71");
  }

  /** The tokenized 201 has no CardNumber: last four come from what was sent. */
  @Test
  void aTokenizedAnswerFallsBackToTheSourceForTheBrand() {
    answer(201, "post_sales_201_token_authorized.json");

    CardAuthorization authorization =
        SaleResponses.toAuthorization(authorize(Duration.ofSeconds(5)), visa());

    assertThat(authorization.status()).isEqualTo(CardStatus.AUTHORIZED);
    assertThat(authorization.returnCode()).isNull();
    assertThat(authorization.brand()).isEqualTo(CardBrand.VISA);
    assertThat(authorization.last4()).isEqualTo("3171");
  }

  /** A decline is a 201 (spec §5): a result, never an exception. */
  @Test
  void aDeclineIsAResultWithOurCode() {
    answer(201, "post_sales_201_denied.json");

    CardAuthorization authorization =
        SaleResponses.toAuthorization(authorize(Duration.ofSeconds(5)), visa());

    assertThat(authorization.status()).isEqualTo(CardStatus.DENIED);
    assertThat(authorization.declineCode()).isEqualTo(CardDeclineCode.INSUFFICIENT_FUNDS);
    assertThat(authorization.authorizationCode()).isNull();
  }

  @Test
  void notFinishedIsInDoubt() {
    answer(201, "post_sales_201_not_finished.json");

    CardAuthorization authorization =
        SaleResponses.toAuthorization(authorize(Duration.ofSeconds(5)), visa());

    assertThat(authorization.status()).isEqualTo(CardStatus.NOT_FINISHED);
    assertThat(authorization.status().inDoubt()).isTrue();
    assertThat(authorization.declineCode()).isNull();
  }

  @Test
  void aFourHundredListIsInvalid() {
    answer(400, "error_400_expiration.json");

    assertThatThrownBy(() -> authorize(Duration.ofSeconds(5)))
        .isInstanceOf(ProviderException.class)
        .satisfies(
            thrown -> {
              ProviderException e = (ProviderException) thrown;
              assertThat(e.code()).isEqualTo(ProviderException.Code.INVALID);
              assertThat(e.providerType()).isEqualTo("126");
              assertThat(e.getMessage()).doesNotContain("4024007153763171");
            });
  }

  @Test
  void aWrongKeyIsUnauthenticated() {
    server.stubFor(
        post(urlEqualTo("/1/sales"))
            .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

    assertThatThrownBy(() -> authorize(Duration.ofSeconds(5)))
        .isInstanceOf(ProviderException.class)
        .extracting(thrown -> ((ProviderException) thrown).code())
        .isEqualTo(ProviderException.Code.UNAUTHENTICATED);
  }

  @Test
  void aSlowCieloIsATimeout() {
    server.stubFor(
        post(urlEqualTo("/1/sales"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withFixedDelay(1500)
                    .withBody(CieloFixtures.read("post_sales_201_captured.json"))));

    assertThatThrownBy(() -> authorize(Duration.ofMillis(300)))
        .isInstanceOf(ProviderException.class)
        .extracting(thrown -> ((ProviderException) thrown).code())
        .isEqualTo(ProviderException.Code.TIMEOUT);
  }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-providers -Dtest='SaleRequestFactoryTest,CieloSalesClientAuthorizeContractTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `CieloHttp`, `CieloSalesClient`, `SaleRequestFactory`, `SaleResponses` não existem.

- [ ] **Step 4: `CieloHttp`**

`gateway-providers/src/main/java/com/gateway/providers/cielo/CieloHttp.java`:
```java
package com.gateway.providers.cielo;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.cielo.auth.CieloCredentials;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import tools.jackson.databind.ObjectMapper;

/**
 * The transport the sales and card clients share: the two credential headers, RequestId, JSON, and
 * the transport-failure mapping. No mTLS and no OAuth at the Cielo
 * (reference/gerenciamento-de-credenciais), so one HttpClient serves every merchant.
 *
 * <p>No retry: an authorization that timed out may have landed, and only the flow (which knows the
 * MerchantOrderId) can ask. Exception messages carry the method, never a body or a header.
 */
public final class CieloHttp {
  private static final Pattern GUID =
      Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final HttpClient http;
  private final Duration readTimeout;
  private final ObjectMapper mapper = new ObjectMapper();

  public CieloHttp(Duration connectTimeout, Duration readTimeout) {
    this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    this.readTimeout = readTimeout;
  }

  public HttpRequest.Builder request(URI base, String pathAndQuery) {
    return HttpRequest.newBuilder(URI.create(base + pathAndQuery)).timeout(readTimeout);
  }

  public HttpRequest.BodyPublisher json(Object body) {
    return HttpRequest.BodyPublishers.ofString(
        mapper.writeValueAsString(body), StandardCharsets.UTF_8);
  }

  public HttpResponse<String> send(CieloCredentials credentials, HttpRequest.Builder builder) {
    HttpRequest request =
        builder
            .header("MerchantId", credentials.merchantId())
            .header("MerchantKey", credentials.merchantKey().reveal())
            .header("RequestId", requestId())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build();

    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpTimeoutException e) {
      throw new ProviderException(
          ProviderException.Code.TIMEOUT, "Cielo " + request.method() + " timed out", e);
    } catch (IOException e) {
      throw new ProviderException(
          ProviderException.Code.UNAVAILABLE,
          "Cielo " + request.method() + " failed: " + e.getClass().getSimpleName(),
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProviderException(
          ProviderException.Code.UNAVAILABLE, "interrupted calling the Cielo", e);
    }
  }

  public <T> T read(HttpResponse<String> response, Class<T> type) {
    try {
      return mapper.readValue(response.body(), type);
    } catch (RuntimeException e) {
      throw new ProviderException(ProviderException.Code.UNKNOWN, "unreadable Cielo response", e);
    }
  }

  /** RequestId is 36 characters: the correlation id when it is a GUID, a new one otherwise. */
  private static String requestId() {
    String fromMdc = MDC.get("correlationId");
    return fromMdc != null && GUID.matcher(fromMdc).matches()
        ? fromMdc
        : UUID.randomUUID().toString();
  }
}
```

- [ ] **Step 5: DTOs**

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/dto/SaleRequest.java`:
```java
package com.gateway.providers.cielo.sale.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /1/sales, the fields this phase sends (reference/criar-pagamento-credito). Null fields are
 * left out: a tokenized charge has no CardNumber, and a first charge no CardOnFile.Reason.
 *
 * <p>Every toString is overridden: this is the one object that holds the card number as a plain
 * string, and a generated toString would print it into the first log line that touched it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaleRequest(
    @JsonProperty("MerchantOrderId") String merchantOrderId,
    @JsonProperty("Customer") Customer customer,
    @JsonProperty("Payment") Payment payment) {

  @Override
  public String toString() {
    return "SaleRequest[" + merchantOrderId + "]";
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Customer(
      @JsonProperty("Name") String name,
      @JsonProperty("Identity") String identity,
      @JsonProperty("IdentityType") String identityType,
      @JsonProperty("Email") String email) {

    @Override
    public String toString() {
      return "Customer[***]";
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payment(
      @JsonProperty("Type") String type,
      @JsonProperty("Amount") long amount,
      @JsonProperty("Installments") int installments,
      @JsonProperty("Interest") String interest,
      @JsonProperty("Capture") boolean capture,
      @JsonProperty("SoftDescriptor") String softDescriptor,
      @JsonProperty("CreditCard") CreditCard creditCard,
      @JsonProperty("InitiatedTransactionIndicator")
          InitiatedTransactionIndicator initiatedTransactionIndicator) {

    @Override
    public String toString() {
      return "Payment[" + amount + "]";
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CreditCard(
      @JsonProperty("CardNumber") String cardNumber,
      @JsonProperty("Holder") String holder,
      @JsonProperty("ExpirationDate") String expirationDate,
      @JsonProperty("SecurityCode") String securityCode,
      @JsonProperty("Brand") String brand,
      @JsonProperty("SaveCard") Boolean saveCard,
      @JsonProperty("CardToken") String cardToken,
      @JsonProperty("CardOnFile") CardOnFile cardOnFile) {

    @Override
    public String toString() {
      return "CreditCard[***]";
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CardOnFile(
      @JsonProperty("Usage") String usage, @JsonProperty("Reason") String reason) {}

  public record InitiatedTransactionIndicator(
      @JsonProperty("Category") String category,
      @JsonProperty("Subcategory") String subcategory) {}
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/dto/SaleResponse.java`:
```java
package com.gateway.providers.cielo.sale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A sale as POST /1/sales (201) and GET /1/sales/{PaymentId} (200) return it. ignoreUnknown on
 * every level: "Os retornos de autorização estão sujeitos a inserção de novos campos advindos das
 * bandeiras/emissores" (reference/criar-pagamento-credito). {@code Interest} is not modeled on
 * purpose: the examples echo it as "ByMerchant" in one and 0 in another (plan D16).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleResponse(
    @JsonProperty("MerchantOrderId") String merchantOrderId,
    @JsonProperty("Payment") Payment payment) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Payment(
      @JsonProperty("PaymentId") String paymentId,
      @JsonProperty("Status") Integer status,
      @JsonProperty("ReturnCode") String returnCode,
      @JsonProperty("ReturnMessage") String returnMessage,
      @JsonProperty("Tid") String tid,
      @JsonProperty("AuthorizationCode") String authorizationCode,
      @JsonProperty("ProofOfSale") String proofOfSale,
      @JsonProperty("Amount") Long amount,
      @JsonProperty("CapturedAmount") Long capturedAmount,
      @JsonProperty("ReceivedDate") String receivedDate,
      @JsonProperty("CapturedDate") String capturedDate,
      @JsonProperty("CreditCard") CreditCard creditCard) {}

  /** CardNumber here is the Cielo's masked echo ("409168******7641"). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CreditCard(
      @JsonProperty("CardNumber") String cardNumber,
      @JsonProperty("Brand") String brand,
      @JsonProperty("CardToken") String cardToken) {

    @Override
    public String toString() {
      return "CreditCard[" + brand + ", token=" + (cardToken == null ? "none" : "***") + "]";
    }
  }
}
```

- [ ] **Step 6: `SaleRequestFactory`, `SaleResponses`, `CieloSalesClient.authorize`**

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/SaleRequestFactory.java`:
```java
package com.gateway.providers.cielo.sale;

import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardCustomer;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardIssueRequest;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.providers.cielo.CieloText;
import com.gateway.providers.cielo.sale.dto.SaleRequest;
import java.util.EnumSet;
import java.util.Set;

/**
 * CardIssueRequest → the Cielo's POST /1/sales body. The only place in the gateway that reveals the
 * card number and the CVV (spec §7): they go from here into the HTTP body and nowhere else.
 *
 * <p>Card On File (docs/card-on-file): only for the three brands that support it, and only on a
 * charge that stores the card (Usage First) or uses a stored one (Usage Used, Reason Unscheduled —
 * customer present, spec §6.6). Mastercard alone requires the InitiatedTransactionIndicator, C1 /
 * CredentialsOnFile for a customer-initiated charge (plan D6).
 */
public final class SaleRequestFactory {
  private static final Set<CardBrand> CARD_ON_FILE_BRANDS =
      EnumSet.of(CardBrand.VISA, CardBrand.MASTER, CardBrand.ELO);
  private static final String CREDIT_CARD = "CreditCard";
  private static final String BY_MERCHANT = "ByMerchant";

  private SaleRequestFactory() {}

  public static SaleRequest from(CardIssueRequest request) {
    SaleRequest.CreditCard creditCard =
        switch (request.source()) {
          case CardData card -> newCard(card, request.saveCard());
          case CardToken token -> storedCard(token);
        };

    boolean markedCardOnFile = creditCard.cardOnFile() != null;
    SaleRequest.InitiatedTransactionIndicator initiated =
        markedCardOnFile && request.source().brand() == CardBrand.MASTER
            ? new SaleRequest.InitiatedTransactionIndicator("C1", "CredentialsOnFile")
            : null;

    SaleRequest.Payment payment =
        new SaleRequest.Payment(
            CREDIT_CARD,
            request.amount().cents(),
            request.installments().count(),
            BY_MERCHANT,
            request.capture(),
            request.softDescriptor() == null ? null : request.softDescriptor().value(),
            creditCard,
            initiated);

    return new SaleRequest(request.merchantOrderId(), customer(request.customer()), payment);
  }

  private static SaleRequest.CreditCard newCard(CardData card, boolean saveCard) {
    SaleRequest.CardOnFile first =
        saveCard && CARD_ON_FILE_BRANDS.contains(card.brand())
            ? new SaleRequest.CardOnFile(usage(CardOnFileUsage.FIRST), null)
            : null;

    return new SaleRequest.CreditCard(
        card.number().reveal(),
        CieloText.holder(card.holder()),
        card.expiry().formatted(),
        card.securityCode().reveal(),
        CieloBrands.nameOf(card.brand()),
        saveCard,
        null,
        first);
  }

  private static SaleRequest.CreditCard storedCard(CardToken token) {
    SaleRequest.CardOnFile used =
        CARD_ON_FILE_BRANDS.contains(token.brand())
            ? new SaleRequest.CardOnFile(usage(token.usage()), "Unscheduled")
            : null;

    return new SaleRequest.CreditCard(
        null,
        null,
        null,
        token.securityCode().reveal(),
        CieloBrands.nameOf(token.brand()),
        null,
        token.value(),
        used);
  }

  private static String usage(CardOnFileUsage usage) {
    return usage == CardOnFileUsage.FIRST ? "First" : "Used";
  }

  private static SaleRequest.Customer customer(CardCustomer customer) {
    String identity = customer.document() == null ? null : customer.document().digits();
    String identityType = null;
    if (customer.document() != null) {
      identityType = customer.document().isCompany() ? "CNPJ" : "CPF";
    }

    return new SaleRequest.Customer(
        CieloText.customerName(customer.name()), identity, identityType, customer.email());
  }
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/SaleResponses.java`:
```java
package com.gateway.providers.cielo.sale;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardSource;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.providers.cielo.sale.dto.SaleResponse;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * A Cielo sale → {@link CardAuthorization}. The decline code is read only for DENIED and ABORTED;
 * brand and last four come from the Cielo's echo and, when a tokenized answer has no number
 * (reference/cartao-tokenizado-api's 201 has none), from what we sent.
 */
public final class SaleResponses {
  private static final Set<CardStatus> DECLINED =
      EnumSet.of(CardStatus.DENIED, CardStatus.ABORTED);

  private SaleResponses() {}

  /** {@code source} is null for a query: then only the Cielo's echo is used. */
  public static CardAuthorization toAuthorization(SaleResponse response, CardSource source) {
    SaleResponse.Payment payment = response.payment();
    if (payment == null || payment.paymentId() == null) {
      throw new ProviderException(
          ProviderException.Code.UNKNOWN, 0, null, "Cielo sale without Payment.PaymentId");
    }

    CardStatus status = CieloStatuses.of(payment.status());
    SaleResponse.CreditCard creditCard = payment.creditCard();

    return new CardAuthorization(
        payment.paymentId(),
        status,
        payment.returnCode(),
        payment.returnMessage(),
        DECLINED.contains(status) ? CieloDeclines.of(payment.returnCode()) : null,
        payment.tid(),
        payment.authorizationCode(),
        payment.proofOfSale(),
        payment.amount() == null ? null : Money.brl(payment.amount()),
        payment.capturedAmount() == null ? null : Money.brl(payment.capturedAmount()),
        brand(creditCard, source),
        last4(creditCard, source),
        Optional.ofNullable(creditCard == null ? null : creditCard.cardToken()),
        CieloDates.parse(payment.receivedDate()),
        Optional.ofNullable(CieloDates.parse(payment.capturedDate())));
  }

  private static CardBrand brand(SaleResponse.CreditCard creditCard, CardSource source) {
    CardBrand echoed = creditCard == null ? null : CieloBrands.of(creditCard.brand());
    if (echoed != null || source == null) {
      return echoed;
    }

    return source.brand();
  }

  private static String last4(SaleResponse.CreditCard creditCard, CardSource source) {
    String masked = creditCard == null ? null : creditCard.cardNumber();
    if (masked != null && masked.length() >= 4) {
      return masked.substring(masked.length() - 4);
    }

    return source instanceof CardData card ? card.last4() : null;
  }
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/CieloSalesClient.java`:
```java
package com.gateway.providers.cielo.sale;

import com.gateway.providers.cielo.CieloErrors;
import com.gateway.providers.cielo.CieloHttp;
import com.gateway.providers.cielo.auth.CieloCredentials;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.sale.dto.SaleRequest;
import com.gateway.providers.cielo.sale.dto.SaleResponse;
import java.net.http.HttpResponse;

/**
 * The Cielo's sales resource for one environment. Writes go to the transactional host, reads to
 * the query host. Capture, void and the two queries join in Task 5.
 */
public class CieloSalesClient {
  private final CieloHttp http;
  private final CieloEndpoints endpoints;

  public CieloSalesClient(CieloHttp http, CieloEndpoints endpoints) {
    this.http = http;
    this.endpoints = endpoints;
  }

  /** 201 for every business answer, a decline included (reference/api-codes). */
  public SaleResponse authorize(CieloCredentials credentials, SaleRequest request) {
    HttpResponse<String> response =
        http.send(
            credentials, http.request(endpoints.api(), "/1/sales").POST(http.json(request)));

    if (response.statusCode() != 201 && response.statusCode() != 200) {
      throw CieloErrors.from(response.statusCode(), response.body());
    }

    return http.read(response, SaleResponse.class);
  }
}
```

- [ ] **Step 7: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-providers -Dtest='SaleRequestFactoryTest,CieloSalesClientAuthorizeContractTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-providers
git commit -m "feat(providers): cielo authorization against the documented examples

SaleRequestFactory is the one place that reveals the card number and CVV; the
request DTO overrides every toString. Card On File only for Visa, Master and
Elo, the initiated-transaction indicator only for Master (docs/card-on-file).
Fixtures are the Cielo's own examples, each with its page in the README; the
Elo link-payment 201 is left out because it is not valid JSON.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---
### Task 5: Captura, void, consultas, tokenização — `CieloCardProvider` e o wiring

**Files:**
- Modify: `gateway-providers/src/main/java/com/gateway/providers/cielo/sale/CieloSalesClient.java` (+ capture, voidSale, findByPaymentId, findPaymentIdsByOrder)
- Create: `gateway-providers/src/main/java/com/gateway/providers/cielo/sale/dto/{SaleUpdateResponse,SalesByOrderResponse}.java`
- Create: `gateway-providers/src/main/java/com/gateway/providers/cielo/card/{CieloCardClient,CardTokenRequestFactory}.java`, `cielo/card/dto/{CardTokenRequest,CardTokenResponse}.java`
- Create: `gateway-providers/src/main/java/com/gateway/providers/cielo/notification/NotificationBody.java`
- Create: `gateway-providers/src/main/java/com/gateway/providers/cielo/CieloCardProvider.java`
- Modify: `gateway-providers/src/main/java/com/gateway/providers/ProvidersConfiguration.java` (+ `CieloProperties`, `@Bean CardMethodProvider`)
- Create fixtures: `gateway-providers/src/test/resources/cielo/fixtures/{put_capture_200,put_void_200,put_void_200_refunded,get_sale_200_credit,get_sale_200_authorized,get_sales_by_order_200,post_card_request,post_card_201,notification_change_type_2,notification_status_changed}.json`
- Test: `gateway-providers/src/test/java/com/gateway/providers/cielo/sale/CieloSalesClientContractTest.java`, `cielo/card/CieloCardClientContractTest.java`, `cielo/CieloCardProviderTest.java`, `gateway-providers/src/test/java/com/gateway/providers/CieloPropertiesTest.java`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces:
  ```java
  // CieloSalesClient
  SaleUpdateResponse capture(CieloCredentials c, String paymentId, Optional<Money> amount);   // PUT {api}/1/sales/{id}/capture[?amount=]
  SaleUpdateResponse voidSale(CieloCredentials c, String paymentId, Optional<Money> amount);  // PUT {api}/1/sales/{id}/void[?amount=]
  Optional<SaleResponse> findByPaymentId(CieloCredentials c, String paymentId);              // GET {apiQuery}/1/sales/{id}; empty on 404 or 307
  List<String> findPaymentIdsByOrder(CieloCredentials c, String merchantOrderId);           // newest first
  // CieloCardClient
  CardTokenResponse create(CieloCredentials c, CardTokenRequest request);                    // POST {api}/1/card/
  // CieloCardProvider implements CardMethodProvider, id "CIELO"
  CieloCardProvider(CieloHttp http, CieloEndpoints live, CieloEndpoints test)
  // ProvidersConfiguration
  @ConfigurationProperties("gateway.providers.cielo") record CieloProperties(String liveApiBase, String liveQueryApiBase,
      String testApiBase, String testQueryApiBase, Duration readTimeout) { CieloEndpoints live(); CieloEndpoints test(); }
  ```

- [ ] **Step 1: Os fixtures**

`put_capture_200.json` (verbatim — `reference/capturar-apos-autorizacao`, 200 "Result"):
```json
{
  "Status": 2,
  "Tid": "0719094510712",
  "ProofOfSale": "4510712",
  "AuthorizationCode": "693066",
  "ReturnCode": "6",
  "ReturnMessage": "Operation Successful",
  "Links": [
    {
      "Method": "GET",
      "Rel": "self",
      "Href": "https://api.cieloecommerce.cielo.com.br/1/sales/{PaymentId}"
    },
    {
      "Method": "PUT",
      "Rel": "void",
      "Href": "https://api.cieloecommerce.cielo.com.br/1/sales/{PaymentId}/void"
    }
  ]
}
```

`put_void_200.json` (verbatim — `reference/cancelamento-paymentid`, 200 "Result"):
```json
{
  "Status": 10,
  "Tid": "0719094510712",
  "ProofOfSale": "4510712",
  "AuthorizationCode": "693066",
  "ReturnCode": "9",
  "ReturnMessage": "Operation Successful",
  "Links": [
    {
      "Method": "GET",
      "Rel": "self",
      "Href": "https://apiquerysandbox.cieloecommerce.cielo.com.br/1/sales/{PaymentId}"
    }
  ]
}
```

`put_void_200_refunded.json` (derivado de `put_void_200.json`: `Status: 11` — "Pagamento cancelado após 23h59 do dia de autorização", `reference/payment-status`):
```json
{
  "Status": 11,
  "Tid": "0719094510712",
  "ProofOfSale": "4510712",
  "AuthorizationCode": "693066",
  "ReturnCode": "9",
  "ReturnMessage": "Operation Successful",
  "Links": [
    {
      "Method": "GET",
      "Rel": "self",
      "Href": "https://apiquerysandbox.cieloecommerce.cielo.com.br/1/sales/{PaymentId}"
    }
  ]
}
```

`get_sale_200_credit.json` (verbatim — `reference/consulta-paymentid-api`, 200 "Transação de crédito"):
```json
{
  "MerchantOrderId": "Loja123456",
  "Customer": {
    "Name": "Comprador crédito completo",
    "Email": "compradorteste@teste.com",
    "Birthdate": "1991-01-02",
    "Address": {
      "Street": "Rua Teste",
      "Number": "123",
      "Complement": "AP 123",
      "ZipCode": "12345987",
      "City": "Rio de Janeiro",
      "State": "RJ",
      "Country": "BRA"
    }
  },
  "Payment": {
    "ServiceTaxAmount": 0,
    "Installments": 1,
    "Interest": "ByMerchant",
    "Capture": true,
    "Authenticate": false,
    "Recurrent": false,
    "IssuerTransactionId": "580027442382078",
    "CreditCard": {
      "CardNumber": "123412******1234",
      "Holder": "Teste Holder",
      "ExpirationDate": "12/2030",
      "Brand": "Visa",
      "PaymentAccountReference": "NUKBZZQ3E34FLV2SAHZXJBVSGWWV8"
    },
    "ProofOfSale": "553486",
    "Tid": "1212092931654",
    "AuthorizationCode": "520520",
    "PaymentId": "2352fc91-f9a4-4ca2-aedb-31488b9658c9",
    "Type": "CreditCard",
    "Amount": 15700,
    "ReceivedDate": "2024-12-12 09:29:31",
    "CapturedAmount": 15700,
    "CapturedDate": "2024-12-12 09:29:31",
    "Currency": "BRL",
    "Country": "BRA",
    "Provider": "Simulado",
    "Status": 2,
    "Links": [
      {
        "Method": "GET",
        "Rel": "self",
        "Href": "https://apiquerysandbox.cieloecommerce.cielo.com.br/1/sales/2352fc91-f9a4-4ca2-aedb-31488b9658c9"
      },
      {
        "Method": "PUT",
        "Rel": "void",
        "Href": "https://apisandbox.cieloecommerce.cielo.com.br/1/sales/2352fc91-f9a4-4ca2-aedb-31488b9658c9/void"
      }
    ]
  }
}
```

`get_sale_200_authorized.json` (derivado de `get_sale_200_credit.json`: `Capture: false`, `Status: 1`, sem `CapturedAmount`/`CapturedDate`/`Links`, cliente reduzido ao nome):
```json
{
  "MerchantOrderId": "Loja123456",
  "Customer": {
    "Name": "Comprador crédito completo"
  },
  "Payment": {
    "Installments": 1,
    "Interest": "ByMerchant",
    "Capture": false,
    "CreditCard": {
      "CardNumber": "123412******1234",
      "Holder": "Teste Holder",
      "ExpirationDate": "12/2030",
      "Brand": "Visa"
    },
    "ProofOfSale": "553486",
    "Tid": "1212092931654",
    "AuthorizationCode": "520520",
    "PaymentId": "2352fc91-f9a4-4ca2-aedb-31488b9658c9",
    "Type": "CreditCard",
    "Amount": 15700,
    "ReceivedDate": "2024-12-12 09:29:31",
    "Currency": "BRL",
    "Country": "BRA",
    "Status": 1
  }
}
```

`get_sales_by_order_200.json` (verbatim — `reference/consulta-merchantorderid-api`, 200 "Result"):
```json
{
  "ReasonCode": 0,
  "ReasonMessage": "Successful",
  "Payments": [
    {
      "PaymentId": "4b62cc74-bb20-4629-ab2d-002262738481",
      "ReceveidDate": "2024-11-29T13:36:04.033"
    },
    {
      "PaymentId": "1e7abcb6-39be-4aae-b70b-002889ee15d0",
      "ReceveidDate": "2025-04-13T02:08:16.467"
    },
    {
      "PaymentId": "6c936046-f8df-4e4a-ba8c-003b28879055",
      "ReceveidDate": "2025-05-17T07:48:10.677"
    },
    {
      "PaymentId": "55f0a6c8-387e-476b-a6e8-ffef82e9a18e",
      "ReceveidDate": "2025-02-18T14:10:10.61"
    }
  ]
}
```

`post_card_request.json` (verbatim — `reference/criar-cardtoken`, "Request Example"):
```json
{
  "CustomerName": "Comprador Teste Cielo",
  "CardNumber": "4024007110880035",
  "Holder": "Comprador T Cielo",
  "ExpirationDate": "10/2026",
  "Brand": "Visa"
}
```

`post_card_201.json` (verbatim — `reference/criar-cardtoken`, 201 "Result"):
```json
{
  "CardToken": "db62dc71-d07b-4745-9969-42697b988ccb",
  "Links": {
    "Method": "GET",
    "Rel": "self",
    "Href": "https://apiquerydev.cieloecommerce.cielo.com.br/1/card/db62dc71-d07b-4745-9969-42697b988ccb"
  }
}
```

`notification_change_type_2.json` (verbatim — `docs/webhook`):
```json
{
  "RecurrentPaymentId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "PaymentId": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx",
  "ChangeType": 2
}
```

`notification_status_changed.json` (derivado: `ChangeType: 1`, o `PaymentId` de `get_sale_200_credit.json`, sem `RecurrentPaymentId` — "aplicável somente para ChangeType 2 ou 4", `docs/webhook`):
```json
{
  "PaymentId": "2352fc91-f9a4-4ca2-aedb-31488b9658c9",
  "ChangeType": 1
}
```

- [ ] **Step 2: Os testes**

`gateway-providers/src/test/java/com/gateway/providers/cielo/sale/CieloSalesClientContractTest.java`:
```java
package com.gateway.providers.cielo.sale;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.money.Money;
import com.gateway.providers.cielo.sale.dto.SaleResponse;
import com.gateway.providers.cielo.sale.dto.SaleUpdateResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Capture, void and the two queries, against the pages' own examples (fixtures README). */
class CieloSalesClientContractTest {
  static final String PAYMENT_ID = "2352fc91-f9a4-4ca2-aedb-31488b9658c9";
  static WireMockServer server;

  @BeforeAll
  static void start() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @BeforeEach
  void reset() {
    server.resetAll();
  }

  static CieloSalesClient client() {
    return CieloSalesClientAuthorizeContractTest.client(server, Duration.ofSeconds(5));
  }

  @Test
  void aTotalCaptureSendsNoAmount() {
    server.stubFor(
        put(urlEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .willReturn(okJson(CieloFixtures.read("put_capture_200.json"))));

    SaleUpdateResponse captured =
        client().capture(CieloSalesClientAuthorizeContractTest.credentials(), PAYMENT_ID, Optional.empty());

    assertThat(captured.status()).isEqualTo(2);
    assertThat(captured.returnCode()).isEqualTo("6");
    server.verify(putRequestedFor(urlEqualTo("/1/sales/" + PAYMENT_ID + "/capture")));
  }

  /** "Para captura parcial, envie o campo Amount" — as the query string, in cents. */
  @Test
  void aPartialCaptureSendsTheAmountInCents() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .willReturn(okJson(CieloFixtures.read("put_capture_200.json"))));

    client()
        .capture(
            CieloSalesClientAuthorizeContractTest.credentials(),
            PAYMENT_ID,
            Optional.of(Money.brl(5000)));

    server.verify(
        putRequestedFor(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .withQueryParam("amount", equalTo("5000")));
  }

  @Test
  void aVoidBeforeCaptureIsTotalAndAnswersTen() {
    server.stubFor(
        put(urlEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200.json"))));

    SaleUpdateResponse voided =
        client().voidSale(CieloSalesClientAuthorizeContractTest.credentials(), PAYMENT_ID, Optional.empty());

    assertThat(voided.status()).isEqualTo(10);
  }

  @Test
  void aPartialVoidAfterCaptureSendsTheAmount() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200_refunded.json"))));

    SaleUpdateResponse refunded =
        client()
            .voidSale(
                CieloSalesClientAuthorizeContractTest.credentials(),
                PAYMENT_ID,
                Optional.of(Money.brl(700)));

    assertThat(refunded.status()).isEqualTo(11);
    server.verify(
        putRequestedFor(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .withQueryParam("amount", equalTo("700")));
  }

  @Test
  void aQueryByPaymentIdGoesToTheQueryHost() {
    server.stubFor(
        get(urlEqualTo("/1/sales/" + PAYMENT_ID))
            .willReturn(okJson(CieloFixtures.read("get_sale_200_credit.json"))));

    Optional<SaleResponse> sale =
        client().findByPaymentId(CieloSalesClientAuthorizeContractTest.credentials(), PAYMENT_ID);

    assertThat(sale).isPresent();
    assertThat(sale.get().payment().status()).isEqualTo(2);
    assertThat(sale.get().payment().capturedAmount()).isEqualTo(15700);
  }

  /** Plan D10: 404 and 400/307 both mean "not at the Cielo". */
  @Test
  void anUnknownPaymentIdIsEmpty() {
    server.stubFor(get(urlEqualTo("/1/sales/unknown-404")).willReturn(aResponse().withStatus(404)));
    server.stubFor(
        get(urlEqualTo("/1/sales/unknown-307"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withBody("[{\"Code\":307,\"Message\":\"Transaction not found\"}]")));

    assertThat(client().findByPaymentId(CieloSalesClientAuthorizeContractTest.credentials(), "unknown-404"))
        .isEmpty();
    assertThat(client().findByPaymentId(CieloSalesClientAuthorizeContractTest.credentials(), "unknown-307"))
        .isEmpty();
  }

  @Test
  void aQueryByOrderListsTheNewestFirst() {
    server.stubFor(
        get(urlPathEqualTo("/1/sales"))
            .withQueryParam("merchantOrderId", equalTo("Loja123456"))
            .willReturn(okJson(CieloFixtures.read("get_sales_by_order_200.json"))));

    assertThat(
            client()
                .findPaymentIdsByOrder(
                    CieloSalesClientAuthorizeContractTest.credentials(), "Loja123456"))
        .containsExactly(
            "6c936046-f8df-4e4a-ba8c-003b28879055",
            "1e7abcb6-39be-4aae-b70b-002889ee15d0",
            "55f0a6c8-387e-476b-a6e8-ffef82e9a18e",
            "4b62cc74-bb20-4629-ab2d-002262738481");
  }

  @Test
  void anOrderTheCieloDoesNotKnowIsAnEmptyList() {
    server.stubFor(get(urlPathEqualTo("/1/sales")).willReturn(aResponse().withStatus(404)));
    server.stubFor(
        get(urlPathEqualTo("/1/sales"))
            .withQueryParam("merchantOrderId", equalTo("empty"))
            .willReturn(okJson("{\"ReasonCode\":0,\"ReasonMessage\":\"Successful\"}")));

    assertThat(
            client().findPaymentIdsByOrder(CieloSalesClientAuthorizeContractTest.credentials(), "nope"))
        .isEmpty();
    assertThat(
            client().findPaymentIdsByOrder(CieloSalesClientAuthorizeContractTest.credentials(), "empty"))
        .isEmpty();
  }
}
```

`gateway-providers/src/test/java/com/gateway/providers/cielo/card/CieloCardClientContractTest.java`:
```java
package com.gateway.providers.cielo.card;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.providers.cielo.CieloHttp;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.card.dto.CardTokenRequest;
import com.gateway.providers.cielo.sale.CieloFixtures;
import com.gateway.providers.cielo.sale.CieloSalesClientAuthorizeContractTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.time.Duration;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** POST /1/card/ (reference/criar-cardtoken): our body equals the page's example for its card. */
class CieloCardClientContractTest {
  static WireMockServer server;

  @BeforeAll
  static void start() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @Test
  void tokenizesWithTheDocumentedBody() {
    server.stubFor(
        post(urlEqualTo("/1/card/"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(CieloFixtures.read("post_card_201.json"))));
    URI base = URI.create(server.baseUrl());
    CieloCardClient client =
        new CieloCardClient(
            new CieloHttp(Duration.ofSeconds(2), Duration.ofSeconds(5)),
            new CieloEndpoints(base, base));
    CardData card =
        CardData.of("4024007110880035", "Comprador T Cielo", "10/2026", "123", null, YearMonth.of(2026, 9));
    CardTokenRequest request =
        CardTokenRequestFactory.from(card, PersonName.of("Comprador Teste Cielo"));

    String token =
        client.create(CieloSalesClientAuthorizeContractTest.credentials(), request).cardToken();

    assertThat(token).isEqualTo("db62dc71-d07b-4745-9969-42697b988ccb");
    server.verify(
        postRequestedFor(urlEqualTo("/1/card/"))
            .withRequestBody(equalToJson(CieloFixtures.read("post_card_request.json"))));
    assertThat(request.toString()).doesNotContain("4024007110880035");
  }
}
```

`gateway-providers/src/test/java/com/gateway/providers/cielo/CieloCardProviderTest.java`:
```java
package com.gateway.providers.cielo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardNotification;
import com.gateway.kernel.provider.card.CardNotificationKind;
import com.gateway.kernel.provider.card.CardRefundResult;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.kernel.provider.card.StoredCard;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.sale.CieloFixtures;
import com.gateway.providers.cielo.sale.SaleRequestFactoryTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CieloCardProviderTest {
  static final String PAYMENT_ID = "2352fc91-f9a4-4ca2-aedb-31488b9658c9";
  static WireMockServer server;
  static CieloCardProvider provider;

  @BeforeAll
  static void start() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    URI base = URI.create(server.baseUrl());
    CieloEndpoints wiremock = new CieloEndpoints(base, base);
    provider =
        new CieloCardProvider(
            new CieloHttp(Duration.ofSeconds(2), Duration.ofSeconds(5)), wiremock, wiremock);
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @BeforeEach
  void reset() {
    server.resetAll();
  }

  static ProviderCredentials credentials() {
    return new ProviderCredentials(
        ("{\"merchant_id\":\"11111111-2222-3333-4444-555555555555\",\"merchant_key\":\""
                + "A".repeat(40)
                + "\"}")
            .getBytes(StandardCharsets.UTF_8),
        ProviderEnvironment.TEST);
  }

  @Test
  void isTheCieloCardProduct() {
    assertThat(provider.id()).isEqualTo("CIELO");
    assertThat(provider.method()).isEqualTo(PaymentMethod.CARD);
  }

  @Test
  void anIncompleteCredentialNamesTheFieldBeforeAnyHttp() {
    ProviderCredentials noKey =
        new ProviderCredentials(
            "{\"merchant_id\":\"11111111-2222-3333-4444-555555555555\"}".getBytes(StandardCharsets.UTF_8),
            ProviderEnvironment.TEST);

    assertThatThrownBy(() -> provider.requireIssueCredentials(noKey))
        .isInstanceOf(ProviderException.class)
        .satisfies(
            thrown -> {
              ProviderException e = (ProviderException) thrown;
              assertThat(e.code()).isEqualTo(ProviderException.Code.CREDENTIALS_INCOMPLETE);
              assertThat(e.providerType()).isEqualTo("merchant_key");
            });
    assertThat(server.getAllServeEvents()).isEmpty();
  }

  @Test
  void issueAuthorizes() {
    server.stubFor(
        post(urlEqualTo("/1/sales"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(CieloFixtures.read("post_sales_201_captured.json"))));

    CardAuthorization authorization =
        provider.issue(credentials(), SaleRequestFactoryTest.request(SaleRequestFactoryTest.visa(), false));

    assertThat(authorization.status()).isEqualTo(CardStatus.PAID);
  }

  /** The capture answer has no captured amount or date (plan D17): the provider asks after it. */
  @Test
  void captureReturnsTheSaleAsTheQuerySeesItAfterwards() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .willReturn(okJson(CieloFixtures.read("put_capture_200.json"))));
    server.stubFor(
        get(urlEqualTo("/1/sales/" + PAYMENT_ID))
            .willReturn(okJson(CieloFixtures.read("get_sale_200_credit.json"))));

    CardAuthorization captured =
        provider.capture(credentials(), PAYMENT_ID, Optional.of(Money.brl(15700)));

    assertThat(captured.status()).isEqualTo(CardStatus.PAID);
    assertThat(captured.capturedAmount()).isEqualTo(Money.brl(15700));
    assertThat(captured.capturedAt()).isPresent();
  }

  @Test
  void cancelIsATotalVoidThatMustEndVoided() {
    server.stubFor(
        put(urlEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200.json"))));

    provider.cancel(credentials(), PAYMENT_ID);
  }

  @Test
  void aVoidThatDoesNotVoidIsAConflict() {
    server.stubFor(
        put(urlEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_capture_200.json"))));

    assertThatThrownBy(() -> provider.cancel(credentials(), PAYMENT_ID))
        .isInstanceOf(ProviderException.class)
        .extracting(thrown -> ((ProviderException) thrown).code())
        .isEqualTo(ProviderException.Code.CONFLICT);
  }

  /** Plan D2: 10 on the day of the sale, 11 after it — both are the money going back. */
  @Test
  void aRefundIsCompletedWhetherTheCieloSaysVoidedOrRefunded() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200_refunded.json"))));

    CardRefundResult refund = provider.refund(credentials(), PAYMENT_ID, Optional.of(Money.brl(700)));

    assertThat(refund.completed()).isTrue();
    assertThat(refund.status()).isEqualTo(CardStatus.REFUNDED);
    assertThat(refund.refundedAmount()).isEqualTo(Money.brl(700));
    assertThat(refund.returnCode()).isEqualTo("9");
  }

  @Test
  void findByOrderReadsTheNewestSale() {
    server.stubFor(
        get(urlPathEqualTo("/1/sales"))
            .withQueryParam("merchantOrderId", equalTo("01K0PAYMENTIDULID000000000"))
            .willReturn(okJson(CieloFixtures.read("get_sales_by_order_200.json"))));
    server.stubFor(
        get(urlEqualTo("/1/sales/6c936046-f8df-4e4a-ba8c-003b28879055"))
            .willReturn(okJson(CieloFixtures.read("get_sale_200_credit.json"))));

    Optional<CardAuthorization> found =
        provider.findByOrder(credentials(), "01K0PAYMENTIDULID000000000");

    assertThat(found).isPresent();
    server.verify(getRequestedFor(urlEqualTo("/1/sales/6c936046-f8df-4e4a-ba8c-003b28879055")));
  }

  @Test
  void findByOrderIsEmptyWhenTheCieloHasNothing() {
    server.stubFor(get(urlPathEqualTo("/1/sales")).willReturn(aResponse().withStatus(404)));

    assertThat(provider.findByOrder(credentials(), "01K0NOTHERE00000000000000")).isEmpty();
  }

  @Test
  void tokenizeStoresTheCard() {
    server.stubFor(
        post(urlEqualTo("/1/card/"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(CieloFixtures.read("post_card_201.json"))));
    CardData card =
        CardData.of("4024007110880035", "Comprador T Cielo", "10/2026", "123", null, YearMonth.of(2026, 9));

    StoredCard stored = provider.tokenize(credentials(), card, "Comprador Teste Cielo");

    assertThat(stored.token()).isEqualTo("db62dc71-d07b-4745-9969-42697b988ccb");
    assertThat(stored.last4()).isEqualTo("0035");
    assertThat(stored.expiry()).isEqualTo(YearMonth.of(2026, 10));
  }

  @Test
  void notificationsAreClassifiedByChangeType() {
    CardNotification status =
        provider.parseWebhook(CieloFixtures.read("notification_status_changed.json").getBytes(StandardCharsets.UTF_8));
    CardNotification recurrence =
        provider.parseWebhook(CieloFixtures.read("notification_change_type_2.json").getBytes(StandardCharsets.UTF_8));

    assertThat(status.paymentId()).isEqualTo(PAYMENT_ID);
    assertThat(status.kind()).isEqualTo(CardNotificationKind.STATUS_CHANGED);
    assertThat(recurrence.kind()).isEqualTo(CardNotificationKind.IGNORED);
    for (int[] pair : new int[][] {{25, 1}, {5, 2}, {8, 3}, {3, 4}, {4, 4}, {6, 4}, {7, 4}}) {
      CardNotificationKind expected =
          new CardNotificationKind[] {
            null,
            CardNotificationKind.PARTIAL_REFUND,
            CardNotificationKind.VOID_DENIED,
            CardNotificationKind.FRAUD_ALERT,
            CardNotificationKind.IGNORED
          }[pair[1]];
      byte[] body =
          ("{\"PaymentId\":\"" + PAYMENT_ID + "\",\"ChangeType\":" + pair[0] + "}")
              .getBytes(StandardCharsets.UTF_8);
      assertThat(provider.parseWebhook(body).kind()).as("ChangeType %s", pair[0]).isEqualTo(expected);
    }
  }

  @Test
  void aNotificationWithoutPaymentIdIsUnreadable() {
    assertThatThrownBy(() -> provider.parseWebhook("{\"ChangeType\":1}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("notification without PaymentId");
  }
}
```

`gateway-providers/src/test/java/com/gateway/providers/CieloPropertiesTest.java`:
```java
package com.gateway.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.providers.ProvidersConfiguration.CieloProperties;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CieloPropertiesTest {

  @Test
  void unsetMeansTheDocumentedHostsAndThirtySeconds() {
    CieloProperties properties = new CieloProperties(null, null, null, null, null);

    assertThat(properties.live()).isEqualTo(CieloEndpoints.forEnvironment(ProviderEnvironment.LIVE));
    assertThat(properties.test()).isEqualTo(CieloEndpoints.forEnvironment(ProviderEnvironment.TEST));
    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
  }

  /** How the app's tests point the provider at WireMock. */
  @Test
  void eachHostCanBeOverridden() {
    CieloProperties properties =
        new CieloProperties(null, null, "http://localhost:1/api", "http://localhost:1/query", null);

    assertThat(properties.test().api()).hasToString("http://localhost:1/api");
    assertThat(properties.test().apiQuery()).hasToString("http://localhost:1/query");
  }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-providers -Dtest='CieloSalesClientContractTest,CieloCardClientContractTest,CieloCardProviderTest,CieloPropertiesTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `capture`, `voidSale`, `CieloCardClient`, `CieloCardProvider`, `CieloProperties` não existem.

- [ ] **Step 4: DTOs das respostas curtas e do token**

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/dto/SaleUpdateResponse.java`:
```java
package com.gateway.providers.cielo.sale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * What PUT …/capture and PUT …/void answer: the new status and codes, nothing about amounts
 * (reference/capturar-apos-autorizacao, reference/cancelamento-paymentid).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleUpdateResponse(
    @JsonProperty("Status") Integer status,
    @JsonProperty("ReturnCode") String returnCode,
    @JsonProperty("ReturnMessage") String returnMessage,
    @JsonProperty("Tid") String tid,
    @JsonProperty("ProofOfSale") String proofOfSale,
    @JsonProperty("AuthorizationCode") String authorizationCode) {}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/sale/dto/SalesByOrderResponse.java`:
```java
package com.gateway.providers.cielo.sale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * GET /1/sales?merchantOrderId= (reference/consulta-merchantorderid-api). The date field is
 * spelled {@code ReceveidDate} by the Cielo, in the example and in the schema (plan D11).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesByOrderResponse(
    @JsonProperty("ReasonCode") Integer reasonCode,
    @JsonProperty("ReasonMessage") String reasonMessage,
    @JsonProperty("Payments") List<Item> payments) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Item(
      @JsonProperty("PaymentId") String paymentId,
      @JsonProperty("ReceveidDate") String receivedDate) {}
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/card/dto/CardTokenRequest.java`:
```java
package com.gateway.providers.cielo.card.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** POST /1/card/ (reference/criar-cardtoken). Holds a card number: toString is overridden. */
public record CardTokenRequest(
    @JsonProperty("CustomerName") String customerName,
    @JsonProperty("CardNumber") String cardNumber,
    @JsonProperty("Holder") String holder,
    @JsonProperty("ExpirationDate") String expirationDate,
    @JsonProperty("Brand") String brand) {

  @Override
  public String toString() {
    return "CardTokenRequest[" + brand + "]";
  }
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/card/dto/CardTokenResponse.java`:
```java
package com.gateway.providers.cielo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The page's table says {@code Cardtoken}; the example and the schema say {@code CardToken}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CardTokenResponse(@JsonProperty("CardToken") String cardToken) {

  @Override
  public String toString() {
    return "CardTokenResponse[***]";
  }
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/notification/NotificationBody.java`:
```java
package com.gateway.providers.cielo.notification;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The Post de Notificação body (docs/webhook): three fields, RecurrentPaymentId only for 2/4. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationBody(
    @JsonProperty("PaymentId") String paymentId,
    @JsonProperty("ChangeType") Integer changeType,
    @JsonProperty("RecurrentPaymentId") String recurrentPaymentId) {}
```

- [ ] **Step 5: `CieloSalesClient` completo e `CieloCardClient`**

Acrescente a `CieloSalesClient` (imports `com.gateway.kernel.money.Money`, `com.gateway.kernel.provider.ProviderException`, `com.gateway.providers.cielo.sale.dto.{SaleUpdateResponse,SalesByOrderResponse}`, `java.net.URLEncoder`, `java.net.http.HttpRequest`, `java.nio.charset.StandardCharsets`, `java.util.{Comparator,List,Optional}`, `java.time.Instant`):
```java
  /**
   * Empty amount captures everything. "Esse modelo de captura pode ocorrer apenas uma vez por
   * transação" — the second call is the Cielo's to refuse (308), not ours to retry.
   */
  public SaleUpdateResponse capture(
      CieloCredentials credentials, String paymentId, Optional<Money> amount) {
    return update(credentials, "/1/sales/" + segment(paymentId) + "/capture" + amountQuery(amount));
  }

  /** Before capture only total; after capture a refund, partial allowed and repeatable. */
  public SaleUpdateResponse voidSale(
      CieloCredentials credentials, String paymentId, Optional<Money> amount) {
    return update(credentials, "/1/sales/" + segment(paymentId) + "/void" + amountQuery(amount));
  }

  public Optional<SaleResponse> findByPaymentId(CieloCredentials credentials, String paymentId) {
    HttpResponse<String> response =
        http.send(
            credentials,
            http.request(endpoints.apiQuery(), "/1/sales/" + segment(paymentId)).GET());

    if (response.statusCode() == 200) {
      return Optional.of(http.read(response, SaleResponse.class));
    }

    ProviderException failure = CieloErrors.from(response.statusCode(), response.body());
    if (CieloErrors.isTransactionNotFound(failure)) {
      return Optional.empty();
    }
    throw failure;
  }

  /**
   * Newest first: after a timeout the gateway sent one sale per MerchantOrderId, but a retry of the
   * same order by hand (the docs' 24 h reuse rule) must not make it adopt the older one.
   */
  public List<String> findPaymentIdsByOrder(CieloCredentials credentials, String merchantOrderId) {
    String query = "/1/sales?merchantOrderId=" + URLEncoder.encode(merchantOrderId, StandardCharsets.UTF_8);
    HttpResponse<String> response =
        http.send(credentials, http.request(endpoints.apiQuery(), query).GET());

    if (response.statusCode() != 200) {
      ProviderException failure = CieloErrors.from(response.statusCode(), response.body());
      if (CieloErrors.isTransactionNotFound(failure)) {
        return List.of();
      }
      throw failure;
    }

    SalesByOrderResponse sales = http.read(response, SalesByOrderResponse.class);
    if (sales.payments() == null) {
      return List.of();
    }

    return sales.payments().stream()
        .sorted(
            Comparator.comparing(
                    (SalesByOrderResponse.Item item) -> received(item.receivedDate()))
                .reversed())
        .map(SalesByOrderResponse.Item::paymentId)
        .toList();
  }

  private SaleUpdateResponse update(CieloCredentials credentials, String pathAndQuery) {
    HttpResponse<String> response =
        http.send(
            credentials,
            http.request(endpoints.api(), pathAndQuery).PUT(HttpRequest.BodyPublishers.noBody()));

    if (response.statusCode() != 200) {
      throw CieloErrors.from(response.statusCode(), response.body());
    }

    return http.read(response, SaleUpdateResponse.class);
  }

  private static Instant received(String date) {
    Instant parsed = CieloDates.parse(date);
    return parsed == null ? Instant.EPOCH : parsed;
  }

  private static String amountQuery(Optional<Money> amount) {
    return amount.map(money -> "?amount=" + money.cents()).orElse("");
  }

  private static String segment(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
  }
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/card/CardTokenRequestFactory.java`:
```java
package com.gateway.providers.cielo.card;

import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.providers.cielo.CieloText;
import com.gateway.providers.cielo.card.dto.CardTokenRequest;
import com.gateway.providers.cielo.sale.CieloBrands;

/**
 * CardData → POST /1/card/. With SaleRequestFactory, the only two places that reveal a card
 * number (spec §7); this one is not reached by payments in this phase (plan C13).
 */
public final class CardTokenRequestFactory {
  private CardTokenRequestFactory() {}

  public static CardTokenRequest from(CardData card, PersonName customerName) {
    return new CardTokenRequest(
        CieloText.customerName(customerName),
        card.number().reveal(),
        CieloText.holder(card.holder()),
        card.expiry().formatted(),
        CieloBrands.nameOf(card.brand()));
  }
}
```

`gateway-providers/src/main/java/com/gateway/providers/cielo/card/CieloCardClient.java`:
```java
package com.gateway.providers.cielo.card;

import com.gateway.providers.cielo.CieloErrors;
import com.gateway.providers.cielo.CieloHttp;
import com.gateway.providers.cielo.auth.CieloCredentials;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.card.dto.CardTokenRequest;
import com.gateway.providers.cielo.card.dto.CardTokenResponse;
import java.net.http.HttpResponse;

/**
 * POST /1/card/ — with the trailing slash, as both endpoint rows of reference/criar-cardtoken
 * write it. The token is bound to the MerchantId that created it (spec §1).
 */
public class CieloCardClient {
  private final CieloHttp http;
  private final CieloEndpoints endpoints;

  public CieloCardClient(CieloHttp http, CieloEndpoints endpoints) {
    this.http = http;
    this.endpoints = endpoints;
  }

  public CardTokenResponse create(CieloCredentials credentials, CardTokenRequest request) {
    HttpResponse<String> response =
        http.send(credentials, http.request(endpoints.api(), "/1/card/").POST(http.json(request)));

    if (response.statusCode() != 201 && response.statusCode() != 200) {
      throw CieloErrors.from(response.statusCode(), response.body());
    }

    return http.read(response, CardTokenResponse.class);
  }
}
```

- [ ] **Step 6: `CieloCardProvider`**

`gateway-providers/src/main/java/com/gateway/providers/cielo/CieloCardProvider.java`:
```java
package com.gateway.providers.cielo;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardIssueRequest;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardNotification;
import com.gateway.kernel.provider.card.CardNotificationKind;
import com.gateway.kernel.provider.card.CardRefundResult;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.kernel.provider.card.StoredCard;
import com.gateway.providers.cielo.auth.CieloCredentials;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.card.CardTokenRequestFactory;
import com.gateway.providers.cielo.card.CieloCardClient;
import com.gateway.providers.cielo.notification.NotificationBody;
import com.gateway.providers.cielo.sale.CieloSalesClient;
import com.gateway.providers.cielo.sale.CieloStatuses;
import com.gateway.providers.cielo.sale.SaleRequestFactory;
import com.gateway.providers.cielo.sale.SaleResponses;
import com.gateway.providers.cielo.sale.dto.SaleUpdateResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** The only class that knows the Cielo's vocabulary and the gateway's card contract at once. */
public class CieloCardProvider implements CardMethodProvider {
  /** docs/webhook, "Tabela de ChangeType"; everything else is not this phase's (plan D14). */
  private static final Map<Integer, CardNotificationKind> CHANGE_TYPES =
      Map.of(
          1, CardNotificationKind.STATUS_CHANGED,
          25, CardNotificationKind.PARTIAL_REFUND,
          5, CardNotificationKind.VOID_DENIED,
          8, CardNotificationKind.FRAUD_ALERT);

  private final CieloSalesClient liveSales;
  private final CieloSalesClient testSales;
  private final CieloCardClient liveCards;
  private final CieloCardClient testCards;
  private final ObjectMapper mapper = new ObjectMapper();

  public CieloCardProvider(CieloHttp http, CieloEndpoints live, CieloEndpoints test) {
    this.liveSales = new CieloSalesClient(http, live);
    this.testSales = new CieloSalesClient(http, test);
    this.liveCards = new CieloCardClient(http, live);
    this.testCards = new CieloCardClient(http, test);
  }

  @Override
  public String id() {
    return "CIELO";
  }

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  @Override
  public void requireIssueCredentials(ProviderCredentials credentials) {
    credentialsOf(credentials);
  }

  @Override
  public CardAuthorization issue(ProviderCredentials credentials, CardIssueRequest request) {
    return SaleResponses.toAuthorization(
        sales(credentials).authorize(credentialsOf(credentials), SaleRequestFactory.from(request)),
        request.source());
  }

  @Override
  public Optional<CardAuthorization> find(ProviderCredentials credentials, String bankReference) {
    return sales(credentials)
        .findByPaymentId(credentialsOf(credentials), bankReference)
        .map(sale -> SaleResponses.toAuthorization(sale, null));
  }

  @Override
  public Optional<CardAuthorization> findByOrder(
      ProviderCredentials credentials, String merchantOrderId) {
    List<String> paymentIds =
        sales(credentials).findPaymentIdsByOrder(credentialsOf(credentials), merchantOrderId);

    if (paymentIds.isEmpty()) {
      return Optional.empty();
    }

    return find(credentials, paymentIds.getFirst());
  }

  /** A void of an authorization is total by definition: no amount, and the answer must be 10. */
  @Override
  public void cancel(ProviderCredentials credentials, String bankReference) {
    SaleUpdateResponse voided =
        sales(credentials).voidSale(credentialsOf(credentials), bankReference, Optional.empty());
    CardStatus status = CieloStatuses.of(voided.status());

    if (status != CardStatus.VOIDED) {
      throw new ProviderException(
          ProviderException.Code.CONFLICT,
          200,
          voided.returnCode(),
          "void answered status " + status);
    }
  }

  /**
   * The capture answer carries no captured amount or date (reference/capturar-apos-autorizacao),
   * which is what the payment stores: one GET after it, on the query host (plan D17).
   */
  @Override
  public CardAuthorization capture(
      ProviderCredentials credentials, String bankReference, Optional<Money> amount) {
    sales(credentials).capture(credentialsOf(credentials), bankReference, amount);

    return find(credentials, bankReference)
        .orElseThrow(
            () ->
                new ProviderException(
                    ProviderException.Code.UNKNOWN,
                    0,
                    null,
                    "sale " + bankReference + " not found right after its capture"));
  }

  @Override
  public CardRefundResult refund(
      ProviderCredentials credentials, String bankReference, Optional<Money> amount) {
    SaleUpdateResponse refunded =
        sales(credentials).voidSale(credentialsOf(credentials), bankReference, amount);

    return new CardRefundResult(
        CieloStatuses.of(refunded.status()),
        amount.orElse(null),
        refunded.returnCode(),
        refunded.returnMessage());
  }

  @Override
  public StoredCard tokenize(ProviderCredentials credentials, CardData card, String customerName) {
    String token =
        cards(credentials)
            .create(
                credentialsOf(credentials),
                CardTokenRequestFactory.from(card, PersonName.of(customerName)))
            .cardToken();

    return new StoredCard(token, card.brand(), card.last4(), card.expiry().value());
  }

  @Override
  public CardNotification parseWebhook(byte[] body) {
    NotificationBody notification = mapper.readValue(body, NotificationBody.class);

    if (notification.paymentId() == null || notification.paymentId().isBlank()) {
      throw new IllegalArgumentException("notification without PaymentId");
    }

    int changeType = notification.changeType() == null ? 0 : notification.changeType();
    return new CardNotification(
        notification.paymentId(),
        CHANGE_TYPES.getOrDefault(changeType, CardNotificationKind.IGNORED),
        changeType);
  }

  /**
   * CREDENTIALS_INCOMPLETE with the field as providerType: CieloCredentials starts every message
   * with the field name, so the merchant's 422 can say which one.
   */
  private static CieloCredentials credentialsOf(ProviderCredentials credentials) {
    try {
      return CieloCredentials.parse(credentials.payload());
    } catch (IllegalArgumentException e) {
      String field = e.getMessage().split(" ")[0];
      throw new ProviderException(
          ProviderException.Code.CREDENTIALS_INCOMPLETE,
          0,
          field,
          "the CIELO credential is incomplete: " + e.getMessage());
    }
  }

  private CieloSalesClient sales(ProviderCredentials credentials) {
    return credentials.environment() == ProviderEnvironment.LIVE ? liveSales : testSales;
  }

  private CieloCardClient cards(ProviderCredentials credentials) {
    return credentials.environment() == ProviderEnvironment.LIVE ? liveCards : testCards;
  }
}
```

- [ ] **Step 7: Wiring em `ProvidersConfiguration`**

Em `ProvidersConfiguration.java`: troque a anotação por
```java
@EnableConfigurationProperties({
  ProvidersConfiguration.ProvidersProperties.class,
  ProvidersConfiguration.CieloProperties.class
})
```
e acrescente dentro da classe:
```java
  /**
   * Every field optional: unset means the hosts of docs/providers/cielo/NOTES.md. The read timeout
   * is 30 s because an authorization may take that long at the issuer, and the Status 0 answer
   * covers the rest (spec §5).
   */
  @ConfigurationProperties("gateway.providers.cielo")
  public record CieloProperties(
      String liveApiBase,
      String liveQueryApiBase,
      String testApiBase,
      String testQueryApiBase,
      Duration readTimeout) {
    public CieloProperties {
      if (readTimeout == null) {
        readTimeout = Duration.ofSeconds(30);
      }
    }

    public CieloEndpoints live() {
      return merge(CieloEndpoints.forEnvironment(ProviderEnvironment.LIVE), liveApiBase, liveQueryApiBase);
    }

    public CieloEndpoints test() {
      return merge(CieloEndpoints.forEnvironment(ProviderEnvironment.TEST), testApiBase, testQueryApiBase);
    }

    private static CieloEndpoints merge(CieloEndpoints defaults, String api, String query) {
      return new CieloEndpoints(
          api == null || api.isBlank() ? defaults.api() : URI.create(api),
          query == null || query.isBlank() ? defaults.apiQuery() : URI.create(query));
    }
  }

  @Bean
  CardMethodProvider cieloCardProvider(CieloProperties properties) {
    return new CieloCardProvider(
        new CieloHttp(Duration.ofSeconds(3), properties.readTimeout()),
        properties.live(),
        properties.test());
  }
```
(imports `com.gateway.kernel.provider.card.CardMethodProvider`, `com.gateway.providers.cielo.{CieloCardProvider,CieloHttp}`, `com.gateway.providers.cielo.auth.CieloEndpoints`.)

- [ ] **Step 8: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-providers test`
Expected: PASS (a suíte inteira do módulo, Itaú inclusive).

- [ ] **Step 9: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-providers
git commit -m "feat(providers): cielo card provider with capture, void, queries and token

Capture queries the sale right after, because the capture answer carries no
captured amount or date. A refund counts as done on Status 10 or 11: a void on
the sale's day answers 10 even after capture. findByOrder reads the newest sale
the by-order query lists (ReceveidDate, the Cielo's spelling). Notifications are
classified by ChangeType; 2, 3, 4, 6 and 7 are ignored.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---
### Task 6: Domínio — `AUTHORIZED`, as transições do cartão, `details.card` e o `Payment` de cartão

**Files:**
- Modify: `gateway-payments/src/main/java/com/gateway/payments/payment/{PaymentStatus,PaymentTransitions,Payment,PaymentDetailsJson,PaymentEvents}.java`
- Create: `gateway-payments/src/main/java/com/gateway/payments/payment/card/{CardDetails,CardDetailsJson}.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/payment/persistence/{PaymentRepository,PaymentRepositoryImpl,PaymentJpaRepository}.java`
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/{PaymentTransitionsTest,PaymentTest,PaymentDetailsJsonTest,PaymentEventsCardTest}.java`, `payment/card/CardDetailsJsonTest.java`, `payment/persistence/PaymentRepositoryIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 (`CardAuthorization`), Task 2 (`PaymentMethod.CARD`).
- Produces:
  ```java
  enum PaymentStatus { CREATED, AUTHORIZED, PENDING, COMPLETED, EXPIRED, CANCELED, FAILED }     // AUTHORIZED not terminal
  record CardDetails(String paymentId, String tid, String authorizationCode, String proofOfSale, String brand, String last4,
      int installments, Long capturedAmount, String cardId, String declineCode) {
    static CardDetails requested(int installments, String brand, String last4, String cardId);
    CardDetails withAuthorization(CardAuthorization authorization);
    CardDetails withCaptured(long cents);  CardDetails withCardId(String cardId);  CardDetails withDecline(String declineCode);
  }
  final class CardDetailsJson { static String write(CardDetails card); static CardDetails read(String details); }
  PaymentDetailsJson.write(PixDetails, BoletoDetails, CardDetails)   // "card" key only when non-null; "pix":{} when pix is null
  PaymentDetailsJson.readCard(String details); readPix → null for "pix":{}
  Payment.createCard(MerchantId, ProviderEnvironment, String provider, Money amount, String reference, String description,
      String customerDocumentHash, CardDetails card, Clock clock)
  PaymentEvent Payment.markAuthorized(CardDetails details, EventSource by)
  PaymentEvent Payment.markCompletedByCard(CardDetails details, Money capturedAmount, Instant capturedAt, EventSource by)
  PaymentEvent Payment.markCaptured(Money capturedAmount, Instant capturedAt, EventSource by)
  PaymentEvent Payment.markDeclined(CardDetails details, EventSource by)                        // FAILED, reason CARD_DECLINED
  CardDetails Payment.card();  Money Payment.refundable();                                      // paidAmount when set, else amount
  Payment.rehydrate(..., PixDetails pix, BoletoDetails boleto, CardDetails card, ...)            // new 21-arg overload
  Optional<Payment> PaymentRepository.findByMerchantAndCardPaymentId(MerchantId, String provider, String cardPaymentId)
  PaymentEvents.paymentJson: "card": {brand, last4, installments, authorization_code, tid, captured_amount, card_id} | null; "pix": null for CARD
  ```

- [ ] **Step 1: Os testes de domínio**

Acrescente a `gateway-payments/src/test/java/com/gateway/payments/payment/PaymentTransitionsTest.java` (a tabela já é coberta por inteiro pelos dois testes parametrizados; este fixa as linhas da spec §4 por nome):
```java
  /** Spec 2026-09-28 §4, the card rows. A card never passes through PENDING nor EXPIRED. */
  @org.junit.jupiter.api.Test
  void theCardRows() {
    assertThat(PaymentTransitions.allowed(PaymentStatus.CREATED, PaymentStatus.COMPLETED, EventSource.API)).isTrue();
    assertThat(PaymentTransitions.allowed(PaymentStatus.CREATED, PaymentStatus.AUTHORIZED, EventSource.API)).isTrue();
    assertThat(PaymentTransitions.allowed(PaymentStatus.CREATED, PaymentStatus.AUTHORIZED, EventSource.SYSTEM)).isTrue();
    for (EventSource by : new EventSource[] {EventSource.API, EventSource.PROVIDER_WEBHOOK, EventSource.RECONCILIATION}) {
      assertThat(PaymentTransitions.allowed(PaymentStatus.AUTHORIZED, PaymentStatus.COMPLETED, by)).as("%s", by).isTrue();
      assertThat(PaymentTransitions.allowed(PaymentStatus.AUTHORIZED, PaymentStatus.CANCELED, by)).as("%s", by).isTrue();
    }
    assertThat(PaymentTransitions.allowed(PaymentStatus.AUTHORIZED, PaymentStatus.EXPIRED, EventSource.EXPIRATION_JOB)).isFalse();
    assertThat(PaymentTransitions.allowed(PaymentStatus.AUTHORIZED, PaymentStatus.FAILED, EventSource.API)).isFalse();
    assertThat(PaymentTransitions.allowed(PaymentStatus.CREATED, PaymentStatus.COMPLETED, EventSource.PROVIDER_WEBHOOK)).isFalse();
    assertThat(PaymentStatus.AUTHORIZED.terminal()).isFalse();
  }
```

Acrescente a `gateway-payments/src/test/java/com/gateway/payments/payment/PaymentTest.java` (imports `com.gateway.payments.payment.card.CardDetails`, `com.gateway.kernel.money.Money` já existe):
```java
  Payment card() {
    return Payment.createCard(
        MerchantId.next(),
        ProviderEnvironment.TEST,
        "CIELO",
        Money.brl(10000),
        "order-42",
        "Order 42",
        null,
        CardDetails.requested(3, "VISA", "3171", null),
        clock);
  }

  static CardDetails authorized(CardDetails requested) {
    return new CardDetails(
        "6f8d1753-86bb-4dc0-9ebb-09a29093e1fb",
        "1124060407175",
        "663864",
        "182738",
        requested.brand(),
        requested.last4(),
        requested.installments(),
        null,
        null,
        null);
  }

  @Test
  void aCardPaymentHasNoPixSideAndNoExpiry() {
    Payment p = card();

    assertThat(p.method()).isEqualTo(PaymentMethod.CARD);
    assertThat(p.pix()).isNull();
    assertThat(p.boleto()).isNull();
    assertThat(p.expiresAt()).isNull();
    assertThat(p.card().installments()).isEqualTo(3);
    assertThat(p.createdEvent().payload()).contains("\"method\":\"CARD\"");
  }

  @Test
  void authorizedThenPartiallyCaptured() {
    Payment p = card();
    p.markAuthorized(authorized(p.card()), EventSource.API);
    assertThat(p.status()).isEqualTo(PaymentStatus.AUTHORIZED);

    PaymentEvent captured =
        p.markCaptured(Money.brl(6000), Instant.parse("2026-09-24T12:30:00Z"), EventSource.API);

    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(p.paidAmount()).isEqualTo(Money.brl(6000));
    assertThat(p.card().capturedAmount()).isEqualTo(6000L);
    assertThat(captured.payload()).contains("\"paidVia\":\"CARD\"").contains("\"paidAmount\":6000");
  }

  @Test
  void anAutomaticCaptureGoesStraightToCompleted() {
    Payment p = card();

    p.markCompletedByCard(
        authorized(p.card()), Money.brl(10000), Instant.parse("2026-09-24T12:00:01Z"), EventSource.API);

    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(p.card().paymentId()).isEqualTo("6f8d1753-86bb-4dc0-9ebb-09a29093e1fb");
  }

  @Test
  void aDeclineFailsAndKeepsTheDeclineCode() {
    Payment p = card();

    PaymentEvent failed =
        p.markDeclined(authorized(p.card()).withDecline("INSUFFICIENT_FUNDS"), EventSource.API);

    assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(p.card().declineCode()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(failed.payload())
        .isEqualTo("{\"reason\":\"CARD_DECLINED\",\"declineCode\":\"INSUFFICIENT_FUNDS\"}");
  }

  /** Spec §4: the refunds' sum is capped by paid_amount, which a partial capture makes smaller. */
  @Test
  void aCardRefundIsCappedByWhatWasCaptured() {
    Payment p = card();
    p.markAuthorized(authorized(p.card()), EventSource.API);
    p.markCaptured(Money.brl(6000), Instant.parse("2026-09-24T12:30:00Z"), EventSource.API);

    p.applyRefund(Money.brl(6000));

    assertThat(p.fullyRefunded()).isTrue();
    assertThatThrownBy(() -> p.applyRefund(Money.brl(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cardTransitionsRefuseAPixPayment() {
    Payment pix = fresh();

    assertThatThrownBy(() -> pix.markAuthorized(CardDetails.requested(1, "VISA", "3171", null), EventSource.API))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("markAuthorized on a PIX payment");
  }
```

`gateway-payments/src/test/java/com/gateway/payments/payment/card/CardDetailsJsonTest.java`:
```java
package com.gateway.payments.payment.card;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CardDetailsJsonTest {
  static final CardDetails FULL =
      new CardDetails(
          "6f8d1753-86bb-4dc0-9ebb-09a29093e1fb",
          "1124060407175",
          "663864",
          "182738",
          "VISA",
          "3171",
          3,
          6000L,
          "01K0CARDID0000000000000000",
          null);

  @Test
  void roundTrips() {
    assertThat(CardDetailsJson.read("{\"card\":" + CardDetailsJson.write(FULL) + "}")).isEqualTo(FULL);
  }

  /** Postgres adds a space after every colon on the way back out of jsonb (see PixDetailsJson). */
  @Test
  void readsWhatPostgresGivesBack() {
    String fromJsonb =
        "{\"card\": {\"tid\": \"1124060407175\", \"brand\": \"VISA\", \"last4\": \"3171\", \"cardId\": null,"
            + " \"paymentId\": \"6f8d1753-86bb-4dc0-9ebb-09a29093e1fb\", \"declineCode\": \"GENERIC\","
            + " \"proofOfSale\": null, \"installments\": 1, \"capturedAmount\": null,"
            + " \"authorizationCode\": null}}";

    CardDetails read = CardDetailsJson.read(fromJsonb);

    assertThat(read.paymentId()).isEqualTo("6f8d1753-86bb-4dc0-9ebb-09a29093e1fb");
    assertThat(read.installments()).isEqualTo(1);
    assertThat(read.capturedAmount()).isNull();
    assertThat(read.declineCode()).isEqualTo("GENERIC");
  }

  @Test
  void noCardBlockIsNull() {
    assertThat(CardDetailsJson.read("{\"pix\":{\"txid\":\"t\"},\"boleto\":null}")).isNull();
    assertThat(CardDetailsJson.read(null)).isNull();
  }

  /** The block never carries a number, an expiry or a CVV (spec §4): only these ten keys. */
  @Test
  void writesExactlyTheTenKeys() {
    assertThat(CardDetailsJson.write(FULL))
        .isEqualTo(
            "{\"paymentId\":\"6f8d1753-86bb-4dc0-9ebb-09a29093e1fb\",\"tid\":\"1124060407175\","
                + "\"authorizationCode\":\"663864\",\"proofOfSale\":\"182738\",\"brand\":\"VISA\","
                + "\"last4\":\"3171\",\"installments\":3,\"capturedAmount\":6000,"
                + "\"cardId\":\"01K0CARDID0000000000000000\",\"declineCode\":null}");
  }
}
```

Acrescente a `gateway-payments/src/test/java/com/gateway/payments/payment/PaymentDetailsJsonTest.java`:
```java
  @Test
  void aCardPaymentHasAnEmptyPixAndACardBlock() {
    com.gateway.payments.payment.card.CardDetails card =
        com.gateway.payments.payment.card.CardDetails.requested(1, "VISA", "3171", null);

    String json = PaymentDetailsJson.write(null, null, card);

    assertThat(json).startsWith("{\"pix\":{},\"boleto\":null,\"card\":{");
    assertThat(PaymentDetailsJson.readPix(json)).isNull();
    assertThat(PaymentDetailsJson.readPix(json.replace("\"pix\":{}", "\"pix\": { }"))).isNull();
    assertThat(PaymentDetailsJson.readBoleto(json)).isNull();
    assertThat(PaymentDetailsJson.readCard(json)).isEqualTo(card);
  }

  /** Card keys must not collide with pix or boleto keys: the three readers scan the whole document. */
  @Test
  void theCardBlockSharesNoKeyWithTheOthers() {
    String cardJson =
        com.gateway.payments.payment.card.CardDetailsJson.write(
            new com.gateway.payments.payment.card.CardDetails("a", "b", "c", "d", "e", "f", 1, 2L, "g", "h"));
    String others =
        com.gateway.payments.payment.pix.PixDetailsJson.write(new PixDetails("a", "b", "c", "d"))
            + com.gateway.payments.payment.boleto.BoletoDetailsJson.write(
                new BoletoDetails("a", "b", "c", "d", LocalDate.EPOCH, LocalDate.EPOCH, PaidVia.BOLETO));
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([A-Za-z0-9]+)\":").matcher(cardJson);
    while (m.find()) {
      assertThat(others).doesNotContain("\"" + m.group(1) + "\":");
    }
  }
```

`gateway-payments/src/test/java/com/gateway/payments/payment/PaymentEventsCardTest.java`:
```java
package com.gateway.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.card.CardDetails;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The public JSON of a card payment: the card block of spec §9, pix null, no card data. */
class PaymentEventsCardTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);

  @Test
  @SuppressWarnings("unchecked")
  void aCapturedCardPaymentShowsTheCardBlock() {
    Payment payment =
        Payment.createCard(
            MerchantId.next(),
            ProviderEnvironment.TEST,
            "CIELO",
            Money.brl(12990),
            "order-42",
            "Pedido 42",
            null,
            CardDetails.requested(3, "VISA", "3171", null),
            clock);
    payment.markCompletedByCard(
        new CardDetails("pid", "tid-1", "auth-1", "pos-1", "VISA", "3171", 3, null, "card-1", null),
        Money.brl(12990),
        clock.instant(),
        EventSource.API);

    Map<String, Object> json = PaymentEvents.paymentJson(payment);

    assertThat(json.get("pix")).isNull();
    assertThat(json.get("boleto")).isNull();
    Map<String, Object> card = (Map<String, Object>) json.get("card");
    assertThat(card)
        .containsOnlyKeys(
            "brand", "last4", "installments", "authorization_code", "tid", "captured_amount", "card_id");
    assertThat(card)
        .containsEntry("brand", "VISA")
        .containsEntry("last4", "3171")
        .containsEntry("installments", 3)
        .containsEntry("authorization_code", "auth-1")
        .containsEntry("tid", "tid-1")
        .containsEntry("captured_amount", 12990L)
        .containsEntry("card_id", "card-1");
  }

  @Test
  void aPixPaymentHasANullCardBlock() {
    Payment pix =
        Payment.create(
            MerchantId.next(), ProviderEnvironment.TEST, "ITAU", Money.brl(100), null, null, null, 3600, clock);

    assertThat(PaymentEvents.paymentJson(pix)).containsEntry("card", null);
  }
}
```

Acrescente a `gateway-payments/src/test/java/com/gateway/payments/payment/persistence/PaymentRepositoryIntegrationTest.java` (import `com.gateway.payments.payment.card.CardDetails`):
```java
  @Test
  void aCardPaymentRoundTripsAndIsFoundByTheAcquirersPaymentId() {
    MerchantId merchant = MerchantId.next();
    Payment draft =
        Payment.createCard(
            merchant,
            ProviderEnvironment.TEST,
            "CIELO",
            Money.brl(12990),
            "order-42",
            "Pedido 42",
            null,
            CardDetails.requested(2, "MASTER", "0634", null),
            clock);
    tx().executeWithoutResult(status -> repository.save(draft, List.of(draft.createdEvent())));

    Payment loaded = repository.findById(draft.id()).orElseThrow();
    loaded.markAuthorized(
        new CardDetails("5cd9ccaf-e3b2-430c-b5dd-2b674cfd656a", "t", "a", "p", "MASTER", "0634", 2, null, null, null),
        EventSource.API);
    tx().executeWithoutResult(
            status -> repository.save(loaded, List.of(loaded.markCaptured(Money.brl(12990), clock.instant(), EventSource.API))));

    Payment found =
        repository
            .findByMerchantAndCardPaymentId(merchant, "CIELO", "5cd9ccaf-e3b2-430c-b5dd-2b674cfd656a")
            .orElseThrow();
    assertThat(found.id()).isEqualTo(draft.id());
    assertThat(found.method()).isEqualTo(PaymentMethod.CARD);
    assertThat(found.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(found.pix()).isNull();
    assertThat(found.card().capturedAmount()).isEqualTo(12990L);
    assertThat(found.paidAmount()).isEqualTo(Money.brl(12990));
    assertThat(repository.findByMerchantAndCardPaymentId(MerchantId.next(), "CIELO", "5cd9ccaf-e3b2-430c-b5dd-2b674cfd656a"))
        .isEmpty();
  }
```
(Se o teste existente não tiver um `executeWithoutResult` em uso, use `tx().execute(status -> { ...; return null; })`.)

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -Dtest='PaymentTransitionsTest,PaymentTest,PaymentDetailsJsonTest,PaymentEventsCardTest,CardDetailsJsonTest,PaymentRepositoryIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `AUTHORIZED`, `CardDetails`, `createCard` não existem.

- [ ] **Step 3: Estado e transições**

`PaymentStatus.java`:
```java
public enum PaymentStatus {
  CREATED,
  /** A card authorization waiting for its capture (spec 2026-09-28 §4). Holds the payer's limit. */
  AUTHORIZED,
  PENDING,
  COMPLETED,
  EXPIRED,
  CANCELED,
  FAILED;
```
(o resto do arquivo não muda: `TERMINAL` continua `COMPLETED, CANCELED, FAILED`.)

Em `PaymentTransitions.TABLE`, acrescente depois da última linha:
```java
          // Card (spec 2026-09-28 §4). CREATED -> COMPLETED is the automatic capture; SYSTEM is the
          // stuck-CREATED sweeper adopting a sale the acquirer confirms by MerchantOrderId. A card
          // never passes through PENDING nor EXPIRED: an authorization does not expire at the Cielo,
          // and cancelling it on our own would free a limit the merchant may still want (§11).
          new Transition(CREATED, COMPLETED, EnumSet.of(API, SYSTEM)),
          new Transition(CREATED, AUTHORIZED, EnumSet.of(API, SYSTEM)),
          new Transition(AUTHORIZED, COMPLETED, EnumSet.of(API, PROVIDER_WEBHOOK, RECONCILIATION)),
          new Transition(AUTHORIZED, CANCELED, EnumSet.of(API, PROVIDER_WEBHOOK, RECONCILIATION))
```
(troque o `)` e `;` final de lugar para a lista continuar válida; `Set.of` aceita os 11 elementos.)

- [ ] **Step 4: `CardDetails` e `CardDetailsJson`**

`gateway-payments/src/main/java/com/gateway/payments/payment/card/CardDetails.java`:
```java
package com.gateway.payments.payment.card;

import com.gateway.kernel.provider.card.CardAuthorization;

/**
 * The card side of a payment, as {@code details.card} stores it (spec §4). {@code paymentId} is the
 * Cielo's PaymentId — the bank reference capture, void and query take — and {@code cardId} is our
 * saved-card id when the payment stored or used one. Never a number, an expiry or a CVV.
 *
 * <p>{@code brand} is the kernel CardBrand's name, kept as text so the details document reads back
 * even if a brand is ever renamed.
 */
public record CardDetails(
    String paymentId,
    String tid,
    String authorizationCode,
    String proofOfSale,
    String brand,
    String last4,
    int installments,
    Long capturedAmount,
    String cardId,
    String declineCode) {

  /** What is known before the acquirer answers: the merchant's request and the card's face. */
  public static CardDetails requested(int installments, String brand, String last4, String cardId) {
    return new CardDetails(null, null, null, null, brand, last4, installments, null, cardId, null);
  }

  /** The acquirer's identifiers; its brand and last four win when it sent them. */
  public CardDetails withAuthorization(CardAuthorization authorization) {
    return new CardDetails(
        authorization.paymentId(),
        authorization.tid(),
        authorization.authorizationCode(),
        authorization.proofOfSale(),
        authorization.brand() == null ? brand : authorization.brand().name(),
        authorization.last4() == null ? last4 : authorization.last4(),
        installments,
        authorization.capturedAmount() == null ? null : authorization.capturedAmount().cents(),
        cardId,
        authorization.declineCode() == null ? null : authorization.declineCode().name());
  }

  public CardDetails withCaptured(long cents) {
    return new CardDetails(
        paymentId, tid, authorizationCode, proofOfSale, brand, last4, installments, cents, cardId,
        declineCode);
  }

  public CardDetails withCardId(String cardId) {
    return new CardDetails(
        paymentId, tid, authorizationCode, proofOfSale, brand, last4, installments, capturedAmount,
        cardId, declineCode);
  }

  public CardDetails withDecline(String declineCode) {
    return new CardDetails(
        paymentId, tid, authorizationCode, proofOfSale, brand, last4, installments, capturedAmount,
        cardId, declineCode);
  }
}
```

`gateway-payments/src/main/java/com/gateway/payments/payment/card/CardDetailsJson.java`:
```java
package com.gateway.payments.payment.card;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-rolled codec for {@code details.card}, the style of PixDetailsJson and BoletoDetailsJson: no
 * Jackson in the domain, and it only reads back what {@link #write} produced (plus the space
 * Postgres adds after each colon). Keys are disjoint from the pix and boleto ones
 * (PaymentDetailsJsonTest pins it), so the reader scans the whole document.
 */
public final class CardDetailsJson {
  private static final Pattern HAS_CARD = Pattern.compile("\"card\":\\s*\\{");

  private CardDetailsJson() {}

  public static String write(CardDetails card) {
    if (card == null) {
      return "null";
    }

    return "{\"paymentId\":"
        + text(card.paymentId())
        + ",\"tid\":"
        + text(card.tid())
        + ",\"authorizationCode\":"
        + text(card.authorizationCode())
        + ",\"proofOfSale\":"
        + text(card.proofOfSale())
        + ",\"brand\":"
        + text(card.brand())
        + ",\"last4\":"
        + text(card.last4())
        + ",\"installments\":"
        + card.installments()
        + ",\"capturedAmount\":"
        + (card.capturedAmount() == null ? "null" : card.capturedAmount())
        + ",\"cardId\":"
        + text(card.cardId())
        + ",\"declineCode\":"
        + text(card.declineCode())
        + "}";
  }

  /** Null when the document has no card object (a Pix or a Bolecode payment). */
  public static CardDetails read(String details) {
    if (details == null || !HAS_CARD.matcher(details).find()) {
      return null;
    }

    Long installments = number(details, "installments");
    return new CardDetails(
        field(details, "paymentId"),
        field(details, "tid"),
        field(details, "authorizationCode"),
        field(details, "proofOfSale"),
        field(details, "brand"),
        field(details, "last4"),
        installments == null ? 1 : installments.intValue(),
        number(details, "capturedAmount"),
        field(details, "cardId"),
        field(details, "declineCode"));
  }

  /** Every value written here is an id, a code or four digits: nothing needs escaping but quotes. */
  private static String text(String value) {
    return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  private static String field(String json, String key) {
    Matcher matcher =
        Pattern.compile("\"" + key + "\":\\s*(null|\"((?:\\\\.|[^\"\\\\])*)\")").matcher(json);
    if (!matcher.find() || matcher.group(2) == null) {
      return null;
    }

    return matcher.group(2).replace("\\\"", "\"").replace("\\\\", "\\");
  }

  private static Long number(String json, String key) {
    Matcher matcher = Pattern.compile("\"" + key + "\":\\s*(null|-?\\d+)").matcher(json);
    if (!matcher.find() || "null".equals(matcher.group(1))) {
      return null;
    }

    return Long.parseLong(matcher.group(1));
  }
}
```

- [ ] **Step 5: `PaymentDetailsJson`**

Substitua o corpo de `PaymentDetailsJson`:
```java
public final class PaymentDetailsJson {
  /** A card payment has no Pix side: written as an empty object, read back as null. */
  private static final Pattern EMPTY_PIX = Pattern.compile("\"pix\":\\s*\\{\\s*}");

  private PaymentDetailsJson() {}

  public static String write(PixDetails pix, BoletoDetails boleto) {
    return write(pix, boleto, null);
  }

  /**
   * {@code "card"} is written only when there is one, so a Pix or Bolecode row is byte for byte what
   * it was before cards existed.
   */
  public static String write(PixDetails pix, BoletoDetails boleto, CardDetails card) {
    String pixAndBoleto =
        "{\"pix\":" + PixDetailsJson.write(pix) + ",\"boleto\":" + BoletoDetailsJson.write(boleto);

    return card == null
        ? pixAndBoleto + "}"
        : pixAndBoleto + ",\"card\":" + CardDetailsJson.write(card) + "}";
  }

  public static PixDetails readPix(String details) {
    if (details != null && EMPTY_PIX.matcher(details).find()) {
      return null;
    }

    return PixDetailsJson.read(details);
  }

  public static BoletoDetails readBoleto(String details) {
    return BoletoDetailsJson.read(details);
  }

  public static CardDetails readCard(String details) {
    return CardDetailsJson.read(details);
  }
}
```
(imports `com.gateway.payments.payment.card.{CardDetails,CardDetailsJson}`, `java.util.regex.Pattern`; ajuste o javadoc da classe para "`{"pix": {...}, "boleto": {...}|null, "card": {...}}` — card only on card payments (spec 2026-09-28 §4)".)

- [ ] **Step 6: `Payment`**

Em `Payment.java` (import `com.gateway.payments.payment.card.CardDetails`):

1. Campo, junto de `private BoletoDetails boleto;`: `private CardDetails card;`
2. Depois de `createBolecode`:
```java
  /**
   * A card payment starts with what the merchant asked for (installments) and the card's face
   * (brand, last four), and no acquirer reference: the PaymentId exists only once the Cielo
   * answers. It never expires on its own (spec §11), so {@code expiresAt} stays null, and it has
   * no Pix side.
   */
  public static Payment createCard(
      MerchantId merchantId,
      ProviderEnvironment environment,
      String provider,
      Money amount,
      String reference,
      String description,
      String customerDocumentHash,
      CardDetails card,
      Clock clock) {
    String id = Ulid.next();
    Payment payment =
        new Payment(
            id,
            PaymentMethod.CARD,
            merchantId,
            environment,
            provider,
            amount,
            reference,
            description,
            customerDocumentHash,
            clock.instant(),
            clock);
    payment.pix = null;
    payment.card = card;
    payment.version = 1;
    payment.createdEvent =
        new PaymentEvent(
            Ulid.next(),
            id,
            payment.version,
            "created",
            EventSource.API,
            "{\"amount\":"
                + amount.cents()
                + ",\"method\":\"CARD\",\"installments\":"
                + card.installments()
                + "}",
            payment.createdAt);
    return payment;
  }
```
3. Depois de `markCompletedByBoleto`:
```java
  /** CREATED -> AUTHORIZED: the acquirer holds the amount on the card, waiting for a capture. */
  public PaymentEvent markAuthorized(CardDetails details, EventSource by) {
    requireCard("markAuthorized");
    PaymentEvent event =
        transition(
            PaymentStatus.AUTHORIZED,
            by,
            "authorized",
            "{\"paymentId\":" + json(details.paymentId()) + "}");
    this.card = details;
    return event;
  }

  /**
   * The card was captured: from CREATED (automatic capture) or AUTHORIZED (a later capture, or the
   * acquirer showing it captured elsewhere). {@code paid_amount} is what was captured, which a
   * partial capture makes smaller than the amount.
   */
  public PaymentEvent markCompletedByCard(
      CardDetails details, Money capturedAmount, Instant capturedAt, EventSource by) {
    requireCard("markCompletedByCard");
    PaymentEvent event =
        transition(
            PaymentStatus.COMPLETED,
            by,
            "completed",
            "{\"paidVia\":\"CARD\",\"paidAmount\":"
                + capturedAmount.cents()
                + ",\"paymentId\":"
                + json(details.paymentId())
                + "}");
    this.card = details.withCaptured(capturedAmount.cents());
    this.paidAmount = capturedAmount;
    this.paidAt = capturedAt;
    return event;
  }

  /** AUTHORIZED -> COMPLETED with the details already known from the authorization. */
  public PaymentEvent markCaptured(Money capturedAmount, Instant capturedAt, EventSource by) {
    return markCompletedByCard(card, capturedAmount, capturedAt, by);
  }

  /**
   * CREATED -> FAILED because the issuer said no. A result, not an error (spec §11): the event
   * records our decline code; the issuer's own text never reaches the payment.
   */
  public PaymentEvent markDeclined(CardDetails details, EventSource by) {
    requireCard("markDeclined");
    PaymentEvent event =
        transition(
            PaymentStatus.FAILED,
            by,
            "failed",
            "{\"reason\":\"CARD_DECLINED\",\"declineCode\":" + json(details.declineCode()) + "}");
    this.card = details;
    return event;
  }

  private void requireCard(String operation) {
    if (method != PaymentMethod.CARD) {
      throw new IllegalStateException(operation + " on a " + method + " payment");
    }
  }
```
4. `applyRefund` e `fullyRefunded` passam a usar o que foi pago:
```java
  public void applyRefund(Money amount) {
    if (status != PaymentStatus.COMPLETED) {
      throw new IllegalStateException("cannot refund a payment in status " + status);
    }
    Money total = refundedAmount.plus(amount);
    if (total.cents() > refundable().cents()) {
      throw new IllegalArgumentException(
          "refund total " + total.cents() + " exceeds paid amount " + refundable().cents());
    }
    this.refundedAmount = total;
  }

  /**
   * What can go back: the paid amount once there is one. A partial card capture makes it smaller
   * than the amount (spec §4: "soma ≤ paid_amount"); for Pix the settlement refuses any other
   * amount, so the two are equal there.
   */
  public Money refundable() {
    return paidAmount == null ? amount : paidAmount;
  }

  public boolean fullyRefunded() {
    return refundedAmount.cents() == refundable().cents();
  }
```
5. Accessor: `public CardDetails card() { return card; }` (em linhas separadas, como os outros).
6. `rehydrate` com cartão: o de 20 argumentos passa a delegar e nasce o de 21:
```java
  public static Payment rehydrate(
      String id,
      MerchantId merchantId,
      ProviderEnvironment environment,
      String provider,
      PaymentMethod method,
      PaymentStatus status,
      Money amount,
      String reference,
      String description,
      String customerDocumentHash,
      PixDetails pix,
      BoletoDetails boleto,
      Instant expiresAt,
      Instant paidAt,
      Money paidAmount,
      Money refundedAmount,
      long version,
      Instant createdAt,
      Instant updatedAt,
      Clock clock) {
    return rehydrate(
        id, merchantId, environment, provider, method, status, amount, reference, description,
        customerDocumentHash, pix, boleto, null, expiresAt, paidAt, paidAmount, refundedAmount,
        version, createdAt, updatedAt, clock);
  }

  public static Payment rehydrate(
      String id,
      MerchantId merchantId,
      ProviderEnvironment environment,
      String provider,
      PaymentMethod method,
      PaymentStatus status,
      Money amount,
      String reference,
      String description,
      String customerDocumentHash,
      PixDetails pix,
      BoletoDetails boleto,
      CardDetails card,
      Instant expiresAt,
      Instant paidAt,
      Money paidAmount,
      Money refundedAmount,
      long version,
      Instant createdAt,
      Instant updatedAt,
      Clock clock) {
    Payment payment =
        new Payment(
            id,
            method,
            merchantId,
            environment,
            provider,
            amount,
            reference,
            description,
            customerDocumentHash,
            createdAt,
            clock);
    payment.status = status;
    payment.pix = pix;
    payment.boleto = boleto;
    payment.card = card;
    payment.expiresAt = expiresAt;
    payment.paidAt = paidAt;
    payment.paidAmount = paidAmount;
    payment.refundedAmount = refundedAmount == null ? Money.ZERO_BRL : refundedAmount;
    payment.version = version;
    payment.updatedAt = updatedAt;
    return payment;
  }
```
(o corpo antigo do de 20 argumentos é o do novo, com `payment.card = card`.) O javadoc da classe passa a dizer "A Pix charge, a Bolecode (a registered boleto with a Pix QR on it) or a card payment."

`Payment.java` passa de 535 para ~660 linhas. Dividi-lo (um agregado por método, ou as transições de cartão num tipo próprio) é um refactor com commit e decisão próprios; fica registrado como follow-up no corpo do commit, não feito aqui — as transições precisam do estado privado do agregado. A entrada "Fase 2" do `DECISOES.md` manda a próxima classe acima do limite citá-la ou ser dividida; a Task 13 registra a citação.

- [ ] **Step 7: Persistência**

`PaymentJpaRepository`, depois de `findByProviderAndTxid`:
```java
  /** Native: the Cielo's PaymentId lives inside jsonb (V204 indexes this expression). */
  @Query(
      value =
          "SELECT * FROM payments.payments WHERE merchant_id = :merchantId AND provider = :provider"
              + " AND details->'card'->>'paymentId' = :paymentId",
      nativeQuery = true)
  Optional<PaymentEntity> findByMerchantAndCardPaymentId(
      @Param("merchantId") String merchantId,
      @Param("provider") String provider,
      @Param("paymentId") String paymentId);
```

`PaymentRepository`:
```java
  /**
   * By the acquirer's PaymentId, scoped to the merchant whose notification URL was called: the
   * notification names only the PaymentId, and another merchant's sale must not be reachable
   * through it.
   */
  Optional<Payment> findByMerchantAndCardPaymentId(
      MerchantId merchantId, String provider, String cardPaymentId);
```

`PaymentRepositoryImpl`: em `save`, `String details = PaymentDetailsJson.write(payment.pix(), payment.boleto(), payment.card());`; novo método
```java
  @Override
  public Optional<Payment> findByMerchantAndCardPaymentId(
      MerchantId merchantId, String provider, String cardPaymentId) {
    return jpa.findByMerchantAndCardPaymentId(merchantId.value(), provider, cardPaymentId)
        .map(PaymentRepositoryImpl::toDomain);
  }
```
e em `toDomain` a chamada passa a ser o `rehydrate` de 21 argumentos, com `PaymentDetailsJson.readCard(entity.details)` depois de `readBoleto`.

- [ ] **Step 8: `PaymentEvents` — o bloco `card`**

Em `PaymentEvents.paymentJson`, troque o bloco do Pix por:
```java
    // Null for a card payment, which has no Pix side (spec 2026-09-28 §9); same spelling as the REST
    // PaymentResponse.
    PixDetails pix = payment.pix();
    if (pix == null) {
      json.put("pix", null);
    } else {
      Map<String, Object> pixJson = new LinkedHashMap<>();
      pixJson.put("txid", pix.txid());
      pixJson.put("copia_e_cola", pix.pixCopiaECola());
      pixJson.put("location", pix.location());
      pixJson.put("end_to_end_id", pix.endToEndId());
      json.put("pix", pixJson);
    }
```
e, depois do bloco `boleto`:
```java
    // Spec §9: never a number, an expiry or a CVV — CardDetails has none to give.
    CardDetails card = payment.card();
    if (card == null) {
      json.put("card", null);
    } else {
      Map<String, Object> cardJson = new LinkedHashMap<>();
      cardJson.put("brand", card.brand());
      cardJson.put("last4", card.last4());
      cardJson.put("installments", card.installments());
      cardJson.put("authorization_code", card.authorizationCode());
      cardJson.put("tid", card.tid());
      cardJson.put("captured_amount", card.capturedAmount());
      cardJson.put("card_id", card.cardId());
      json.put("card", cardJson);
    }
```
(import `com.gateway.payments.payment.card.CardDetails`.) Um Pix sempre teve `pix` não nulo (`Payment.create` e o V203 garantem), então a troca de `payment.id()` por `null` só alcança o cartão.

- [ ] **Step 9: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments test`
Expected: PASS — a suíte inteira do módulo; nenhum teste antigo alterado.

- [ ] **Step 10: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-payments
git commit -m "feat(payments): authorized state and the card payment aggregate

AUTHORIZED joins the state machine with the four card rows of spec
2026-09-28 section 4; a card never passes through PENDING or EXPIRED.
details.card holds the acquirer's ids, brand, last four, installments and the
captured amount, never card data, and is written only on card rows so every
Pix and Bolecode row stays byte for byte the same. Refunds are capped by the
paid amount, which a partial capture makes smaller.

Payment.java grows past 600 lines; splitting the aggregate is left as its own
refactor.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: A porta `Sealer`, o `EnvelopeSealer` dos merchants e os cartões guardados

**Files:**
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/security/Sealer.java`
- Create: `gateway-merchants/src/main/java/com/gateway/merchants/crypto/EnvelopeSealer.java`
- Modify: `gateway-merchants/src/main/java/com/gateway/merchants/MerchantsConfiguration.java` (+ `@Bean Sealer`)
- Create: `gateway-payments/src/main/resources/db/migration/payments/V204__cards.sql`
- Create: `gateway-payments/src/main/java/com/gateway/payments/card/{SavedCard,SavedCards}.java`
- Create: `gateway-payments/src/main/java/com/gateway/payments/card/persistence/{SavedCardEntity,SavedCardJpaRepository,SavedCardRepository,SavedCardRepositoryImpl}.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/PaymentsConfiguration.java` (+ `@Import(SavedCardRepositoryImpl.class)`, `@Bean SavedCards`)
- Create: `gateway-payments/src/test/java/com/gateway/payments/support/TestSealer.java`; Modify: `support/ServiceTestConfig.java`
- Test: `gateway-merchants/src/test/java/com/gateway/merchants/crypto/EnvelopeSealerTest.java`, `gateway-payments/src/test/java/com/gateway/payments/card/SavedCardsIntegrationTest.java`

**Interfaces:**
- Consumes: Task 1 (`CardBrand`, `CardToken`, `CardOnFileUsage`), `EnvelopeCipher`/`Encrypted`.
- Produces:
  ```java
  // kernel
  interface Sealer { byte[] seal(byte[] plaintext, String context); byte[] open(byte[] sealed, String context); }  // open: SecurityException on tamper/wrong context
  // merchants
  final class EnvelopeSealer implements Sealer { EnvelopeSealer(EnvelopeCipher cipher); }
  // payments
  record SavedCard(String id, MerchantId merchantId, String provider, ProviderEnvironment environment, CardBrand brand,
      String last4, YearMonth expiry, String holder, String customerDocumentHash, Instant createdAt, Instant deletedAt)
  class SavedCards {
    SavedCards(SavedCardRepository cards, Sealer sealer, Clock clock);
    SavedCard save(MerchantId, String provider, ProviderEnvironment, String acquirerToken, CardBrand, String last4, YearMonth expiry,
        String holder, String customerDocumentHash);                       // MANDATORY transaction (joins the adoption's)
    SavedCard get(MerchantId, String cardId);                              // NotFoundException("card", id) for absent/deleted/other merchant
    CardToken tokenFor(MerchantId, ProviderEnvironment, String cardId, Secret securityCode);  // DomainException CARD_NOT_FOUND
    void delete(MerchantId, String cardId);                                // marks deleted_at; NOT_FOUND like get
  }
  ```

**Decisão (plan C8):** `payments` não pode importar `merchants`, e cifrar não é conceito de pagamento. A porta mora em `kernel/security` (o kernel já tem `Secret`); `merchants` a implementa com o `EnvelopeCipher` que já existe, empacotando o `Encrypted` em um `byte[]` (`nonce 12 | dekNonce 12 | encryptedDek 48 | ciphertext`), e `MerchantsConfiguration` publica o bean. Nenhuma cola no `app`: o bean do tipo `Sealer` chega a `PaymentsConfiguration` pelo contexto, como `CredentialLookup`. Nos testes de `payments`, `TestSealer` (AES-GCM com chave aleatória) faz o papel.

- [ ] **Step 1: Teste do `EnvelopeSealer`**

`gateway-merchants/src/test/java/com/gateway/merchants/crypto/EnvelopeSealerTest.java`:
```java
package com.gateway.merchants.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EnvelopeSealerTest {
  final EnvelopeSealer sealer = new EnvelopeSealer(new EnvelopeCipher(MasterKey.randomForTests()));
  static final String CONTEXT = "01K0MERCHANT0000000000000|CIELO|TEST|card";

  @Test
  void opensWhatItSealed() {
    byte[] token = "6e1bf77a-b28b-4660-b14f-455e2a1c95e9".getBytes(StandardCharsets.UTF_8);

    byte[] sealed = sealer.seal(token, CONTEXT);

    assertThat(sealed).hasSizeGreaterThan(token.length + 12 + 12 + 48);
    assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("6e1bf77a");
    assertThat(sealer.open(sealed, CONTEXT)).isEqualTo(token);
  }

  /** The AAD binds the token to its merchant: a row copied to another merchant does not open. */
  @Test
  void anotherContextDoesNotOpen() {
    byte[] sealed = sealer.seal("tok".getBytes(StandardCharsets.UTF_8), CONTEXT);

    assertThatThrownBy(() -> sealer.open(sealed, "01K0OTHER|CIELO|TEST|card"))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void aTruncatedBlobIsRefused() {
    assertThatThrownBy(() -> sealer.open(new byte[10], CONTEXT)).isInstanceOf(SecurityException.class);
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-merchants -am -Dtest=EnvelopeSealerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `EnvelopeSealer` e `Sealer` não existem.

- [ ] **Step 3: A porta e a implementação**

`gateway-kernel/src/main/java/com/gateway/kernel/security/Sealer.java`:
```java
package com.gateway.kernel.security;

/**
 * Authenticated encryption of a small secret bound to a context: what one module seals, only the
 * same context opens. In the kernel because payments must store an acquirer's card token encrypted
 * and may not import merchants, where the envelope cipher and its master key live (plan C8).
 *
 * <p>{@link #open} throws {@link SecurityException} for a tampered blob or another context, never a
 * detail of which one.
 */
public interface Sealer {
  byte[] seal(byte[] plaintext, String context);

  byte[] open(byte[] sealed, String context);
}
```

`gateway-merchants/src/main/java/com/gateway/merchants/crypto/EnvelopeSealer.java`:
```java
package com.gateway.merchants.crypto;

import com.gateway.kernel.security.Sealer;
import java.nio.ByteBuffer;

/**
 * {@link Sealer} over the same AES-256-GCM envelope the provider credentials use, so a stored card
 * token gets the fresh-DEK-per-row and master-key rotation story those already have. The four parts
 * of {@link Encrypted} are packed into one blob because the payments table has one column for it:
 * nonce (12) | DEK nonce (12) | encrypted DEK (32 + 16 tag = 48) | ciphertext (the rest).
 */
public final class EnvelopeSealer implements Sealer {
  private static final int NONCE = 12;
  private static final int ENCRYPTED_DEK = 48;
  private static final int HEADER = NONCE + NONCE + ENCRYPTED_DEK;

  private final EnvelopeCipher cipher;

  public EnvelopeSealer(EnvelopeCipher cipher) {
    this.cipher = cipher;
  }

  @Override
  public byte[] seal(byte[] plaintext, String context) {
    Encrypted encrypted = cipher.encrypt(plaintext, context);

    return ByteBuffer.allocate(HEADER + encrypted.ciphertext().length)
        .put(encrypted.nonce())
        .put(encrypted.dekNonce())
        .put(encrypted.encryptedDek())
        .put(encrypted.ciphertext())
        .array();
  }

  @Override
  public byte[] open(byte[] sealed, String context) {
    if (sealed == null || sealed.length <= HEADER) {
      throw new SecurityException("decryption failed");
    }

    ByteBuffer buffer = ByteBuffer.wrap(sealed);
    byte[] nonce = new byte[NONCE];
    byte[] dekNonce = new byte[NONCE];
    byte[] encryptedDek = new byte[ENCRYPTED_DEK];
    byte[] ciphertext = new byte[sealed.length - HEADER];
    buffer.get(nonce).get(dekNonce).get(encryptedDek).get(ciphertext);

    return cipher.decrypt(new Encrypted(nonce, ciphertext, encryptedDek, dekNonce), context);
  }
}
```

Em `MerchantsConfiguration`:
```java
  /** The kernel port payments stores card tokens through (plan C8). */
  @Bean
  public Sealer sealer(EnvelopeCipher cipher) {
    return new EnvelopeSealer(cipher);
  }
```
(imports `com.gateway.kernel.security.Sealer`, `com.gateway.merchants.crypto.EnvelopeSealer`.)

- [ ] **Step 4: Rodar e ver passar; commit**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-merchants -am -Dtest=EnvelopeSealerTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS.

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-kernel/src/main/java/com/gateway/kernel/security/Sealer.java gateway-merchants
git commit -m "feat(merchants): sealer port over the envelope cipher

Payments must keep the acquirer's card token encrypted and may not import
merchants, where the cipher and master key live. The port sits in the kernel
next to Secret; merchants implements it with the same AES-256-GCM envelope the
provider credentials use, packed into one blob, and publishes the bean.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

- [ ] **Step 5: O teste dos cartões guardados**

`gateway-payments/src/test/java/com/gateway/payments/support/TestSealer.java`:
```java
package com.gateway.payments.support;

import com.gateway.kernel.security.Sealer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The payments module's stand-in for merchants' EnvelopeSealer: real AES-GCM with the context as
 * AAD, one random key per test context. Real encryption on purpose — a test that "seals" by
 * copying would let a plaintext token in the table pass.
 */
public class TestSealer implements Sealer {
  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  public TestSealer() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      this.key = generator.generateKey();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public byte[] seal(byte[] plaintext, String context) {
    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, nonce, context, plaintext);
    return ByteBuffer.allocate(12 + ciphertext.length).put(nonce).put(ciphertext).array();
  }

  @Override
  public byte[] open(byte[] sealed, String context) {
    ByteBuffer buffer = ByteBuffer.wrap(sealed);
    byte[] nonce = new byte[12];
    byte[] ciphertext = new byte[sealed.length - 12];
    buffer.get(nonce).get(ciphertext);
    return gcm(Cipher.DECRYPT_MODE, nonce, context, ciphertext);
  }

  private byte[] gcm(int mode, byte[] nonce, String context, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new SecurityException("decryption failed");
    }
  }
}
```

Em `ServiceTestConfig`:
```java
  @Bean
  TestSealer testSealer() {
    return new TestSealer();
  }
```

`gateway-payments/src/test/java/com/gateway/payments/card/SavedCardsIntegrationTest.java`:
```java
package com.gateway.payments.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.security.Secret;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

class SavedCardsIntegrationTest extends ServiceIntegrationTestBase {
  static final String TOKEN = "db62dc71-d07b-4745-9969-42697b988ccb";

  @Autowired SavedCards savedCards;
  @Autowired TransactionTemplate paymentsTransactionTemplate;

  SavedCard saveOne(MerchantId owner) {
    return paymentsTransactionTemplate.execute(
        status ->
            savedCards.save(
                owner,
                "CIELO",
                ProviderEnvironment.TEST,
                TOKEN,
                CardBrand.VISA,
                "3171",
                YearMonth.of(2030, 12),
                "JOAO DA SILVA",
                null));
  }

  @Test
  void theTokenIsStoredEncryptedAndComesBackAsAUsedToken() {
    SavedCard saved = saveOne(merchant);

    byte[] stored =
        jdbc.queryForObject(
            "SELECT token_ciphertext FROM payments.cards WHERE id = ?", byte[].class, saved.id());
    assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("db62dc71");

    CardToken token = savedCards.tokenFor(merchant, ProviderEnvironment.TEST, saved.id(), Secret.of("123"));
    assertThat(token.value()).isEqualTo(TOKEN);
    assertThat(token.brand()).isEqualTo(CardBrand.VISA);
    assertThat(token.usage()).isEqualTo(CardOnFileUsage.USED);
  }

  @Test
  void getShowsTheFaceOfTheCard() {
    SavedCard saved = saveOne(merchant);

    SavedCard read = savedCards.get(merchant, saved.id());

    assertThat(read.brand()).isEqualTo(CardBrand.VISA);
    assertThat(read.last4()).isEqualTo("3171");
    assertThat(read.expiry()).isEqualTo(YearMonth.of(2030, 12));
    assertThat(read.holder()).isEqualTo("JOAO DA SILVA");
    assertThat(read.toString()).doesNotContain(TOKEN);
  }

  /** Spec §4: another merchant's card_id is NOT_FOUND, never 403 — existence does not leak. */
  @Test
  void anotherMerchantsCardDoesNotExist() {
    SavedCard saved = saveOne(MerchantId.next());

    assertThatThrownBy(() -> savedCards.get(merchant, saved.id())).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () -> savedCards.tokenFor(merchant, ProviderEnvironment.TEST, saved.id(), Secret.of("123")))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("CARD_NOT_FOUND");
  }

  /** A TEST key must not reach a card stored under LIVE: the token belongs to the other MerchantId. */
  @Test
  void anotherEnvironmentsCardIsNotFoundForCharging() {
    SavedCard saved = saveOne(merchant);

    assertThatThrownBy(
            () -> savedCards.tokenFor(merchant, ProviderEnvironment.LIVE, saved.id(), Secret.of("123")))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("CARD_NOT_FOUND");
  }

  /** The Cielo has no token deletion (spec §4): deleting is ours, and final. */
  @Test
  void aDeletedCardIsGoneForEveryone() {
    SavedCard saved = saveOne(merchant);

    savedCards.delete(merchant, saved.id());

    assertThatThrownBy(() -> savedCards.get(merchant, saved.id())).isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () -> savedCards.tokenFor(merchant, ProviderEnvironment.TEST, saved.id(), Secret.of("123")))
        .isInstanceOf(DomainException.class);
    assertThatThrownBy(() -> savedCards.delete(merchant, saved.id())).isInstanceOf(NotFoundException.class);
    assertThat(
            jdbc.queryForObject(
                "SELECT deleted_at IS NOT NULL FROM payments.cards WHERE id = ?", Boolean.class, saved.id()))
        .isTrue();
  }
}
```

- [ ] **Step 6: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -am -Dtest=SavedCardsIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `SavedCards` não existe.

- [ ] **Step 7: A migração**

`gateway-payments/src/main/resources/db/migration/payments/V204__cards.sql`:
```sql
-- Cards a merchant saved (spec 2026-09-28 §4). The acquirer's token is sealed with the merchants'
-- envelope (Sealer port, AAD = merchant|provider|environment|card), so a row copied to another
-- merchant does not open. Never a number, a CVV or a full expiry date string: brand, last four and
-- month/year are what the merchant's checkout shows.
CREATE TABLE payments.cards (
    id                     CHAR(26)     PRIMARY KEY,
    merchant_id            CHAR(26)     NOT NULL,
    provider               VARCHAR(20)  NOT NULL,
    environment            VARCHAR(10)  NOT NULL,
    token_ciphertext       BYTEA        NOT NULL,
    brand                  VARCHAR(10)  NOT NULL,
    last4                  CHAR(4)      NOT NULL,
    expiry_month           SMALLINT     NOT NULL,
    expiry_year            SMALLINT     NOT NULL,
    holder                 VARCHAR(25)  NOT NULL,
    customer_document_hash CHAR(64),
    created_at             TIMESTAMPTZ  NOT NULL,
    deleted_at             TIMESTAMPTZ
);
CREATE INDEX idx_cards_merchant ON payments.cards (merchant_id) WHERE deleted_at IS NULL;

-- The Cielo's notification names only its PaymentId; the inbox and the reconciliation find the
-- payment by it, scoped to the merchant (PaymentRepository.findByMerchantAndCardPaymentId).
CREATE INDEX idx_payments_card_payment_id
    ON payments.payments (merchant_id, provider, (details->'card'->>'paymentId'))
 WHERE details ? 'card';
```

- [ ] **Step 8: Domínio e persistência**

`gateway-payments/src/main/java/com/gateway/payments/card/SavedCard.java`:
```java
package com.gateway.payments.card;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardBrand;
import java.time.Instant;
import java.time.YearMonth;

/**
 * A card a merchant saved: our {@code card_id} (spec §11 — never the acquirer's token) and the face
 * the merchant's checkout shows. The sealed token is not a field: only {@link SavedCards} opens it,
 * at the moment of a charge.
 */
public record SavedCard(
    String id,
    MerchantId merchantId,
    String provider,
    ProviderEnvironment environment,
    CardBrand brand,
    String last4,
    YearMonth expiry,
    String holder,
    String customerDocumentHash,
    Instant createdAt,
    Instant deletedAt) {}
```

`gateway-payments/src/main/java/com/gateway/payments/card/persistence/SavedCardEntity.java`:
```java
package com.gateway.payments.card.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "cards", schema = "payments")
class SavedCardEntity {
  // CHAR(n) columns carry @JdbcTypeCode(CHAR): see PaymentEntity for why validation needs it.
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "provider", nullable = false, length = 20)
  String provider;

  @Column(name = "environment", nullable = false, length = 10)
  String environment;

  @Column(name = "token_ciphertext", nullable = false)
  byte[] tokenCiphertext;

  @Column(name = "brand", nullable = false, length = 10)
  String brand;

  @Column(name = "last4", length = 4, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String last4;

  @Column(name = "expiry_month", nullable = false)
  short expiryMonth;

  @Column(name = "expiry_year", nullable = false)
  short expiryYear;

  @Column(name = "holder", nullable = false, length = 25)
  String holder;

  @Column(name = "customer_document_hash", length = 64)
  @JdbcTypeCode(SqlTypes.CHAR)
  String customerDocumentHash;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  @Column(name = "deleted_at")
  Instant deletedAt;

  protected SavedCardEntity() {}
}
```

`gateway-payments/src/main/java/com/gateway/payments/card/persistence/SavedCardJpaRepository.java`:
```java
package com.gateway.payments.card.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface SavedCardJpaRepository extends JpaRepository<SavedCardEntity, String> {
  Optional<SavedCardEntity> findByIdAndMerchantIdAndDeletedAtIsNull(String id, String merchantId);
}
```

`gateway-payments/src/main/java/com/gateway/payments/card/persistence/SavedCardRepository.java`:
```java
package com.gateway.payments.card.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.card.SavedCard;
import java.time.Instant;
import java.util.Optional;

public interface SavedCardRepository {
  /** Requires a transaction: a card is saved together with the payment that stored it. */
  void insert(SavedCard card, byte[] tokenCiphertext);

  /** Only the merchant's own, not deleted. */
  Optional<SavedCard> findActive(MerchantId merchantId, String id);

  Optional<byte[]> findActiveToken(MerchantId merchantId, String id);

  /** Returns whether a live row was marked. */
  boolean markDeleted(MerchantId merchantId, String id, Instant at);
}
```

`gateway-payments/src/main/java/com/gateway/payments/card/persistence/SavedCardRepositoryImpl.java`:
```java
package com.gateway.payments.card.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.payments.card.SavedCard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SavedCardRepositoryImpl implements SavedCardRepository {
  private final SavedCardJpaRepository jpa;

  @PersistenceContext private EntityManager entityManager;

  public SavedCardRepositoryImpl(SavedCardJpaRepository jpa) {
    this.jpa = jpa;
  }

  /** persist, not save: the id is assigned (a ULID), same reasoning as PaymentRepositoryImpl. */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public void insert(SavedCard card, byte[] tokenCiphertext) {
    SavedCardEntity entity = new SavedCardEntity();
    entity.id = card.id();
    entity.merchantId = card.merchantId().value();
    entity.provider = card.provider();
    entity.environment = card.environment().name();
    entity.tokenCiphertext = tokenCiphertext;
    entity.brand = card.brand().name();
    entity.last4 = card.last4();
    entity.expiryMonth = (short) card.expiry().getMonthValue();
    entity.expiryYear = (short) card.expiry().getYear();
    entity.holder = card.holder();
    entity.customerDocumentHash = card.customerDocumentHash();
    entity.createdAt = card.createdAt();
    entityManager.persist(entity);
  }

  @Override
  public Optional<SavedCard> findActive(MerchantId merchantId, String id) {
    return jpa.findByIdAndMerchantIdAndDeletedAtIsNull(id, merchantId.value())
        .map(SavedCardRepositoryImpl::toDomain);
  }

  @Override
  public Optional<byte[]> findActiveToken(MerchantId merchantId, String id) {
    return jpa.findByIdAndMerchantIdAndDeletedAtIsNull(id, merchantId.value())
        .map(entity -> entity.tokenCiphertext);
  }

  @Override
  @Transactional
  public boolean markDeleted(MerchantId merchantId, String id, Instant at) {
    Optional<SavedCardEntity> found =
        jpa.findByIdAndMerchantIdAndDeletedAtIsNull(id, merchantId.value());
    found.ifPresent(entity -> entity.deletedAt = at);
    return found.isPresent();
  }

  private static SavedCard toDomain(SavedCardEntity entity) {
    return new SavedCard(
        entity.id,
        new MerchantId(entity.merchantId),
        entity.provider,
        ProviderEnvironment.valueOf(entity.environment),
        CardBrand.valueOf(entity.brand),
        entity.last4,
        YearMonth.of(entity.expiryYear, entity.expiryMonth),
        entity.holder,
        entity.customerDocumentHash,
        entity.createdAt,
        entity.deletedAt);
  }
}
```

`gateway-payments/src/main/java/com/gateway/payments/card/SavedCards.java`:
```java
package com.gateway.payments.card;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.security.Sealer;
import com.gateway.kernel.security.Secret;
import com.gateway.payments.card.persistence.SavedCardRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.YearMonth;

/**
 * Saving, reading, charging with and deleting a merchant's cards. The acquirer's token is sealed
 * with {@code merchant|provider|environment|card} as context (spec §4): a row moved to another
 * merchant, or read under the other environment, does not open.
 *
 * <p>Absent, deleted and another merchant's card are the same answer (spec §4): NOT_FOUND for the
 * card resource, CARD_NOT_FOUND for a charge — never 403, so existence does not leak.
 */
public class SavedCards {
  private final SavedCardRepository cards;
  private final Sealer sealer;
  private final Clock clock;

  public SavedCards(SavedCardRepository cards, Sealer sealer, Clock clock) {
    this.cards = cards;
    this.sealer = sealer;
    this.clock = clock;
  }

  /** Joins the caller's transaction: a card is saved with the adoption of the payment that stored it. */
  public SavedCard save(
      MerchantId merchantId,
      String provider,
      ProviderEnvironment environment,
      String acquirerToken,
      CardBrand brand,
      String last4,
      YearMonth expiry,
      String holder,
      String customerDocumentHash) {
    SavedCard card =
        new SavedCard(
            Ulid.next(),
            merchantId,
            provider,
            environment,
            brand,
            last4,
            expiry,
            holder,
            customerDocumentHash,
            clock.instant(),
            null);
    byte[] sealed =
        sealer.seal(
            acquirerToken.getBytes(StandardCharsets.UTF_8), context(merchantId, provider, environment));

    cards.insert(card, sealed);

    return card;
  }

  public SavedCard get(MerchantId merchantId, String cardId) {
    return cards
        .findActive(merchantId, cardId)
        .orElseThrow(() -> new NotFoundException("card", cardId));
  }

  /** The stored card as a charge needs it: the token, marked USED, with the CVV the payer typed now. */
  public CardToken tokenFor(
      MerchantId merchantId, ProviderEnvironment environment, String cardId, Secret securityCode) {
    SavedCard card =
        cards
            .findActive(merchantId, cardId)
            .filter(found -> found.environment() == environment)
            .orElseThrow(() -> new DomainException("CARD_NOT_FOUND", "card_id " + cardId + " not found"));
    byte[] sealed = cards.findActiveToken(merchantId, cardId).orElseThrow();
    String token =
        new String(
            sealer.open(sealed, context(merchantId, card.provider(), card.environment())),
            StandardCharsets.UTF_8);

    return new CardToken(token, card.brand(), CardOnFileUsage.USED, securityCode);
  }

  public void delete(MerchantId merchantId, String cardId) {
    if (!cards.markDeleted(merchantId, cardId, clock.instant())) {
      throw new NotFoundException("card", cardId);
    }
  }

  private static String context(
      MerchantId merchantId, String provider, ProviderEnvironment environment) {
    return merchantId.value() + "|" + provider + "|" + environment.name() + "|card";
  }
}
```

Em `PaymentsConfiguration`: acrescente `SavedCardRepositoryImpl.class` ao `@Import` e
```java
  /** Sealer comes from the context: merchants' EnvelopeSealer in the app, TestSealer in tests. */
  @Bean
  SavedCards savedCards(SavedCardRepository cards, Sealer sealer, Clock clock) {
    return new SavedCards(cards, sealer, clock);
  }
```
(imports `com.gateway.kernel.security.Sealer`, `com.gateway.payments.card.SavedCards`, `com.gateway.payments.card.persistence.{SavedCardRepository,SavedCardRepositoryImpl}`.) `@EntityScan("com.gateway.payments")` e `@EnableJpaRepositories("com.gateway.payments")` já cobrem o pacote novo.

- [ ] **Step 9: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments test`
Expected: PASS.

- [ ] **Step 10: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-payments
git commit -m "feat(payments): saved cards with the acquirer token sealed

payments.cards keeps our card_id, the card's face and the Cielo token sealed
with merchant|provider|environment|card as context, so a copied row does not
open. Absent, deleted and another merchant's card are one answer: NOT_FOUND,
CARD_NOT_FOUND for a charge, never 403. Deleting is ours: the Cielo has no
token deletion. V204 also indexes the Cielo PaymentId inside details.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---
### Task 8: `CreateCardPayment` e o `CardPaymentFlow` com todos os desfechos

**Files:**
- Create: `gateway-payments/src/main/java/com/gateway/payments/payment/create/{CreateCardPayment,CardChoice,CardCustomerData,CardCustomerFactory,CardDataFactory,InstallmentPlan,CardAuthorizationRecovery}.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/payment/create/{CreatePaymentCommand,PaymentDraftFactory,CardPaymentFlow}.java`
- Create: `gateway-payments/src/main/java/com/gateway/payments/payment/card/{CardAdoption,CardToSave,CardDeclinedException}.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/payment/StuckCreatedSweep.java` (+ ramo `CARD`)
- Modify: `gateway-payments/src/main/java/com/gateway/payments/PaymentsConfiguration.java`
- Create: `gateway-payments/src/test/java/com/gateway/payments/support/RecordingCardProvider.java`; Modify: `support/{ServiceTestConfig,InMemoryCredentialLookup,ServiceIntegrationTestBase}.java`
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/create/{CardDataFactoryTest,InstallmentPlanTest,CardPaymentFlowIntegrationTest}.java`

**Interfaces:**
- Consumes: Tasks 1, 2, 6, 7.
- Produces:
  ```java
  sealed interface CardChoice { record NewCard(CardData card, boolean save); record SavedCardChoice(String cardId, Secret securityCode); }
  record CardCustomerData(String name, String document, String email)
  record CreateCardPayment(MerchantId merchantId, ProviderEnvironment environment, Money amount, String reference, String description,
      CardChoice card, Integer installments, Boolean capture, String softDescriptor, CardCustomerData customer) implements CreatePaymentCommand
  final class CardDataFactory { static CardData from(String number, String holder, String expiry, String cvv, String brand, YearMonth currentMonth);
                                static Secret securityCodeForSavedCard(String cvv); }       // DomainException CARD_INVALID "card.<field> <reason>" / "cvv is required with card_id"
  final class InstallmentPlan { static Installments of(Money amount, Integer installments); } // DomainException INVALID_INSTALLMENTS
  final class CardCustomerFactory { static CardCustomer from(CardCustomerData data); }        // CUSTOMER_REQUIRED "customer.name ..."
  record CardToSave(String holder, YearMonth expiry, String customerDocumentHash)
  class CardDeclinedException extends DomainException { CardDeclinedException(String paymentId, String declineCode); String paymentId(); String declineCode(); } // code CARD_DECLINED
  class CardAdoption { Payment adopt(String paymentId, CardAuthorization authorization, CardToSave toSave, EventSource by); } // idempotent
  class CardAuthorizationRecovery {
    Payment recover(Payment payment, ResolvedProvider<CardMethodProvider> resolved, ProviderException cause, CardToSave toSave);
    void sweep(Payment payment);                                                               // stuck CREATED card, by SYSTEM
  }
  Payment PaymentDraftFactory.card(CreateCardPayment command, String providerId, CardDetails requested, String customerDocumentHash)
  CardPaymentFlow(ProviderGateway, PaymentDraftFactory, CardAdoption, CardAuthorizationRecovery, SavedCards, CreateFailures)
  // test support
  RecordingCardProvider: failNextAuthorizeWith(e), landNextAuthorizeThenFailWith(e), nextAuthorizeStatus(CardStatus),
      nextFindByOrderStatus(CardStatus), failNextFindByOrderWith(e), withholdNextToken(), failNextCaptureWith(e), failNextRefundWith(e),
      setStatus(String paymentId, CardStatus), CardAuthorization sale(String paymentId), CardIssueRequest lastIssued(), List<String> callsFor(String key)
  ServiceIntegrationTestBase: RecordingCardProvider cards; newCard(long cents, String number) / newCard(CreateCardPayment)
  ```

- [ ] **Step 1: Testes das fábricas**

`gateway-payments/src/test/java/com/gateway/payments/payment/create/CardDataFactoryTest.java`:
```java
package com.gateway.payments.payment.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** The 422 names the field in the API's own spelling (card.number …); the value never appears. */
class CardDataFactoryTest {
  static final YearMonth NOW = YearMonth.of(2026, 9);

  @Test
  void eachFieldIsNamedTheWayTheMerchantSentIt() {
    assertCardInvalid(() -> CardDataFactory.from("4024007153763192", "JOAO", "12/2030", "123", null, NOW),
        "card.number must pass the Luhn check");
    assertCardInvalid(() -> CardDataFactory.from("4024007153763171", "JOAO 2", "12/2030", "123", null, NOW),
        "card.holder must contain only letters and spaces");
    assertCardInvalid(() -> CardDataFactory.from("4024007153763171", "JOAO", "08/2026", "123", null, NOW),
        "card.expiry must not be in the past");
    assertCardInvalid(() -> CardDataFactory.from("4024007153763171", "JOAO", "12/2030", "12", null, NOW),
        "card.cvv must be 3 digits");
    assertCardInvalid(() -> CardDataFactory.from("4024007153763171", "JOAO", "12/2030", "123", "AMEX", NOW),
        "card.brand does not match the card number (VISA)");
  }

  @Test
  void aValidCardBuilds() {
    assertThat(CardDataFactory.from("4024 0071 5376 3171", "JOAO", "12/2030", "123", null, NOW).last4())
        .isEqualTo("3171");
  }

  /** Plan D3: the Cielo requires SecurityCode with a CardToken. */
  @Test
  void aSavedCardChargeNeedsTheCvv() {
    assertCardInvalid(() -> CardDataFactory.securityCodeForSavedCard(null), "cvv is required with card_id");
    assertCardInvalid(() -> CardDataFactory.securityCodeForSavedCard("12a"), "cvv must be 3 or 4 digits");
    assertThat(CardDataFactory.securityCodeForSavedCard("1234").reveal()).isEqualTo("1234");
  }

  static void assertCardInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String message) {
    assertThatThrownBy(call)
        .isInstanceOf(DomainException.class)
        .satisfies(
            thrown -> {
              assertThat(((DomainException) thrown).code()).isEqualTo("CARD_INVALID");
              assertThat(thrown.getMessage()).isEqualTo(message).doesNotContain("4024007153763192");
            });
  }
}
```

`gateway-payments/src/test/java/com/gateway/payments/payment/create/InstallmentPlanTest.java`:
```java
package com.gateway.payments.payment.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import org.junit.jupiter.api.Test;

/**
 * reference/criar-pagamento-credito, Interest: "No caso de parcelamento pela loja (ByMerchant), o
 * valor mínimo da parcela precisa ser de R$5,00" (plan D5, Review Focus 4).
 */
class InstallmentPlanTest {

  @Test
  void oneByDefaultAndUpToTwelve() {
    assertThat(InstallmentPlan.of(Money.brl(100), null).count()).isEqualTo(1);
    assertThat(InstallmentPlan.of(Money.brl(6000), 12).count()).isEqualTo(12);
  }

  @Test
  void aNonExactDivisionIsFineWhileEveryInstallmentReachesTheMinimum() {
    assertThat(InstallmentPlan.of(Money.brl(1600), 3).count()).isEqualTo(3);
    assertThat(InstallmentPlan.of(Money.brl(1500), 3).count()).isEqualTo(3);
  }

  @Test
  void belowTheMinimumOrOutOfRangeIsRefused() {
    for (Object[] invalid :
        new Object[][] {
          {1000L, 3, "each installment must be at least 500 cents (1000 in 3)"},
          {1499L, 3, "each installment must be at least 500 cents (1499 in 3)"},
          {100000L, 13, "installments must be between 1 and 12"},
          {100000L, 0, "installments must be between 1 and 12"}
        }) {
      assertThatThrownBy(() -> InstallmentPlan.of(Money.brl((Long) invalid[0]), (Integer) invalid[1]))
          .isInstanceOf(DomainException.class)
          .satisfies(
              thrown -> {
                assertThat(((DomainException) thrown).code()).isEqualTo("INVALID_INSTALLMENTS");
                assertThat(thrown.getMessage()).isEqualTo(invalid[2]);
              });
    }
  }

  /** One installment has no minimum: R$ 1,00 à vista is a sale. */
  @Test
  void aSingleInstallmentHasNoMinimum() {
    assertThat(InstallmentPlan.of(Money.brl(100), 1).count()).isEqualTo(1);
  }
}
```

- [ ] **Step 2: O dublê do adquirente e o suporte**

`gateway-payments/src/test/java/com/gateway/payments/support/RecordingCardProvider.java`:
```java
package com.gateway.payments.support;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardDeclineCode;
import com.gateway.kernel.provider.card.CardIssueRequest;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardNotification;
import com.gateway.kernel.provider.card.CardNotificationKind;
import com.gateway.kernel.provider.card.CardRefundResult;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.kernel.provider.card.StoredCard;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * In-memory {@link CardMethodProvider} for the payments module's tests only. Behaves like the
 * Cielo sandbox by the card's last digit (reference/credito-sandbox), with production-like codes:
 * 2 is declined with 51 (insufficient funds), 3 with 57 (do not honor), anything else approves —
 * PAID with capture, AUTHORIZED without. The hooks below play what the sandbox cannot on demand:
 * a lost answer, an in-doubt 201, a query that fails.
 */
public class RecordingCardProvider implements CardMethodProvider {
  private static final Pattern PAYMENT_ID = Pattern.compile("\"PaymentId\"\\s*:\\s*\"([^\"]+)\"");
  private static final Pattern CHANGE_TYPE = Pattern.compile("\"ChangeType\"\\s*:\\s*(\\d+)");

  private final Clock clock;
  private final Map<String, CardAuthorization> sales = new ConcurrentHashMap<>();
  private final Map<String, String> paymentIdByOrder = new ConcurrentHashMap<>();
  private final List<CardIssueRequest> issued = new CopyOnWriteArrayList<>();
  private final List<String> calls = new CopyOnWriteArrayList<>();
  private volatile ProviderException failNextAuthorize;
  private volatile ProviderException landThenFail;
  private volatile ProviderException failNextFindByOrder;
  private volatile ProviderException failNextCapture;
  private volatile ProviderException failNextRefund;
  private volatile CardStatus nextAuthorizeStatus;
  private volatile CardStatus nextFindByOrderStatus;
  private volatile boolean withholdNextToken;

  public RecordingCardProvider(Clock clock) {
    this.clock = clock;
  }

  public void failNextAuthorizeWith(ProviderException e) {
    this.failNextAuthorize = e;
  }

  /** The sale reaches the Cielo, but the caller sees {@code e} (a timeout, a 503). */
  public void landNextAuthorizeThenFailWith(ProviderException e) {
    this.landThenFail = e;
  }

  /** The next 201 carries this status (NOT_FINISHED, PENDING …) instead of the digit's answer. */
  public void nextAuthorizeStatus(CardStatus status) {
    this.nextAuthorizeStatus = status;
  }

  /** Between the in-doubt answer and the query, the acquirer decides this. */
  public void nextFindByOrderStatus(CardStatus status) {
    this.nextFindByOrderStatus = status;
  }

  public void failNextFindByOrderWith(ProviderException e) {
    this.failNextFindByOrder = e;
  }

  /** The Cielo approved but returned no CardToken for a SaveCard request. */
  public void withholdNextToken() {
    this.withholdNextToken = true;
  }

  public void failNextCaptureWith(ProviderException e) {
    this.failNextCapture = e;
  }

  public void failNextRefundWith(ProviderException e) {
    this.failNextRefund = e;
  }

  /** What the Cielo shows now: a capture or a void done outside the gateway. */
  public void setStatus(String paymentId, CardStatus status) {
    CardAuthorization sale = sales.get(paymentId);
    Money captured = status == CardStatus.PAID ? sale.amount() : sale.capturedAmount();
    sales.put(paymentId, with(sale, status, captured));
  }

  public CardAuthorization sale(String paymentId) {
    return sales.get(paymentId);
  }

  public CardIssueRequest lastIssued() {
    return issued.getLast();
  }

  /** The calls naming {@code key} (a MerchantOrderId or a PaymentId), as "operation:key". */
  public List<String> callsFor(String key) {
    return calls.stream().filter(call -> call.endsWith(":" + key)).toList();
  }

  @Override
  public String id() {
    return "CIELO";
  }

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  @Override
  public void requireIssueCredentials(ProviderCredentials credentials) {}

  @Override
  public CardAuthorization issue(ProviderCredentials credentials, CardIssueRequest request) {
    calls.add("authorize:" + request.merchantOrderId());
    issued.add(request);

    ProviderException fail = failNextAuthorize;
    if (fail != null) {
      failNextAuthorize = null;
      throw fail;
    }

    CardAuthorization sale = newSale(request);
    sales.put(sale.paymentId(), sale);
    paymentIdByOrder.put(request.merchantOrderId(), sale.paymentId());

    ProviderException after = landThenFail;
    if (after != null) {
      landThenFail = null;
      throw after;
    }

    return sale;
  }

  @Override
  public Optional<CardAuthorization> find(ProviderCredentials credentials, String paymentId) {
    calls.add("find:" + paymentId);
    return Optional.ofNullable(sales.get(paymentId));
  }

  @Override
  public Optional<CardAuthorization> findByOrder(
      ProviderCredentials credentials, String merchantOrderId) {
    calls.add("findByOrder:" + merchantOrderId);

    ProviderException fail = failNextFindByOrder;
    if (fail != null) {
      failNextFindByOrder = null;
      throw fail;
    }

    String paymentId = paymentIdByOrder.get(merchantOrderId);
    CardStatus decided = nextFindByOrderStatus;
    if (paymentId != null && decided != null) {
      nextFindByOrderStatus = null;
      setStatus(paymentId, decided);
    }

    return Optional.ofNullable(paymentId == null ? null : sales.get(paymentId));
  }

  @Override
  public void cancel(ProviderCredentials credentials, String paymentId) {
    calls.add("void:" + paymentId);
    CardAuthorization sale = sales.get(paymentId);
    if (sale == null || sale.status() != CardStatus.AUTHORIZED) {
      throw new ProviderException(ProviderException.Code.CONFLICT, 200, "309", "void answered status");
    }
    sales.put(paymentId, with(sale, CardStatus.VOIDED, sale.capturedAmount()));
  }

  /** One capture per sale, like the Cielo: a second one is 308. */
  @Override
  public CardAuthorization capture(
      ProviderCredentials credentials, String paymentId, Optional<Money> amount) {
    calls.add("capture:" + paymentId);

    ProviderException fail = failNextCapture;
    if (fail != null) {
      failNextCapture = null;
      throw fail;
    }

    CardAuthorization sale = sales.get(paymentId);
    if (sale.status() != CardStatus.AUTHORIZED) {
      throw new ProviderException(
          ProviderException.Code.INVALID, 400, "308", "308 Transaction not available to capture");
    }

    CardAuthorization captured = with(sale, CardStatus.PAID, amount.orElse(sale.amount()));
    sales.put(paymentId, captured);
    return captured;
  }

  @Override
  public CardRefundResult refund(
      ProviderCredentials credentials, String paymentId, Optional<Money> amount) {
    calls.add("refund:" + paymentId);

    ProviderException fail = failNextRefund;
    if (fail != null) {
      failNextRefund = null;
      throw fail;
    }

    CardAuthorization sale = sales.get(paymentId);
    sales.put(paymentId, with(sale, CardStatus.REFUNDED, sale.capturedAmount()));
    return new CardRefundResult(CardStatus.REFUNDED, amount.orElse(null), "9", "Operation Successful");
  }

  @Override
  public StoredCard tokenize(ProviderCredentials credentials, CardData card, String customerName) {
    return new StoredCard(UUID.randomUUID().toString(), card.brand(), card.last4(), card.expiry().value());
  }

  @Override
  public CardNotification parseWebhook(byte[] body) {
    String text = new String(body, StandardCharsets.UTF_8);
    Matcher paymentId = PAYMENT_ID.matcher(text);
    Matcher changeType = CHANGE_TYPE.matcher(text);
    if (!paymentId.find() || !changeType.find()) {
      throw new IllegalArgumentException("notification without PaymentId");
    }

    int change = Integer.parseInt(changeType.group(1));
    CardNotificationKind kind =
        switch (change) {
          case 1 -> CardNotificationKind.STATUS_CHANGED;
          case 25 -> CardNotificationKind.PARTIAL_REFUND;
          case 5 -> CardNotificationKind.VOID_DENIED;
          case 8 -> CardNotificationKind.FRAUD_ALERT;
          default -> CardNotificationKind.IGNORED;
        };
    return new CardNotification(paymentId.group(1), kind, change);
  }

  private CardAuthorization newSale(CardIssueRequest request) {
    CardStatus status = nextAuthorizeStatus;
    nextAuthorizeStatus = null;
    String returnCode = "6";

    if (status == null) {
      String lastDigit = request.source() instanceof CardData card ? card.last4().substring(3) : "1";
      returnCode =
          switch (lastDigit) {
            case "2" -> "51";
            case "3" -> "57";
            default -> request.capture() ? "6" : "4";
          };
      status =
          returnCode.equals("51") || returnCode.equals("57")
              ? CardStatus.DENIED
              : request.capture() ? CardStatus.PAID : CardStatus.AUTHORIZED;
    }

    boolean approved = status == CardStatus.PAID || status == CardStatus.AUTHORIZED;
    boolean token = approved && request.saveCard() && !withholdNextToken;
    withholdNextToken = false;
    CardDeclineCode decline =
        status == CardStatus.DENIED
            ? (returnCode.equals("51") ? CardDeclineCode.INSUFFICIENT_FUNDS : CardDeclineCode.DO_NOT_HONOR)
            : null;

    return new CardAuthorization(
        UUID.randomUUID().toString(),
        status,
        returnCode,
        "sandbox-like",
        decline,
        "tid-" + request.merchantOrderId(),
        approved ? "123456" : null,
        "654321",
        request.amount(),
        status == CardStatus.PAID ? request.amount() : null,
        request.source().brand(),
        request.source() instanceof CardData card ? card.last4() : null,
        token ? Optional.of(UUID.randomUUID().toString()) : Optional.empty(),
        clock.instant(),
        status == CardStatus.PAID ? Optional.of(clock.instant()) : Optional.empty());
  }

  private CardAuthorization with(CardAuthorization sale, CardStatus status, Money captured) {
    return new CardAuthorization(
        sale.paymentId(),
        status,
        sale.returnCode(),
        sale.returnMessage(),
        sale.declineCode(),
        sale.tid(),
        sale.authorizationCode(),
        sale.proofOfSale(),
        sale.amount(),
        captured,
        sale.brand(),
        sale.last4(),
        sale.cardToken(),
        sale.receivedAt(),
        status == CardStatus.PAID ? Optional.of(clock.instant()) : sale.capturedAt());
  }
}
```

Em `ServiceTestConfig`:
```java
  @Bean
  RecordingCardProvider recordingCardProvider(MutableClock clock) {
    return new RecordingCardProvider(clock);
  }
```

Em `InMemoryCredentialLookup.find`, antes do `return Optional.empty()`:
```java
    // Every test merchant also has a Cielo TEST credential, in the shape CieloCredentials parses.
    if ("CIELO".equals(provider) && env == ProviderEnvironment.TEST) {
      return Optional.of(
          new ProviderCredentials(
              ("{\"merchant_id\":\"11111111-2222-3333-4444-555555555555\",\"merchant_key\":\""
                      + "A".repeat(40)
                      + "\"}")
                  .getBytes(StandardCharsets.UTF_8),
              env));
    }
```
(e o javadoc da classe passa a "every merchant has an Itau and a Cielo TEST credential and no LIVE one".)

Em `ServiceIntegrationTestBase` (imports `com.gateway.kernel.provider.card.CardData`, `com.gateway.payments.payment.create.{CardChoice,CardCustomerData,CardDataFactory,CreateCardPayment}`, `java.time.YearMonth`):
```java
  @Autowired protected RecordingCardProvider cards;

  protected static final YearMonth CARD_TEST_MONTH = YearMonth.of(2026, 9);

  protected static CardData card(String number) {
    return CardDataFactory.from(number, "JOAO DA SILVA", "12/2030", "123", null, CARD_TEST_MONTH);
  }

  protected CreateCardPayment cardCommand(long cents, CardChoice choice, Integer installments, Boolean capture) {
    return new CreateCardPayment(
        merchant,
        ProviderEnvironment.TEST,
        Money.brl(cents),
        "order-1",
        "Pedido 1",
        choice,
        installments,
        capture,
        "LOJA42",
        new CardCustomerData("Joao da Silva", "12345678901", "joao@example.com"));
  }

  /** A captured-by-default card payment; the number's last digit steers RecordingCardProvider. */
  protected Payment newCard(long cents, String number) {
    return paymentService.create(
        cardCommand(cents, new CardChoice.NewCard(card(number), false), null, null));
  }
```

- [ ] **Step 3: O teste do fluxo**

`gateway-payments/src/test/java/com/gateway/payments/payment/create/CardPaymentFlowIntegrationTest.java`:
```java
package com.gateway.payments.payment.create;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.security.Secret;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.StuckCreatedSweep;
import com.gateway.payments.payment.card.CardDeclinedException;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec 2026-09-28 §6: every outcome of an authorization, and the card-data rule on the way. */
class CardPaymentFlowIntegrationTest extends ServiceIntegrationTestBase {
  static final String APPROVES = "4024007153763171";
  static final String INSUFFICIENT_FUNDS = "4024007153760052";

  @Autowired StuckCreatedSweep sweep;

  int paymentRows() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM payments.payments WHERE merchant_id = ?", Integer.class, merchant.value());
  }

  String details(Payment payment) {
    return jdbc.queryForObject(
        "SELECT details::text FROM payments.payments WHERE id = ?", String.class, payment.id());
  }

  @Test
  void anApprovedSaleWithCaptureIsCompletedAndStoresNoCardData() {
    Payment payment = newCard(12990, APPROVES);

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.provider()).isEqualTo("CIELO");
    assertThat(payment.paidAmount()).isEqualTo(Money.brl(12990));
    assertThat(payment.card().paymentId()).isNotNull();
    assertThat(payment.card().last4()).isEqualTo("3171");
    assertThat(outboxTypes(payment.id())).containsExactly("payment.completed");
    assertThat(cards.lastIssued().merchantOrderId()).isEqualTo(payment.id());
    assertThat(details(payment)).doesNotContain(APPROVES).doesNotContain("\"123\"");
  }

  @Test
  void captureFalseStopsAtAuthorized() {
    Payment payment =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), false), 3, false));

    assertThat(payment.status()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(payment.paidAmount()).isNull();
    assertThat(payment.card().installments()).isEqualTo(3);
    assertThat(cards.lastIssued().capture()).isFalse();
    assertThat(outboxTypes(payment.id())).containsExactly("payment.authorized");
  }

  /** A decline is a result (spec §11): FAILED with our code, a 402 to the merchant, no retry. */
  @Test
  void aDeclineFailsWithTheDeclineCode() {
    assertThatThrownBy(() -> newCard(12990, INSUFFICIENT_FUNDS))
        .isInstanceOf(CardDeclinedException.class)
        .satisfies(
            thrown -> {
              CardDeclinedException declined = (CardDeclinedException) thrown;
              assertThat(declined.code()).isEqualTo("CARD_DECLINED");
              assertThat(declined.declineCode()).isEqualTo("INSUFFICIENT_FUNDS");
              Payment failed = paymentQueries.get(merchant, declined.paymentId());
              assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
              assertThat(failed.card().declineCode()).isEqualTo("INSUFFICIENT_FUNDS");
              assertThat(outboxTypes(failed.id())).containsExactly("payment.failed");
              assertThat(cards.callsFor(failed.id())).containsExactly("authorize:" + failed.id());
            });
  }

  @Test
  void aTimeoutThatLandedIsAdoptedFromTheQuery() {
    cards.landNextAuthorizeThenFailWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo POST timed out", null));

    Payment payment = newCard(12990, APPROVES);

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(cards.callsFor(payment.id())).contains("findByOrder:" + payment.id());
  }

  /** Spec §6.4: no transaction at the Cielo after a timeout is FAILED, unlike the boleto. */
  @Test
  void aTimeoutWithNothingAtTheCieloFails() {
    cards.failNextAuthorizeWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo POST timed out", null));

    assertThatThrownBy(() -> newCard(12990, APPROVES))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_TIMEOUT");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payments.payments WHERE merchant_id = ?", String.class, merchant.value()))
        .isEqualTo("FAILED");
  }

  /** Review Focus: a 201 that is not an answer (Status 12, 0, 14) is asked again, never adopted. */
  @Test
  void anInDoubtAnswerIsAskedAgainAndAdoptedWhenTheCieloDecided() {
    cards.nextAuthorizeStatus(CardStatus.PENDING);
    cards.nextFindByOrderStatus(CardStatus.PAID);

    Payment payment = newCard(12990, APPROVES);

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(cards.callsFor(payment.id())).containsExactly("authorize:" + payment.id(), "findByOrder:" + payment.id());
  }

  @Test
  void anInDoubtAnswerThatStaysInDoubtFails() {
    cards.nextAuthorizeStatus(CardStatus.NOT_FINISHED);

    assertThatThrownBy(() -> newCard(12990, APPROVES))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_TIMEOUT");
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payments.payments WHERE merchant_id = ?", String.class, merchant.value()))
        .isEqualTo("FAILED");
  }

  /**
   * The query itself failing proves nothing about the sale: the payment stays CREATED and the
   * stuck-CREATED sweep asks again after stuckCreatedAfter (plan decision in Task 8).
   */
  @Test
  void aFailedQueryLeavesItCreatedForTheSweeper() {
    cards.landNextAuthorizeThenFailWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo POST timed out", null));
    cards.failNextFindByOrderWith(
        new ProviderException(ProviderException.Code.UNAVAILABLE, "Cielo GET failed", null));

    assertThatThrownBy(() -> newCard(12990, APPROVES))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_TIMEOUT");
    String id =
        jdbc.queryForObject("SELECT id FROM payments.payments WHERE merchant_id = ?", String.class, merchant.value());
    assertThat(paymentQueries.get(merchant, id).status()).isEqualTo(PaymentStatus.CREATED);

    clock.advance(Duration.ofMinutes(11));
    sweep.sweepStuckCreated(clock.instant());

    assertThat(paymentQueries.get(merchant, id).status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(outboxTypes(id)).containsExactly("payment.completed");
  }

  @Test
  void saveCardWithATokenStoresTheCardInTheSameTransaction() {
    Payment payment =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), true), null, null));

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.card().cardId()).isNotNull();
    assertThat(cards.lastIssued().saveCard()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT last4 FROM payments.cards WHERE id = ? AND merchant_id = ?",
                String.class,
                payment.card().cardId(),
                merchant.value()))
        .isEqualTo("3171");
  }

  /** Spec §6.5: never fail an approved payment because the token did not come back. */
  @Test
  void saveCardWithoutATokenStillCompletesWithNoCardId() {
    cards.withholdNextToken();

    Payment payment =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), true), null, null));

    assertThat(payment.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payment.card().cardId()).isNull();
    assertThat(
            jdbc.queryForObject("SELECT count(*) FROM payments.cards WHERE merchant_id = ?", Integer.class, merchant.value()))
        .isZero();
  }

  /** Spec §6.6: a stored card goes as a token, marked Used. */
  @Test
  void aSavedCardIsChargedByItsToken() {
    Payment first =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), true), null, null));

    Payment second =
        paymentService.create(
            cardCommand(
                5000,
                new CardChoice.SavedCardChoice(first.card().cardId(), Secret.of("123")),
                null,
                null));

    assertThat(second.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(second.card().cardId()).isEqualTo(first.card().cardId());
    assertThat(second.card().last4()).isEqualTo("3171");
    CardToken token = (CardToken) cards.lastIssued().source();
    assertThat(token.usage()).isEqualTo(CardOnFileUsage.USED);
    assertThat(cards.lastIssued().saveCard()).isFalse();
  }

  /** Spec §4: another merchant's card_id is CARD_NOT_FOUND, and nothing is written. */
  @Test
  void anotherMerchantsCardIdIsNotFoundBeforeARowExists() {
    MerchantId owner = merchant;
    Payment ownersPayment =
        paymentService.create(
            cardCommand(12990, new CardChoice.NewCard(card(APPROVES), true), null, null));
    merchant = MerchantId.next();

    assertThatThrownBy(
            () ->
                paymentService.create(
                    cardCommand(
                        5000,
                        new CardChoice.SavedCardChoice(ownersPayment.card().cardId(), Secret.of("123")),
                        null,
                        null)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("CARD_NOT_FOUND");
    assertThat(paymentRows()).isZero();
    merchant = owner;
  }

  /** Review Focus 4. */
  @Test
  void installmentsBelowTheMinimumAreRefusedBeforeARowExists() {
    assertThatThrownBy(
            () ->
                paymentService.create(
                    cardCommand(1000, new CardChoice.NewCard(card(APPROVES), false), 3, null)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("INVALID_INSTALLMENTS");
    assertThat(paymentRows()).isZero();

    Payment inThree =
        paymentService.create(
            cardCommand(1600, new CardChoice.NewCard(card(APPROVES), false), 3, null));
    assertThat(inThree.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(cards.lastIssued().installments().count()).isEqualTo(3);
  }

  @Test
  void aCustomerWithoutANameIsRefusedBeforeARowExists() {
    CreateCardPayment noName =
        new CreateCardPayment(
            merchant,
            com.gateway.kernel.provider.ProviderEnvironment.TEST,
            Money.brl(100),
            null,
            null,
            new CardChoice.NewCard(card(APPROVES), false),
            null,
            null,
            null,
            new CardCustomerData(" ", null, null));

    assertThatThrownBy(() -> paymentService.create(noName))
        .isInstanceOf(DomainException.class)
        .satisfies(
            thrown -> {
              assertThat(((DomainException) thrown).code()).isEqualTo("CUSTOMER_REQUIRED");
              assertThat(thrown.getMessage()).isEqualTo("customer.name is required");
            });
    assertThat(paymentRows()).isZero();
  }

  @Test
  void aBadSoftDescriptorIsRefusedBeforeARowExists() {
    CreateCardPayment longDescriptor =
        new CreateCardPayment(
            merchant,
            com.gateway.kernel.provider.ProviderEnvironment.TEST,
            Money.brl(100),
            null,
            null,
            new CardChoice.NewCard(card(APPROVES), false),
            null,
            null,
            "LOJA-42 PEDIDO",
            new CardCustomerData("Joao", null, null));

    assertThatThrownBy(() -> paymentService.create(longDescriptor))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("INVALID_SOFT_DESCRIPTOR");
    assertThat(paymentRows()).isZero();
  }

  @Test
  void theEventLogOfACardPaymentCarriesNoCardData() {
    Payment payment = newCard(12990, APPROVES);

    List<String> payloads =
        jdbc.queryForList(
            "SELECT payload::text FROM payments.payment_events WHERE payment_id = ?", String.class, payment.id());

    assertThat(payloads).isNotEmpty().allSatisfy(payload -> assertThat(payload).doesNotContain(APPROVES));
  }
}
```

- [ ] **Step 4: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -Dtest='CardDataFactoryTest,InstallmentPlanTest,CardPaymentFlowIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `CreateCardPayment`, `CardChoice`, `CardDataFactory`, `InstallmentPlan` não existem.

- [ ] **Step 5: Comando e fábricas**

`gateway-payments/src/main/java/com/gateway/payments/payment/create/CardChoice.java`:
```java
package com.gateway.payments.payment.create;

import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.security.Secret;

/**
 * {@code card} or {@code card_id}, never both (spec §9). Sealed so the flow's switch names the two
 * and nothing else. {@code save} is the merchant's {@code save_card}.
 */
public sealed interface CardChoice permits CardChoice.NewCard, CardChoice.SavedCardChoice {

  record NewCard(CardData card, boolean save) implements CardChoice {}

  /** The CVV the payer typed for this charge: the Cielo requires it with a token (plan D3). */
  record SavedCardChoice(String cardId, Secret securityCode) implements CardChoice {}
}
```

`gateway-payments/src/main/java/com/gateway/payments/payment/create/CardCustomerData.java`:
```java
package com.gateway.payments.payment.create;

/** The customer exactly as the merchant sent it; {@link CardCustomerFactory} is the door. */
public record CardCustomerData(String name, String document, String email) {}
```

`gateway-payments/src/main/java/com/gateway/payments/payment/create/CreateCardPayment.java`:
```java
package com.gateway.payments.payment.create;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;

/**
 * A credit card charge (spec 2026-09-28 §6). The card arrives already a {@code CardData} — built at
 * the edge by {@link CardDataFactory}, where the 422 names {@code card.number} — or as a saved
 * {@code card_id} the flow resolves. {@code installments} null is 1, {@code capture} null is true.
 */
public record CreateCardPayment(
    MerchantId merchantId,
    ProviderEnvironment environment,
    Money amount,
    String reference,
    String description,
    CardChoice card,
    Integer installments,
    Boolean capture,
    String softDescriptor,
    CardCustomerData customer)
    implements CreatePaymentCommand {

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  public boolean captures() {
    return capture == null || capture;
  }
}
```

Em `CreatePaymentCommand`: `permits CreatePixPayment, CreateBolecodePayment, CreateCardPayment`.

`gateway-payments/src/main/java/com/gateway/payments/payment/create/CardDataFactory.java`:
```java
package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.InvalidCardValue;
import com.gateway.kernel.security.Secret;
import java.time.YearMonth;
import java.util.regex.Pattern;

/**
 * The raw card fields → {@link CardData}, with the 422 the merchant reads: CARD_INVALID naming the
 * field as the API spells it ({@code card.number must pass the Luhn check}). Like PayerFactory, it
 * owns only the field path and the code; the rules are the kernel's value objects.
 */
public final class CardDataFactory {
  private static final String CODE = "CARD_INVALID";
  private static final Pattern THREE_OR_FOUR_DIGITS = Pattern.compile("\\d{3,4}");

  private CardDataFactory() {}

  public static CardData from(
      String number,
      String holder,
      String expiry,
      String cvv,
      String brand,
      YearMonth currentMonth) {
    try {
      return CardData.of(number, holder, expiry, cvv, brand, currentMonth);
    } catch (InvalidCardValue e) {
      throw new DomainException(CODE, "card." + e.field() + " " + e.reason());
    }
  }

  /** With a card_id the brand is the stored card's, so only the shape is checked here. */
  public static Secret securityCodeForSavedCard(String cvv) {
    if (cvv == null || cvv.isBlank()) {
      throw new DomainException(CODE, "cvv is required with card_id");
    }
    if (!THREE_OR_FOUR_DIGITS.matcher(cvv.trim()).matches()) {
      throw new DomainException(CODE, "cvv must be 3 or 4 digits");
    }

    return Secret.of(cvv.trim());
  }
}
```

`gateway-payments/src/main/java/com/gateway/payments/payment/create/InstallmentPlan.java`:
```java
package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.InvalidValue;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.card.Installments;

/**
 * The installment count against the amount. The per-installment minimum is the Cielo's rule for
 * ByMerchant, R$ 5,00 (reference/criar-pagamento-credito, Interest; plan D5). Checked as
 * {@code amount >= 500 × n}: the acquirer splits a non-exact division itself, so 1600 in 3 is fine.
 */
public final class InstallmentPlan {
  private static final String CODE = "INVALID_INSTALLMENTS";
  private static final long MINIMUM_INSTALLMENT_CENTS = 500;

  private InstallmentPlan() {}

  public static Installments of(Money amount, Integer installments) {
    Installments count;
    try {
      count = Installments.of(installments);
    } catch (InvalidValue e) {
      throw new DomainException(CODE, "installments " + e.reason());
    }

    if (count.count() > 1 && amount.cents() < MINIMUM_INSTALLMENT_CENTS * count.count()) {
      throw new DomainException(
          CODE,
          "each installment must be at least 500 cents ("
              + amount.cents()
              + " in "
              + count.count()
              + ")");
    }

    return count;
  }
}
```

`gateway-payments/src/main/java/com/gateway/payments/payment/create/CardCustomerFactory.java`:
```java
package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.InvalidValue;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.card.CardCustomer;

/**
 * The card customer: the name is required (the Cielo refuses a sale without Customer.Name, code
 * 105), the document and e-mail are optional. Same code and field spelling as PayerFactory.
 */
public final class CardCustomerFactory {
  private static final String CODE = "CUSTOMER_REQUIRED";

  private CardCustomerFactory() {}

  public static CardCustomer from(CardCustomerData data) {
    if (data == null) {
      throw new DomainException(CODE, "customer.name is required");
    }

    PersonName name;
    try {
      name = PersonName.of(data.name());
    } catch (InvalidValue e) {
      throw new DomainException(CODE, "customer.name " + e.reason());
    }

    return new CardCustomer(name, documentOf(data), blankToNull(data.email()));
  }

  private static Document documentOf(CardCustomerData data) {
    if (data.document() == null || data.document().isBlank()) {
      return null;
    }

    try {
      return Document.of(data.document());
    } catch (InvalidValue e) {
      throw new DomainException(CODE, "customer.document " + e.reason());
    }
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text.trim();
  }
}
```

Em `PaymentDraftFactory` (import `com.gateway.payments.payment.card.CardDetails`):
```java
  /** No card data reaches the row: only what CardDetails carries (spec §6.2). */
  public Payment card(
      CreateCardPayment command,
      String providerId,
      CardDetails requested,
      String customerDocumentHash) {
    return unitOfWork.inTransaction(
        () -> {
          Payment draft =
              Payment.createCard(
                  command.merchantId(),
                  command.environment(),
                  providerId,
                  command.amount(),
                  command.reference(),
                  command.description(),
                  customerDocumentHash,
                  requested,
                  clock);

          return payments.save(draft, List.of(draft.createdEvent()));
        });
  }
```

- [ ] **Step 6: Adoção, negativa e recuperação**

`gateway-payments/src/main/java/com/gateway/payments/payment/card/CardToSave.java`:
```java
package com.gateway.payments.payment.card;

import java.time.YearMonth;

/**
 * What a saved card keeps besides the acquirer's token, which only the answer brings: the face of
 * the card from the request. Null when the merchant did not ask for save_card.
 */
public record CardToSave(String holder, YearMonth expiry, String customerDocumentHash) {}
```

`gateway-payments/src/main/java/com/gateway/payments/payment/card/CardDeclinedException.java`:
```java
package com.gateway.payments.payment.card;

import com.gateway.kernel.errors.DomainException;

/**
 * The issuer said no. A 402 at the edge with {@code decline_code} (spec §9). The message is fixed:
 * the issuer's ReturnMessage never reaches a merchant, and a fixed text is also what the
 * idempotency store may replay for 24 h.
 */
public class CardDeclinedException extends DomainException {
  private final String paymentId;
  private final String declineCode;

  public CardDeclinedException(String paymentId, String declineCode) {
    super("CARD_DECLINED", "The card was declined.");
    this.paymentId = paymentId;
    this.declineCode = declineCode;
  }

  public String paymentId() {
    return paymentId;
  }

  public String declineCode() {
    return declineCode;
  }
}
```

`gateway-payments/src/main/java/com/gateway/payments/payment/card/CardAdoption.java`:
```java
package com.gateway.payments.payment.card;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardDeclineCode;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.card.SavedCard;
import com.gateway.payments.card.SavedCards;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvent;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * A decided authorization → the payment's state, its outbox row and, with save_card, the stored
 * card — one transaction (spec §6.4–6.5). The one place that switches on the acquirer's status.
 *
 * <p>Idempotent, like PendingAdoption: a payment no longer CREATED is returned as it is, because
 * the stuck-CREATED sweep and a slow create may both adopt the same sale.
 */
public class CardAdoption {
  private final PaymentRepository payments;
  private final PaymentEvents events;
  private final SavedCards savedCards;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public CardAdoption(
      PaymentRepository payments,
      PaymentEvents events,
      SavedCards savedCards,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.payments = payments;
    this.events = events;
    this.savedCards = savedCards;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  /** {@code authorization} must be decided ({@code !status().inDoubt()}); the callers ask first. */
  public Payment adopt(
      String paymentId, CardAuthorization authorization, CardToSave toSave, EventSource by) {
    if (authorization.status().inDoubt()) {
      throw new IllegalStateException(
          "an in-doubt authorization (" + authorization.status() + ") is recovered, never adopted");
    }

    return unitOfWork.inTransaction(
        () -> {
          Payment payment = payments.findById(paymentId).orElseThrow();
          if (payment.status() != PaymentStatus.CREATED) {
            return payment;
          }

          CardDetails details = payment.card().withAuthorization(authorization);
          String type;
          PaymentEvent event;

          switch (authorization.status()) {
            case AUTHORIZED -> {
              event = payment.markAuthorized(saveCard(payment, details, authorization, toSave), by);
              type = "payment.authorized";
            }
            case PAID -> {
              event =
                  payment.markCompletedByCard(
                      saveCard(payment, details, authorization, toSave),
                      capturedAmount(payment, authorization),
                      capturedAt(authorization),
                      by);
              type = "payment.completed";
            }
            default -> {
              // DENIED and ABORTED carry the issuer's answer; VOIDED/REFUNDED on a fresh sale mean
              // the acquirer undid it (the Cancellation Guarantee), which is no approval either.
              CardDeclineCode decline =
                  authorization.declineCode() == null
                      ? CardDeclineCode.GENERIC
                      : authorization.declineCode();
              event = payment.markDeclined(details.withDecline(decline.name()), by);
              type = "payment.failed";
            }
          }

          Payment saved = payments.save(payment, List.of(event));
          events.emit(saved.merchantId(), type, saved);
          return saved;
        });
  }

  /**
   * Spec §6.5: the token becomes a saved card in this same transaction; no token means no card and
   * the payment goes on — an approved sale is never failed because the token did not come back.
   */
  private CardDetails saveCard(
      Payment payment, CardDetails details, CardAuthorization authorization, CardToSave toSave) {
    if (toSave == null || authorization.cardToken().isEmpty()) {
      return details;
    }

    SavedCard card =
        savedCards.save(
            payment.merchantId(),
            payment.provider(),
            payment.environment(),
            authorization.cardToken().get(),
            authorization.brand() == null
                ? com.gateway.kernel.provider.card.CardBrand.valueOf(details.brand())
                : authorization.brand(),
            details.last4(),
            toSave.expiry(),
            toSave.holder(),
            toSave.customerDocumentHash());

    return details.withCardId(card.id());
  }

  private static Money capturedAmount(Payment payment, CardAuthorization authorization) {
    return authorization.capturedAmount() == null ? payment.amount() : authorization.capturedAmount();
  }

  private Instant capturedAt(CardAuthorization authorization) {
    return authorization.capturedAt().orElse(clock.instant());
  }
}
```

`gateway-payments/src/main/java/com/gateway/payments/payment/create/CardAuthorizationRecovery.java`:
```java
package com.gateway.payments.payment.create;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.card.CardAdoption;
import com.gateway.payments.payment.card.CardToSave;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An authorization whose answer was lost, or came back in doubt (Status 0, 12, 14). The
 * MerchantOrderId is ours — the payment id — so the Cielo can be asked (spec §6.4):
 *
 * <ul>
 *   <li>found and decided: adopted, as if the answer had arrived;
 *   <li>not found, or still in doubt: FAILED. Unlike the boleto there is no "in progress" at the
 *       Cielo worth waiting for, and its Cancellation Guarantee undoes what stayed NotFinished;
 *   <li>the query itself failed: nothing is known, so the payment stays CREATED and the
 *       stuck-CREATED sweep asks again after stuckCreatedAfter. Failing it here would say "not
 *       charged" about a sale that may hold the payer's limit.
 * </ul>
 */
public class CardAuthorizationRecovery {
  private static final Logger log = LoggerFactory.getLogger(CardAuthorizationRecovery.class);

  private final ProviderGateway providers;
  private final CardAdoption adoption;
  private final CreateFailures failures;

  public CardAuthorizationRecovery(
      ProviderGateway providers, CardAdoption adoption, CreateFailures failures) {
    this.providers = providers;
    this.adoption = adoption;
    this.failures = failures;
  }

  public Payment recover(
      Payment payment,
      ResolvedProvider<CardMethodProvider> resolved,
      ProviderException cause,
      CardToSave toSave) {
    String code = ProviderFailures.timeoutCodeOf(cause);

    Optional<CardAuthorization> found;
    try {
      found = findByOrder(payment, resolved);
    } catch (ProviderException again) {
      throw ProviderErrors.toDomain(code, again, log, "findCardByOrder", payment.id());
    }

    if (found.isEmpty() || found.get().status().inDoubt()) {
      throw failures.fail(payment.id(), code, cause, null);
    }

    return adoption.adopt(payment.id(), found.get(), toSave, EventSource.API);
  }

  /**
   * A CREATED card payment older than stuckCreatedAfter. A failed query propagates: the sweep logs
   * it and asks again next run. The card cannot be saved from here — its holder and expiry lived
   * only in the lost request — so a token in the sale is not kept.
   */
  public void sweep(Payment payment) {
    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(payment.merchantId(), payment.environment(), payment.provider());

    Optional<CardAuthorization> found = findByOrder(payment, resolved);

    if (found.isEmpty() || found.get().status().inDoubt()) {
      failures.markFailed(payment.id(), "PROVIDER_TIMEOUT", EventSource.SYSTEM);
      return;
    }

    adoption.adopt(payment.id(), found.get(), null, EventSource.SYSTEM);
  }

  private Optional<CardAuthorization> findByOrder(
      Payment payment, ResolvedProvider<CardMethodProvider> resolved) {
    return providers.call(
        payment.id(),
        "findCardByOrder",
        resolved,
        target -> target.provider().findByOrder(target.credentials(), payment.id()));
  }
}
```

- [ ] **Step 7: O flow**

Substitua `CardPaymentFlow.java`:
```java
package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.InvalidValue;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardCustomer;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardIssueRequest;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardSource;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.provider.card.Installments;
import com.gateway.kernel.provider.card.SoftDescriptor;
import com.gateway.payments.card.SavedCard;
import com.gateway.payments.card.SavedCards;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.card.CardAdoption;
import com.gateway.payments.payment.card.CardDeclinedException;
import com.gateway.payments.payment.card.CardDetails;
import com.gateway.payments.payment.card.CardToSave;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.Set;

/**
 * Creating a card payment (spec 2026-09-28 §6). Everything that can be refused is refused before a
 * row exists: the credential, the installments against the amount, the soft descriptor, the
 * customer's name and a card_id that is not this merchant's. Then CREATED with no card data, the
 * authorization outside any transaction with MerchantOrderId = the payment id, and one adoption.
 *
 * <p>A decline is a result, not an exception from the acquirer (spec §11): the payment is FAILED
 * with its decline code and the merchant gets CARD_DECLINED — thrown only after the FAILED row and
 * its outbox row have committed.
 */
public class CardPaymentFlow implements PaymentFlow {
  /** The one acquirer the card method goes to until per-merchant routing exists (spec §2). */
  public static final String PROVIDER = "CIELO";

  /** A timeout, or a 503/504 in front of the Cielo, says nothing about whether the sale exists. */
  private static final Set<ProviderException.Code> MAY_HAVE_LANDED =
      Set.of(ProviderException.Code.TIMEOUT, ProviderException.Code.UNAVAILABLE);

  private final ProviderGateway providers;
  private final PaymentDraftFactory drafts;
  private final CardAdoption adoption;
  private final CardAuthorizationRecovery recovery;
  private final SavedCards savedCards;
  private final CreateFailures failures;

  public CardPaymentFlow(
      ProviderGateway providers,
      PaymentDraftFactory drafts,
      CardAdoption adoption,
      CardAuthorizationRecovery recovery,
      SavedCards savedCards,
      CreateFailures failures) {
    this.providers = providers;
    this.drafts = drafts;
    this.adoption = adoption;
    this.recovery = recovery;
    this.savedCards = savedCards;
    this.failures = failures;
  }

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  @Override
  public Payment create(CreatePaymentCommand command) {
    CreateCardPayment cardPayment = (CreateCardPayment) command;

    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(cardPayment.merchantId(), cardPayment.environment(), PROVIDER);
    requireIssueCredentials(resolved, cardPayment);

    Installments installments =
        InstallmentPlan.of(cardPayment.amount(), cardPayment.installments());
    SoftDescriptor softDescriptor = softDescriptorOf(cardPayment);
    CardCustomer customer = CardCustomerFactory.from(cardPayment.customer());
    String documentHash =
        cardPayment.customer() == null
            ? null
            : CustomerDocumentHash.of(cardPayment.customer().document());

    ChosenCard chosen = choose(cardPayment);

    Payment payment =
        drafts.card(
            cardPayment,
            PROVIDER,
            CardDetails.requested(
                installments.count(), chosen.source().brand().name(), chosen.last4(), chosen.cardId()),
            documentHash);

    CardToSave toSave = toSaveOf(cardPayment, documentHash);
    CardIssueRequest request =
        new CardIssueRequest(
            payment.id(),
            cardPayment.amount(),
            installments,
            cardPayment.captures(),
            toSave != null,
            softDescriptor,
            chosen.source(),
            customer);

    return declinedOrAdopted(authorize(payment, resolved, request, toSave));
  }

  private Payment authorize(
      Payment payment,
      ResolvedProvider<CardMethodProvider> resolved,
      CardIssueRequest request,
      CardToSave toSave) {
    CardAuthorization authorization;
    try {
      authorization =
          providers.call(
              payment.id(),
              "authorizeCard",
              resolved,
              target -> target.provider().issue(target.credentials(), request));
    } catch (ProviderException failure) {
      return recover(payment, resolved, failure, toSave);
    }

    if (authorization.status().inDoubt()) {
      // A 201 that is not an answer yet (Status 0, 12, 14): the same question as a lost answer.
      ProviderException inDoubt =
          new ProviderException(
              ProviderException.Code.TIMEOUT,
              201,
              authorization.returnCode(),
              "authorization answered " + authorization.status());
      return recovery.recover(payment, resolved, inDoubt, toSave);
    }

    return adoption.adopt(payment.id(), authorization, toSave, EventSource.API);
  }

  private Payment recover(
      Payment payment,
      ResolvedProvider<CardMethodProvider> resolved,
      ProviderException failure,
      CardToSave toSave) {
    ProviderFailures.Outcome outcome = ProviderFailures.classify(failure, MAY_HAVE_LANDED);

    if (outcome != ProviderFailures.Outcome.MAY_HAVE_LANDED) {
      String code =
          outcome == ProviderFailures.Outcome.DECLINED
              ? "PROVIDER_DECLINED"
              : "PROVIDER_UNAVAILABLE";
      throw failures.fail(payment.id(), code, failure, null);
    }

    return recovery.recover(payment, resolved, failure, toSave);
  }

  /** Thrown after the FAILED row committed, so a retry with the same key replays the same 402. */
  private static Payment declinedOrAdopted(Payment payment) {
    if (payment.status() == PaymentStatus.FAILED && payment.card().declineCode() != null) {
      throw new CardDeclinedException(payment.id(), payment.card().declineCode());
    }

    return payment;
  }

  /** The source the acquirer charges, and the face of the card the payment shows. */
  private record ChosenCard(CardSource source, String last4, String cardId) {}

  private ChosenCard choose(CreateCardPayment cardPayment) {
    return switch (cardPayment.card()) {
      case CardChoice.NewCard newCard ->
          new ChosenCard(newCard.card(), newCard.card().last4(), null);
      case CardChoice.SavedCardChoice saved -> {
        // tokenFor first: CARD_NOT_FOUND (422), not the card resource's NOT_FOUND (404).
        CardToken token =
            savedCards.tokenFor(
                cardPayment.merchantId(),
                cardPayment.environment(),
                saved.cardId(),
                saved.securityCode());
        SavedCard card = savedCards.get(cardPayment.merchantId(), saved.cardId());
        yield new ChosenCard(token, card.last4(), card.id());
      }
    };
  }

  private static CardToSave toSaveOf(CreateCardPayment cardPayment, String documentHash) {
    if (!(cardPayment.card() instanceof CardChoice.NewCard newCard) || !newCard.save()) {
      return null;
    }

    CardData card = newCard.card();
    return new CardToSave(card.holder().value(), card.expiry().value(), documentHash);
  }

  private static SoftDescriptor softDescriptorOf(CreateCardPayment cardPayment) {
    try {
      return SoftDescriptor.ofNullable(cardPayment.softDescriptor());
    } catch (InvalidValue e) {
      throw new DomainException("INVALID_SOFT_DESCRIPTOR", "soft_descriptor " + e.reason());
    }
  }

  private static void requireIssueCredentials(
      ResolvedProvider<CardMethodProvider> resolved, CreateCardPayment cardPayment) {
    try {
      resolved.provider().requireIssueCredentials(resolved.credentials());
    } catch (ProviderException e) {
      if (e.code() == ProviderException.Code.CREDENTIALS_INCOMPLETE) {
        throw new DomainException(
            "PROVIDER_CREDENTIALS_MISSING",
            "the "
                + PROVIDER
                + " "
                + cardPayment.environment()
                + " credential is missing "
                + e.providerType());
      }
      throw e;
    }
  }
}
```

- [ ] **Step 8: O sweep aprende o cartão, e o wiring**

Em `StuckCreatedSweep`: novo campo e parâmetro do construtor `CardAuthorizationRecovery cardRecovery` (último), e no começo do corpo do `try`, antes do `if (payment.method() == PaymentMethod.BOLECODE)`:
```java
        if (payment.method() == PaymentMethod.CARD) {
          // The MerchantOrderId is ours, so the Cielo can say what became of the sale; empty or
          // still in doubt after stuckCreatedAfter is FAILED (spec §6.4). A failed query throws and
          // is logged below: the next run asks again.
          cardRecovery.sweep(payment);
          if (payments
              .findById(payment.id())
              .map(reloaded -> reloaded.status() != PaymentStatus.CREATED)
              .orElse(false)) {
            changed++;
          }
          continue;
        }
```
(import `com.gateway.payments.payment.create.CardAuthorizationRecovery`.)

Em `PaymentsConfiguration`, troque o bean `cardPaymentFlow` e acrescente os novos; o `stuckCreatedSweep` recebe `cardRecovery`:
```java
  @Bean
  CardAdoption cardAdoption(
      PaymentRepository payments,
      PaymentEvents events,
      SavedCards savedCards,
      UnitOfWork unitOfWork,
      Clock clock) {
    return new CardAdoption(payments, events, savedCards, unitOfWork, clock);
  }

  @Bean
  CardAuthorizationRecovery cardAuthorizationRecovery(
      ProviderGateway providers, CardAdoption adoption, CreateFailures failures) {
    return new CardAuthorizationRecovery(providers, adoption, failures);
  }

  @Bean
  CardPaymentFlow cardPaymentFlow(
      ProviderGateway providers,
      PaymentDraftFactory drafts,
      CardAdoption adoption,
      CardAuthorizationRecovery recovery,
      SavedCards savedCards,
      CreateFailures failures) {
    return new CardPaymentFlow(providers, drafts, adoption, recovery, savedCards, failures);
  }

  @Bean
  StuckCreatedSweep stuckCreatedSweep(
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentService paymentService,
      PixSettlement pixSettlement,
      BoletoSettlement boletoSettlement,
      PaymentsProperties properties,
      CardAuthorizationRecovery cardRecovery) {
    return new StuckCreatedSweep(
        payments, providers, paymentService, pixSettlement, boletoSettlement, properties, cardRecovery);
  }
```
(imports `com.gateway.payments.payment.card.CardAdoption`, `com.gateway.payments.payment.create.CardAuthorizationRecovery`.)

- [ ] **Step 9: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments test`
Expected: PASS — a suíte inteira do módulo.

- [ ] **Step 10: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-payments
git commit -m "feat(payments): card payment flow with every authorization outcome

Captured, authorized, declined, lost answer found or not, and the in-doubt
201s (Status 0, 12, 14) that are asked again by MerchantOrderId instead of
adopted. A failed query leaves the payment CREATED for the sweeper: failing
it would say not charged about a sale that may hold the payer's limit.
save_card stores the token in the adoption's transaction and never fails an
approved sale when no token comes back; a card_id charges the token marked
Used. Installments below the Cielo's R\$ 5,00 minimum are refused before any
row.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---
### Task 9: Captura, cancelamento como void e devolução síncrona

**Files:**
- Create: `gateway-payments/src/main/java/com/gateway/payments/payment/card/{CardCapture,CardVoid}.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/payment/PaymentCancellation.java` (despacho do cartão)
- Create: `gateway-payments/src/main/java/com/gateway/payments/refund/CardRefunds.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/refund/RefundService.java` (despacho do cartão)
- Modify: `gateway-payments/src/main/java/com/gateway/payments/PaymentsConfiguration.java`
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/card/{CardCaptureIntegrationTest,CardVoidIntegrationTest}.java`, `gateway-payments/src/test/java/com/gateway/payments/refund/CardRefundsIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 2, 6, 8 (`Payment.markCaptured`, `CardDetails.paymentId`, `RecordingCardProvider`, `ServiceIntegrationTestBase.cardCommand/card`).
- Produces:
  ```java
  class CardCapture { Payment capture(MerchantId merchantId, String paymentId, Money amountOrNull); }
      // CAPTURE_NOT_ALLOWED (not an AUTHORIZED card), ALREADY_CAPTURED (COMPLETED card, or the Cielo's 308), CAPTURE_AMOUNT_INVALID (< 20 or > authorized)
  class CardVoid { Payment cancel(Payment current); }                       // AUTHORIZED -> CANCELED; COMPLETED -> INVALID_STATE ("use refunds")
  class CardRefunds { Refund request(MerchantId merchantId, Payment payment, Money amountOrNull); }   // synchronous; timeout → PROCESSING + REFUND_UNKNOWN + PROVIDER_TIMEOUT
  PaymentCancellation(PaymentQueries, PaymentRepository, PaymentEvents, ProviderGateway, UnitOfWork, BoletoSettlement, CardVoid)
  RefundService(RefundRepository, PaymentRepository, JobRepository, ProviderGateway, PaymentEvents, PaymentService, TransactionTemplate, Clock, CardRefunds)
  ```

- [ ] **Step 1: Os testes**

`gateway-payments/src/test/java/com/gateway/payments/payment/card/CardCaptureIntegrationTest.java`:
```java
package com.gateway.payments.payment.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec §6, POST /v1/payments/{id}/capture: one capture per sale, 20 cents to the authorized. */
class CardCaptureIntegrationTest extends ServiceIntegrationTestBase {
  static final String APPROVES = "4024007153763171";

  @Autowired CardCapture capture;

  Payment authorized(long cents) {
    return paymentService.create(
        cardCommand(cents, new CardChoice.NewCard(card(APPROVES), false), null, false));
  }

  static void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
    assertThatThrownBy(call)
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo(code);
  }

  @Test
  void aTotalCaptureCompletesWithTheAuthorizedAmount() {
    Payment payment = authorized(10000);

    Payment captured = capture.capture(merchant, payment.id(), null);

    assertThat(captured.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(captured.paidAmount()).isEqualTo(Money.brl(10000));
    assertThat(captured.card().capturedAmount()).isEqualTo(10000L);
    assertThat(outboxTypes(payment.id())).containsExactly("payment.authorized", "payment.completed");
  }

  @Test
  void aPartialCaptureCompletesWithWhatWasCaptured() {
    Payment payment = authorized(10000);

    Payment captured = capture.capture(merchant, payment.id(), Money.brl(6000));

    assertThat(captured.paidAmount()).isEqualTo(Money.brl(6000));
    assertThat(cards.sale(payment.card().paymentId()).capturedAmount()).isEqualTo(Money.brl(6000));
  }

  /** "Após uma captura, não é possível realizar capturas adicionais" (capturar-apos-autorizacao). */
  @Test
  void aSecondCaptureIsAlreadyCaptured() {
    Payment payment = authorized(10000);
    capture.capture(merchant, payment.id(), Money.brl(6000));

    assertCode(() -> capture.capture(merchant, payment.id(), Money.brl(1000)), "ALREADY_CAPTURED");
    assertThat(cards.callsFor(payment.card().paymentId())).containsOnlyOnce("capture:" + payment.card().paymentId());
  }

  @Test
  void anAutomaticallyCapturedPaymentIsAlreadyCaptured() {
    Payment payment = newCard(10000, APPROVES);

    assertCode(() -> capture.capture(merchant, payment.id(), null), "ALREADY_CAPTURED");
  }

  @Test
  void aPixPaymentCannotBeCaptured() {
    Payment pix = newCharge(10000);

    assertCode(() -> capture.capture(merchant, pix.id(), null), "CAPTURE_NOT_ALLOWED");
  }

  /** Twenty cents is the Cielo's floor ("valor inferior a 20 centavos … não são liquidadas"). */
  @Test
  void anAmountOutsideTwentyCentsToTheAuthorizedIsRefusedBeforeTheCielo() {
    Payment payment = authorized(10000);

    assertCode(() -> capture.capture(merchant, payment.id(), Money.brl(19)), "CAPTURE_AMOUNT_INVALID");
    assertCode(() -> capture.capture(merchant, payment.id(), Money.brl(10001)), "CAPTURE_AMOUNT_INVALID");
    assertThat(cards.callsFor(payment.card().paymentId())).noneMatch(call -> call.startsWith("capture:"));
  }

  @Test
  void aCaptureWhoseAnswerWasLostIsReadBackFromTheQuery() {
    Payment payment = authorized(10000);
    cards.setStatus(payment.card().paymentId(), CardStatus.PAID);
    cards.failNextCaptureWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo PUT timed out", null));

    Payment captured = capture.capture(merchant, payment.id(), null);

    assertThat(captured.status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  void aCaptureThatTimedOutAndDidNotLandStaysAuthorized() {
    Payment payment = authorized(10000);
    cards.failNextCaptureWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo PUT timed out", null));

    assertCode(() -> capture.capture(merchant, payment.id(), null), "PROVIDER_TIMEOUT");
    assertThat(paymentQueries.get(merchant, payment.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
  }
}
```

`gateway-payments/src/test/java/com/gateway/payments/payment/card/CardVoidIntegrationTest.java`:
```java
package com.gateway.payments.payment.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;

/** Spec §6: cancel on AUTHORIZED is a void; on COMPLETED it is refused — that is a refund. */
class CardVoidIntegrationTest extends ServiceIntegrationTestBase {
  static final String APPROVES = "4024007153763171";

  @Test
  void cancellingAnAuthorizationVoidsItAtTheCielo() {
    Payment payment =
        paymentService.create(
            cardCommand(10000, new CardChoice.NewCard(card(APPROVES), false), null, false));

    Payment canceled = paymentCancellation.cancel(merchant, payment.id());

    assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
    assertThat(cards.sale(payment.card().paymentId()).status()).isEqualTo(CardStatus.VOIDED);
    assertThat(outboxTypes(payment.id())).containsExactly("payment.authorized", "payment.canceled");
  }

  @Test
  void aCapturedPaymentIsRefundedNotCanceled() {
    Payment payment = newCard(10000, APPROVES);

    assertThatThrownBy(() -> paymentCancellation.cancel(merchant, payment.id()))
        .isInstanceOf(DomainException.class)
        .satisfies(
            thrown -> {
              assertThat(((DomainException) thrown).code()).isEqualTo("INVALID_STATE");
              assertThat(thrown.getMessage()).contains("refund");
            });
    assertThat(cards.callsFor(payment.card().paymentId())).noneMatch(call -> call.startsWith("void:"));
  }
}
```

`gateway-payments/src/test/java/com/gateway/payments/refund/CardRefundsIntegrationTest.java`:
```java
package com.gateway.payments.refund;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.card.CardCapture;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spec §4: a card refund is the Cielo's void with an amount, synchronous — REQUESTED to COMPLETED
 * or FAILED in the request, no polling job; partial allowed; the sum capped by paid_amount.
 */
class CardRefundsIntegrationTest extends ServiceIntegrationTestBase {
  static final String APPROVES = "4024007153763171";

  @Autowired RefundService refunds;
  @Autowired CardCapture capture;

  @Test
  void aTotalRefundCompletesInTheRequest() {
    Payment payment = newCard(10000, APPROVES);

    Refund refund = refunds.request(merchant, payment.id(), null);

    assertThat(refund.state()).isEqualTo(RefundState.COMPLETED);
    assertThat(refund.amount()).isEqualTo(Money.brl(10000));
    assertThat(paymentQueries.get(merchant, payment.id()).refundedAmount()).isEqualTo(Money.brl(10000));
    assertThat(outboxTypes(refund.id())).containsExactly("refund.completed");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments.jobs WHERE ref_id = ?", Integer.class, refund.id()))
        .isZero();
  }

  @Test
  void partialRefundsAddUpToThePaidAmountAndNoFurther() {
    Payment payment = newCard(10000, APPROVES);

    refunds.request(merchant, payment.id(), Money.brl(3000));
    refunds.request(merchant, payment.id(), Money.brl(7000));

    assertThat(paymentQueries.get(merchant, payment.id()).fullyRefunded()).isTrue();
    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), Money.brl(1)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("REFUND_EXCEEDS_AMOUNT");
  }

  @Test
  void aPartialCaptureCapsTheRefund() {
    Payment payment =
        paymentService.create(
            cardCommand(10000, new CardChoice.NewCard(card(APPROVES), false), null, false));
    capture.capture(merchant, payment.id(), Money.brl(6000));

    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), Money.brl(6001)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("REFUND_EXCEEDS_AMOUNT");
    assertThat(refunds.request(merchant, payment.id(), null).amount()).isEqualTo(Money.brl(6000));
  }

  @Test
  void anAuthorizationIsCanceledNotRefunded() {
    Payment payment =
        paymentService.create(
            cardCommand(10000, new CardChoice.NewCard(card(APPROVES), false), null, false));

    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), null))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("INVALID_STATE");
  }

  @Test
  void aRefusedRefundFailsAndFreesTheAmount() {
    Payment payment = newCard(10000, APPROVES);
    cards.failNextRefundWith(
        new ProviderException(ProviderException.Code.INVALID, 400, "312", "312 Transaction not available to refund"));

    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), Money.brl(4000)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_DECLINED");

    assertThat(refunds.list(merchant, payment.id())).singleElement().extracting(Refund::state).isEqualTo(RefundState.FAILED);
    assertThat(refunds.request(merchant, payment.id(), null).amount()).isEqualTo(Money.brl(10000));
  }

  /**
   * Timeout is not failure (CLAUDE.md): the void may have landed, so the amount stays reserved, the
   * refund stays PROCESSING and a human sees REFUND_UNKNOWN (plan C11).
   */
  @Test
  void aTimeoutKeepsTheAmountReservedAndOpensADivergence() {
    Payment payment = newCard(10000, APPROVES);
    cards.failNextRefundWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "Cielo PUT timed out", null));

    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), Money.brl(4000)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_TIMEOUT");

    assertThat(refunds.list(merchant, payment.id()))
        .singleElement()
        .extracting(Refund::state)
        .isEqualTo(RefundState.PROCESSING);
    assertThat(
            jdbc.queryForList(
                "SELECT provider_status FROM payments.reconciliation_divergences WHERE payment_id = ?",
                String.class,
                payment.id()))
        .containsExactly("REFUND_UNKNOWN");
    assertThatThrownBy(() -> refunds.request(merchant, payment.id(), Money.brl(6001)))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("REFUND_EXCEEDS_AMOUNT");
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -Dtest='CardCaptureIntegrationTest,CardVoidIntegrationTest,CardRefundsIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `CardCapture`, `CardVoid`, `CardRefunds` não existem.

- [ ] **Step 3: `CardCapture`**

`gateway-payments/src/main/java/com/gateway/payments/payment/card/CardCapture.java`:
```java
package com.gateway.payments.payment.card;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.create.ProviderFailures;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Capturing an authorization (spec §6). The Cielo takes one capture per sale — total or one
 * partial — so a second call is ALREADY_CAPTURED, and 20 cents is its floor
 * (reference/capturar-apos-autorizacao: "valor inferior a 20 centavos … não são liquidadas").
 *
 * <p>The Cielo call runs outside any transaction, like every bank call here; a lost answer is read
 * back from the query before anything is decided.
 */
public class CardCapture {
  private static final Logger log = LoggerFactory.getLogger(CardCapture.class);
  private static final long MINIMUM_CENTS = 20;
  private static final String NOT_AVAILABLE_TO_CAPTURE = "308";

  private final PaymentQueries queries;
  private final PaymentRepository payments;
  private final PaymentEvents events;
  private final ProviderGateway providers;
  private final UnitOfWork unitOfWork;

  public CardCapture(
      PaymentQueries queries,
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    this.queries = queries;
    this.payments = payments;
    this.events = events;
    this.providers = providers;
    this.unitOfWork = unitOfWork;
  }

  public Payment capture(MerchantId merchantId, String paymentId, Money amountOrNull) {
    Payment current = queries.get(merchantId, paymentId);
    requireAuthorizedCard(current);
    Optional<Money> amount = amountOf(current, amountOrNull);

    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(merchantId, current.environment(), current.provider());
    String cieloPaymentId = current.card().paymentId();

    CardAuthorization captured;
    try {
      captured =
          providers.call(
              paymentId,
              "captureCard",
              resolved,
              target -> target.provider().capture(target.credentials(), cieloPaymentId, amount));
    } catch (ProviderException failure) {
      captured = readBack(current, resolved, failure);
    }

    return complete(merchantId, paymentId, captured);
  }

  private static void requireAuthorizedCard(Payment current) {
    if (current.method() == PaymentMethod.CARD && current.status() == PaymentStatus.COMPLETED) {
      throw new DomainException("ALREADY_CAPTURED", "this payment was already captured");
    }
    if (current.method() != PaymentMethod.CARD || current.status() != PaymentStatus.AUTHORIZED) {
      throw new DomainException(
          "CAPTURE_NOT_ALLOWED",
          "only an authorized card payment can be captured, this one is "
              + current.method()
              + " "
              + current.status());
    }
  }

  private static Optional<Money> amountOf(Payment current, Money amountOrNull) {
    if (amountOrNull == null) {
      return Optional.empty();
    }
    if (amountOrNull.cents() < MINIMUM_CENTS || amountOrNull.greaterThan(current.amount())) {
      throw new DomainException(
          "CAPTURE_AMOUNT_INVALID",
          "amount must be between 20 and " + current.amount().cents() + " cents");
    }

    return Optional.of(amountOrNull);
  }

  /**
   * A timeout or a 503 may have captured; the Cielo's 308 says the sale is not capturable, most
   * likely because it already was. Either way the query decides: PAID is adopted (and, after a 308,
   * reported as ALREADY_CAPTURED); anything else leaves the payment AUTHORIZED.
   */
  private CardAuthorization readBack(
      Payment current, ResolvedProvider<CardMethodProvider> resolved, ProviderException failure) {
    boolean notCapturable =
        failure.code() == ProviderException.Code.INVALID
            && NOT_AVAILABLE_TO_CAPTURE.equals(failure.providerType());
    boolean mayHaveLanded =
        failure.code() == ProviderException.Code.TIMEOUT
            || failure.code() == ProviderException.Code.UNAVAILABLE;

    if (!notCapturable && !mayHaveLanded) {
      throw ProviderErrors.toDomain("PROVIDER_DECLINED", failure, log, "captureCard", current.id());
    }

    Optional<CardAuthorization> atCielo;
    try {
      atCielo =
          providers.call(
              current.id(),
              "findCard",
              resolved,
              target -> target.provider().find(target.credentials(), current.card().paymentId()));
    } catch (ProviderException again) {
      throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", again, log, "findCard", current.id());
    }

    if (atCielo.isEmpty() || atCielo.get().status() != CardStatus.PAID) {
      String code =
          notCapturable ? "PROVIDER_DECLINED" : ProviderFailures.timeoutCodeOf(failure);
      throw ProviderErrors.toDomain(code, failure, log, "captureCard", current.id());
    }

    if (notCapturable) {
      complete(current.merchantId(), current.id(), atCielo.get());
      throw new DomainException("ALREADY_CAPTURED", "this payment was already captured");
    }

    return atCielo.get();
  }

  /** Idempotent: a payment already COMPLETED (a racing notification) is returned as it is. */
  private Payment complete(MerchantId merchantId, String paymentId, CardAuthorization captured) {
    return unitOfWork.inTransaction(
        () -> {
          Payment payment = payments.findByMerchantAndId(merchantId, paymentId).orElseThrow();
          if (payment.status() == PaymentStatus.COMPLETED) {
            return payment;
          }

          Money capturedAmount =
              captured.capturedAmount() == null ? payment.amount() : captured.capturedAmount();
          Payment saved =
              payments.save(
                  payment,
                  List.of(
                      payment.markCaptured(
                          capturedAmount,
                          captured.capturedAt().orElse(captured.receivedAt()),
                          EventSource.API)));
          events.emit(saved.merchantId(), "payment.completed", saved);
          return saved;
        });
  }
}
```

- [ ] **Step 4: `CardVoid` e o despacho em `PaymentCancellation`**

`gateway-payments/src/main/java/com/gateway/payments/payment/card/CardVoid.java`:
```java
package com.gateway.payments.payment.card;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cancelling a card payment: only an authorization, by the Cielo's total void (spec §6). A captured
 * payment is refunded, never canceled — cancel on COMPLETED stays INVALID_STATE, as it is today.
 * The Cielo first, then the payment; a lost answer is read back from the query.
 */
public class CardVoid {
  private static final Logger log = LoggerFactory.getLogger(CardVoid.class);

  private final PaymentRepository payments;
  private final PaymentEvents events;
  private final ProviderGateway providers;
  private final UnitOfWork unitOfWork;

  public CardVoid(
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    this.payments = payments;
    this.events = events;
    this.providers = providers;
    this.unitOfWork = unitOfWork;
  }

  public Payment cancel(Payment current) {
    if (current.status() != PaymentStatus.AUTHORIZED) {
      throw new DomainException(
          "INVALID_STATE",
          "only an authorized card payment can be canceled, this one is "
              + current.status()
              + "; a captured payment is refunded");
    }

    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(current.merchantId(), current.environment(), current.provider());
    String cieloPaymentId = current.card().paymentId();

    try {
      providers.run(
          current.id(),
          "voidCard",
          resolved,
          target -> target.provider().cancel(target.credentials(), cieloPaymentId));
    } catch (ProviderException failure) {
      requireVoidedAtCielo(current, resolved, failure);
    }

    return unitOfWork.inTransaction(
        () -> {
          Payment payment = payments.findById(current.id()).orElseThrow();
          if (payment.status() == PaymentStatus.CANCELED) {
            return payment;
          }
          if (payment.status() != PaymentStatus.AUTHORIZED) {
            throw new DomainException(
                "INVALID_STATE", "payment changed to " + payment.status() + " while cancelling");
          }

          Payment saved = payments.save(payment, List.of(payment.markCanceled(EventSource.API)));
          events.emit(saved.merchantId(), "payment.canceled", saved);
          return saved;
        });
  }

  /** The void's answer did not say VOIDED: only the query can tell whether it happened anyway. */
  private void requireVoidedAtCielo(
      Payment current, ResolvedProvider<CardMethodProvider> resolved, ProviderException failure) {
    Optional<CardAuthorization> atCielo;
    try {
      atCielo =
          providers.call(
              current.id(),
              "findCard",
              resolved,
              target -> target.provider().find(target.credentials(), current.card().paymentId()));
    } catch (ProviderException again) {
      throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", again, log, "findCard", current.id());
    }

    if (atCielo.isPresent() && atCielo.get().status() == CardStatus.VOIDED) {
      return;
    }
    if (failure.code() == ProviderException.Code.TIMEOUT
        || failure.code() == ProviderException.Code.UNAVAILABLE) {
      throw ProviderErrors.toDomain("PROVIDER_TIMEOUT", failure, log, "voidCard", current.id());
    }

    throw new DomainException("INVALID_STATE", "the acquirer no longer accepts voiding this payment");
  }
}
```

Em `PaymentCancellation`: campo e último parâmetro do construtor `CardVoid cardVoid`, e o começo de `cancel` passa a ser:
```java
  public Payment cancel(MerchantId merchantId, String id) {
    Payment current = queries.get(merchantId, id);
    // A card is canceled from AUTHORIZED, by the acquirer's void; Pix and Bolecode from PENDING.
    // Dispatched here, once, because the two paths share nothing but the name.
    if (current.method() == PaymentMethod.CARD) {
      return cardVoid.cancel(current);
    }
    if (current.status() != PaymentStatus.PENDING) {
```
(o resto do método não muda; import `com.gateway.payments.payment.card.CardVoid`.)

- [ ] **Step 5: `CardRefunds` e o despacho em `RefundService`**

`gateway-payments/src/main/java/com/gateway/payments/refund/CardRefunds.java`:
```java
package com.gateway.payments.refund;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardRefundResult;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import com.gateway.payments.reconciliation.Divergences;
import com.gateway.payments.refund.persistence.RefundRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A card refund: the Cielo's void with an amount, which answers in the same call (spec §4). So the
 * refund goes REQUESTED → COMPLETED | FAILED inside the request, with no POLL_REFUND job.
 *
 * <p>The reserve is the Pix one — the payment row locked, everything not FAILED counted — against
 * the paid amount, which a partial capture makes smaller than the amount. A timeout is not a
 * failure: the void may have gone through, so the refund stays PROCESSING with its amount reserved
 * and a REFUND_UNKNOWN divergence for a human (plan C11); the notification or the reconciliation
 * shows what the Cielo did.
 */
public class CardRefunds {
  private static final Logger log = LoggerFactory.getLogger(CardRefunds.class);
  private static final Set<ProviderException.Code> MAY_HAVE_LANDED =
      Set.of(ProviderException.Code.TIMEOUT, ProviderException.Code.UNAVAILABLE);

  private final RefundRepository refunds;
  private final PaymentRepository payments;
  private final ProviderGateway providers;
  private final PaymentEvents events;
  private final Divergences divergences;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public CardRefunds(
      RefundRepository refunds,
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentEvents events,
      Divergences divergences,
      TransactionTemplate transactionTemplate,
      Clock clock) {
    this.refunds = refunds;
    this.payments = payments;
    this.providers = providers;
    this.events = events;
    this.divergences = divergences;
    this.transactionTemplate = transactionTemplate;
    this.clock = clock;
  }

  public Refund request(MerchantId merchantId, Payment payment, Money amountOrNull) {
    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(merchantId, payment.environment(), payment.provider());

    Refund refund = reserve(merchantId, payment.id(), amountOrNull);

    CardRefundResult result;
    try {
      result =
          providers.call(
              payment.id(),
              "refundCard",
              resolved,
              target ->
                  target
                      .provider()
                      .refund(
                          target.credentials(),
                          payment.card().paymentId(),
                          Optional.of(refund.amount())));
    } catch (ProviderException failure) {
      throw afterFailure(merchantId, payment, refund, failure);
    }

    if (!result.completed()) {
      ProviderException refused =
          new ProviderException(
              ProviderException.Code.DECLINED,
              200,
              result.returnCode(),
              "void answered status " + result.status());
      throw markFailed(merchantId, refund, refused);
    }

    return complete(merchantId, refund);
  }

  private Refund reserve(MerchantId merchantId, String paymentId, Money amountOrNull) {
    return transactionTemplate.execute(
        transaction -> {
          Payment locked = payments.findByIdForUpdate(paymentId).orElseThrow();
          if (locked.status() != PaymentStatus.COMPLETED) {
            throw new DomainException(
                "INVALID_STATE",
                "only a captured card payment can be refunded, this one is "
                    + locked.status()
                    + "; an authorization is canceled");
          }

          long reserved =
              refunds.findByPayment(paymentId).stream()
                  .filter(existing -> existing.state() != RefundState.FAILED)
                  .mapToLong(existing -> existing.amount().cents())
                  .sum();
          long remaining = locked.refundable().cents() - reserved;
          Money amount =
              amountOrNull == null
                  ? new Money(Math.max(remaining, 0), locked.amount().currency())
                  : amountOrNull;
          if (amount.isZero() || amount.cents() > remaining) {
            throw new DomainException(
                "REFUND_EXCEEDS_AMOUNT",
                "refunds would total more than the paid amount; remaining "
                    + Math.max(remaining, 0));
          }

          return refunds.save(Refund.request(paymentId, merchantId, amount, clock));
        });
  }

  private Refund complete(MerchantId merchantId, Refund refund) {
    return transactionTemplate.execute(
        transaction -> {
          Payment payment = payments.findByIdForUpdate(refund.paymentId()).orElseThrow();
          Refund loaded = refunds.findById(refund.id()).orElseThrow();
          loaded.markCompleted(clock.instant());
          Refund saved = refunds.save(loaded);
          Payment updated =
              payments.save(
                  payment,
                  List.of(payment.recordRefund(saved.id(), saved.amount(), true, EventSource.API)));
          events.emitRefund(merchantId, "refund.completed", saved, updated);
          return saved;
        });
  }

  private DomainException afterFailure(
      MerchantId merchantId, Payment payment, Refund refund, ProviderException failure) {
    if (!MAY_HAVE_LANDED.contains(failure.code())) {
      return markFailed(merchantId, refund, failure);
    }

    transactionTemplate.executeWithoutResult(
        transaction -> {
          Refund loaded = refunds.findById(refund.id()).orElseThrow();
          loaded.markProcessing();
          Refund saved = refunds.save(loaded);
          events.emitRefund(merchantId, "refund.requested", saved, payment);
          divergences.open(
              payment,
              "REFUND_UNKNOWN",
              "card refund " + refund.id() + " ended in " + failure.code() + "; check the Cielo");
        });

    String code =
        failure.code() == ProviderException.Code.TIMEOUT ? "PROVIDER_TIMEOUT" : "PROVIDER_UNAVAILABLE";
    return ProviderErrors.toDomain(code, failure, log, "refundCard", refund.id());
  }

  private DomainException markFailed(MerchantId merchantId, Refund refund, ProviderException cause) {
    String code = "PROVIDER_DECLINED";
    transactionTemplate.executeWithoutResult(
        transaction -> {
          Payment payment = payments.findByIdForUpdate(refund.paymentId()).orElseThrow();
          Refund loaded = refunds.findById(refund.id()).orElseThrow();
          loaded.markFailed(ProviderErrors.message(code));
          events.emitRefund(merchantId, "refund.failed", refunds.save(loaded), payment);
        });
    return ProviderErrors.toDomain(code, cause, log, "refundCard", refund.id());
  }
}
```

Em `RefundService`: campo e último parâmetro do construtor `CardRefunds cardRefunds`; em `request`, logo depois do teste de `isZero`:
```java
    // A card refund is the acquirer's synchronous void, with no endToEndId, no polling job and no
    // 90-day Pix window: its own class (spec 2026-09-28 §4). Dispatched here, once.
    if (payment.method() == PaymentMethod.CARD) {
      return cardRefunds.request(merchantId, payment, amountOrNull);
    }
```
`RefundService` já passava de 300 linhas e tinha 8 dependências antes desta task; o cartão entra como uma linha de despacho e uma dependência, e o resto do comportamento do cartão mora em `CardRefunds` para não piorar o arquivo. Dividir o `RefundService` do Pix fica anotado no commit.

- [ ] **Step 6: Wiring**

Em `PaymentsConfiguration`:
```java
  @Bean
  CardCapture cardCapture(
      PaymentQueries queries,
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    return new CardCapture(queries, payments, events, providers, unitOfWork);
  }

  @Bean
  CardVoid cardVoid(
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    return new CardVoid(payments, events, providers, unitOfWork);
  }

  @Bean
  CardRefunds cardRefunds(
      RefundRepository refunds,
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentEvents events,
      Divergences divergences,
      TransactionTemplate paymentsTransactionTemplate,
      Clock clock) {
    return new CardRefunds(
        refunds, payments, providers, events, divergences, paymentsTransactionTemplate, clock);
  }
```
e os beans `paymentCancellation` (último argumento `CardVoid cardVoid`) e `refundService` (último argumento `CardRefunds cardRefunds`) passam o novo colaborador. Imports `com.gateway.payments.payment.card.{CardCapture,CardVoid}`, `com.gateway.payments.refund.CardRefunds`.

- [ ] **Step 7: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments test`
Expected: PASS — a suíte inteira, os testes de cancelamento e devolução do Pix e do Bolecode inclusive.

- [ ] **Step 8: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-payments
git commit -m "feat(payments): card capture, void on cancel and synchronous refunds

Capture takes one call per sale, 20 cents to the authorized amount; a second
one is ALREADY_CAPTURED and a lost answer is read back from the query. Cancel
on an authorization is the Cielo void; on a captured payment it stays
INVALID_STATE. A card refund is the Cielo void with an amount, settled in the
request against the paid amount; a timeout keeps the amount reserved with a
REFUND_UNKNOWN divergence instead of freeing it.

RefundService was already past 300 lines; the card path lives in CardRefunds
and only its dispatch line was added there.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---
### Task 10: A notificação da Cielo na inbox e a reconciliação do cartão

**Files:**
- Create: `gateway-payments/src/main/java/com/gateway/payments/payment/card/CardStatusSync.java`
- Create: `gateway-payments/src/main/java/com/gateway/payments/inbox/CardNotifications.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/inbox/WebhookInboxService.java` (despacho do cartão)
- Create: `gateway-payments/src/main/java/com/gateway/payments/reconciliation/CardReconciliation.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/reconciliation/ReconciliationService.java` (escopo Pix sem `CARD`)
- Modify: `gateway-payments/src/main/java/com/gateway/payments/jobs/ReconcileJob.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/{PaymentsProperties,PaymentsConfiguration}.java`
- Test: `gateway-payments/src/test/java/com/gateway/payments/inbox/CardNotificationsIntegrationTest.java`, `gateway-payments/src/test/java/com/gateway/payments/reconciliation/CardReconciliationIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 2, 6, 8, 9 (`ProviderGateway.cardProvider/hasCardProvider`, `PaymentRepository.findByMerchantAndCardPaymentId`, `RecordingCardProvider.setStatus`).
- Produces:
  ```java
  class CardStatusSync { void sync(Payment payment, EventSource by); }   // GET /1/sales/{PaymentId}; the answer is the truth
  class CardNotifications { boolean apply(MerchantId merchantId, String provider, CardNotification notification); } // false = not ours → IGNORED
  class CardReconciliation { int reconcile(Instant now); }               // sync pass + CAPTURE_OVERDUE
  PaymentsProperties += Duration cardCaptureDeadline (P5D), Duration cardReconciliationLookback (PT48H), int cardReconciliationCap (200)
  ReconcileJob(StuckCreatedSweep, ReconciliationService, CardReconciliation, JobBackoff)
  divergence provider_status values: REFUNDED_AT_PROVIDER, VOID_DENIED, FRAUD_ALERT, CAPTURE_OVERDUE, CARD_ACTIVE_AT_PROVIDER, NOT_FOUND_AT_PROVIDER
  ```

- [ ] **Step 1: Os testes**

`gateway-payments/src/test/java/com/gateway/payments/inbox/CardNotificationsIntegrationTest.java`:
```java
package com.gateway.payments.inbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Spec §8: the body is a hint — PaymentId and ChangeType — and the query of that PaymentId is the
 * truth. docs/webhook, "Tabela de ChangeType".
 */
class CardNotificationsIntegrationTest extends ServiceIntegrationTestBase {
  static final String APPROVES = "4024007153763171";

  @Autowired WebhookInboxService inbox;

  Payment authorized() {
    return paymentService.create(
        cardCommand(10000, new CardChoice.NewCard(card(APPROVES), false), null, false));
  }

  String notify(MerchantId to, String cieloPaymentId, int changeType) {
    String body = "{\"PaymentId\":\"" + cieloPaymentId + "\",\"ChangeType\":" + changeType + "}";
    String id = inbox.accept("CIELO", to, "{}", body.getBytes(StandardCharsets.UTF_8));
    inbox.process(id);
    return id;
  }

  String inboxStatus(String id) {
    return jdbc.queryForObject("SELECT status FROM payments.webhook_inbox WHERE id = ?", String.class, id);
  }

  java.util.List<String> divergences(Payment payment) {
    return jdbc.queryForList(
        "SELECT provider_status FROM payments.reconciliation_divergences WHERE payment_id = ?",
        String.class,
        payment.id());
  }

  @Test
  void aCaptureDoneOutsideTheGatewayCompletesTheAuthorization() {
    Payment payment = authorized();
    cards.setStatus(payment.card().paymentId(), CardStatus.PAID);

    String id = notify(merchant, payment.card().paymentId(), 1);

    Payment after = paymentQueries.get(merchant, payment.id());
    assertThat(after.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(outboxTypes(payment.id())).containsExactly("payment.authorized", "payment.completed");
    assertThat(inboxStatus(id)).isEqualTo("PROCESSED");
    assertThat(
            jdbc.queryForObject(
                "SELECT source FROM payments.payment_events WHERE payment_id = ? AND type = 'completed'",
                String.class,
                payment.id()))
        .isEqualTo("PROVIDER_WEBHOOK");
  }

  @Test
  void aVoidDoneOutsideTheGatewayCancelsTheAuthorization() {
    Payment payment = authorized();
    cards.setStatus(payment.card().paymentId(), CardStatus.VOIDED);

    notify(merchant, payment.card().paymentId(), 1);

    assertThat(paymentQueries.get(merchant, payment.id()).status()).isEqualTo(PaymentStatus.CANCELED);
    assertThat(outboxTypes(payment.id())).containsExactly("payment.authorized", "payment.canceled");
  }

  /** ChangeType 25 on a captured payment the gateway did not refund: a human looks. */
  @Test
  void aRefundDoneOutsideTheGatewayOpensADivergence() {
    Payment payment = newCard(10000, APPROVES);
    cards.setStatus(payment.card().paymentId(), CardStatus.REFUNDED);

    notify(merchant, payment.card().paymentId(), 25);

    assertThat(paymentQueries.get(merchant, payment.id()).status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(divergences(payment)).containsExactly("REFUNDED_AT_PROVIDER");
  }

  @Test
  void aDeniedCancelAndAFraudAlertAreDivergences() {
    Payment payment = newCard(10000, APPROVES);

    notify(merchant, payment.card().paymentId(), 5);
    notify(merchant, payment.card().paymentId(), 8);

    assertThat(divergences(payment)).containsExactlyInAnyOrder("VOID_DENIED", "FRAUD_ALERT");
  }

  /** 2, 3, 4, 6, 7 are not this phase's (plan D14): a stored fact, no transition. */
  @Test
  void aRecurrenceNotificationIsRecordedAsIgnored() {
    Payment payment = newCard(10000, APPROVES);

    String id = notify(merchant, payment.card().paymentId(), 2);

    assertThat(inboxStatus(id)).isEqualTo("PROCESSED");
    assertThat(
            jdbc.queryForList(
                "SELECT type FROM payments.payment_events WHERE payment_id = ? ORDER BY sequence",
                String.class,
                payment.id()))
        .endsWith("ignored");
  }

  /** Review Focus 5: another system on the same Cielo store, or a sale from before the gateway. */
  @Test
  void anUnknownPaymentIdIsIgnored() {
    String id = notify(merchant, "2352fc91-f9a4-4ca2-aedb-31488b9658c9", 1);

    assertThat(inboxStatus(id)).isEqualTo("IGNORED");
  }

  /** The URL's merchant scopes the lookup: another merchant's PaymentId is not reachable. */
  @Test
  void anotherMerchantsPaymentIdIsIgnored() {
    Payment payment = authorized();
    cards.setStatus(payment.card().paymentId(), CardStatus.PAID);

    String id = notify(MerchantId.next(), payment.card().paymentId(), 1);

    assertThat(inboxStatus(id)).isEqualTo("IGNORED");
    assertThat(paymentQueries.get(merchant, payment.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
  }

  @Test
  void anUnreadableBodyFailsWithoutRetrying() {
    String id = inbox.accept("CIELO", merchant, "{}", "{\"ChangeType\":1}".getBytes(StandardCharsets.UTF_8));

    inbox.process(id);

    assertThat(inboxStatus(id)).isEqualTo("FAILED");
  }
}
```

`gateway-payments/src/test/java/com/gateway/payments/reconciliation/CardReconciliationIntegrationTest.java`:
```java
package com.gateway.payments.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Spec §8: an authorization nobody captured, and a card sale that moved at the Cielo. */
class CardReconciliationIntegrationTest extends ServiceIntegrationTestBase {
  static final String APPROVES = "4024007153763171";

  @Autowired CardReconciliation reconciliation;

  Payment authorized() {
    return paymentService.create(
        cardCommand(10000, new CardChoice.NewCard(card(APPROVES), false), null, false));
  }

  List<String> divergences(Payment payment) {
    return jdbc.queryForList(
        "SELECT provider_status FROM payments.reconciliation_divergences WHERE payment_id = ?",
        String.class,
        payment.id());
  }

  /**
   * The Cielo does not expire an authorization and the limit stays held on the payer's card, so
   * after cardCaptureDeadline (5 days) a human is told (spec §11) — once, not every 15 minutes.
   */
  @Test
  void anAuthorizationPastTheDeadlineIsCaptureOverdue() {
    Payment payment = authorized();

    clock.advance(Duration.ofDays(4));
    reconciliation.reconcile(clock.instant());
    assertThat(divergences(payment)).isEmpty();

    clock.advance(Duration.ofDays(2));
    reconciliation.reconcile(clock.instant());
    reconciliation.reconcile(clock.instant());

    assertThat(divergences(payment)).containsExactly("CAPTURE_OVERDUE");
    assertThat(paymentQueries.get(merchant, payment.id()).status()).isEqualTo(PaymentStatus.AUTHORIZED);
  }

  @Test
  void aCaptureTheNotificationNeverDeliveredIsFoundByTheQuery() {
    Payment payment = authorized();
    cards.setStatus(payment.card().paymentId(), CardStatus.PAID);

    reconciliation.reconcile(clock.instant());

    Payment after = paymentQueries.get(merchant, payment.id());
    assertThat(after.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(
            jdbc.queryForObject(
                "SELECT source FROM payments.payment_events WHERE payment_id = ? AND type = 'completed'",
                String.class,
                payment.id()))
        .isEqualTo("RECONCILIATION");
  }

  @Test
  void anOverdueAuthorizationCapturedAtTheCieloIsCompletedNotFlagged() {
    Payment payment = authorized();
    cards.setStatus(payment.card().paymentId(), CardStatus.PAID);

    clock.advance(Duration.ofDays(6));
    reconciliation.reconcile(clock.instant());

    assertThat(paymentQueries.get(merchant, payment.id()).status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(divergences(payment)).isEmpty();
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -Dtest='CardNotificationsIntegrationTest,CardReconciliationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `CardReconciliation` não existe; a inbox manda "CIELO" para `pixProvider` e falha com `PROVIDER_UNKNOWN`.

- [ ] **Step 3: `CardStatusSync`**

`gateway-payments/src/main/java/com/gateway/payments/payment/card/CardStatusSync.java`:
```java
package com.gateway.payments.payment.card;

import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import com.gateway.payments.reconciliation.Divergences;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Cielo's word on a card payment, from GET /1/sales/{PaymentId} — reached by a notification or
 * by the reconciliation, never by a body alone (spec §8). Moves the payment only where the state
 * machine has a row for it (AUTHORIZED → COMPLETED or CANCELED); everything else that disagrees is
 * a divergence for a human, because there is no legal way out of COMPLETED, FAILED or CANCELED and
 * guessing would be worse.
 */
public class CardStatusSync {
  private static final Set<CardStatus> MONEY_WENT_BACK = EnumSet.of(CardStatus.VOIDED, CardStatus.REFUNDED);
  private static final Set<CardStatus> ACTIVE = EnumSet.of(CardStatus.AUTHORIZED, CardStatus.PAID);

  private final PaymentRepository payments;
  private final PaymentEvents events;
  private final ProviderGateway providers;
  private final Divergences divergences;
  private final UnitOfWork unitOfWork;

  public CardStatusSync(
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      Divergences divergences,
      UnitOfWork unitOfWork) {
    this.payments = payments;
    this.events = events;
    this.providers = providers;
    this.divergences = divergences;
    this.unitOfWork = unitOfWork;
  }

  /** The Cielo unreachable propagates: the inbox job and the reconciliation both retry. */
  public void sync(Payment payment, EventSource by) {
    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(payment.merchantId(), payment.environment(), payment.provider());

    Optional<CardAuthorization> atCielo =
        providers.call(
            payment.id(),
            "findCard",
            resolved,
            target -> target.provider().find(target.credentials(), payment.card().paymentId()));

    if (atCielo.isEmpty()) {
      // The query answers only for the last three months (consulta-merchantorderid-api); inside the
      // reconciliation window an unknown PaymentId is a real disagreement.
      divergences.open(payment, "NOT_FOUND_AT_PROVIDER", "GET /1/sales/" + payment.card().paymentId() + " empty");
      return;
    }

    apply(payment.id(), atCielo.get(), by);
  }

  private void apply(String paymentId, CardAuthorization sale, EventSource by) {
    unitOfWork.run(
        () -> {
          Payment payment = payments.findById(paymentId).orElseThrow();

          switch (payment.status()) {
            case AUTHORIZED -> fromAuthorized(payment, sale, by);
            case COMPLETED -> fromCompleted(payment, sale);
            case FAILED, CANCELED -> fromClosed(payment, sale);
            default -> {
              // CREATED is the flow's and the sweeper's; PENDING and EXPIRED do not exist for a card.
            }
          }
        });
  }

  private void fromAuthorized(Payment payment, CardAuthorization sale, EventSource by) {
    if (sale.status() == CardStatus.PAID) {
      Payment saved =
          payments.save(
              payment,
              List.of(
                  payment.markCaptured(
                      sale.capturedAmount() == null ? payment.amount() : sale.capturedAmount(),
                      sale.capturedAt().orElse(sale.receivedAt()),
                      by)));
      events.emit(saved.merchantId(), "payment.completed", saved);
    } else if (sale.status() == CardStatus.VOIDED) {
      Payment saved = payments.save(payment, List.of(payment.markCanceled(by)));
      events.emit(saved.merchantId(), "payment.canceled", saved);
    }
  }

  private void fromCompleted(Payment payment, CardAuthorization sale) {
    if (MONEY_WENT_BACK.contains(sale.status()) && !payment.fullyRefunded()) {
      divergences.open(
          payment,
          "REFUNDED_AT_PROVIDER",
          "the Cielo shows " + sale.status() + "; the gateway refunded " + payment.refundedAmount().cents());
    }
  }

  private void fromClosed(Payment payment, CardAuthorization sale) {
    if (ACTIVE.contains(sale.status())) {
      divergences.open(
          payment,
          "CARD_ACTIVE_AT_PROVIDER",
          "the Cielo shows " + sale.status() + " for a " + payment.status() + " payment");
    }
  }
}
```

- [ ] **Step 4: `CardNotifications` e o despacho da inbox**

`gateway-payments/src/main/java/com/gateway/payments/inbox/CardNotifications.java`:
```java
package com.gateway.payments.inbox;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.card.CardNotification;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.card.CardStatusSync;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.Divergences;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A stored Cielo notification (spec §8). The payment is found by PaymentId within the merchant
 * whose URL was called; not found is "not ours" — another system on the same Cielo store, or a sale
 * from before the gateway — and the entry ends IGNORED (Review Focus 5).
 */
public class CardNotifications {
  private static final Logger log = LoggerFactory.getLogger(CardNotifications.class);

  private final PaymentRepository payments;
  private final CardStatusSync statusSync;
  private final Divergences divergences;
  private final UnitOfWork unitOfWork;

  public CardNotifications(
      PaymentRepository payments,
      CardStatusSync statusSync,
      Divergences divergences,
      UnitOfWork unitOfWork) {
    this.payments = payments;
    this.statusSync = statusSync;
    this.divergences = divergences;
    this.unitOfWork = unitOfWork;
  }

  /** Returns whether the notification was about one of this merchant's payments. */
  public boolean apply(MerchantId merchantId, String provider, CardNotification notification) {
    Optional<Payment> found =
        payments.findByMerchantAndCardPaymentId(merchantId, provider, notification.paymentId());
    if (found.isEmpty()) {
      log.info("{} notification for unknown PaymentId {}", provider, notification.paymentId());
      return false;
    }

    Payment payment = found.get();
    switch (notification.kind()) {
      case STATUS_CHANGED, PARTIAL_REFUND -> statusSync.sync(payment, EventSource.PROVIDER_WEBHOOK);
      case VOID_DENIED ->
          unitOfWork.run(
              () ->
                  divergences.open(
                      payment, "VOID_DENIED", "ChangeType 5 for " + notification.paymentId()));
      case FRAUD_ALERT ->
          unitOfWork.run(
              () ->
                  divergences.open(
                      payment, "FRAUD_ALERT", "ChangeType 8 for " + notification.paymentId()));
      case IGNORED -> recordIgnored(payment.id(), notification);
    }

    return true;
  }

  private void recordIgnored(String paymentId, CardNotification notification) {
    unitOfWork.run(
        () -> {
          Payment payment = payments.findById(paymentId).orElseThrow();
          payments.save(
              payment,
              List.of(
                  payment
                      .recordIgnored(
                          "card notification ChangeType " + notification.changeType(),
                          EventSource.PROVIDER_WEBHOOK)
                      .orElseThrow()));
        });
  }
}
```
(`UnitOfWork` tem `run(Runnable)` e `inTransaction(Supplier)` — os dois já usados em `CreateFailures` e `PendingAdoption`.)

Em `WebhookInboxService`: campo e último parâmetro do construtor `CardNotifications cardNotifications`; em `process`, logo depois do `if (entry == null || …) return;`:
```java
    // The Cielo's notification is a card's, not a Pix body: its own parser and its own handler.
    // Dispatched here, once, by the provider the URL belonged to.
    if (providers.hasCardProvider(entry.provider())) {
      processCard(entry);
      return;
    }
```
e o método:
```java
  /**
   * An unreadable body is FAILED and never retried; the Cielo being unreachable during the query
   * propagates, so the job retries — the same split as the Pix path above.
   */
  private void processCard(WebhookInboxEntry entry) {
    CardNotification notification;
    try {
      notification = providers.cardProvider(entry.provider()).parseWebhook(entry.rawBody());
    } catch (RuntimeException e) {
      log.warn("unreadable {} notification {}", entry.provider(), entry.id(), e);
      mark(entry, "FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
      return;
    }

    boolean matched = cardNotifications.apply(entry.merchantId(), entry.provider(), notification);
    mark(entry, matched ? "PROCESSED" : "IGNORED", null);
  }
```
(import `com.gateway.kernel.provider.card.CardNotification`.) `WebhookInboxService` passa a 8 dependências; a alternativa — um handler por provider na inbox — é um refactor do caminho Pix que fica fora desta fase, anotado no commit.

- [ ] **Step 5: A reconciliação**

Em `PaymentsProperties` acrescente três componentes ao fim do record (`Duration cardCaptureDeadline, Duration cardReconciliationLookback, int cardReconciliationCap`), os defaults no construtor compacto
```java
    // The Cielo does not expire an authorization and the payer's limit stays held (spec §8, §11):
    // five days is when a human hears about it.
    if (cardCaptureDeadline == null) {
      cardCaptureDeadline = Duration.ofDays(5);
    }
    // One GET per card payment per run (spec §8): the same two days as the Pix listing, capped.
    if (cardReconciliationLookback == null) {
      cardReconciliationLookback = Duration.ofHours(48);
    }
    if (cardReconciliationCap <= 0) {
      cardReconciliationCap = 200;
    }
```
e `defaults()` passa `null, null, 0` a mais no fim.

`gateway-payments/src/main/java/com/gateway/payments/reconciliation/CardReconciliation.java`:
```java
package com.gateway.payments.reconciliation;

import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.card.CardStatusSync;
import com.gateway.payments.payment.persistence.PaymentRepository;
import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The card side of the periodic pass (spec §8). There is no listing API at the Cielo, so each card
 * payment is one GET — which is why the window and the cap are the card's own. Then CAPTURE_OVERDUE:
 * an authorization older than cardCaptureDeadline that the query still shows authorized. The sync
 * runs first, so one captured at the Cielo and never notified is completed, not flagged.
 *
 * <p>Separate from ReconciliationService, which lists Pix charges by merchant; the two share
 * nothing but the job that runs them.
 */
public class CardReconciliation {
  private static final Logger log = LoggerFactory.getLogger(CardReconciliation.class);

  private final PaymentRepository payments;
  private final CardStatusSync statusSync;
  private final Divergences divergences;
  private final PaymentsProperties properties;

  public CardReconciliation(
      PaymentRepository payments,
      CardStatusSync statusSync,
      Divergences divergences,
      PaymentsProperties properties) {
    this.payments = payments;
    this.statusSync = statusSync;
    this.divergences = divergences;
    this.properties = properties;
  }

  /** Returns how many payments moved or got a new divergence. */
  public int reconcile(Instant now) {
    Map<String, Payment> candidates = new LinkedHashMap<>();
    for (Payment payment :
        payments.findByMethodAndStatusIn(
            PaymentMethod.CARD,
            EnumSet.of(PaymentStatus.AUTHORIZED, PaymentStatus.COMPLETED),
            now.minus(properties.cardReconciliationLookback()),
            properties.cardReconciliationCap())) {
      candidates.put(payment.id(), payment);
    }
    // AUTHORIZED is card-only, so the status query needs no method filter.
    Instant overdueBefore = now.minus(properties.cardCaptureDeadline());
    for (Payment payment :
        payments.findByStatusCreatedBefore(
            PaymentStatus.AUTHORIZED, overdueBefore, properties.cardReconciliationCap())) {
      candidates.put(payment.id(), payment);
    }

    int changed = 0;
    for (Payment payment : candidates.values()) {
      try {
        changed += reconcileOne(payment, overdueBefore);
      } catch (RuntimeException e) {
        // One merchant's revoked credential must not stop the pass for everyone else.
        log.warn("card reconciliation failed for payment {}", payment.id(), e);
      }
    }

    return changed;
  }

  private int reconcileOne(Payment payment, Instant overdueBefore) {
    statusSync.sync(payment, EventSource.RECONCILIATION);

    Payment after = payments.findById(payment.id()).orElseThrow();
    if (after.status() != payment.status()) {
      return 1;
    }

    boolean overdue =
        after.status() == PaymentStatus.AUTHORIZED && after.createdAt().isBefore(overdueBefore);
    if (overdue
        && divergences.open(
            after, "CAPTURE_OVERDUE", "authorized since " + after.createdAt() + ", never captured")) {
      return 1;
    }

    return 0;
  }
}
```

Em `ReconciliationService.reconcileAll`, o laço dos escopos Pix passa a ler só PIX e BOLECODE — o laço atual lia todos os métodos, e um merchant só de cartão sem credencial Itaú logaria uma falha a cada 15 minutos (plan C10). Troque
```java
    for (Payment payment :
        payments.findByStatusIn(
            EnumSet.of(
                PaymentStatus.PENDING,
                PaymentStatus.EXPIRED,
                PaymentStatus.COMPLETED,
                PaymentStatus.FAILED,
                PaymentStatus.CANCELED),
            from,
            CANDIDATES)) {
```
por
```java
    // Pix and Bolecode only (the Bolecode's Pix side is listed by /cob too); card payments have
    // their own pass, CardReconciliation. One query per method, so card rows cannot fill the cap.
    List<Payment> pixSide = new java.util.ArrayList<>();
    for (PaymentMethod method : EnumSet.of(PaymentMethod.PIX, PaymentMethod.BOLECODE)) {
      pixSide.addAll(
          payments.findByMethodAndStatusIn(
              method,
              EnumSet.of(
                  PaymentStatus.PENDING,
                  PaymentStatus.EXPIRED,
                  PaymentStatus.COMPLETED,
                  PaymentStatus.FAILED,
                  PaymentStatus.CANCELED),
              from,
              CANDIDATES));
    }
    for (Payment payment : pixSide) {
```
(o corpo do laço não muda.)

`ReconcileJob`: campo e terceiro parâmetro do construtor `CardReconciliation cardReconciliation`; `run` passa a
```java
  @Override
  public boolean run(String refId, Instant now) {
    sweep.sweepStuckCreated(now);
    reconciliation.reconcileAll(now);
    cardReconciliation.reconcile(now);
    return true;
  }
```
e o javadoc da classe: "The periodic pass: the stuck-CREATED sweep, then reconciliation against the bank and against the acquirer."

Em `PaymentsConfiguration`:
```java
  @Bean
  CardStatusSync cardStatusSync(
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      Divergences divergences,
      UnitOfWork unitOfWork) {
    return new CardStatusSync(payments, events, providers, divergences, unitOfWork);
  }

  @Bean
  CardNotifications cardNotifications(
      PaymentRepository payments,
      CardStatusSync statusSync,
      Divergences divergences,
      UnitOfWork unitOfWork) {
    return new CardNotifications(payments, statusSync, divergences, unitOfWork);
  }

  @Bean
  CardReconciliation cardReconciliation(
      PaymentRepository payments,
      CardStatusSync statusSync,
      Divergences divergences,
      PaymentsProperties properties) {
    return new CardReconciliation(payments, statusSync, divergences, properties);
  }
```
e os beans `webhookInboxService` (último argumento `CardNotifications cardNotifications`) e `reconcileJob` (`new ReconcileJob(sweep, reconciliation, cardReconciliation, backoff)`) passam os novos colaboradores.

- [ ] **Step 6: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments test`
Expected: PASS — a suíte inteira, `ExpirationAndReconciliationIntegrationTest` e `WebhookInboxServiceIntegrationTest` inclusive.

- [ ] **Step 7: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-payments
git commit -m "feat(payments): cielo notifications and card reconciliation

A stored notification is only a PaymentId and a ChangeType: the query of that
PaymentId decides. Status changes move an authorization to COMPLETED or
CANCELED; a refund done outside the gateway, a denied cancel and a fraud
alert open divergences; an unknown PaymentId is IGNORED. The periodic pass
queries card payments one by one within their own window and cap, and flags an
authorization past five days as CAPTURE_OVERDUE after the query, so one
captured at the Cielo is completed rather than flagged. The Pix pass no longer
reads card rows.

WebhookInboxService reaches eight dependencies; a handler per provider is the
refactor that would bring it back, left for its own change.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---
### Task 11: A API do cartão no `app` — request, resposta, captura, cartões, erros e mascaramento

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/api/payment/dto/{CardPaymentRequest,CardFields,CardCustomer,CaptureRequestBody}.java`
- Modify: `gateway-app/src/main/java/com/gateway/app/api/payment/dto/{CreatePaymentRequest,PaymentResponse}.java`
- Modify: `gateway-app/src/main/java/com/gateway/app/api/payment/PaymentsController.java` (+ `/capture`)
- Create: `gateway-app/src/main/java/com/gateway/app/api/card/CardsController.java`, `api/card/dto/CardResponse.java`
- Modify: `gateway-app/src/main/java/com/gateway/app/api/support/{ErrorHandler,IdempotencyFilter}.java`
- Modify: `gateway-app/src/main/java/com/gateway/app/observability/Masker.java`
- Create: `gateway-app/src/main/java/com/gateway/app/observability/CardJsonMixins.java`
- Test: `gateway-app/src/test/java/com/gateway/app/api/payment/dto/{CreatePaymentRequestTest,CardPaymentJsonContractTest}.java`, `gateway-app/src/test/java/com/gateway/app/api/support/ErrorHandlerTest.java`, `gateway-app/src/test/java/com/gateway/app/observability/{MaskerTest,CardJsonMixinsTest}.java`

**Interfaces:**
- Consumes: Tasks 1, 6–9 (`CreateCardPayment`, `CardChoice`, `CardCustomerData`, `CardDataFactory`, `CardCapture`, `SavedCards`, `CardDeclinedException`, `PaymentEvents.paymentJson`).
- Produces (HTTP):
  ```
  POST /v1/payments  {"method":"CARD", amount, currency, reference?, description?, soft_descriptor?, card{number, holder, expiry, cvv, brand?} | card_id + cvv,
                      installments?, capture?, save_card?, customer{name, document?, email?}}
       201 {…, "pix": null, "boleto": null, "card": {brand, last4, installments, authorization_code, tid, captured_amount, card_id}}
       402 CARD_DECLINED {decline_code, payment_id}; 422 CARD_INVALID | CARD_NOT_FOUND | INVALID_INSTALLMENTS | INVALID_SOFT_DESCRIPTOR | CUSTOMER_REQUIRED
  POST /v1/payments/{id}/capture {amount?} → 200 payment; 409 CAPTURE_NOT_ALLOWED | ALREADY_CAPTURED; 422 CAPTURE_AMOUNT_INVALID   (Idempotency-Key honoured)
  GET /v1/cards/{id} → 200 {id, brand, last4, expiry "MM/YYYY", holder, created_at};  DELETE /v1/cards/{id} → 204;  404 NOT_FOUND for absent/deleted/other merchant
  400 INVALID_REQUEST "method must be PIX, BOLECODE or CARD"  (declared contract change)
  ```
- Produces (Java): `Masker.mask` também mascara PAN (13–19 dígitos, com ou sem espaço/hífen, que passam em Luhn → `****last4`) e `"cvv"`/`"SecurityCode"`; `CardJsonMixins` (bean `JsonMapperBuilderCustomizer`) torna `CardData`, `CardToken`, `CardNumber` e `Secret` tipos ignorados pelo Jackson do app.

- [ ] **Step 1: Os testes**

Acrescente a `gateway-app/src/test/java/com/gateway/app/api/payment/dto/CreatePaymentRequestTest.java` (imports `com.gateway.kernel.errors.DomainException`, `com.gateway.payments.payment.create.{CardChoice,CreateCardPayment}`):
```java
  static final String CARD_BODY =
      "{\"method\":\"CARD\",\"amount\":12990,\"currency\":\"BRL\",\"reference\":\"order-42\","
          + "\"description\":\"Pedido 42\",\"soft_descriptor\":\"LOJA42\","
          + "\"card\":{\"number\":\"4024007153763171\",\"holder\":\"JOAO DA SILVA\",\"expiry\":\"12/2030\","
          + "\"cvv\":\"123\",\"brand\":\"VISA\"},"
          + "\"installments\":3,\"capture\":true,\"save_card\":true,"
          + "\"customer\":{\"name\":\"Joao da Silva\",\"document\":\"12345678901\",\"email\":\"joao@example.com\"}}";

  /** Spec §9's own example body. */
  @Test
  void aCardBodyDeserialisesToTheCardShapeAndBuildsTheCommand() {
    CreatePaymentRequest request = read(CARD_BODY);

    assertThat(request).isInstanceOf(CardPaymentRequest.class);
    request.validate();
    CreateCardPayment command =
        (CreateCardPayment) request.toCommand(MerchantId.next(), ProviderEnvironment.TEST);
    assertThat(command.card()).isInstanceOf(CardChoice.NewCard.class);
    assertThat(((CardChoice.NewCard) command.card()).save()).isTrue();
    assertThat(((CardChoice.NewCard) command.card()).card().last4()).isEqualTo("3171");
    assertThat(command.installments()).isEqualTo(3);
    assertThat(command.softDescriptor()).isEqualTo("LOJA42");
    assertThat(command.customer().email()).isEqualTo("joao@example.com");
  }

  @Test
  void aCardRequestNeverPrintsTheCard() {
    assertThat(read(CARD_BODY).toString()).doesNotContain("4024007153763171").doesNotContain("\"123\"").doesNotContain("cvv=123");
  }

  @Test
  void cardAndCardIdAreExclusive() {
    CreatePaymentRequest both =
        read(CARD_BODY.replace("\"installments\":3", "\"card_id\":\"01K0CARD\",\"installments\":3"));
    CreatePaymentRequest neither =
        read("{\"method\":\"CARD\",\"amount\":100,\"currency\":\"BRL\",\"customer\":{\"name\":\"Ana\"}}");

    assertThatThrownBy(both::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("exactly one of card and card_id is required");
    assertThatThrownBy(neither::validate)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("exactly one of card and card_id is required");
  }

  /** Plan D3: with card_id the cvv is required. */
  @Test
  void aCardIdWithoutCvvIsCardInvalid() {
    CreatePaymentRequest request =
        read(
            "{\"method\":\"CARD\",\"amount\":100,\"currency\":\"BRL\",\"card_id\":\"01K0CARD\","
                + "\"customer\":{\"name\":\"Ana\"}}");
    request.validate();

    assertThatThrownBy(() -> request.toCommand(MerchantId.next(), ProviderEnvironment.TEST))
        .isInstanceOf(DomainException.class)
        .hasMessage("cvv is required with card_id");
  }

  @Test
  void aBadCardNumberIsCardInvalidNamingTheField() {
    CreatePaymentRequest request = read(CARD_BODY.replace("4024007153763171", "4024007153763172"));

    assertThatThrownBy(() -> request.toCommand(MerchantId.next(), ProviderEnvironment.TEST))
        .isInstanceOf(DomainException.class)
        .satisfies(
            thrown -> {
              assertThat(((DomainException) thrown).code()).isEqualTo("CARD_INVALID");
              assertThat(thrown.getMessage()).isEqualTo("card.number must pass the Luhn check");
            });
  }

  @Test
  void aPixFieldInACardBodyIsRefused() {
    assertThatThrownBy(() -> read(CARD_BODY.replace("\"installments\":3", "\"expires_in\":60")))
        .isInstanceOf(JacksonException.class);
  }
```

`gateway-app/src/test/java/com/gateway/app/api/payment/dto/CardPaymentJsonContractTest.java`:
```java
package com.gateway.app.api.payment.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.card.CardDetails;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * One resource, one vocabulary: the REST response of a card payment and its webhook JSON have the
 * same keys, the card block included (spec §9), and neither carries card data.
 */
class CardPaymentJsonContractTest {
  final JsonMapper mapper =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
  final Clock clock = Clock.fixed(Instant.parse("2026-09-28T12:00:00Z"), ZoneOffset.UTC);

  @Test
  @SuppressWarnings("unchecked")
  void restAndWebhookAgreeOnTheCardBlock() {
    Payment payment =
        Payment.createCard(
            MerchantId.next(),
            ProviderEnvironment.TEST,
            "CIELO",
            Money.brl(12990),
            "order-42",
            "Pedido 42",
            null,
            CardDetails.requested(3, "VISA", "3171", null),
            clock);
    payment.markCompletedByCard(
        new CardDetails("pid", "tid", "auth", "pos", "VISA", "3171", 3, null, "card-1", null),
        Money.brl(12990),
        clock.instant(),
        EventSource.API);

    Map<String, Object> rest = mapper.convertValue(PaymentResponse.from(payment), Map.class);
    Map<String, Object> webhook = PaymentEvents.paymentJson(payment);

    assertThat(rest.keySet()).isEqualTo(webhook.keySet());
    assertThat(rest.get("pix")).isNull();
    assertThat(rest.get("boleto")).isNull();
    assertThat(((Map<String, Object>) rest.get("card")).keySet())
        .isEqualTo(((Map<String, Object>) webhook.get("card")).keySet());
    assertThat((Map<String, Object>) rest.get("card"))
        .containsEntry("last4", "3171")
        .containsEntry("card_id", "card-1")
        .containsEntry("authorization_code", "auth");
  }
}
```

Acrescente a `gateway-app/src/test/java/com/gateway/app/api/support/ErrorHandlerTest.java` (imports `com.gateway.kernel.errors.DomainException`, `com.gateway.payments.payment.card.CardDeclinedException`, `org.springframework.http.ProblemDetail`):
```java
  @Test
  void cardCodesHaveTheirStatuses() {
    ErrorHandler handler = new ErrorHandler();

    assertThat(handler.domainError(new DomainException("CAPTURE_NOT_ALLOWED", "x")).getStatus()).isEqualTo(409);
    assertThat(handler.domainError(new DomainException("ALREADY_CAPTURED", "x")).getStatus()).isEqualTo(409);
    assertThat(handler.domainError(new DomainException("ALREADY_PAID", "x")).getStatus()).isEqualTo(409);
    assertThat(handler.domainError(new DomainException("CAPTURE_AMOUNT_INVALID", "x")).getStatus()).isEqualTo(422);
    assertThat(handler.domainError(new DomainException("CARD_NOT_FOUND", "x")).getStatus()).isEqualTo(422);
    assertThat(handler.domainError(new DomainException("CARD_INVALID", "x")).getStatus()).isEqualTo(422);
  }

  /** Spec §9: a decline is 402 with our decline_code; the issuer's text is never there. */
  @Test
  void aDeclineIs402WithTheDeclineCodeAndThePayment() {
    ProblemDetail problem =
        new ErrorHandler().cardDeclined(new CardDeclinedException("01K0PAY", "INSUFFICIENT_FUNDS"));

    assertThat(problem.getStatus()).isEqualTo(402);
    assertThat(problem.getType()).hasToString("urn:gateway:CARD_DECLINED");
    assertThat(problem.getDetail()).isEqualTo("The card was declined.");
    assertThat(problem.getProperties())
        .containsEntry("decline_code", "INSUFFICIENT_FUNDS")
        .containsEntry("payment_id", "01K0PAY");
  }
```
(se o arquivo não importar `assertThat`/`@Test`, acrescente `import static org.assertj.core.api.Assertions.assertThat;` e `import org.junit.jupiter.api.Test;`.)

Acrescente a `gateway-app/src/test/java/com/gateway/app/observability/MaskerTest.java`:
```java
  /** Spec §7: 13–19 digits that pass Luhn become ****last4, however the payer grouped them. */
  @Test
  void masksCardNumbersThatPassLuhn() {
    assertThat(Masker.mask("number 4024007153763171 ok")).isEqualTo("number ****3171 ok");
    assertThat(Masker.mask("{\"number\":\"4024 0071 5376 3171\"}")).isEqualTo("{\"number\":\"****3171\"}");
    assertThat(Masker.mask("pan 4024-0071-5376-3171")).isEqualTo("pan ****3171");
    assertThat(Masker.mask("amex 378282246310005")).isEqualTo("amex ****0005");
  }

  /** A long number that fails Luhn is not a card: an order id stays readable. */
  @Test
  void leavesLongNumbersThatFailLuhnAlone() {
    assertThat(Masker.mask("order 4024007153763172")).isEqualTo("order 4024007153763172");
  }

  @Test
  void masksTheCvvInOursAndTheCielosSpelling() {
    assertThat(Masker.mask("{\"cvv\":\"123\",\"SecurityCode\": \"4567\",\"holder\":\"JOAO\"}"))
        .isEqualTo("{\"cvv\":\"***\",\"SecurityCode\": \"***\",\"holder\":\"JOAO\"}");
  }
```

`gateway-app/src/test/java/com/gateway/app/observability/CardJsonMixinsTest.java`:
```java
package com.gateway.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.security.Secret;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Spec §7: nothing the app's Jackson writes can carry a CardData, a CardToken or a Secret. */
class CardJsonMixinsTest {

  record Holder(String id, CardData card, CardToken token, Secret cvv) {}

  @Test
  void cardTypesAreLeftOutOfAnyJsonTheAppWrites() {
    JsonMapper.Builder builder = JsonMapper.builder();
    new CardJsonMixins().cardDataIsNeverSerialized().customize(builder);
    JsonMapper mapper = builder.build();

    String json =
        mapper.writeValueAsString(
            new Holder(
                "pay-1",
                CardData.of("4024007153763171", "JOAO DA SILVA", "12/2030", "123", null, YearMonth.of(2026, 9)),
                new CardToken("tok", CardBrand.VISA, CardOnFileUsage.USED, Secret.of("123")),
                Secret.of("123")));

    assertThat(json).isEqualTo("{\"id\":\"pay-1\"}");
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-app -am -Dtest='CreatePaymentRequestTest,CardPaymentJsonContractTest,ErrorHandlerTest,MaskerTest,CardJsonMixinsTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `CardPaymentRequest`, `CardJsonMixins`, `cardDeclined` não existem.

- [ ] **Step 3: O request**

`gateway-app/src/main/java/com/gateway/app/api/payment/dto/CardFields.java`:
```java
package com.gateway.app.api.payment.dto;

import com.gateway.kernel.provider.card.CardData;
import com.gateway.payments.payment.create.CardDataFactory;
import java.time.YearMonth;

/**
 * The {@code card} object of a CARD create, exactly as sent. Lives only until {@link #toCardData};
 * toString is overridden because a record's would print the number and the CVV into the first log
 * line or exception that touched the request.
 */
public record CardFields(String number, String holder, String expiry, String cvv, String brand) {

  CardData toCardData(YearMonth currentMonth) {
    return CardDataFactory.from(number, holder, expiry, cvv, brand, currentMonth);
  }

  @Override
  public String toString() {
    return "CardFields[***]";
  }
}
```

`gateway-app/src/main/java/com/gateway/app/api/payment/dto/CardCustomer.java`:
```java
package com.gateway.app.api.payment.dto;

import com.gateway.payments.payment.create.CardCustomerData;

/**
 * The customer of a CARD create: name (required by the domain), document and e-mail. Not {@link
 * Customer}: that one has the boleto's address, which a card does not take, and fail-on-unknown
 * should refuse an address on a card body rather than swallow it.
 */
public record CardCustomer(String name, String document, String email) {

  CardCustomerData toData() {
    return new CardCustomerData(name, document, email);
  }
}
```

`gateway-app/src/main/java/com/gateway/app/api/payment/dto/CardPaymentRequest.java`:
```java
package com.gateway.app.api.payment.dto;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardDataFactory;
import com.gateway.payments.payment.create.CreateCardPayment;
import com.gateway.payments.payment.create.CreatePaymentCommand;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * A credit card charge (spec 2026-09-28 §9): {@code card} or {@code card_id}, never both; {@code
 * cvv} goes with {@code card_id}. The card is turned into a {@code CardData} here, where the body is
 * read, so the command carries no raw number (spec §6.1); the domain still owns the 422 text
 * ({@link CardDataFactory}).
 *
 * <p>The current month is São Paulo's, the zone every date of this gateway is decided in.
 */
public record CardPaymentRequest(
    Long amount,
    String currency,
    String reference,
    String description,
    String softDescriptor,
    CardFields card,
    String cardId,
    String cvv,
    Integer installments,
    Boolean capture,
    Boolean saveCard,
    CardCustomer customer)
    implements CreatePaymentRequest {
  private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  @Override
  public void validate() {
    RequestedAmount.of(amount, currency);

    if ((card == null) == (cardId == null)) {
      throw new IllegalArgumentException("exactly one of card and card_id is required");
    }
    if (cardId != null && Boolean.TRUE.equals(saveCard)) {
      throw new IllegalArgumentException("save_card applies to a new card, not to card_id");
    }
  }

  @Override
  public CreatePaymentCommand toCommand(MerchantId merchantId, ProviderEnvironment environment) {
    return new CreateCardPayment(
        merchantId,
        environment,
        RequestedAmount.of(amount, currency),
        reference,
        description,
        choice(),
        installments,
        capture,
        softDescriptor,
        customer == null ? null : customer.toData());
  }

  private CardChoice choice() {
    if (card != null) {
      return new CardChoice.NewCard(
          card.toCardData(YearMonth.now(SAO_PAULO)), Boolean.TRUE.equals(saveCard));
    }

    return new CardChoice.SavedCardChoice(cardId, CardDataFactory.securityCodeForSavedCard(cvv));
  }

  @Override
  public String toString() {
    return "CardPaymentRequest[amount=" + amount + ", reference=" + reference + ", card=***]";
  }
}
```

`CreatePaymentRequest`:
```java
@JsonSubTypes({
  @JsonSubTypes.Type(value = PixPaymentRequest.class, name = "PIX"),
  @JsonSubTypes.Type(value = BolecodePaymentRequest.class, name = "BOLECODE"),
  @JsonSubTypes.Type(value = CardPaymentRequest.class, name = "CARD")
})
public sealed interface CreatePaymentRequest
    permits PixPaymentRequest, BolecodePaymentRequest, CardPaymentRequest {
```
(e o javadoc ganha "CARD takes a {@code card} or a {@code card_id} (spec 2026-09-28 §9)".)

`gateway-app/src/main/java/com/gateway/app/api/payment/dto/CaptureRequestBody.java`:
```java
package com.gateway.app.api.payment.dto;

/** POST /v1/payments/{id}/capture. No amount captures everything authorized. */
public record CaptureRequestBody(Long amount) {}
```

- [ ] **Step 4: A resposta**

Em `PaymentResponse`: novo componente `Card card` depois de `Boleto boleto`, o record
```java
  /** Spec §9: what the merchant's checkout shows; never a number, an expiry or a CVV. */
  public record Card(
      String brand,
      String last4,
      int installments,
      String authorizationCode,
      String tid,
      Long capturedAmount,
      String cardId) {}
```
e, em `from`, o Pix nulo para o cartão e o bloco novo:
```java
        pix == null
            ? null
            : new Pix(pix.txid(), pix.pixCopiaECola(), pix.location(), pix.endToEndId()),
        …(boleto como está)…,
        card == null
            ? null
            : new Card(
                card.brand(),
                card.last4(),
                card.installments(),
                card.authorizationCode(),
                card.tid(),
                card.capturedAmount(),
                card.cardId()),
```
com `CardDetails card = payment.card();` junto de `pix` e `boleto` (import `com.gateway.payments.payment.card.CardDetails`). O javadoc: "`pix`, `boleto` and `card` are null for the other methods so the key set is the same for every method". Um Pix sempre tem `pix` (Task 6, Step 8), então trocar `new Pix(payment.id(), …)` por `null` só alcança o cartão.

- [ ] **Step 5: Captura e cartões no controller; idempotência**

Em `PaymentsController`: campo e parâmetro do construtor `CardCapture capture` (import `com.gateway.payments.payment.card.CardCapture`, `com.gateway.app.api.payment.dto.CaptureRequestBody`, `com.gateway.kernel.money.Money`) e
```java
  /** Spec §6. Wrapped by IdempotencyFilter like every POST action on a payment. */
  @PostMapping("/{id}/capture")
  public ResponseEntity<PaymentResponse> capture(
      @PathVariable String id, @RequestBody(required = false) CaptureRequestBody body) {
    Long cents = body == null ? null : body.amount();
    if (cents != null && cents <= 0) {
      throw new IllegalArgumentException("amount must be a positive number of cents");
    }

    Payment captured =
        capture.capture(
            MerchantContext.current().merchantId(), id, cents == null ? null : Money.brl(cents));

    return withResource(HttpStatus.OK, captured);
  }
```

Em `IdempotencyFilter`:
```java
  private static final Pattern PAYMENT_ACTION = Pattern.compile("^/v1/payments/[^/]+/(cancel|refunds|capture)$");
```

`gateway-app/src/main/java/com/gateway/app/api/card/dto/CardResponse.java`:
```java
package com.gateway.app.api.card.dto;

import com.gateway.payments.card.SavedCard;
import java.time.Instant;

/** GET /v1/cards/{id} (spec §7): brand, last four, MM/YYYY and the holder. Never the token. */
public record CardResponse(
    String id, String brand, String last4, String expiry, String holder, Instant createdAt) {

  public static CardResponse from(SavedCard card) {
    return new CardResponse(
        card.id(),
        card.brand().name(),
        card.last4(),
        String.format("%02d/%04d", card.expiry().getMonthValue(), card.expiry().getYear()),
        card.holder(),
        card.createdAt());
  }
}
```

`gateway-app/src/main/java/com/gateway/app/api/card/CardsController.java`:
```java
package com.gateway.app.api.card;

import com.gateway.app.api.card.dto.CardResponse;
import com.gateway.app.security.MerchantContext;
import com.gateway.payments.card.SavedCards;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling merchant's saved cards. Another merchant's card is 404 like an absent one — never
 * 403 (spec §4). DELETE marks the row; the Cielo has no token deletion, so the token simply stops
 * being usable through the gateway.
 */
@RestController
@RequestMapping("/v1/cards")
public class CardsController {
  private final SavedCards cards;

  public CardsController(SavedCards cards) {
    this.cards = cards;
  }

  @GetMapping("/{id}")
  public CardResponse get(@PathVariable String id) {
    return CardResponse.from(cards.get(MerchantContext.current().merchantId(), id));
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(@PathVariable String id) {
    cards.delete(MerchantContext.current().merchantId(), id);
  }
}
```

- [ ] **Step 6: `ErrorHandler`**

Em `ErrorHandler` (imports `com.gateway.payments.payment.card.CardDeclinedException`, `java.util.Map`):
```java
  /**
   * The domain codes that are not a 422. ALREADY_PAID and the two capture conflicts are 409: the
   * resource is in a state the call cannot change (the cancel lost to the payer; the sale was
   * captured already, or is not an authorization). CARD_DECLINED has its own handler (402).
   */
  private static final Map<String, HttpStatus> STATUS_BY_CODE =
      Map.of(
          "ALREADY_PAID", HttpStatus.CONFLICT,
          "CAPTURE_NOT_ALLOWED", HttpStatus.CONFLICT,
          "ALREADY_CAPTURED", HttpStatus.CONFLICT);

  @ExceptionHandler(DomainException.class)
  public ProblemDetail domainError(DomainException e) {
    HttpStatus status = STATUS_BY_CODE.getOrDefault(e.code(), HttpStatus.UNPROCESSABLE_ENTITY);
    return problem(status, e.code(), e.getMessage());
  }

  /**
   * Spec §9: 402 with our decline_code, and the payment id — the payment exists, FAILED, and the
   * merchant needs it to reconcile. The detail is fixed; the issuer's text never reaches here.
   */
  @ExceptionHandler(CardDeclinedException.class)
  public ProblemDetail cardDeclined(CardDeclinedException e) {
    ProblemDetail problem = problem(HttpStatus.PAYMENT_REQUIRED, e.code(), e.getMessage());
    problem.setProperty("decline_code", e.declineCode());
    problem.setProperty("payment_id", e.paymentId());
    return problem;
  }
```
(substitui o `domainError` atual e seu javadoc.) Em `unreadableBody`, a mensagem passa a `"method must be PIX, BOLECODE or CARD"` e o javadoc do método registra a mudança: "An error message is contract: it gained CARD with the card method (plan 2026-09-28, Global Constraints)."

- [ ] **Step 7: `Masker` e o mixin do Jackson**

`Masker.java` — acrescente os padrões e aplique o PAN primeiro:
```java
  /** 13–19 digits, grouped by spaces or hyphens or not; only those that pass Luhn are masked. */
  private static final Pattern CARD_NUMBER = Pattern.compile("(?<![\\d-])\\d(?:[ -]?\\d){12,18}(?![\\d-])");

  private static final Pattern CARD_SECURITY_CODE =
      Pattern.compile("(\"(?:cvv|SecurityCode)\"\\s*:\\s*\")\\d+(\")");

  public static String mask(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    String r = maskCardNumbers(s);
    r = CARD_SECURITY_CODE.matcher(r).replaceAll("$1***$2");
    r = BEARER.matcher(r).replaceAll("$1***");
    r = API_KEY.matcher(r).replaceAll("***");
    r = CPF.matcher(r).replaceAll("***");
    r = FIELDS.matcher(r).replaceAll("$1***$2");
    return r;
  }

  /**
   * Spec §7: a PAN becomes ****last4. Luhn-checked so a 13-digit epoch millisecond or an order id
   * survives nine times out of ten; the tenth is masked, which is the cheap mistake.
   */
  private static String maskCardNumbers(String s) {
    Matcher matcher = CARD_NUMBER.matcher(s);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String digits = matcher.group().replaceAll("[ -]", "");
      String replacement =
          passesLuhn(digits) ? "****" + digits.substring(digits.length() - 4) : matcher.group();
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static boolean passesLuhn(String digits) {
    int sum = 0;
    boolean doubleIt = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int digit = digits.charAt(i) - '0';
      if (doubleIt) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubleIt = !doubleIt;
    }
    return sum % 10 == 0;
  }
```
(import `java.util.regex.Matcher`; o `mask` antigo é substituído por este.)

`gateway-app/src/main/java/com/gateway/app/observability/CardJsonMixins.java`:
```java
package com.gateway.app.observability;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardNumber;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.security.Secret;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spec §7 asks for {@code @JsonIgnoreType} on CardData; the kernel has no Jackson (plan C1), so the
 * annotation is applied here, as a mixin on the app's JsonMapper — the one that writes responses,
 * idempotency replays and JSON logs. A property of these types is left out of anything it writes.
 */
@Configuration(proxyBeanMethods = false)
public class CardJsonMixins {

  @JsonIgnoreType
  private interface NeverSerialized {}

  @Bean
  public JsonMapperBuilderCustomizer cardDataIsNeverSerialized() {
    return builder ->
        builder
            .addMixIn(CardData.class, NeverSerialized.class)
            .addMixIn(CardToken.class, NeverSerialized.class)
            .addMixIn(CardNumber.class, NeverSerialized.class)
            .addMixIn(Secret.class, NeverSerialized.class);
  }
}
```

- [ ] **Step 8: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-app -am -Dtest='CreatePaymentRequestTest,CardPaymentJsonContractTest,ErrorHandlerTest,MaskerTest,CardJsonMixinsTest,PaymentJsonContractTest,ArchitectureTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS. (Se `Secret` como tipo ignorado quebrar a serialização de algum DTO admin que exponha um `Secret` de propósito — `grep -rn "Secret " gateway-app/src/main/java/com/gateway/app/api` não acha nenhum hoje —, tire `Secret` da lista e registre o motivo no comentário.)

- [ ] **Step 9: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-app
git commit -m "feat(app): card payments api, capture, saved cards and 402

CardPaymentRequest is the third sealed variant: card or card_id, the card
turned into CardData where the body is read, toString never printing it. The
response gains the card block and a null pix for cards; capture is a POST
action under the idempotency key; GET/DELETE /v1/cards answer 404 for another
merchant's card. CARD_DECLINED is a 402 with decline_code and payment_id;
the two capture conflicts are 409.

Contract change, declared: an unknown method now answers \"method must be
PIX, BOLECODE or CARD\". Masker masks Luhn-valid card numbers and the cvv, and
the app's JsonMapper ignores the card types the kernel cannot annotate.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---
### Task 12: A notificação da Cielo na borda, a chave do merchant, o `application.yml` e os dois testes de ponta a ponta

**Files:**
- Modify: `gateway-merchants/src/main/java/com/gateway/merchants/credential/Provider.java` (+ `CIELO`)
- Create: `gateway-merchants/src/main/resources/db/migration/merchants/V102__inbound_notification_keys.sql`
- Create: `gateway-merchants/src/main/java/com/gateway/merchants/notification/InboundNotificationKeyService.java`, `notification/persistence/{InboundNotificationKeyEntity,InboundNotificationKeyJpaRepository,InboundNotificationKeyRepository,InboundNotificationKeyRepositoryImpl}.java`
- Modify: `gateway-merchants/src/main/java/com/gateway/merchants/MerchantsConfiguration.java`
- Modify: `gateway-app/src/main/java/com/gateway/app/api/admin/MerchantsAdminController.java` (+ `PUT …/notification-key`), Create: `api/admin/dto/NotificationKeyRequest.java`
- Create: `gateway-app/src/main/java/com/gateway/app/inbound/card/CardNotificationController.java`
- Modify: `gateway-app/src/main/java/com/gateway/app/inbound/mtls/MtlsPortFilter.java` (só Itaú na porta mTLS)
- Modify: `gateway-app/src/main/resources/application.yml`
- Modify: `gateway-app/src/test/java/com/gateway/app/architecture/ArchitectureTest.java` (+ `cieloVocabularyStaysInProviders`, contagem)
- Test: `gateway-merchants/src/test/java/com/gateway/merchants/notification/InboundNotificationKeyServiceIntegrationTest.java`, `gateway-app/src/test/java/com/gateway/app/inbound/mtls/MtlsPortFilterTest.java`, `gateway-app/src/test/java/com/gateway/app/{CardFlowIntegrationTest,CardDataNeverLeavesTheRequestTest}.java`, `gateway-app/src/test/resources/cielo/fixtures/` (cópia dos fixtures de `gateway-providers` que os testes usam)

**Interfaces:**
- Consumes: Tasks 5, 7–11.
- Produces:
  ```java
  class InboundNotificationKeyService {
    void set(MerchantId merchantId, String provider, Secret key);                    // stores SHA-256 of the key; replaces the previous
    boolean matches(MerchantId merchantId, String provider, String presented);      // constant time; false when none is set or presented is null
  }
  // HTTP
  PUT  /v1/admin/merchants/{id}/providers/{provider}/notification-key {"key": "..."}  → 204   (admin key)
  POST /v1/providers/cielo/webhooks/{token}  header X-Gateway-Notification-Key       → 200 | 404 (unknown token, missing or wrong key) | 413
  ```

**Decisão (plan C6, C7):** a chave que o merchant configura no site da Cielo como header fixo (`docs/webhook`: "até 3 tipos de informação de retorno no header") é um conceito novo dos merchants, com tabela própria, e só o **hash** fica guardado: a comparação em tempo constante é entre hashes, e um vazamento da tabela não entrega o header. O `MtlsPortFilter` passa a reservar só `/v1/providers/itau/**` para a porta mTLS; a Cielo não oferece mTLS e só entrega em HTTPS na 443 (`docs/webhook`), ou seja, no conector principal.

- [ ] **Step 1: A chave do merchant (merchants)**

`gateway-merchants/src/test/java/com/gateway/merchants/notification/InboundNotificationKeyServiceIntegrationTest.java`:
```java
package com.gateway.merchants.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.security.Secret;
import com.gateway.merchants.TestApp;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(classes = TestApp.class)
@Testcontainers
class InboundNotificationKeyServiceIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired InboundNotificationKeyService keys;
  @Autowired JdbcTemplate jdbc;

  @Test
  void matchesOnlyTheKeyThatWasSetAndStoresOnlyItsHash() {
    MerchantId merchant = MerchantId.next();
    keys.set(merchant, "CIELO", Secret.of("s3cr3t-header-value"));

    assertThat(keys.matches(merchant, "CIELO", "s3cr3t-header-value")).isTrue();
    assertThat(keys.matches(merchant, "CIELO", "s3cr3t-header-valuE")).isFalse();
    assertThat(keys.matches(merchant, "CIELO", null)).isFalse();
    assertThat(keys.matches(MerchantId.next(), "CIELO", "s3cr3t-header-value")).isFalse();
    assertThat(
            jdbc.queryForObject(
                "SELECT key_hash FROM merchants.inbound_notification_keys WHERE merchant_id = ?",
                String.class,
                merchant.value()))
        .hasSize(64)
        .doesNotContain("s3cr3t");
  }

  @Test
  void settingAgainReplacesTheKey() {
    MerchantId merchant = MerchantId.next();
    keys.set(merchant, "CIELO", Secret.of("old"));
    keys.set(merchant, "CIELO", Secret.of("new"));

    assertThat(keys.matches(merchant, "CIELO", "old")).isFalse();
    assertThat(keys.matches(merchant, "CIELO", "new")).isTrue();
  }

  @Test
  void aMerchantWithoutAKeyMatchesNothing() {
    assertThat(keys.matches(MerchantId.next(), "CIELO", "anything")).isFalse();
  }
}
```

`gateway-merchants/src/main/resources/db/migration/merchants/V102__inbound_notification_keys.sql`:
```sql
-- The fixed header a merchant configures at the acquirer for its notifications (the Cielo's "Post de
-- Notificação" has no signature; docs/webhook: up to three static headers). Only the SHA-256 of the
-- value is kept: the check compares hashes in constant time, and a leaked row does not hand out the
-- header. One per merchant and provider.
CREATE TABLE merchants.inbound_notification_keys (
    id          CHAR(26)    PRIMARY KEY,
    merchant_id CHAR(26)    NOT NULL,
    provider    VARCHAR(20) NOT NULL,
    key_hash    CHAR(64)    NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_inbound_notification_keys UNIQUE (merchant_id, provider)
);
```

`gateway-merchants/src/main/java/com/gateway/merchants/notification/persistence/InboundNotificationKeyEntity.java`:
```java
package com.gateway.merchants.notification.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "inbound_notification_keys", schema = "merchants")
class InboundNotificationKeyEntity {
  // CHAR(n) needs @JdbcTypeCode(CHAR) for schema validation (see ProviderCredentialEntity).
  @Id
  @Column(name = "id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String id;

  @Column(name = "merchant_id", length = 26, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String merchantId;

  @Column(name = "provider", nullable = false, length = 20)
  String provider;

  @Column(name = "key_hash", length = 64, nullable = false)
  @JdbcTypeCode(SqlTypes.CHAR)
  String keyHash;

  @Column(name = "created_at", nullable = false)
  Instant createdAt;

  protected InboundNotificationKeyEntity() {}
}
```

`gateway-merchants/src/main/java/com/gateway/merchants/notification/persistence/InboundNotificationKeyJpaRepository.java`:
```java
package com.gateway.merchants.notification.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

interface InboundNotificationKeyJpaRepository
    extends JpaRepository<InboundNotificationKeyEntity, String> {
  Optional<InboundNotificationKeyEntity> findByMerchantIdAndProvider(String merchantId, String provider);
}
```

`gateway-merchants/src/main/java/com/gateway/merchants/notification/persistence/InboundNotificationKeyRepository.java`:
```java
package com.gateway.merchants.notification.persistence;

import com.gateway.kernel.ids.MerchantId;
import java.util.Optional;

public interface InboundNotificationKeyRepository {
  void upsert(MerchantId merchantId, String provider, String keyHash);

  Optional<String> findHash(MerchantId merchantId, String provider);
}
```

`gateway-merchants/src/main/java/com/gateway/merchants/notification/persistence/InboundNotificationKeyRepositoryImpl.java`:
```java
package com.gateway.merchants.notification.persistence;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class InboundNotificationKeyRepositoryImpl implements InboundNotificationKeyRepository {
  private final InboundNotificationKeyJpaRepository jpa;

  public InboundNotificationKeyRepositoryImpl(InboundNotificationKeyJpaRepository jpa) {
    this.jpa = jpa;
  }

  @Override
  @Transactional
  public void upsert(MerchantId merchantId, String provider, String keyHash) {
    InboundNotificationKeyEntity entity =
        jpa.findByMerchantIdAndProvider(merchantId.value(), provider)
            .orElseGet(
                () -> {
                  InboundNotificationKeyEntity created = new InboundNotificationKeyEntity();
                  created.id = Ulid.next();
                  created.merchantId = merchantId.value();
                  created.provider = provider;
                  return created;
                });
    entity.keyHash = keyHash;
    entity.createdAt = Instant.now();
    jpa.save(entity);
  }

  @Override
  public Optional<String> findHash(MerchantId merchantId, String provider) {
    return jpa.findByMerchantIdAndProvider(merchantId.value(), provider).map(entity -> entity.keyHash);
  }
}
```

`gateway-merchants/src/main/java/com/gateway/merchants/notification/InboundNotificationKeyService.java`:
```java
package com.gateway.merchants.notification;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.security.Secret;
import com.gateway.merchants.notification.persistence.InboundNotificationKeyRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * The value an acquirer sends back as a fixed header on its notifications — the only proof they
 * came from it when, like the Cielo, it offers neither mTLS nor a signature (spec §8). Compared in
 * constant time, as SHA-256 hashes, so neither timing nor a leaked row reveals it.
 */
public class InboundNotificationKeyService {
  private final InboundNotificationKeyRepository keys;

  public InboundNotificationKeyService(InboundNotificationKeyRepository keys) {
    this.keys = keys;
  }

  public void set(MerchantId merchantId, String provider, Secret key) {
    keys.upsert(merchantId, provider, sha256Hex(key.reveal()));
  }

  public boolean matches(MerchantId merchantId, String provider, String presented) {
    if (presented == null || presented.isEmpty()) {
      return false;
    }

    Optional<String> stored = keys.findHash(merchantId, provider);
    return stored.isPresent()
        && MessageDigest.isEqual(
            stored.get().getBytes(StandardCharsets.US_ASCII),
            sha256Hex(presented).getBytes(StandardCharsets.US_ASCII));
  }

  private static String sha256Hex(String value) {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
```

Em `MerchantsConfiguration`: acrescente `"com.gateway.merchants.notification.persistence"` a `@EntityScan` e `@EnableJpaRepositories`, `InboundNotificationKeyRepositoryImpl.class` ao `@Import`, e
```java
  @Bean
  public InboundNotificationKeyService inboundNotificationKeyService(
      InboundNotificationKeyRepository keys) {
    return new InboundNotificationKeyService(keys);
  }
```
Em `credential/Provider.java`: `ITAU, CIELO, FAKE` (o comentário da coluna em V100 diz `ITAU | FAKE`; a coluna é `VARCHAR(20)` sem CHECK, então nenhuma migração).

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-merchants -am test`
Expected: PASS.

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-merchants
git commit -m "feat(merchants): cielo provider and the inbound notification key

The Cielo's notification carries no signature and no client certificate;
its only authentication is a fixed header the merchant configures at the
Cielo. The key is a merchants concept with its own table, stored as SHA-256
and compared in constant time. CIELO joins the credential providers.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

- [ ] **Step 2: A porta mTLS só para o Itaú**

Acrescente a `gateway-app/src/test/java/com/gateway/app/inbound/mtls/MtlsPortFilterTest.java`:
```java
  /** The Cielo offers no mTLS and delivers only on 443 (docs/webhook): its path is on the main port. */
  @Test
  void aCieloNotificationPassesOnTheMainPortAndIsFencedOffTheMtlsOne() throws Exception {
    MtlsPortFilter filter =
        new MtlsPortFilter(new WebhookMtlsProperties(PORT, "ks", "", "ts", "", null, 1024, List.of()));

    MockHttpServletRequest onMain = new MockHttpServletRequest("POST", "/v1/providers/cielo/webhooks/T");
    onMain.setLocalPort(8080);
    MockFilterChain mainChain = new MockFilterChain();
    filter.doFilter(onMain, new MockHttpServletResponse(), mainChain);

    MockHttpServletRequest onMtls = new MockHttpServletRequest("POST", "/v1/providers/cielo/webhooks/T");
    onMtls.setLocalPort(PORT);
    MockHttpServletResponse mtlsResponse = new MockHttpServletResponse();
    filter.doFilter(onMtls, mtlsResponse, new MockFilterChain());

    MockHttpServletRequest itauOnMain = new MockHttpServletRequest("POST", "/v1/providers/itau/webhooks/T");
    itauOnMain.setLocalPort(8080);
    MockHttpServletResponse itauResponse = new MockHttpServletResponse();
    filter.doFilter(itauOnMain, itauResponse, new MockFilterChain());

    assertThat(mainChain.getRequest()).isNotNull();
    assertThat(mtlsResponse.getStatus()).isEqualTo(403);
    assertThat(itauResponse.getStatus()).isEqualTo(404);
  }
```

Em `MtlsPortFilter`: o javadoc troca "provider paths anywhere but the mTLS port are 404" por "the Itaú's provider paths anywhere but the mTLS port are 404 (the Cielo's live on the main connector: it offers no client certificate and delivers only on 443)", e
```java
  /** Only the bank that authenticates with a client certificate is fenced onto the mTLS port. */
  static boolean isProviderPath(String path) {
    return path.equals("/v1/providers/itau") || path.startsWith("/v1/providers/itau/");
  }
```

- [ ] **Step 3: O endpoint de notificação e a chave no admin**

`gateway-app/src/main/java/com/gateway/app/inbound/card/CardNotificationController.java`:
```java
package com.gateway.app.inbound.card;

import com.gateway.app.inbound.pix.WebhookTokenGuard;
import com.gateway.app.security.Problems;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.notification.InboundNotificationKeyService;
import com.gateway.payments.inbox.WebhookInboxService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

/**
 * The Cielo's "Post de Notificação" (spec §8, docs/webhook). No mTLS and no signature exist, so the
 * caller is authenticated by two things together: the merchant's URL token and the fixed header the
 * merchant configured at the Cielo. Either missing or wrong is the same 404 as an unknown token.
 *
 * <p>200, not the 202 of the Itaú path: "A loja deverá retornar como resposta à notificação: HTTP
 * Status Code 200 OK" (plan D13). The body is only stored; the inbox job queries the sale.
 *
 * <p>Named without the acquirer: ArchitectureTest.cieloVocabularyStaysInProviders.
 */
@RestController
public class CardNotificationController {
  static final String KEY_HEADER = "X-Gateway-Notification-Key";
  static final String PROVIDER = "CIELO";

  /** Three fields (docs/webhook); 16 KB is generous and still bounded for a chunked body. */
  private static final int MAX_BODY_BYTES = 16 * 1024;

  private final WebhookTokenGuard guard;
  private final InboundNotificationKeyService keys;
  private final WebhookInboxService inbox;
  private final ObjectMapper json;

  public CardNotificationController(
      WebhookTokenGuard guard,
      InboundNotificationKeyService keys,
      WebhookInboxService inbox,
      ObjectMapper json) {
    this.guard = guard;
    this.keys = keys;
    this.inbox = inbox;
    this.json = json;
  }

  @PostMapping("/v1/providers/cielo/webhooks/{token}")
  public ResponseEntity<Void> receive(
      @PathVariable String token, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    Merchant merchant = guard.resolve(token);
    if (!keys.matches(merchant.id(), PROVIDER, request.getHeader(KEY_HEADER))) {
      throw new NotFoundException("webhook", "unknown");
    }

    byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
    if (body.length > MAX_BODY_BYTES) {
      Problems.write(response, 413, "PAYLOAD_TOO_LARGE", "notification body exceeds " + MAX_BODY_BYTES + " bytes");
      return null;
    }

    inbox.accept(PROVIDER, merchant.id(), headers(request), body);
    return ResponseEntity.ok().build();
  }

  /** What traces a delivery; never the notification key header itself. */
  private String headers(HttpServletRequest request) {
    Map<String, String> traced = new LinkedHashMap<>();
    put(traced, "X-Correlation-Id", request.getHeader("X-Correlation-Id"));
    put(traced, "User-Agent", request.getHeader("User-Agent"));
    put(traced, "Content-Type", request.getContentType());
    return json.writeValueAsString(traced);
  }

  private static void put(Map<String, String> traced, String name, String value) {
    if (value != null) {
      traced.put(name, value);
    }
  }
}
```

`gateway-app/src/main/java/com/gateway/app/api/admin/dto/NotificationKeyRequest.java`:
```java
package com.gateway.app.api.admin.dto;

/** The value the merchant typed as the fixed header at the acquirer (Cielo: up to 1500 chars). */
public record NotificationKeyRequest(String key) {

  @Override
  public String toString() {
    return "NotificationKeyRequest[***]";
  }
}
```

Em `MerchantsAdminController`: campo e parâmetro do construtor `InboundNotificationKeyService notificationKeys`, e
```java
  /**
   * The header value the merchant configures at the acquirer's portal. 1500 is the Cielo's own
   * limit for a header value (docs/webhook, "VALUE — limitado a 1500 caracteres").
   */
  @PutMapping("/{id}/providers/{provider}/notification-key")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void storeNotificationKey(
      @PathVariable String id,
      @PathVariable Provider provider,
      @RequestBody NotificationKeyRequest request) {
    if (request.key() == null || request.key().isBlank() || request.key().length() > 1500) {
      throw new IllegalArgumentException("key must be 1 to 1500 characters");
    }

    merchants.get(new MerchantId(id));
    notificationKeys.set(new MerchantId(id), provider.name(), Secret.of(request.key()));
  }
```
(imports `com.gateway.merchants.notification.InboundNotificationKeyService`, `com.gateway.kernel.security.Secret`, `com.gateway.app.api.admin.dto.NotificationKeyRequest`.)

- [ ] **Step 4: `application.yml` e o ArchUnit**

Em `application.yml`, dentro de `gateway.providers`, depois do bloco `itau`:
```yaml
    cielo:
      # docs/providers/cielo/NOTES.md, "Hosts": transactional and query, per environment. Overridden by
      # the app's tests to point at WireMock.
      live-api-base: https://api.cieloecommerce.cielo.com.br
      live-query-api-base: https://apiquery.cieloecommerce.cielo.com.br
      test-api-base: https://apisandbox.cieloecommerce.cielo.com.br
      test-query-api-base: https://apiquerysandbox.cieloecommerce.cielo.com.br
      read-timeout: PT30S                      # an authorization may take this long at the issuer; Status 0 covers the rest
```
e dentro de `gateway.payments`:
```yaml
    card-capture-deadline: P5D                 # an authorization nobody captured becomes CAPTURE_OVERDUE (the Cielo never expires it)
    card-reconciliation-lookback: PT48H        # card payments are queried one by one: a window of their own
    card-reconciliation-cap: 200
```

Em `ArchitectureTest`:
```java
  /** The Cielo's vocabulary, like the Itaú's, does not leave gateway-providers (spec 2026-09-28). */
  @ArchTest
  static final ArchRule cieloVocabularyStaysInProviders =
      noClasses()
          .that()
          .resideOutsideOfPackage("com.gateway.providers..")
          .should()
          .haveSimpleNameContaining("Cielo");
```
e a guarda de contagem: meça `find gateway-*/src/main -name '*.java' | wc -l` depois desta task (202 no Plano C; este plano acrescenta ~95) e troque o comentário e o `isGreaterThan(90)` por metade do número medido, arredondada para baixo à dezena, com o número no comentário — por exemplo, 297 medidos → `isGreaterThan(140)` e "297 main classes after Plan D's card work".

- [ ] **Step 5: Os fixtures no `app` e o teste de fluxo**

Copie para `gateway-app/src/test/resources/cielo/fixtures/` os arquivos `post_sales_201_authorized_saved.json`, `post_sales_201_denied.json`, `put_capture_200.json`, `put_void_200.json`, `get_sale_200_credit.json`, `post_sales_201_token_authorized.json` e o `README.md` de `gateway-providers/src/test/resources/cielo/fixtures/` (mesmo conteúdo, byte a byte; `cp` é o passo: `cp gateway-providers/src/test/resources/cielo/fixtures/{post_sales_201_authorized_saved,post_sales_201_denied,put_capture_200,put_void_200,get_sale_200_credit,post_sales_201_token_authorized}.json gateway-providers/src/test/resources/cielo/fixtures/README.md gateway-app/src/test/resources/cielo/fixtures/`). O Itaú fez o mesmo (`gateway-app/src/test/resources/itau`).

`gateway-app/src/test/java/com/gateway/app/CardFlowIntegrationTest.java`:
```java
package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The card path through the real app (spec §10): authorize without capture and save the card,
 * capture part of it, refund part of that, read and delete the saved card, the Cielo notification
 * with and without its header, and a decline as a 402. WireMock plays both Cielo hosts.
 *
 * <p>One test method on purpose, like the Pix and Bolecode flow tests: every step builds on the
 * previous.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "gateway.payments.jobs-poll-ms=200",
      "gateway.payments.outbox-relay-ms=200",
      "gateway.rate-limit.requests-per-minute=1000"
    })
@ActiveProfiles("test")
@Testcontainers
class CardFlowIntegrationTest {
  static final String SALE = "6f8d1753-86bb-4dc0-9ebb-09a29093e1fb";
  static final String NOTIFICATION_KEY = "notification-key-for-tests";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer CIELO = new WireMockServer(options().dynamicPort());

  static {
    CIELO.start();
  }

  @DynamicPropertySource
  static void cielo(DynamicPropertyRegistry registry) {
    registry.add("gateway.providers.cielo.test-api-base", () -> CIELO.baseUrl() + "/api");
    registry.add("gateway.providers.cielo.test-query-api-base", () -> CIELO.baseUrl() + "/query");
  }

  @BeforeAll
  static void stubs() {
    CIELO.stubFor(
        post(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo("4024007153763171")))
            .willReturn(created(fixture("post_sales_201_authorized_saved.json"))));
    CIELO.stubFor(
        post(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo("4024007153760052")))
            .willReturn(created(fixture("post_sales_201_denied.json"))));
    CIELO.stubFor(
        put(urlPathEqualTo("/api/1/sales/" + SALE + "/capture"))
            .willReturn(okJson(fixture("put_capture_200.json"))));
    CIELO.stubFor(
        get(urlEqualTo("/query/1/sales/" + SALE))
            .willReturn(okJson(capturedSale(6000))));
    CIELO.stubFor(
        put(urlPathEqualTo("/api/1/sales/" + SALE + "/void"))
            .willReturn(okJson(fixture("put_void_200.json"))));
  }

  @AfterAll
  static void stop() {
    CIELO.stop();
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder created(String body) {
    return aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(body);
  }

  static String fixture(String name) {
    try (InputStream in = CardFlowIntegrationTest.class.getResourceAsStream("/cielo/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** get_sale_200_credit with this test's PaymentId and a partial capture (fixtures README). */
  static String capturedSale(long captured) {
    return fixture("get_sale_200_credit.json")
        .replace("2352fc91-f9a4-4ca2-aedb-31488b9658c9", SALE)
        .replace("\"CapturedAmount\": 15700", "\"CapturedAmount\": " + captured)
        .replace("\"Amount\": 15700", "\"Amount\": 12990");
  }

  RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> admin(String method, String uri, Object body) {
    // Every admin call this test makes that needs a body back is a POST.
    return http()
        .post()
        .uri(uri)
        .header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectStatus()
        .is2xxSuccessful()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("unchecked")
  EntityExchangeResult<Map> post(String apiKey, String idempotencyKey, String uri, Object body) {
    return http()
        .post()
        .uri(uri)
        .header("Authorization", "Bearer " + apiKey)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  static Map<String, Object> cardBody(String number, boolean capture, boolean saveCard) {
    Map<String, Object> body = new HashMap<>();
    body.put("method", "CARD");
    body.put("amount", 12990);
    body.put("currency", "BRL");
    body.put("reference", "order-42");
    body.put("description", "Pedido 42");
    body.put("soft_descriptor", "LOJA42");
    body.put(
        "card",
        Map.of("number", number, "holder", "JOAO DA SILVA", "expiry", "12/2030", "cvv", "987", "brand", "VISA"));
    body.put("installments", 3);
    body.put("capture", capture);
    body.put("save_card", saveCard);
    body.put("customer", Map.of("name", "Joao da Silva", "document", "12345678901", "email", "joao@example.com"));
    return body;
  }

  @Test
  @SuppressWarnings("unchecked")
  void authorizeCaptureRefundCardsNotificationAndDecline() {
    // 1. Merchant, TEST key, Cielo credential, notification key.
    String merchantId = (String) admin("POST", "/v1/admin/merchants", Map.of("name", "Card Store")).get("id");
    String testKey =
        (String) admin("POST", "/v1/admin/merchants/" + merchantId + "/api-keys", Map.of("environment", "TEST")).get("key");
    http()
        .put()
        .uri("/v1/admin/merchants/" + merchantId + "/providers/CIELO/credentials")
        .header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            Map.of(
                "environment", "TEST",
                "payload", Map.of("merchant_id", "11111111-2222-3333-4444-555555555555", "merchant_key", "A".repeat(40))))
        .exchange()
        .expectStatus()
        .is2xxSuccessful();
    http()
        .put()
        .uri("/v1/admin/merchants/" + merchantId + "/providers/CIELO/notification-key")
        .header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("key", NOTIFICATION_KEY))
        .exchange()
        .expectStatus()
        .isNoContent();

    // 2. Authorize without capture, saving the card: 201 AUTHORIZED with the card block.
    EntityExchangeResult<Map> created = post(testKey, "c1", "/v1/payments", cardBody("4024007153763171", false, true));
    assertThat(created.getStatus().value()).isEqualTo(201);
    Map<String, Object> payment = created.getResponseBody();
    String paymentId = (String) payment.get("id");
    assertThat(payment).containsEntry("status", "AUTHORIZED").containsEntry("method", "CARD").containsEntry("provider", "CIELO");
    assertThat(payment.get("pix")).isNull();
    assertThat(payment.get("boleto")).isNull();
    Map<String, Object> card = (Map<String, Object>) payment.get("card");
    assertThat(card).containsEntry("brand", "VISA").containsEntry("installments", 3).containsEntry("tid", "1124060407175");
    String cardId = (String) card.get("card_id");
    assertThat(cardId).isNotNull();

    // 3. Capture 60,00 of 129,90: 200 COMPLETED with the captured amount.
    EntityExchangeResult<Map> captured = post(testKey, "c2", "/v1/payments/" + paymentId + "/capture", Map.of("amount", 6000));
    assertThat(captured.getStatus().value()).isEqualTo(200);
    assertThat(captured.getResponseBody()).containsEntry("status", "COMPLETED").containsEntry("paid_amount", 6000);
    CIELO.verify(putRequestedFor(urlPathEqualTo("/api/1/sales/" + SALE + "/capture")).withQueryParam("amount", equalTo("6000")));
    EntityExchangeResult<Map> again = post(testKey, "c3", "/v1/payments/" + paymentId + "/capture", Map.of());
    assertThat(again.getStatus().value()).isEqualTo(409);
    assertThat(again.getResponseBody().get("type")).isEqualTo("urn:gateway:ALREADY_CAPTURED");

    // 4. Refund 20,00: synchronous, COMPLETED in the response.
    EntityExchangeResult<Map> refund = post(testKey, "c4", "/v1/payments/" + paymentId + "/refunds", Map.of("amount", 2000));
    assertThat(refund.getStatus().value()).isEqualTo(201);
    assertThat(refund.getResponseBody()).containsEntry("state", "COMPLETED");
    CIELO.verify(putRequestedFor(urlPathEqualTo("/api/1/sales/" + SALE + "/void")).withQueryParam("amount", equalTo("2000")));

    // 5. The saved card, then gone.
    Map<String, Object> saved =
        http().get().uri("/v1/cards/" + cardId).header("Authorization", "Bearer " + testKey)
            .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    assertThat(saved).containsEntry("brand", "VISA").containsEntry("expiry", "12/2030").containsEntry("holder", "JOAO DA SILVA");
    assertThat(saved).doesNotContainKey("token");
    http().delete().uri("/v1/cards/" + cardId).header("Authorization", "Bearer " + testKey).exchange().expectStatus().isNoContent();
    http().get().uri("/v1/cards/" + cardId).header("Authorization", "Bearer " + testKey).exchange().expectStatus().isNotFound();

    // 6. The Cielo notification: 404 without the header or with a wrong one, 200 with it.
    String token =
        jdbc.queryForObject("SELECT inbound_webhook_token FROM merchants.merchants WHERE id = ?", String.class, merchantId);
    String notification = "{\"PaymentId\":\"" + SALE + "\",\"ChangeType\":1}";
    http().post().uri("/v1/providers/cielo/webhooks/" + token).contentType(MediaType.APPLICATION_JSON)
        .body(notification).exchange().expectStatus().isNotFound();
    http().post().uri("/v1/providers/cielo/webhooks/" + token).header("X-Gateway-Notification-Key", "wrong")
        .contentType(MediaType.APPLICATION_JSON).body(notification).exchange().expectStatus().isNotFound();
    http().post().uri("/v1/providers/cielo/webhooks/" + token).header("X-Gateway-Notification-Key", NOTIFICATION_KEY)
        .contentType(MediaType.APPLICATION_JSON).body(notification).exchange().expectStatus().isOk();
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(
            () ->
                jdbc.queryForObject(
                    "SELECT status FROM payments.webhook_inbox WHERE merchant_id = ? AND provider = 'CIELO'",
                    String.class,
                    merchantId),
            status -> !status.equals("RECEIVED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payments.webhook_inbox WHERE merchant_id = ? AND provider = 'CIELO'", String.class, merchantId))
        .isEqualTo("PROCESSED");

    // 7. A decline: 402 with our decline code and the failed payment's id.
    EntityExchangeResult<Map> declined = post(testKey, "c5", "/v1/payments", cardBody("4024007153760052", true, false));
    assertThat(declined.getStatus().value()).isEqualTo(402);
    assertThat(declined.getResponseBody())
        .containsEntry("type", "urn:gateway:CARD_DECLINED")
        .containsEntry("decline_code", "INSUFFICIENT_FUNDS")
        .containsKey("payment_id");
    assertThat(declined.getResponseBody().toString()).doesNotContain("Nao Autorizada");

    List<String> statuses =
        jdbc.queryForList("SELECT status FROM payments.payments WHERE merchant_id = ? ORDER BY created_at", String.class, merchantId);
    assertThat(statuses).containsExactly("COMPLETED", "FAILED");
  }
}
```

- [ ] **Step 6: O teste PCI — a definição de pronto da fase**

`gateway-app/src/test/java/com/gateway/app/CardDataNeverLeavesTheRequestTest.java`:
```java
package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Spec §7, the definition of done: the full card number and the CVV exist in memory between the
 * request and the Cielo call, and nowhere else. Runs every card path that touches card data — save,
 * charge by card_id, capture, refund, a decline, a Cielo 400, a card the gateway refuses — then
 * reads every table the gateway writes and every log event, looking for the number (as digits and
 * as the payer grouped it) and the CVV.
 *
 * <p>Log events are captured raw, before the Masker-backed encoder: the rule is that card data
 * never reaches a logger, not that the encoder catches it.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"gateway.rate-limit.requests-per-minute=1000"})
@ActiveProfiles("test")
@Testcontainers
class CardDataNeverLeavesTheRequestTest {
  static final String NUMBER = "4024007153763171";
  static final String GROUPED = "4024 0071 5376 3171";
  static final String DECLINED_NUMBER = "4024007153760052";
  static final String CIELO_REFUSES = "4024007153760029";
  static final String CVV = "987";
  /** The CVV as a token of its own: a ULID or an amount may contain the digits 987. */
  static final Pattern CVV_TOKEN = Pattern.compile("(?<![0-9A-Za-z])" + CVV + "(?![0-9A-Za-z])");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer CIELO = new WireMockServer(options().dynamicPort());
  static final ListAppender<ILoggingEvent> LOGS = new ListAppender<>();

  static {
    CIELO.start();
  }

  @DynamicPropertySource
  static void cielo(DynamicPropertyRegistry registry) {
    registry.add("gateway.providers.cielo.test-api-base", () -> CIELO.baseUrl() + "/api");
    registry.add("gateway.providers.cielo.test-query-api-base", () -> CIELO.baseUrl() + "/query");
  }

  @BeforeAll
  static void stubsAndLogs() {
    LOGS.start();
    ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(LOGS);

    CIELO.stubFor(
        post(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo(NUMBER)))
            .willReturn(created(CardFlowIntegrationTest.fixture("post_sales_201_authorized_saved.json"))));
    CIELO.stubFor(
        post(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardToken"))
            .willReturn(created(CardFlowIntegrationTest.fixture("post_sales_201_token_authorized.json"))));
    CIELO.stubFor(
        post(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo(DECLINED_NUMBER)))
            .willReturn(created(CardFlowIntegrationTest.fixture("post_sales_201_denied.json"))));
    // A 400 that echoes what it was sent: the worst case for an exception message.
    CIELO.stubFor(
        post(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo(CIELO_REFUSES)))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withBody("{\"CardNumber\":\"" + CIELO_REFUSES + "\",\"SecurityCode\":\"" + CVV + "\"}")));
    CIELO.stubFor(
        put(urlPathEqualTo("/api/1/sales/" + CardFlowIntegrationTest.SALE + "/capture"))
            .willReturn(okJson(CardFlowIntegrationTest.fixture("put_capture_200.json"))));
    CIELO.stubFor(
        get(urlEqualTo("/query/1/sales/" + CardFlowIntegrationTest.SALE))
            .willReturn(okJson(CardFlowIntegrationTest.capturedSale(12990))));
    CIELO.stubFor(
        put(urlPathEqualTo("/api/1/sales/" + CardFlowIntegrationTest.SALE + "/void"))
            .willReturn(okJson(CardFlowIntegrationTest.fixture("put_void_200.json"))));
  }

  @AfterAll
  static void stop() {
    ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(LOGS);
    CIELO.stop();
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder created(String body) {
    return aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(body);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @SuppressWarnings("unchecked")
  String admin(String uri, Object body) {
    return (String)
        http().post().uri(uri).header("X-Admin-Key", "test-admin").contentType(MediaType.APPLICATION_JSON)
            .body(body).exchange().expectStatus().is2xxSuccessful().expectBody(Map.class).returnResult()
            .getResponseBody().values().stream().filter(String.class::isInstance).findFirst().orElseThrow();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> post(String apiKey, String idempotencyKey, String uri, Object body) {
    return http().post().uri(uri).header("Authorization", "Bearer " + apiKey).header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON).body(body).exchange().expectBody(Map.class).returnResult().getResponseBody();
  }

  static Map<String, Object> newCard(String number, boolean capture, boolean save) {
    Map<String, Object> body = new HashMap<>();
    body.put("method", "CARD");
    body.put("amount", 12990);
    body.put("currency", "BRL");
    body.put("card", Map.of("number", number, "holder", "JOAO DA SILVA", "expiry", "12/2030", "cvv", CVV));
    body.put("capture", capture);
    body.put("save_card", save);
    body.put("customer", Map.of("name", "Joao da Silva"));
    return body;
  }

  @Test
  @SuppressWarnings("unchecked")
  void noTableAndNoLogLineEverHoldsTheCardNumberOrTheCvv() {
    String merchantId = admin("/v1/admin/merchants", Map.of("name", "PCI Store"));
    String apiKey =
        (String)
            http().post().uri("/v1/admin/merchants/" + merchantId + "/api-keys").header("X-Admin-Key", "test-admin")
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("environment", "TEST")).exchange()
                .expectBody(Map.class).returnResult().getResponseBody().get("key");
    http().put().uri("/v1/admin/merchants/" + merchantId + "/providers/CIELO/credentials").header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("environment", "TEST", "payload", Map.of("merchant_id", "11111111-2222-3333-4444-555555555555", "merchant_key", "A".repeat(40))))
        .exchange().expectStatus().is2xxSuccessful();

    // Save the card (sent grouped, as a payer types it), capture, refund.
    Map<String, Object> saved = post(apiKey, "p1", "/v1/payments", newCard(GROUPED, false, true));
    String paymentId = (String) saved.get("id");
    String cardId = (String) ((Map<String, Object>) saved.get("card")).get("card_id");
    post(apiKey, "p2", "/v1/payments/" + paymentId + "/capture", Map.of());
    post(apiKey, "p3", "/v1/payments/" + paymentId + "/refunds", Map.of("amount", 1000));

    // Charge the saved card, a decline, a Cielo 400 echoing the card, and a card the gateway refuses.
    Map<String, Object> byCardId = new HashMap<>(Map.of("method", "CARD", "amount", 5000, "currency", "BRL",
        "card_id", cardId, "cvv", CVV, "customer", Map.of("name", "Joao da Silva")));
    post(apiKey, "p4", "/v1/payments", byCardId);
    post(apiKey, "p5", "/v1/payments", newCard(DECLINED_NUMBER, true, false));
    post(apiKey, "p6", "/v1/payments", newCard(CIELO_REFUSES, true, false));
    post(apiKey, "p7", "/v1/payments", newCard("4024007153763172", true, false));

    // The scan is not vacuous: the number did reach the Cielo, and only the Cielo.
    CIELO.verify(postRequestedFor(urlEqualTo("/api/1/sales")).withRequestBody(containing(NUMBER)));

    List<String> stored = new ArrayList<>();
    stored.addAll(text("SELECT coalesce(request,'') || ' ' || coalesce(response,'') FROM payments.provider_requests"));
    stored.addAll(text("SELECT payload::text FROM payments.payment_events"));
    stored.addAll(text("SELECT payload FROM payments.outbox"));
    stored.addAll(text("SELECT details::text FROM payments.payments"));
    stored.addAll(text("SELECT raw_headers || convert_from(raw_body, 'UTF8') FROM payments.webhook_inbox"));
    stored.addAll(text("SELECT coalesce(response_body,'') FROM payments.idempotency_keys"));
    stored.addAll(text("SELECT coalesce(reason,'') FROM payments.refunds"));
    stored.addAll(text("SELECT coalesce(detail,'') FROM payments.reconciliation_divergences"));
    stored.addAll(text("SELECT holder || last4 || brand FROM payments.cards"));
    stored.addAll(
        jdbc.queryForList("SELECT token_ciphertext FROM payments.cards", byte[].class).stream()
            .map(bytes -> new String(bytes, StandardCharsets.ISO_8859_1))
            .toList());

    List<String> logged = new ArrayList<>();
    for (ILoggingEvent event : LOGS.list) {
      logged.add(event.getFormattedMessage());
      for (IThrowableProxy thrown = event.getThrowableProxy(); thrown != null; thrown = thrown.getCause()) {
        logged.add(thrown.getMessage() == null ? "" : thrown.getMessage());
      }
    }

    for (String text : concat(stored, logged)) {
      assertThat(text)
          .doesNotContain(NUMBER)
          .doesNotContain(GROUPED)
          .doesNotContain(DECLINED_NUMBER)
          .doesNotContain(CIELO_REFUSES)
          .doesNotContain("4024007153763172");
      assertThat(CVV_TOKEN.matcher(text).find()).as("CVV in: %s", text).isFalse();
    }
    assertThat(stored).isNotEmpty();
    assertThat(logged).isNotEmpty();
  }

  List<String> text(String sql) {
    return jdbc.queryForList(sql, String.class);
  }

  static List<String> concat(List<String> first, List<String> second) {
    List<String> all = new ArrayList<>(first);
    all.addAll(second);
    return all;
  }
}
```

- [ ] **Step 7: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-app -am -Dtest='MtlsPortFilterTest,CardFlowIntegrationTest,CardDataNeverLeavesTheRequestTest,ArchitectureTest,ItauWebhookMtlsIntegrationTest,AuthenticationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: PASS. Se o teste PCI falhar, **não** afrouxe o teste: ache quem escreveu o número (o texto da asserção mostra a linha) e corrija a origem — é exatamente o incidente que ele existe para impedir (spec §11).

- [ ] **Step 8: Commit**

```bash
export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:apply
git add gateway-app
git commit -m "feat(app): cielo notification endpoint and the card data test

POST /v1/providers/cielo/webhooks/{token} authenticates with the URL token
and the merchant's fixed header together; either missing is the same 404. It
answers 200, which the Cielo requires, and lives on the main connector: the
Cielo has no mTLS and delivers only on 443, so the mTLS fence now covers only
the Itau paths. CardDataNeverLeavesTheRequestTest runs every card path and
scans every table and raw log event for the number and the CVV; it is the
definition of done of spec 2026-09-28 section 7. ArchUnit keeps the Cielo's
name inside providers.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---
### Task 13: Documentação, o procedimento do smoke no sandbox e a verificação completa

**Files:**
- Modify: `README.md` (+ seção "Card (Cielo)")
- Modify: `.env.example` (+ bloco Cielo, só placeholders)
- Create: `docs/providers/cielo/NOTES.md`
- Modify: `docs/superpowers/DECISOES.md` (entradas novas no fim)

**Interfaces:**
- Consumes: tudo acima.
- Produces: documentação; nenhum código.

- [ ] **Step 1: `README.md`**

Depois da seção `### Background jobs` (e antes de `### Sandbox`), acrescente:
````markdown
### Card (Cielo)

Credit card, customer present, through the Cielo E-commerce API (`method: "CARD"`). The card number
reaches the gateway and dies in the call to the Cielo: it is never stored, logged or echoed
(`CardDataNeverLeavesTheRequestTest`). Register the merchant's Cielo credential first:

```bash
curl -X PUT localhost:8080/v1/admin/merchants/$MERCHANT/providers/CIELO/credentials \
  -H "X-Admin-Key: $GATEWAY_ADMIN_KEY" -H 'Content-Type: application/json' \
  -d '{"environment":"TEST","payload":{"merchant_id":"<CIELO_SANDBOX_MERCHANT_ID>","merchant_key":"<CIELO_SANDBOX_MERCHANT_KEY>"}}'
```

```bash
curl -X POST localhost:8080/v1/payments -H "Authorization: Bearer $TEST_KEY" \
  -H 'Idempotency-Key: order-42-1' -H 'Content-Type: application/json' -d '{
    "method": "CARD", "amount": 12990, "currency": "BRL", "reference": "order-42",
    "soft_descriptor": "LOJA42", "installments": 3, "capture": true, "save_card": true,
    "card": {"number": "4024007153763171", "holder": "JOAO DA SILVA", "expiry": "12/2030", "cvv": "123"},
    "customer": {"name": "Joao da Silva", "document": "12345678901", "email": "joao@example.com"}}'
```

- `card` **or** `card_id` (a card saved earlier with `save_card`); with `card_id`, `cvv` is required —
  the Cielo requires the security code with a stored card.
- `brand` is optional when the number identifies it (Visa, Master, Amex, Elo, Aura, JCB, Diners,
  Discover). `installments` 1–12, each installment at least R$ 5,00 (the Cielo's minimum for
  merchant-financed installments). `soft_descriptor` up to 13 letters or digits.
- `capture: false` stops at `AUTHORIZED`; capture with `POST /v1/payments/{id}/capture` (`{"amount": 5000}`
  for a partial capture — one capture per payment, at least 20 cents). `POST /v1/payments/{id}/cancel`
  on an `AUTHORIZED` payment voids it; a captured payment is refunded with `POST …/refunds` (total or
  partial, answered in the same request).
- A decline is `402 CARD_DECLINED` with `decline_code` (`INSUFFICIENT_FUNDS`, `EXPIRED_CARD`,
  `BLOCKED_CARD`, `CANCELED_CARD`, `TIMEOUT`, `DO_NOT_HONOR`, `GENERIC`) and the failed `payment_id`.
  Do not retry a decline automatically: card brands penalize it.
- **Always send an `Idempotency-Key`.** A create repeated without one is a second authorization at the
  Cielo, and a second hold on the payer's limit.
- An authorization nobody captures is never canceled by the gateway; after 5 days
  (`gateway.payments.card-capture-deadline`) reconciliation opens a `CAPTURE_OVERDUE` divergence.
- Saved cards: `GET /v1/cards/{id}` (brand, last four, expiry, holder) and `DELETE /v1/cards/{id}`.

**Cielo notifications.** In the Cielo site, set the notification URL to
`https://<public-host>/v1/providers/cielo/webhooks/<inbound_webhook_token>` (the token is the merchant's,
the same one the Itaú URL uses) and add a fixed header `X-Gateway-Notification-Key` with a value of your
choice; register the same value at the gateway:

```bash
curl -X PUT localhost:8080/v1/admin/merchants/$MERCHANT/providers/CIELO/notification-key \
  -H "X-Admin-Key: $GATEWAY_ADMIN_KEY" -H 'Content-Type: application/json' -d '{"key":"<the header value>"}'
```

The notification is only a hint: the gateway queries the sale and moves the payment on the Cielo's
answer. See `docs/providers/cielo/NOTES.md`.
````

- [ ] **Step 2: `.env.example`**

Depois do bloco do Itaú em produção:
```bash
# --- Cielo sandbox (cadastrosandbox.cieloecommerce.cielo.com.br, no contract); TEST environment ---
# Sent to PUT /v1/admin/merchants/{id}/providers/CIELO/credentials as {"merchant_id", "merchant_key"}.
CIELO_SANDBOX_MERCHANT_ID=     # a GUID
CIELO_SANDBOX_MERCHANT_KEY=    # 40 letters or digits
CIELO_NOTIFICATION_KEY=        # the fixed header value set at the Cielo and at PUT …/providers/CIELO/notification-key
```

- [ ] **Step 3: `docs/providers/cielo/NOTES.md`**

```markdown
# Cielo E-commerce — notes

Source: https://docs.cielo.com.br/ecommerce-cielo/ (the `.md` of each page; index at `llms.txt`), read
2026-09-28. The old manual at developercielo.github.io was discontinued on 2024-08-14 and is not a
source. The Cielo publishes no OpenAPI file: the examples embedded in each reference page are the
fixtures (`gateway-providers/src/test/resources/cielo/fixtures/README.md` lists each file's page).

## Hosts

| | transactional (POST/PUT) | query (GET) |
|---|---|---|
| production | https://api.cieloecommerce.cielo.com.br | https://apiquery.cieloecommerce.cielo.com.br |
| sandbox | https://apisandbox.cieloecommerce.cielo.com.br | https://apiquerysandbox.cieloecommerce.cielo.com.br |

## Authentication

Headers `MerchantId` (GUID) and `MerchantKey` on every call, `RequestId` (36 chars) optional — the
gateway sends the correlation id. No OAuth, no mTLS. The docs call the key a 40-character GUID; their
example is 40 upper-case letters, so the gateway checks 40 letters or digits. Sandbox credentials are
created without a contract at the sandbox signup page.

## Operations used

| operation | call | answer |
|---|---|---|
| authorize | `POST /1/sales` | 201 for every business answer, a decline included (Status 3) |
| capture | `PUT /1/sales/{PaymentId}/capture[?amount=]` | 200 `{Status, Tid, ProofOfSale, AuthorizationCode, ReturnCode, ReturnMessage}` — no captured amount, so the gateway queries after it |
| void / refund | `PUT /1/sales/{PaymentId}/void[?amount=]` | 200 same shape; Status 10 (same day) or 11 (after 23h59 of the authorization day) |
| query | `GET /1/sales/{PaymentId}` | the sale |
| query by order | `GET /1/sales?merchantOrderId=` | `{ReasonCode, ReasonMessage, Payments[{PaymentId, ReceveidDate}]}` — the field is spelled `ReceveidDate`; only the last three months |
| tokenize | `POST /1/card/` (trailing slash) | `{CardToken}` — not used by payments in this phase |

## Status (reference/payment-status)

0 NotFinished, 1 Authorized, 2 PaymentConfirmed, 3 Denied, 10 Voided, 11 Refunded, 12 Pending, 13
Aborted, 20 Scheduled. The spec's 14 Processing and 15 Refunded are tolerated (14 → in doubt, 15 →
refunded). Unknown is in doubt: asked again, never adopted.

## Rules the gateway enforces because the Cielo does

- `MerchantOrderId` only `[A-Za-z0-9]`, up to 50; above 20 characters (our ULID has 26) the Cielo
  generates a `SentOrderId` — the gateway ignores it and queries by its own id.
- `Holder` without accents, 25 characters; `Customer.Name` letters only; `SoftDescriptor` 13 letters or
  digits.
- `Interest: "ByMerchant"` requires installments of at least R$ 5,00; 12 installments is the default
  ceiling.
- One capture per sale (total or one partial); ~50 attempts before code 841; captures under 20 cents are
  not settled.
- Card On File only for Visa, Master and Elo; `InitiatedTransactionIndicator` only for Master
  (`C1`/`CredentialsOnFile` for a customer-present charge with a stored card).
- `SecurityCode` is required with `CardToken` (schema of reference/cartao-tokenizado-api).
- Declines are ABECS codes (page/abecs), not the API codes of reference/api-codes; the sandbox's codes
  differ from production's (its 57 means "expired", production's 57 "not allowed for the card").

## Notifications (docs/webhook)

One HTTPS URL per store, port 443, static, up to 255 characters, configured in the Cielo site. Body
`{PaymentId, ChangeType, RecurrentPaymentId?}`. Sent every 30 minutes with three retries until a
**200**. No signature: up to three fixed headers are the authentication, so the gateway requires its
`X-Gateway-Notification-Key` on top of the URL token. ChangeType 1 status, 2 recurrence created, 3
antifraud, 4 recurrence status, 5 cancel denied, 6 boleto underpaid, 7 chargeback (legacy), 8 fraud
alert, 25 partial cancel/refund. The sandbox sends notifications once the URL is registered by e-mail to
the Cielo support (reference/como-usar-o-sandbox).

## Sandbox cards

The last digit decides (reference/credito-sandbox): 0/1/4 authorized, 2 declined (05), 3 expired (57),
5 blocked (78), 6 timeout (99), 7 canceled (77), 8 card problem (70), 9 random (6 or 9). CVV and expiry
are free (3-digit CVV, MM/YYYY). The page's own example, `4024.0071.5376.3191`, **fails the Luhn check**,
which the gateway applies before the Cielo; use Luhn-valid numbers with the right last digit:

| last digit | number |
|---|---|
| 1 | 4024007153763171 |
| 2 | 4024007153760052 |
| 6 | 4024007153760086 |
| 9 | 4024007153760029 |

## Smoke in the sandbox (run by hand, with the credentials the owner creates)

Placeholders only; nothing here is a real credential. `$GW` is the running gateway, `$ADMIN` the admin
key, `$MERCHANT` a merchant id, `$KEY` its TEST API key.

1. Register `{"merchant_id":"<CIELO_SANDBOX_MERCHANT_ID>","merchant_key":"<CIELO_SANDBOX_MERCHANT_KEY>"}`
   as the merchant's CIELO TEST credential (README, "Card (Cielo)").
2. Card ending 1, `capture: true` → 201 `COMPLETED`. Record `ReturnCode` from `provider_requests`/log.
3. Card ending 2 → 402 `CARD_DECLINED`; record the `decline_code` (expected `GENERIC`, sandbox 05).
4. Card ending 6 → the Cielo answers a timeout code (99): expected 402 `TIMEOUT` decline, not a
   gateway timeout. Record what came back.
5. Card ending 9, five times → a mix of approved and 402.
6. Card ending 1, `capture: false` → `AUTHORIZED`; `POST …/capture {"amount": 5000}` → `COMPLETED`,
   `paid_amount` 5000; a second capture → 409 `ALREADY_CAPTURED`. **Record** whether the Cielo's second
   call answered 308.
7. Card ending 1, `capture: false`, then `POST …/cancel` → `CANCELED`; record the void's Status (10).
8. Card ending 1, captured; `POST …/refunds {"amount": 1000}` then `{"amount": 2000}` → both
   `COMPLETED`. **Record** whether a same-day refund answered 10 or 11.
9. Card ending 1 with `save_card: true` → `card_id` in the response; then a charge with `card_id` and
   `cvv` → `COMPLETED`. **Record** whether the Cielo accepts the same charge without `SecurityCode`
   (send one by hand with curl against the sandbox; the gateway always sends it).
10. `GET /1/sales?merchantOrderId=<a payment id of 26 chars>` against the sandbox query host with the
    sandbox headers → **record** that it finds the sale (the recovery depends on it) and what
    `SentOrderId` the sale shows.
11. `GET /1/sales/<a random GUID>` → **record** whether the Cielo answers 404 or 400 with code 307.
12. Ask the Cielo support to register `https://<public-host>/v1/providers/cielo/webhooks/<token>` with the
    header `X-Gateway-Notification-Key: <CIELO_NOTIFICATION_KEY>`; capture a sale and **record** whether
    a ChangeType 1 notification arrives and how long it took.

Write each recorded answer under "Smoke results" below, with the date. Until then these are the open
questions of spec §1.

## Smoke results

(none yet)
```

- [ ] **Step 4: `DECISOES.md`**

Acrescente ao fim (append-only; nada acima é editado):
```markdown
## 2026-09-28 — Cartão pela Cielo: o cartão passa pelo gateway, e o PAN morre na chamada
O número e o CVV chegam em `POST /v1/payments` e existem em memória só dentro de `CardData`/`CardToken`,
entre a leitura do request e `SaleRequestFactory`. Rejeitado: Silent Order Post (o merchant precisaria de
credencial SOP e de um passo no front). Custo: o gateway entra no escopo PCI de dados em trânsito, pago com
`CardNumber` sem `toString` revelador, o mixin do Jackson no app, o `Masker` com PAN/CVV, o
`CieloPayloadMasker` nas mensagens de exceção e o teste `CardDataNeverLeavesTheRequestTest`. Custo se
errado: um log com PAN é incidente de segurança.

## 2026-09-28 — Negativa é resultado, não exceção
`Status 3/13` vira `FAILED` com `decline_code` e um 402 ao merchant; exceção é só o que impediu a Cielo de
responder. Rejeitado: tratar a negativa como `ProviderException` (entraria no caminho de retentativa).
Custo se errado: retentativa automática de uma negativa, o que as bandeiras penalizam.

## 2026-09-28 — Timeout sem transação é FAILED, e consulta que falha não é "sem transação"
Depois de um timeout (ou de um 201 em dúvida: Status 0, 12, 14) o gateway consulta por `MerchantOrderId`
(o id do pagamento): achou e decidido, adota; não achou ou ainda em dúvida, `FAILED` — ao contrário do
boleto, porque a Cielo não tem "em andamento" que dure e a Garantia de Cancelamento desfaz o `Status 0`.
Se a própria consulta falha, o pagamento fica `CREATED` e o sweeper pergunta de novo. Rejeitado: falhar
também quando a consulta falha (diria "não cobrei" sobre uma venda que pode segurar o limite do pagador).
Custo se errado: uma autorização que a consulta não achou e depois apareceu fica órfã até a reconciliação
(`CARD_ACTIVE_AT_PROVIDER`) apontar.

## 2026-09-28 — AUTHORIZED sem prazo próprio; a reconciliação avisa
Uma autorização não expira no gateway; depois de `card-capture-deadline` (5 dias) a reconciliação abre
`CAPTURE_OVERDUE` — depois de consultar a venda, para não sinalizar uma capturada por fora. Rejeitado:
expirar em N dias como o Pix (a Cielo não expira, e cancelar por conta própria libera um limite que o
merchant pode querer capturar). Custo se errado: limite preso no cartão do cliente até alguém olhar a
divergência.

## 2026-09-28 — card_id é nosso, o token é da Cielo, selado
O merchant recebe e usa `card_id` (ULID nosso); o `CardToken` da Cielo fica em `payments.cards`, selado
pela porta `kernel/security/Sealer` que `merchants` implementa com o `EnvelopeCipher`
(AAD `merchant|provider|environment|card`). Rejeitado: expor o `CardToken` ao merchant (prende o merchant
à Cielo e o token vira dado sensível na mão dele); e `payments` importar `merchants` para cifrar (a
fronteira proíbe). Custo se errado: perder a `GATEWAY_MASTER_KEY` perde todos os cartões guardados, como
já perde as credenciais.

## 2026-09-28 — CVV obrigatório com card_id, contra a spec
A spec dizia `cvv` opcional com `card_id`; o schema da cobrança com token na doc da Cielo lista
`"required": ["CardToken", "SecurityCode"]`. A doc ganha: 422 `CARD_INVALID` "cvv is required with
card_id". Rejeitado: seguir a spec e descobrir no sandbox. Custo se errado: um merchant que guardou o
cartão para cobrar sem pedir o CVV de novo não consegue; o smoke (passo 9 de `NOTES.md`) confirma e,
se a Cielo aceitar sem, uma entrada nova afrouxa a regra.

## 2026-09-28 — A notificação da Cielo: token da URL e um header fixo, só o hash guardado
A Cielo não assina nem oferece mTLS; a autenticação é o token da URL do merchant **e** o header
`X-Gateway-Notification-Key` que ele configura no site da Cielo. A chave mora num conceito próprio dos
merchants (`inbound_notification_keys`, V102) como SHA-256, comparada em tempo constante; sem os dois,
404. A rota fica no conector principal (a Cielo só entrega na 443) e o `MtlsPortFilter` passa a cercar só
`/v1/providers/itau/**`. Rejeitado: guardar a chave em claro ou cifrada (não há quem precise lê-la de
volta). Custo se errado: quem descobre o token de um merchant e a chave forja notificações — que não
movem nada sozinhas, porque o gateway sempre consulta a venda.

## 2026-09-28 — Devolução de cartão síncrona, e o timeout reserva
A devolução do cartão é o void com `amount`, respondido na mesma chamada: `REQUESTED → COMPLETED | FAILED`
sem job de polling, contra o `paid_amount` (que a captura parcial diminui). Timeout ou 503 deixam o
`Refund` em `PROCESSING` com o valor reservado e uma divergência `REFUND_UNKNOWN`. Rejeitado: falhar o
reembolso no timeout (liberaria o valor enquanto o dinheiro pode já ter voltado; um segundo reembolso
devolveria duas vezes). Custo se errado: um reembolso que não aconteceu fica reservado até um humano
fechar a divergência.

## 2026-09-28 — Payment e RefundService crescem de novo, citando a entrada da Fase 2
`Payment` passa de 535 para ~660 linhas (as transições do cartão precisam do estado privado do agregado);
`RefundService` ganha só o despacho para `CardRefunds`, e `WebhookInboxService` o despacho para
`CardNotifications` (8 dependências). Segue a entrada "Fase 2: …" que manda a próxima classe acima do
limite citar a decisão ou ser dividida: esta cita. Rejeitado: dividir o agregado dentro da fase do cartão
(misturaria refactor e feature no mesmo commit). Custo se errado: o próximo método de pagamento encontra
um `Payment` ainda maior e a divisão fica mais cara.
```

- [ ] **Step 5: Verificação completa**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -B verify`
Expected: BUILD SUCCESS em todos os módulos, com Testcontainers (Docker ligado). Nenhum teste pulado além dos que já eram pulados antes deste plano.

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B spotless:check`
Expected: sem saída (nada a formatar).

Run: `git grep -nE "4024007153763191|XKGHUBSBKIRXKAVPSKWLVXYCLVJUGTNZLIHPUSYV|8937bd5b-9796-494d-9fe5-f76b3e4da633" -- ':!docs/superpowers'`
Expected: só `docs/providers/cielo/NOTES.md` com o número da doc citado como o que **não** passa em Luhn (escrito `4024.0071.5376.3191`, então nem ele casa) — ou seja, nenhuma linha. As credenciais públicas do sandbox que a doc imprime nunca entram no repositório.

Run: `git grep -n "Cielo" -- 'gateway-payments/src/main' 'gateway-app/src/main' 'gateway-kernel/src/main' | grep -E "class |interface |record "`
Expected: nenhuma linha (o vocabulário da Cielo fica em `gateway-providers`; comentários citando a doc são permitidos, nomes de tipo não — o ArchUnit cobra os nomes de classe).

- [ ] **Step 6: Commit**

```bash
git add README.md .env.example docs/providers/cielo/NOTES.md docs/superpowers/DECISOES.md
git commit -m "docs(card): cielo notes, sandbox smoke procedure and the card decisions

NOTES.md keeps what the Cielo docs say and the gateway relies on, the
Luhn-valid sandbox cards (the docs' own example fails Luhn) and a smoke
procedure with placeholders only, whose recorded answers close the open
questions of spec 2026-09-28 section 1. DECISOES gains the section 11
decisions and the ones this plan had to make against the spec or the code.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Cobertura da spec (auto-revisão)

| spec | task |
|---|---|
| §1 a Cielo nas palavras da doc; fatos a confirmar no smoke | tabela D1–D20; Task 13 (NOTES.md, smoke passos 6, 8, 9, 10, 11, 12) |
| §2 escopo e fora do escopo | Global Constraints; nenhuma task implementa débito, 3DS, SOP, antifraude, `ByIssuer`, recorrência, chargeback, Zero Auth, moeda, roteamento |
| §3 `PaymentMethod.CARD`, `card/*`, `CardData` (Luhn, validade, holder, bandeira×BIN), `CardMethodProvider`, `find` por PaymentId, `findByOrder`, `resolveCard`, provider por flow | Tasks 1, 2 |
| §4 `AUTHORIZED`, transições, `details.card`, `markAuthorized/markCaptured`, devolução síncrona, `payments.cards` + token cifrado, `DELETE` marca, `card_id` de outro merchant `NOT_FOUND` | Tasks 6, 7, 9 |
| §5 credencial, endpoints, `CieloSalesClient`, `CieloCardClient`, `CieloCardProvider`, `CieloErrors`, `CieloDeclines`, regras de texto, fixtures com URL, mascaramento | Tasks 3, 4, 5 |
| §6 `CreateCardPayment`, `CREATED` sem cartão, `authorize` fora de transação, todos os desfechos (2, 1, 3/13, 0/14/timeout), `save_card`, `card_id` com CardOnFile/ITI, `CardCapture`, cancel = void, idempotência | Tasks 4 (CardOnFile/ITI), 8, 9, 11 (idempotência do `/capture`) |
| §7 PCI: `CardData` sem serialização, `toString` mascarado, CVV `Secret`, `provider_requests`, `Masker`, eventos/outbox/exceções, teste de ponta a ponta | Tasks 1, 3, 4, 11, 12 |
| §8 notificação com token + header, inbox por ChangeType, `CAPTURE_OVERDUE`, janela/cap por método | Tasks 10, 12 |
| §9 `CardPaymentRequest`, `/capture`, `/v1/cards/{id}`, `payment.authorized`, códigos e 402, bloco `card` | Tasks 8 (`payment.authorized`), 11 |
| §10 testes | cada task; smoke na Task 13 |
| §11 decisões | Task 13, Step 4 |
