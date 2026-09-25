# Payment Gateway — Plano C: Bolecode (boleto com Pix) no Itaú

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Um merchant cria um pagamento `BOLECODE` pela API do gateway; o Itaú emite um boleto registrado **e** um QR Pix na mesma chamada (`POST /boletos-pix`); o pagador quita pelo QR (webhook Pix, como hoje) **ou** pelo código de barras (detectado por **polling** da consulta de detalhe `GET /boletos`, a cada 6 h); o merchant recebe `payment.completed` com `paid_via`, pode cancelar (baixa `PATCH /boletos/{id}/baixa`) e **não** pode devolver o que foi pago por boleto.

**Architecture:** Um segundo contrato no `kernel` (`provider/boleto/BoletoProvider`), implementado em `gateway-providers` por três clientes HTTP (emissão, consulta, instrução — três APIs do Itaú com bases e token URLs próprios) compostos em `ItauBoletoProvider`. Em `gateway-payments` o `Payment` ganha `method` (`PIX`|`BOLECODE`) e `details.boleto` ao lado de `details.pix`; um contador `boleto_numbers` por merchant dá o nosso número; `PaymentService.createBolecode` reserva número → grava `CREATED` → emite fora de transação → `PENDING` com dois jobs (`POLL_BOLETO` +6 h e `EXPIRE_PAYMENT` na data limite); `BoletoPollingService` traduz cada `situacao_geral_boleto` em transição ou divergência. Cancelar/expirar/reconciliar/webhook ganham o ramo `BOLECODE`. O `app` só adiciona campos ao request/response e as URLs no `application.yml`.

**Tech Stack:** o do Plano B (Java 25, Spring Boot 4, JDK `HttpClient`, Jackson 3, WireMock 3, `com.networknt:json-schema-validator`, Testcontainers/Postgres 17, Flyway, ArchUnit). Nada novo.

**Spec:** `docs/superpowers/specs/2026-09-25-bolecode-design.md`. Os OpenAPIs-fonte estão em `docs/providers/itau/itau-ep9-api-recebimentos-v1-externo.openapi.json` (emissão), `itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws.openapi.json` (consulta) e `itau-ep9-gtw-cash-management-ext-v2.openapi.json` (baixa). Executores leem a spec, este plano e `docs/providers/itau/NOTES.md`.

## Global Constraints

- **Todo o código em inglês** (identificadores, colunas, comentários, mensagens, commits, README). O vocabulário do Itaú (`nosso_numero`, `linha_digitavel`, `codigo_barras`, `baixa`, `situacao_geral_boleto`) aparece **só** em `gateway-providers` e nos JSONs; no domínio os nomes são `nossoNumero`, `linhaDigitavel`, `codigoBarras` (spec §2 — são termos do produto brasileiro sem tradução útil) e `paidVia`.
- Fronteiras (ArchUnit já cobra): `payments` conhece só `com.gateway.kernel.provider.boleto.*`; ninguém fora de `providers`/`app` importa `com.gateway.providers`; nenhuma classe fora de `providers` tem `Itau` no nome; entidades JPA package-private e só em `..persistence..`.
- **Um único método `BOLECODE`** (híbrido). Não existe boleto puro, juros, multa, desconto, protesto, negativação, e-mail pelo banco, sacador avalista, alteração de vencimento, webhook de boleto, devolução de boleto nem reutilização de nosso número (spec §8). Nenhuma task abaixo implementa nada disso.
- **Nenhum teste fala com a rede.** WireMock em `localhost`; fixtures copiados verbatim dos `components.examples` dos três OpenAPIs (pinados por teste); todo request que o gateway monta é validado contra o schema do OpenAPI correspondente.
- Credenciais de sandbox e produção **nunca** entram em teste, commit, log ou neste plano — o smoke (Task 13) usa só placeholders.
- Datas de boleto são **dias em `America/Sao_Paulo`**: `expires_at` = `payment_limit_date` 23:59:59 nessa zona. Expiração é pela **data limite**, nunca pelo vencimento (spec §2, §10).
- Nosso número: 8 dígitos com zeros à esquerda, sequencial por merchant a partir de `00000001`, alocado com `INSERT … ON CONFLICT … DO UPDATE … RETURNING` **na mesma transação** do `CREATED`.
- Valores: `Money` em centavos; o Itaú fala `^\d+\.\d{2}$` — conversão num único lugar (`BoletoAmounts`), com teste de ida e volta.
- Comentários registram POR QUÊ com evidência. Commits `type(scope): lowercase subject`, corpo em inglês, terminando com `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`. `git commit` **só** nos steps de commit; sem `--amend`.
- Ambiente local: o `PATH` tem um JDK antigo; **todo** comando Maven é prefixado `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH &&`; teste único: `./mvnw -q -B -o -pl <module> -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false test` (`-o` offline; `-am` só quando um módulo a montante mudou na mesma task). Testes com Postgres precisam de Docker (Testcontainers, já configurado em `ServiceIntegrationTestBase` e nos testes do `app`).

---

## O que os OpenAPIs dizem (lido em 2026-09-25) e onde a spec precisou de ajuste

Fatos verificados no JSON, que moldam os nomes abaixo:

| API | fato | consequência |
|---|---|---|
| emissão `POST /boletos-pix` | request `boletoPix` exige `etapa_processo_boleto`, `beneficiario.id_beneficiario`, `dado_boleto{descricao_instrumento_cobranca, tipo_boleto, codigo_carteira, codigo_especie, pagador{pessoa{nome_pessoa, tipo_pessoa{codigo_tipo_pessoa, numero_cadastro_pessoa_fisica|numero_cadastro_nacional_pessoa_juridica}}, endereco{nome_logradouro, nome_bairro, nome_cidade, sigla_UF, numero_CEP}}, dados_individuais_boleto[{numero_nosso_numero, data_vencimento, valor_titulo, data_limite_pagamento?, texto_uso_beneficiario?}]}` | DTO `BoletoPixRequest` (Task 3) |
| emissão, resposta 200 | `dado_boleto.dados_individuais_boleto[0]{id_boleto_individual, dac_titulo, codigo_barras(44), numero_linha_digitavel(47), data_limite_pagamento}` e `dados_qrcode{chave, emv, base64, txid ^BL[0-9]{31}$, location}` | DTO `BoletoPixResponse` |
| emissão, erros | 400/422 são `{codigo, mensagem, campos[{campo, mensagem, valor}]}` — **não** RFC 7807; 403/410/5xx sem corpo; 202 `{codigo:"202", mensagem}` | `BoletoErrors` próprio (o `ItauErrors` do Pix lê `type/title/detail`) |
| emissão, `txid` | descrição: `BL` + agência (4) + conta (7) + carteira (3) + nosso número (15) | reconstrução `"BL" + id_beneficiario[0..11) + carteira + nossoNumero com zeros à esquerda até 15` (a spec escreveu `agência + 00 + conta + carteira + 0000000 + nosso número` — mesmo comprimento se a conta do merchant tiver 5 dígitos úteis; o plano segue a fórmula do OpenAPI e **verifica** com `GET /cob/{txid}` antes de confiar) |
| consulta `GET /boletos` | query `id_beneficiario`, `codigo_carteira`, `nosso_numero` obrigatórios; headers `Authorization`, `x-itau-correlationid`, `x-itau-apikey` obrigatórios; 200 `{data:[{id_boleto, dado_boleto{dados_individuais_boleto[{situacao_geral_boleto, numero_nosso_numero, codigo_barras, numero_linha_digitavel, data_limite_pagamento, …}], pagamentos_cobranca[{valor_pago_total_cobranca, data_inclusao_pagamento, data_hora_inclusao_pagamento, codigo_meio_pagamento_boleto_cobranca, descricao_meio_pagamento}], baixa{data_inclusao_alteracao_baixa, motivo_baixa}, qrcode_pix{emv, imagem_base64}}}]}` | o bloco de pagamento é a **lista** `pagamentos_cobranca` (a spec chamou de `pagamento`); `qrcode_pix.emv` existe e serve de fallback do EMV na adoção |
| consulta, exemplo | o único exemplo mistura estados (`situacao_geral_boleto = "Em Aberto"` com `pagamentos_cobranca` e `baixa` preenchidos); `page` no schema, `pagination` no exemplo | o fixture verbatim é o exemplo; os estados `Pago`/`Baixado`/`Pagamento Rejeitado`/vazio são derivados e documentados no `README.md` dos fixtures |
| baixa `PATCH /boletos/{id_boleto}/baixa` | `id_boleto` **não é o UUID**: "Código Agência (4) + Conta Corrente (7) + DAC (1) + Carteira (3) + Nosso Número (8-16)", `minLength 23, maxLength 31`, exemplo `15000005201211212345678`; sem corpo; 200 `{data:{codigo, mensagem, campos}}`, 204 vazio, 422 sem schema; `x-itau-apikey` obrigatório; `securitySchemes.oauth2.tokenUrl = https://sts.itau.com.br/api/oauth/token` | `BoletoProvider.cancel(c, nossoNumero)` (a spec dizia `idBoletoIndividual`); o id é `id_beneficiario + codigo_carteira + nossoNumero`, tudo já em mãos |
| `ProviderException.Code` | não tem `CONFLICT` (spec §6) | Task 1 adiciona `CONFLICT` e `CREDENTIALS_INCOMPLETE` |
| `WebhookInboxService` | casa o Pix por **id do pagamento** (`findByMerchantAndId(merchantId, txid)`), não por txid; funciona hoje porque `txid == id` no Pix | Task 7 adiciona `findByMerchantAndTxid`; Task 11 usa no webhook e na reconciliação (os txids `BL…` nunca são o id) |
| `ProviderGateway.Resolved` | é `record Resolved(PixProvider provider, ProviderCredentials credentials)` com 13 usos de `x.provider()` | ganha `Optional<BoletoProvider> boleto`; o componente do Pix **continua** `provider` (renomear para `pix` seria um diff mecânico em cinco serviços sem ganho) |
| `ItauTokenClient` | já recebe o token URL por chamada (`ItauEndpoints.tokenUrl()`) e cacheia por `fingerprint|tokenUrl` | nenhuma mudança no cliente; `ItauEndpoints` ganha as fábricas por API e a Task 2 **prova** com um teste de dois token URLs |

---

## Estrutura de arquivos

```
gateway-kernel/src/main/java/com/gateway/kernel/provider/
  ProviderException.java                        + Code.CONFLICT, Code.CREDENTIALS_INCOMPLETE
  boleto/BoletoProvider.java                    interface que payments consome
  boleto/BoletoIssueRequest.java, Payer.java, Address.java, IssuedBoleto.java, BoletoStatus.java, BoletoSituation.java
gateway-providers/src/main/java/com/gateway/providers/
  ProvidersConfiguration.java                   + ProvidersProperties.Boleto, @Bean BoletoProvider
  itau/auth/ItauCredentials.java                + beneficiaryId, walletCode, speciesCode, requireBoletoShape()
  itau/auth/ItauEndpoints.java                  + boletoIssue/boletoQuery/boletoInstruction(env), CASH_MANAGEMENT_TOKEN_URL
  itau/boleto/ItauBoletoEndpoints.java          record (issue, query, instruction)
  itau/boleto/BoletoHttp.java                   headers + envio + timeouts, compartilhado pelos três clientes
  itau/boleto/BoletoErrors.java                 {codigo, mensagem, campos} -> ProviderException
  itau/boleto/BoletoText.java                   sanitização (whitelist do pattern do OpenAPI + palavras proibidas)
  itau/boleto/BoletoAmounts.java                Money <-> "1234.56"
  itau/boleto/BoletoSituations.java             "Aguardando Crédito"/"aguardando credito" -> AWAITING_CREDIT
  itau/boleto/ItauDates.java                    data(-hora) de pagamento -> Instant (America/Sao_Paulo)
  itau/boleto/BoletoPixApiClient.java           POST /boletos-pix
  itau/boleto/BoletoQueryClient.java            GET /boletos
  itau/boleto/BoletoInstructionClient.java      PATCH /boletos/{id}/baixa
  itau/boleto/ItauBoletoProvider.java           BoletoProvider
  itau/boleto/dto/{BoletoPixRequest, BoletoPixResponse, BoletoQueryResponse, BoletoQueryItem, BoletoProblem}.java
gateway-providers/src/test/resources/itau/boleto/{issue,query,instruction}.openapi.json   cópias verbatim
gateway-providers/src/test/resources/itau/boleto/fixtures/*.json + README.md
gateway-payments/src/main/java/com/gateway/payments/
  PaymentsProperties.java                       + boleto* (poll, grace, defaults)
  PaymentsConfiguration.java                    + BoletoPollingService, BoletoNumberRepositoryImpl, ObjectProvider<BoletoProvider>
  payment/PaymentMethod.java                    PIX, BOLECODE
  payment/EventSource.java                      + PROVIDER_POLL
  payment/PaymentTransitions.java               + PROVIDER_POLL nas linhas PENDING/EXPIRED -> COMPLETED
  payment/Payment.java                          + method, boleto, createBolecode, markPendingBolecode, markCompletedByBoleto, paidVia
  payment/PaymentDetailsJson.java               {"pix":{…},"boleto":{…}|null}
  payment/PaymentEvents.java                    + method e bloco boleto no JSON público
  payment/PaymentService.java                   + createBolecode, adoptPendingBolecode, adoptBolecodeFromStatus, settleBoleto, cancel BOLECODE, webhook por txid
  payment/ExpirationService.java                + ramo BOLECODE em expireOne e sweepStuckCreated
  payment/boleto/{BoletoDetails, BoletoDetailsJson, PaidVia, BoletoDates, BoletoPollingService}.java
  payment/boleto/persistence/{BoletoNumberRepository, BoletoNumberRepositoryImpl}.java
  payment/persistence/{PaymentEntity (só comentário), PaymentJpaRepository (+findByProviderAndTxid), PaymentRepository (+findByMerchantAndTxid), PaymentRepositoryImpl}.java
  jobs/{JobType (+POLL_BOLETO), Job (+pollBoleto), JobRunner (+POLL_BOLETO, reagenda 6 h)}.java
  reconciliation/ReconciliationService.java     + passada BOLECODE; casa Pix por txid
  refund/RefundService.java                     + REFUND_NOT_SUPPORTED
  src/main/resources/db/migration/payments/V203__bolecode.sql
gateway-payments/src/test/java/com/gateway/payments/support/{RecordingBoletoProvider, ServiceTestConfig, ServiceIntegrationTestBase, RecordingPixProvider(+register)}.java
gateway-app/src/main/java/com/gateway/app/
  api/dto/CreatePaymentRequest.java             + method BOLECODE, customer{name, address}, due_date, payment_limit_days
  api/dto/PaymentResponse.java                  + boleto{linha_digitavel, codigo_barras, due_date, payment_limit_date, paid_via}, method real
  api/PaymentsController.java                   + ramo BOLECODE
  api/ErrorHandler.java                         + ALREADY_PAID -> 409
  src/main/resources/application.yml            + gateway.providers.itau.boleto.*, gateway.payments.boleto-*
gateway-app/src/test/java/com/gateway/app/{BolecodeFlowIntegrationTest, api/dto/PaymentJsonContractTest}.java
README.md, .env.example, docs/providers/itau/NOTES.md, docs/superpowers/DECISOES.md
```

---

### Task 1: Contrato `BoletoProvider` no `kernel` e dois códigos novos em `ProviderException`

**Files:**
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/provider/boleto/{BoletoProvider,BoletoIssueRequest,Payer,Address,IssuedBoleto,BoletoStatus,BoletoSituation}.java`
- Modify: `gateway-kernel/src/main/java/com/gateway/kernel/provider/ProviderException.java:9` (enum `Code`)
- Test: `gateway-kernel/src/test/java/com/gateway/kernel/provider/boleto/BoletoContractTest.java`

**Interfaces:**
- Consumes: `com.gateway.kernel.money.Money`, `com.gateway.kernel.provider.ProviderCredentials`, `com.gateway.kernel.provider.ProviderException`.
- Produces (todas em `com.gateway.kernel.provider.boleto`):
  ```java
  enum BoletoSituation { OPEN, PAID, SETTLED, AWAITING_CREDIT, CREDITED, PAYMENT_REJECTED, CANCELED }
  record Address(String street, String district, String city, String state, String zip) {}
  record Payer(String name, String document, Address address) {}
  record BoletoIssueRequest(String nossoNumero, Money amount, LocalDate dueDate, LocalDate paymentLimitDate, Payer payer, String description) {}
  record IssuedBoleto(String idBoletoIndividual, String linhaDigitavel, String codigoBarras, LocalDate paymentLimitDate, String pixTxid, String pixCopiaECola, String pixKey) {}
  record BoletoStatus(BoletoSituation situation, Money paidAmount, Instant paidAt, String paidChannel, String idBoletoIndividual, String linhaDigitavel, String codigoBarras, LocalDate paymentLimitDate, String pixCopiaECola) { boolean paid(); }
  interface BoletoProvider {
    String id();                                                              // "ITAU"
    void requireIssueCredentials(ProviderCredentials c);                      // ProviderException(CREDENTIALS_INCOMPLETE, providerType = missing field) before any HTTP
    IssuedBoleto issue(ProviderCredentials c, BoletoIssueRequest r);
    Optional<BoletoStatus> find(ProviderCredentials c, String nossoNumero);
    void cancel(ProviderCredentials c, String nossoNumero);                   // CONFLICT when the bank already shows it paid
    String pixTxidFor(ProviderCredentials c, String nossoNumero);             // the "BL…" txid the bank derives from the account and the number
  }
  ProviderException.Code += CONFLICT, CREDENTIALS_INCOMPLETE
  ```
  Desvios da spec §3, com o motivo: `cancel` recebe `nossoNumero` (o `id_boleto` da baixa é agência+conta+DAC+carteira+nosso número, não o UUID — ver tabela acima); `BoletoStatus.pixCopiaECola` existe porque `GET /boletos` devolve `qrcode_pix.emv`; `requireIssueCredentials` e `pixTxidFor` existem porque só o provider sabe o formato da credencial do Itaú e a fórmula do txid, e `payments` não pode importar `providers`.

- [ ] **Step 1: Teste do kernel**

`gateway-kernel/src/test/java/com/gateway/kernel/provider/boleto/BoletoContractTest.java`:
```java
package com.gateway.kernel.provider.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BoletoContractTest {
  @Test void paidIsTheThreeSettledSituations() {
    for (BoletoSituation s : BoletoSituation.values()) {
      BoletoStatus st = new BoletoStatus(s, Money.brl(100), Instant.parse("2026-10-02T12:00:00Z"), "01", "uuid", "1".repeat(47), "1".repeat(44), LocalDate.of(2026, 10, 31), null);
      boolean expected = s == BoletoSituation.PAID || s == BoletoSituation.SETTLED || s == BoletoSituation.CREDITED;
      assertThat(st.paid()).as("%s", s).isEqualTo(expected);
    }
  }

  @Test void theTwoNewCodesExist() {
    assertThat(ProviderException.Code.valueOf("CONFLICT")).isNotNull();
    assertThat(ProviderException.Code.valueOf("CREDENTIALS_INCOMPLETE")).isNotNull();
    ProviderException e = new ProviderException(ProviderException.Code.CREDENTIALS_INCOMPLETE, 0, "beneficiary_id", "credential lacks beneficiary_id");
    assertThat(e.providerType()).isEqualTo("beneficiary_id");
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-kernel -Dtest=BoletoContractTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compilação (`BoletoStatus` não existe).

- [ ] **Step 3: Tipos do kernel**

`ProviderException.java` — só o enum muda:
```java
  /**
   * CONFLICT: the bank refuses because the resource is already in the state the caller wants to
   * leave (a baixa on a boleto it shows paid). CREDENTIALS_INCOMPLETE: the credential parses but
   * lacks a field this operation needs; {@code providerType} names the field so the merchant's
   * 422 can say which one.
   */
  public enum Code { DECLINED, UNAVAILABLE, INVALID, TIMEOUT, NOT_FOUND, UNAUTHENTICATED, CONFLICT, CREDENTIALS_INCOMPLETE, UNKNOWN }
```

`boleto/BoletoSituation.java`:
```java
package com.gateway.kernel.provider.boleto;

/**
 * The bank's {@code situacao_geral_boleto}, normalized. PAID/SETTLED/CREDITED are the three
 * "money came in" states; AWAITING_CREDIT is paid at another bank but not yet ours (the poll keeps
 * waiting); PAYMENT_REJECTED and CANCELED end nothing on their own — a human decides.
 */
public enum BoletoSituation { OPEN, PAID, SETTLED, AWAITING_CREDIT, CREDITED, PAYMENT_REJECTED, CANCELED }
```

`boleto/Address.java`:
```java
package com.gateway.kernel.provider.boleto;

/** {@code state} is the two-letter UF, {@code zip} the 8-digit CEP: the bank's boleto layout has no room for anything else. */
public record Address(String street, String district, String city, String state, String zip) {}
```

`boleto/Payer.java`:
```java
package com.gateway.kernel.provider.boleto;

/** {@code document} is CPF (11 digits) or CNPJ (14 digits); a registered boleto cannot be issued without a payer. */
public record Payer(String name, String document, Address address) {}
```

`boleto/BoletoIssueRequest.java`:
```java
package com.gateway.kernel.provider.boleto;

import com.gateway.kernel.money.Money;
import java.time.LocalDate;

/** {@code nossoNumero} is ours (8 digits): it is what lets a retry after a timeout ask the bank whether the boleto exists. */
public record BoletoIssueRequest(String nossoNumero, Money amount, LocalDate dueDate, LocalDate paymentLimitDate, Payer payer, String description) {}
```

`boleto/IssuedBoleto.java`:
```java
package com.gateway.kernel.provider.boleto;

import java.time.LocalDate;

/** What the bank answered to an issue: the boleto's identity and the Pix side of the same charge. The QR image (base64) is discarded. */
public record IssuedBoleto(String idBoletoIndividual, String linhaDigitavel, String codigoBarras, LocalDate paymentLimitDate,
                           String pixTxid, String pixCopiaECola, String pixKey) {}
```

`boleto/BoletoStatus.java`:
```java
package com.gateway.kernel.provider.boleto;

import com.gateway.kernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The bank's view of a boleto. The identity fields (id, linha digitável, código de barras, limit
 * date) are here so a charge adopted after a timeout can be completed from the query alone;
 * {@code pixCopiaECola} is the EMV the query carries under {@code qrcode_pix}, a fallback when
 * {@code GET /cob/{txid}} cannot confirm the reconstructed txid.
 */
public record BoletoStatus(BoletoSituation situation, Money paidAmount, Instant paidAt, String paidChannel,
                           String idBoletoIndividual, String linhaDigitavel, String codigoBarras, LocalDate paymentLimitDate,
                           String pixCopiaECola) {
  public boolean paid() {
    return situation == BoletoSituation.PAID || situation == BoletoSituation.SETTLED || situation == BoletoSituation.CREDITED;
  }
}
```

`boleto/BoletoProvider.java`:
```java
package com.gateway.kernel.provider.boleto;

import com.gateway.kernel.provider.ProviderCredentials;
import java.util.Optional;

/**
 * Implemented per bank in {@code gateway-providers} (ItauBoletoProvider); consumed by payments.
 * Every method takes the credential because the bank's account data (beneficiary id, wallet) lives
 * inside it, and only the provider knows the credential's shape.
 */
public interface BoletoProvider {
  String id();

  /** Fails fast with {@code CREDENTIALS_INCOMPLETE} (providerType = the field) before any HTTP and before payments writes a row. */
  void requireIssueCredentials(ProviderCredentials c);

  IssuedBoleto issue(ProviderCredentials c, BoletoIssueRequest r);

  /** Empty when the bank does not know the number (404 or an empty list). */
  Optional<BoletoStatus> find(ProviderCredentials c, String nossoNumero);

  /** The bank's baixa. {@code CONFLICT} when it already shows the boleto paid or settled. */
  void cancel(ProviderCredentials c, String nossoNumero);

  /** The Pix txid the bank derives for this boleto, so a charge adopted from the query can be matched to Pix webhooks. */
  String pixTxidFor(ProviderCredentials c, String nossoNumero);
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: mesmo comando do Step 2.
Expected: PASS (2 testes). Rode também `-Dtest=ProviderExceptionTest` para garantir que o enum estendido não quebrou nada.

- [ ] **Step 5: Commit**

```bash
git add gateway-kernel
git commit -m "feat(kernel): boleto provider contract and two provider error codes

BoletoProvider takes nossoNumero on cancel because the bank's baixa id
is agencia+conta+DAC+carteira+nosso numero (cash_management OpenAPI,
id_boleto minLength 23), not the boleto UUID. CONFLICT and
CREDENTIALS_INCOMPLETE are the two answers the Pix vocabulary had no
word for.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Credencial com `beneficiary_id`/`wallet_code`/`species_code`, endpoints por API e token URL por API

**Files:**
- Modify: `gateway-providers/src/main/java/com/gateway/providers/itau/auth/ItauCredentials.java` (record inteiro), `gateway-providers/src/main/java/com/gateway/providers/itau/auth/ItauEndpoints.java`
- Create: `gateway-providers/src/main/java/com/gateway/providers/itau/boleto/ItauBoletoEndpoints.java`
- Test: `gateway-providers/src/test/java/com/gateway/providers/itau/auth/ItauCredentialsTest.java` (casos novos), `gateway-providers/src/test/java/com/gateway/providers/itau/auth/ItauEndpointsTest.java`, `gateway-providers/src/test/java/com/gateway/providers/itau/auth/ItauTokenClientPerApiTest.java`

**Interfaces:**
- Consumes: `ItauTokenClient.tokenFor(ItauCredentials, ItauEndpoints, KeyStore)` (inalterado; cache por `fingerprint + "|" + tokenUrl`).
- Produces:
  ```java
  public record ItauCredentials(String clientId, Secret clientSecret, String apiKey, String certificatePem, Secret privateKeyPem, String pixKey,
                                String beneficiaryId /* nullable, ^\d{12}$ */, String walletCode /* "109" default, ^\d{3}$ */, String speciesCode /* "01" default, ^\d{2}$ */, String fingerprint) {
    public static ItauCredentials parse(byte[] json);      // JSON keys: beneficiary_id, wallet_code, species_code (all optional)
    public boolean hasBeneficiary();
    public void requireBoletoShape();                        // IllegalArgumentException("beneficiary_id") when absent
  }
  public record ItauEndpoints(URI apiBase, URI tokenUrl, boolean mutualTls) {
    public static final URI CASH_MANAGEMENT_TOKEN_URL;      // https://sts.itau.com.br/api/oauth/token (cash_management OpenAPI securitySchemes.oauth2)
    public static ItauEndpoints boletoIssue(ProviderEnvironment env);
    public static ItauEndpoints boletoQuery(ProviderEnvironment env);
    public static ItauEndpoints boletoInstruction(ProviderEnvironment env);
  }
  public record ItauBoletoEndpoints(ItauEndpoints issue, ItauEndpoints query, ItauEndpoints instruction) { public static ItauBoletoEndpoints forEnvironment(ProviderEnvironment env); }
  ```

- [ ] **Step 1: Testes**

Adicione a `ItauCredentialsTest.java` (mesma classe, ao lado dos existentes):
```java
  static final String WITH_BOLETO = "{\"client_id\":\"sbx-id\",\"client_secret\":\"sbx-secret\",\"pix_key\":\"60701190000104\","
      + "\"beneficiary_id\":\"150000052061\",\"wallet_code\":\"109\",\"species_code\":\"01\"}";

  @Test void parsesTheBoletoFields() {
    ItauCredentials c = ItauCredentials.parse(WITH_BOLETO.getBytes());
    assertThat(c.beneficiaryId()).isEqualTo("150000052061");
    assertThat(c.walletCode()).isEqualTo("109");
    assertThat(c.speciesCode()).isEqualTo("01");
    assertThat(c.hasBeneficiary()).isTrue();
    c.requireBoletoShape();
  }

  @Test void walletAndSpeciesDefaultWhenAbsent() {
    ItauCredentials c = ItauCredentials.parse(WITH_BOLETO.replace(",\"wallet_code\":\"109\",\"species_code\":\"01\"", "").getBytes());
    assertThat(c.walletCode()).isEqualTo("109");
    assertThat(c.speciesCode()).isEqualTo("01");
  }

  @Test void pixOnlyCredentialHasNoBeneficiaryAndSaysSo() {
    ItauCredentials c = ItauCredentials.parse("{\"client_id\":\"sbx-id\",\"client_secret\":\"sbx-secret\",\"pix_key\":\"60701190000104\"}".getBytes());
    assertThat(c.hasBeneficiary()).isFalse();
    assertThatThrownBy(c::requireBoletoShape).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("beneficiary_id");
  }

  @Test void boletoFieldsAreValidatedByRegex() {
    assertThatThrownBy(() -> ItauCredentials.parse(WITH_BOLETO.replace("150000052061", "1500000520").getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("beneficiary_id");
    assertThatThrownBy(() -> ItauCredentials.parse(WITH_BOLETO.replace("\"109\"", "\"1090\"").getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("wallet_code");
    assertThatThrownBy(() -> ItauCredentials.parse(WITH_BOLETO.replace("\"01\"", "\"1\"").getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("species_code");
  }

  @Test void beneficiaryChangesTheFingerprint() {
    assertThat(ItauCredentials.parse(WITH_BOLETO.getBytes()).fingerprint())
        .isNotEqualTo(ItauCredentials.parse(WITH_BOLETO.replace("150000052061", "150000052062").getBytes()).fingerprint());
  }
```

`ItauEndpointsTest.java`:
```java
package com.gateway.providers.itau.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.providers.itau.boleto.ItauBoletoEndpoints;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** The URLs are the spec's (2026-09-25); production instruction calls use the STS URL the cash_management OpenAPI declares. */
class ItauEndpointsTest {
  @Test void liveBoletoEndpointsUseTheirOwnBasesAndTwoTokenUrls() {
    ItauBoletoEndpoints e = ItauBoletoEndpoints.forEnvironment(ProviderEnvironment.LIVE);
    assertThat(e.issue().apiBase()).isEqualTo(URI.create("https://pix-pj.api.itau.com/recebimentos-pix/v1"));
    assertThat(e.query().apiBase()).isEqualTo(URI.create("https://secure.api.cloud.itau.com.br/boletoscash/v2"));
    assertThat(e.instruction().apiBase()).isEqualTo(URI.create("https://api.gateway.itau.com.br/cash_management/v2"));
    assertThat(e.issue().tokenUrl()).isEqualTo(URI.create("https://sts.itau.com.br/as/token.oauth2"));
    assertThat(e.query().tokenUrl()).isEqualTo(URI.create("https://sts.itau.com.br/as/token.oauth2"));
    assertThat(e.instruction().tokenUrl()).isEqualTo(ItauEndpoints.CASH_MANAGEMENT_TOKEN_URL).isEqualTo(URI.create("https://sts.itau.com.br/api/oauth/token"));
    assertThat(e.issue().mutualTls()).isTrue();
    assertThat(e.instruction().mutualTls()).isTrue();
  }

  @Test void sandboxBoletoEndpointsShareThePortalTokenAndNoMtls() {
    ItauBoletoEndpoints e = ItauBoletoEndpoints.forEnvironment(ProviderEnvironment.TEST);
    assertThat(e.issue().apiBase()).isEqualTo(URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-api-recebimentos-v1-externo/v1"));
    assertThat(e.query().apiBase()).isEqualTo(URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws/v1"));
    assertThat(e.instruction().apiBase()).isEqualTo(URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-gtw-cash-management-ext-v2/v2"));
    for (ItauEndpoints x : new ItauEndpoints[] {e.issue(), e.query(), e.instruction()}) {
      assertThat(x.tokenUrl()).isEqualTo(URI.create("https://sandbox.devportal.itau.com.br/api/oauth/jwt"));
      assertThat(x.mutualTls()).isFalse();
    }
  }
}
```

`ItauTokenClientPerApiTest.java` (HTTP simples; o mTLS já foi provado em `ItauTokenClientMtlsTest`):
```java
package com.gateway.providers.itau.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * One credential, two token URLs (Pix/issue/query at one STS path, cash_management at another): the
 * cache key is fingerprint + token URL, so each API gets its own token and neither evicts the other.
 */
class ItauTokenClientPerApiTest {
  static WireMockServer server;

  @BeforeAll static void start() { server = new WireMockServer(WireMockConfiguration.options().dynamicPort()); server.start(); }
  @AfterAll static void stop() { server.stop(); }

  @Test void eachTokenUrlGetsItsOwnCachedToken() {
    server.stubFor(post("/as/token.oauth2").willReturn(okJson("{\"access_token\":\"tok-pix\",\"expires_in\":300}")));
    server.stubFor(post("/api/oauth/token").willReturn(okJson("{\"access_token\":\"tok-cash\",\"expires_in\":300}")));
    ItauCredentials creds = ItauCredentials.parse("{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\"}".getBytes());
    ItauTokenClient client = new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(3));
    ItauEndpoints pixLike = ItauEndpoints.custom(URI.create(server.baseUrl() + "/v2"), URI.create(server.baseUrl() + "/as/token.oauth2"), false);
    ItauEndpoints cash = ItauEndpoints.custom(URI.create(server.baseUrl() + "/cash_management/v2"), URI.create(server.baseUrl() + "/api/oauth/token"), false);

    assertThat(client.tokenFor(creds, pixLike, null).value()).isEqualTo("tok-pix");
    assertThat(client.tokenFor(creds, cash, null).value()).isEqualTo("tok-cash");
    assertThat(client.tokenFor(creds, pixLike, null).value()).isEqualTo("tok-pix");
    assertThat(client.tokenFor(creds, cash, null).value()).isEqualTo("tok-cash");

    server.verify(1, postRequestedFor(urlEqualTo("/as/token.oauth2")));
    server.verify(1, postRequestedFor(urlEqualTo("/api/oauth/token")));
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-providers -am -Dtest='ItauCredentialsTest,ItauEndpointsTest,ItauTokenClientPerApiTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compilação (`beneficiaryId()`, `ItauBoletoEndpoints`).

- [ ] **Step 3: Implementar**

`ItauCredentials.java` — substitua o record inteiro (mantém tudo que existe e acrescenta os três campos):
```java
package com.gateway.providers.itau.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.kernel.security.Secret;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * A merchant's Itaú credential: six values for Pix (docs/providers/itau/NOTES.md) plus the boleto
 * account data (spec 2026-09-25 §4): {@code beneficiary_id} (agência 4 + conta 7 + DAC 1, required
 * to issue a boleto), {@code wallet_code} (carteira, 109 is the only one the product documents) and
 * {@code species_code} (espécie, 01 = DM). The sandbox shape has only client_id/client_secret/pix_key;
 * production requires x_itau_apikey, certificate_pem and private_key_pem — see
 * {@link #requireProductionShape()}; a boleto issue requires beneficiary_id — see {@link #requireBoletoShape()}.
 */
public record ItauCredentials(
    String clientId, Secret clientSecret, String apiKey, String certificatePem, Secret privateKeyPem, String pixKey,
    String beneficiaryId, String walletCode, String speciesCode, String fingerprint) {

  private static final Pattern API_KEY = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
  private static final Pattern BENEFICIARY = Pattern.compile("^\\d{12}$");
  private static final Pattern WALLET = Pattern.compile("^\\d{3}$");
  private static final Pattern SPECIES = Pattern.compile("^\\d{2}$");
  static final String DEFAULT_WALLET = "109";
  static final String DEFAULT_SPECIES = "01";

  public ItauCredentials {
    requireNonBlank(clientId, "client_id");
    if (clientSecret == null) throw new IllegalArgumentException("missing required field: client_secret");
    requireNonBlank(pixKey, "pix_key");
    if (apiKey != null && !API_KEY.matcher(apiKey).matches()) {
      throw new IllegalArgumentException("x_itau_apikey does not match the Itau format: " + apiKey);
    }
    boolean hasCert = certificatePem != null && !certificatePem.isBlank();
    boolean hasKey = privateKeyPem != null;
    if (hasCert != hasKey) {
      throw new IllegalArgumentException(hasCert ? "certificate_pem present without private_key_pem" : "private_key_pem present without certificate_pem");
    }
    if (beneficiaryId != null && !BENEFICIARY.matcher(beneficiaryId).matches()) {
      throw new IllegalArgumentException("beneficiary_id must be 12 digits (agencia + conta + DAC)");
    }
    if (walletCode == null) walletCode = DEFAULT_WALLET;
    if (!WALLET.matcher(walletCode).matches()) throw new IllegalArgumentException("wallet_code must be 3 digits");
    if (speciesCode == null) speciesCode = DEFAULT_SPECIES;
    if (!SPECIES.matcher(speciesCode).matches()) throw new IllegalArgumentException("species_code must be 2 digits");
  }

  private static void requireNonBlank(String value, String field) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("missing required field: " + field);
  }

  public boolean hasCertificate() { return certificatePem != null && privateKeyPem != null; }

  public boolean hasBeneficiary() { return beneficiaryId != null; }

  /** Called when the endpoint requires mTLS (LIVE): fails fast instead of at the first handshake. */
  public void requireProductionShape() {
    if (!hasCertificate()) throw new IllegalArgumentException("production credential missing certificate_pem/private_key_pem");
    if (apiKey == null) throw new IllegalArgumentException("production credential missing x_itau_apikey");
  }

  /** Called before an issue: a boleto needs the beneficiary account, and the bank's 400 would name a field the merchant never sent. */
  public void requireBoletoShape() {
    if (!hasBeneficiary()) throw new IllegalArgumentException("beneficiary_id");
  }

  /**
   * SHA-256 hex of the whole decrypted payload, computed once in {@link #parse}: the cache key for
   * the OAuth token and the mTLS HttpClient. Hashing every byte means any change to the credential
   * (a rotated secret or key, a new beneficiary) is a new cache entry. Never logged.
   */
  @Override public String fingerprint() { return fingerprint; }

  @Override public String toString() {
    return "ItauCredentials[clientId=" + clientId + ", clientSecret=***, apiKey=" + apiKey
        + ", certificatePem=" + (certificatePem == null ? "null" : "***") + ", privateKeyPem=***, pixKey=" + pixKey
        + ", beneficiaryId=" + beneficiaryId + ", walletCode=" + walletCode + ", speciesCode=" + speciesCode + "]";
  }

  public static ItauCredentials parse(byte[] json) {
    String fingerprint = sha256Hex(json);
    Raw raw = new ObjectMapper().readValue(json, Raw.class);
    if (raw.clientId == null || raw.clientId.isBlank()) throw new IllegalArgumentException("missing required field: client_id");
    if (raw.clientSecret == null || raw.clientSecret.isBlank()) throw new IllegalArgumentException("missing required field: client_secret");
    if (raw.pixKey == null || raw.pixKey.isBlank()) throw new IllegalArgumentException("missing required field: pix_key");
    return new ItauCredentials(
        raw.clientId,
        Secret.of(raw.clientSecret),
        raw.apiKey,
        blankToNull(raw.certificatePem),
        // A blank key is a missing key: "" used to pass as present, pair with a certificate, and
        // fail only at the first mTLS handshake instead of here.
        blankToNull(raw.privateKeyPem) == null ? null : Secret.of(raw.privateKeyPem),
        raw.pixKey,
        blankToNull(raw.beneficiaryId),
        blankToNull(raw.walletCode),
        blankToNull(raw.speciesCode),
        fingerprint);
  }

  private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Intermediate shape for Jackson: snake_case wire names, nothing validated yet. */
  private record Raw(
      @JsonProperty("client_id") String clientId,
      @JsonProperty("client_secret") String clientSecret,
      @JsonProperty("x_itau_apikey") String apiKey,
      @JsonProperty("certificate_pem") String certificatePem,
      @JsonProperty("private_key_pem") String privateKeyPem,
      @JsonProperty("pix_key") String pixKey,
      @JsonProperty("beneficiary_id") String beneficiaryId,
      @JsonProperty("wallet_code") String walletCode,
      @JsonProperty("species_code") String speciesCode) {}
}
```
(`ItauCredentialsTest.blankPrivateKeyCountsAsMissing` e os demais continuam válidos: o `JSON` de teste sem os campos novos parseia com os defaults.)

`ItauEndpoints.java` — acrescente as constantes e fábricas (o record e `forEnvironment`/`custom` ficam como estão):
```java
  // Bolecode (spec 2026-09-25 §1): three products, three bases. Production issue/query authenticate at the
  // same STS path as Pix (APIGatewaySTSAuthorizer); cash_management declares its own tokenUrl in its
  // OpenAPI securitySchemes. Sandbox: all three behind the portal, same /api/oauth/jwt, no mTLS.
  private static final URI LIVE_BOLETO_ISSUE_BASE = URI.create("https://pix-pj.api.itau.com/recebimentos-pix/v1");
  private static final URI LIVE_BOLETO_QUERY_BASE = URI.create("https://secure.api.cloud.itau.com.br/boletoscash/v2");
  private static final URI LIVE_BOLETO_INSTRUCTION_BASE = URI.create("https://api.gateway.itau.com.br/cash_management/v2");
  public static final URI CASH_MANAGEMENT_TOKEN_URL = URI.create("https://sts.itau.com.br/api/oauth/token");
  private static final URI TEST_BOLETO_ISSUE_BASE = URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-api-recebimentos-v1-externo/v1");
  private static final URI TEST_BOLETO_QUERY_BASE = URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws/v1");
  private static final URI TEST_BOLETO_INSTRUCTION_BASE = URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-gtw-cash-management-ext-v2/v2");

  public static ItauEndpoints boletoIssue(ProviderEnvironment env) {
    return switch (env) {
      case LIVE -> new ItauEndpoints(LIVE_BOLETO_ISSUE_BASE, LIVE_TOKEN_URL, true);
      case TEST -> new ItauEndpoints(TEST_BOLETO_ISSUE_BASE, TEST_TOKEN_URL, false);
    };
  }

  public static ItauEndpoints boletoQuery(ProviderEnvironment env) {
    return switch (env) {
      case LIVE -> new ItauEndpoints(LIVE_BOLETO_QUERY_BASE, LIVE_TOKEN_URL, true);
      case TEST -> new ItauEndpoints(TEST_BOLETO_QUERY_BASE, TEST_TOKEN_URL, false);
    };
  }

  public static ItauEndpoints boletoInstruction(ProviderEnvironment env) {
    return switch (env) {
      case LIVE -> new ItauEndpoints(LIVE_BOLETO_INSTRUCTION_BASE, CASH_MANAGEMENT_TOKEN_URL, true);
      case TEST -> new ItauEndpoints(TEST_BOLETO_INSTRUCTION_BASE, TEST_TOKEN_URL, false);
    };
  }
```

`itau/boleto/ItauBoletoEndpoints.java`:
```java
package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.providers.itau.auth.ItauEndpoints;

/** The three Itaú APIs a Bolecode touches, each with its own base and token URL (ItauEndpoints has the values and their sources). */
public record ItauBoletoEndpoints(ItauEndpoints issue, ItauEndpoints query, ItauEndpoints instruction) {
  public static ItauBoletoEndpoints forEnvironment(ProviderEnvironment env) {
    return new ItauBoletoEndpoints(ItauEndpoints.boletoIssue(env), ItauEndpoints.boletoQuery(env), ItauEndpoints.boletoInstruction(env));
  }
}
```

- [ ] **Step 4: Rodar e ver passar**

Run: comando do Step 2, e depois a classe inteira de auth: `-Dtest='com.gateway.providers.itau.auth.*Test'`.
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add gateway-providers
git commit -m "feat(providers): boleto fields in the itau credential and endpoints per api

beneficiary_id is validated at parse (12 digits) so an issue fails
before any HTTP with the field's name; wallet and species default to
the product's documented 109/01. cash_management gets its own token
URL, straight from its OpenAPI securitySchemes; the token client
already caches per token URL, and a test now proves it.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: `BoletoPixApiClient` — `POST /boletos-pix` com fixtures oficiais, sanitização e validação de schema

**Files:**
- Create: `gateway-providers/src/main/java/com/gateway/providers/itau/boleto/{BoletoHttp,BoletoErrors,BoletoText,BoletoAmounts,BoletoPixApiClient}.java`, `gateway-providers/src/main/java/com/gateway/providers/itau/boleto/dto/{BoletoPixRequest,BoletoPixResponse,BoletoProblem}.java`
- Create: `gateway-providers/src/test/resources/itau/boleto/issue.openapi.json` (cópia verbatim de `docs/providers/itau/itau-ep9-api-recebimentos-v1-externo.openapi.json`), `gateway-providers/src/test/resources/itau/boleto/fixtures/{post_boletos_pix_request_min.json,post_boletos_pix_200.json,post_boletos_pix_202.json,post_boletos_pix_422.json}`, `gateway-providers/src/test/resources/itau/boleto/fixtures/README.md`
- Test: `gateway-providers/src/test/java/com/gateway/providers/itau/boleto/{BoletoFixturesFromOpenApiTest,BoletoRequestSchemaValidationTest,BoletoTextTest,BoletoAmountsTest,BoletoPixApiClientContractTest}.java`

**Interfaces:**
- Consumes: `ItauTokenClient.httpClientFor/tokenFor/evict`, `ItauEndpoints`, `ItauCredentials` (Task 2), `BoletoIssueRequest`/`Payer`/`Address` (Task 1).
- Produces (package `com.gateway.providers.itau.boleto`; DTOs em `…boleto.dto`, públicos porque o teste de schema e o provider os usam):
  ```java
  final class BoletoHttp {                     // package-private, shared by the three clients
    BoletoHttp(ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout);
    HttpRequest.Builder request(String pathAndQuery);
    HttpResponse<String> send(ItauCredentials creds, HttpRequest.Builder b);   // Bearer, x-itau-correlationID, x-itau-apikey when present; timeouts -> ProviderException; 401 -> evict; returns ANY status
    static String seg(String s); static String enc(String s);
  }
  public final class BoletoErrors { public static ProviderException from(int status, String body); public static boolean mentionsAlreadyPaid(String body); }
     // 202 -> TIMEOUT; 400/422 -> DECLINED; 401/403 -> UNAUTHENTICATED; 404/410 -> NOT_FOUND; 504 -> TIMEOUT; other 5xx -> UNAVAILABLE; else UNKNOWN. providerType = body.codigo. Message = mensagem + " [campo: mensagem; ...]" — never campos[].valor (it echoes payer data)
  public final class BoletoText { public static String name(String s, int max); public static String text(String s, int max); }   // whitelist of the OpenAPI patterns; strips http/javascript/alert; collapses spaces; truncates; ProviderException(INVALID) when nothing usable is left
  public final class BoletoAmounts { public static String toItau(Money m); public static Money fromItau(String s); }   // "^\d{1,15}\.\d{2}$"
  public record BoletoPixRequest(...) { public static BoletoPixRequest forIssue(BoletoIssueRequest r, ItauCredentials c); }   // etapa "efetivacao"
  public record BoletoPixResponse(...) { public Individual first(); }
  public record BoletoProblem(String codigo, String mensagem, List<Campo> campos) { public record Campo(String campo, String mensagem, String valor) {} }
  class BoletoPixApiClient { BoletoPixApiClient(ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout); BoletoPixResponse post(ItauCredentials c, BoletoPixRequest body); }
     // 200/201 -> parsed; anything else -> BoletoErrors.from (202 becomes TIMEOUT there)
  ```
  Limites de texto (descrições do OpenAPI, que são os limites do mainframe, menores que o `maxLength` do schema): `nome_pessoa` 50, `nome_logradouro` 45, `nome_bairro` 15, `nome_cidade` 20, `texto_uso_beneficiario` 25.

- [ ] **Step 1: Copiar o OpenAPI e criar os fixtures**

```bash
mkdir -p gateway-providers/src/test/resources/itau/boleto/fixtures
cp docs/providers/itau/itau-ep9-api-recebimentos-v1-externo.openapi.json gateway-providers/src/test/resources/itau/boleto/issue.openapi.json
python - <<'EOF'
import json
api = json.load(open('gateway-providers/src/test/resources/itau/boleto/issue.openapi.json', encoding='utf-8'))
ex = api['components']['examples']
def dump(name, obj):
    json.dump(obj, open('gateway-providers/src/test/resources/itau/boleto/fixtures/' + name, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
dump('post_boletos_pix_request_min.json', ex['requestPostBoletosPix']['value'])
dump('post_boletos_pix_200.json', ex['200boletoPixResponse']['value'])
dump('post_boletos_pix_202.json', {"codigo": "202", "mensagem": "Operação em andamento, consulte seu bolecode em instantes"})
dump('post_boletos_pix_422.json', {"codigo": "422", "mensagem": "Consulta não realizada por alguma regra de negócio não atendida.",
     "campos": [{"campo": "data_vencimento", "mensagem": "Vencimento menor que prazo mínimo para carteira - (-360 dias)", "valor": "2024-01-01"}]})
EOF
```
`fixtures/README.md`:
```markdown
# Itaú Bolecode fixtures — where each file came from

`../issue.openapi.json`, `../query.openapi.json` and `../instruction.openapi.json` are verbatim copies of
`docs/providers/itau/itau-ep9-api-recebimentos-v1-externo.openapi.json`,
`itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws.openapi.json` and
`itau-ep9-gtw-cash-management-ext-v2.openapi.json` (portal versions 1.0.7, 1.2.9, 2.75.147, read 2026-09-25).

## Copied verbatim from `components.examples` (pinned by `BoletoFixturesFromOpenApiTest`)

| file | OpenAPI | example |
|---|---|---|
| `post_boletos_pix_request_min.json` | issue | `requestPostBoletosPix.value` |
| `post_boletos_pix_200.json` | issue | `200boletoPixResponse.value` |
| `get_boletos_200.json` | query | `query_200_boletos_get_response.value` |
| `patch_baixa_200.json` | instruction | `200_instrucao.value.value` (the example nests `value` twice) |

## Derived — no example in the OpenAPI for this state

- `post_boletos_pix_202.json`, `post_boletos_pix_422.json`: the `bolecode202` / `bolecode422` schema `example` values, assembled into a body.
- `get_boletos_200_paid.json`, `get_boletos_200_canceled.json`, `get_boletos_200_rejected.json`, `get_boletos_200_awaiting.json`:
  `get_boletos_200.json` reduced to its second item (carteira 109), `numero_nosso_numero` set to `00000001`,
  `situacao_geral_boleto` set to `Pago` / `Baixado` / `Pagamento Rejeitado` / `Aguardando Crédito`. The paid one keeps the
  example's `pagamentos_cobranca` (valor 2100.00, data 2020-01-20); the others drop `pagamentos_cobranca` and `baixa`.
- `get_boletos_200_empty.json`: `{"data": [], "page": {...}}` — the shape the schema gives an unknown number.
- `get_boletos_404.json`: the `404` response example of the query OpenAPI.
- `patch_baixa_422_paid.json`: `{"codigo":"422","mensagem":"Boleto já liquidado","campos":[]}` — the 422 has no schema; the text is the
  portal's wording for a baixa on a settled boleto.
```
(Os arquivos de `query`/`instruction` listados nascem nas Tasks 4 e 5; o README já os documenta para não ser editado três vezes.)

- [ ] **Step 2: Testes**

`BoletoFixturesFromOpenApiTest.java`:
```java
package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Every replayed fixture is the bank's own example; a hand-"improved" fixture drifts from the bank and fails here. */
class BoletoFixturesFromOpenApiTest {
  static final ObjectMapper M = new ObjectMapper();

  static JsonNode example(String api, String pointer) throws Exception {
    JsonNode doc = M.readTree(Files.readString(Path.of("src/test/resources/itau/boleto/" + api + ".openapi.json")));
    JsonNode ex = doc.at(pointer);
    assertThat(ex.isMissingNode()).as("%s exists in %s", pointer, api).isFalse();
    return ex;
  }

  static JsonNode fixture(String file) throws Exception {
    return M.readTree(Files.readString(Path.of("src/test/resources/itau/boleto/fixtures/" + file)));
  }

  @Test void issueRequestMin() throws Exception { assertThat(fixture("post_boletos_pix_request_min.json")).isEqualTo(example("issue", "/components/examples/requestPostBoletosPix/value")); }
  @Test void issue200() throws Exception { assertThat(fixture("post_boletos_pix_200.json")).isEqualTo(example("issue", "/components/examples/200boletoPixResponse/value")); }
}
```
(As Tasks 4 e 5 acrescentam `query200` e `baixa200` a esta classe.)

`BoletoAmountsTest.java`:
```java
package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import org.junit.jupiter.api.Test;

class BoletoAmountsTest {
  @Test void roundTrips() {
    assertThat(BoletoAmounts.toItau(Money.brl(123456))).isEqualTo("1234.56");
    assertThat(BoletoAmounts.toItau(Money.brl(5))).isEqualTo("0.05");
    assertThat(BoletoAmounts.fromItau("2100.00")).isEqualTo(Money.brl(210000));
    assertThat(BoletoAmounts.fromItau("0.05")).isEqualTo(Money.brl(5));
  }

  /** The boleto pattern is ^\d+\.\d{2}$ with 15 integer digits; anything else is a bug on our side. */
  @Test void rejectsWhatTheBankRejects() {
    assertThatThrownBy(() -> BoletoAmounts.toItau(Money.brl(0))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BoletoAmounts.toItau(new Money(1, "USD"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BoletoAmounts.fromItau("1,00")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BoletoAmounts.fromItau("1.5")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> BoletoAmounts.fromItau("00000000000001000")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

`BoletoTextTest.java`:
```java
package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.provider.ProviderException;
import org.junit.jupiter.api.Test;

/** The bank's own list (issue OpenAPI, boletoPix.description): no [ : < > & ; ' " ` ( ) # * / | ü and no http/javascript/alert. */
class BoletoTextTest {
  @Test void keepsAccentsAndDropsTheForbiddenCharacters() {
    assertThat(BoletoText.name("João da Silva", 50)).isEqualTo("João da Silva");
    assertThat(BoletoText.name("Ana <b>&amp; Cia (Ltda) / \"x\"", 50)).isEqualTo("Ana bamp Cia Ltda x");
    assertThat(BoletoText.text("Rua das Flores, 10 - apto 3; #2", 45)).isEqualTo("Rua das Flores, 10 - apto 3 2");
    assertThat(BoletoText.text("Müller", 45)).isEqualTo("Mller");
  }

  @Test void namesHaveNoDigitsButAddressesDo() {
    assertThat(BoletoText.name("Loja 24h", 50)).isEqualTo("Loja h");
    assertThat(BoletoText.text("Loja 24h", 45)).isEqualTo("Loja 24h");
  }

  @Test void stripsTheForbiddenWordsAndTruncates() {
    assertThat(BoletoText.text("http://evil javascript alert Pedido 42", 45)).isEqualTo("evil Pedido 42");
    assertThat(BoletoText.text("x".repeat(60), 25)).hasSize(25);
    assertThat(BoletoText.text("  a   b  ", 45)).isEqualTo("a b");
  }

  @Test void nothingUsableIsInvalid() {
    assertThatThrownBy(() -> BoletoText.name("<>()", 50)).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.INVALID));
    assertThat(BoletoText.text(null, 45)).isNull();
  }
}
```

`BoletoRequestSchemaValidationTest.java`:
```java
package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.boleto.Address;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.kernel.provider.boleto.Payer;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.boleto.dto.BoletoPixRequest;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What we send must satisfy the bank's own schema. Same mechanics as the Pix test: the validator is
 * pointed at the whole file by URI + JSON-pointer fragment so {@code $ref}s resolve; networknt is
 * Jackson 2, so the body crosses over as a string.
 */
class BoletoRequestSchemaValidationTest {
  static final tools.jackson.databind.ObjectMapper M3 = new tools.jackson.databind.ObjectMapper();
  static final com.fasterxml.jackson.databind.ObjectMapper M2 = new com.fasterxml.jackson.databind.ObjectMapper();
  static final Path OPENAPI = Path.of("src/test/resources/itau/boleto/issue.openapi.json");

  static JsonSchema schemaOf(String component) {
    String uri = OPENAPI.toAbsolutePath().toUri() + "#/components/schemas/" + component;
    return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(SchemaLocation.of(uri), SchemaValidatorsConfig.builder().build());
  }

  static Set<ValidationMessage> validate(Object body) throws Exception {
    return schemaOf("boletoPix").validate(M2.readTree(M3.writeValueAsString(body)));
  }

  static ItauCredentials creds() {
    return ItauCredentials.parse("{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\"}".getBytes());
  }

  static BoletoIssueRequest request(String document) {
    return new BoletoIssueRequest("00000042", Money.brl(123456), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 30),
        new Payer("João da Silva", document, new Address("Rua das Flores, 10", "Centro", "São Paulo", "SP", "01310100")), "Pedido 42");
  }

  @Test void issueBodyWithCpfIsValid() throws Exception {
    assertThat(validate(BoletoPixRequest.forIssue(request("12345678901"), creds()))).isEmpty();
  }

  @Test void issueBodyWithCnpjIsValid() throws Exception {
    assertThat(validate(BoletoPixRequest.forIssue(request("12345678000190"), creds()))).isEmpty();
  }

  @Test void sanitizedTextsStillValidate() throws Exception {
    BoletoIssueRequest dirty = new BoletoIssueRequest("00000042", Money.brl(100), LocalDate.of(2026, 12, 31), null,
        new Payer("Ana & Cia (Ltda)", "12345678901", new Address("Av. Paulista, 1000 / 10º", "Bela Vista <x>", "São Paulo", "SP", "01310100")), "http://x alert #1");
    assertThat(validate(BoletoPixRequest.forIssue(dirty, creds()))).isEmpty();
  }

  /** Guards the guard: the schema must reject an obviously wrong body. */
  @Test void theSchemaRejectsABadAmount() throws Exception {
    BoletoPixRequest body = BoletoPixRequest.forIssue(request("12345678901"), creds());
    var node = (com.fasterxml.jackson.databind.node.ObjectNode) M2.readTree(M3.writeValueAsString(body));
    ((com.fasterxml.jackson.databind.node.ObjectNode) node.at("/dado_boleto/dados_individuais_boleto/0")).put("valor_titulo", "1234");
    assertThat(schemaOf("boletoPix").validate(node)).isNotEmpty();
  }
}
```

`BoletoPixApiClientContractTest.java`:
```java
package com.gateway.providers.itau.boleto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.Address;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.kernel.provider.boleto.Payer;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.dto.BoletoPixRequest;
import com.gateway.providers.itau.boleto.dto.BoletoPixResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.*;

/** POST /boletos-pix against the bank's own examples. Plain HTTP, sandbox-shaped credentials (mTLS is proven in ItauTokenClientMtlsTest). */
class BoletoPixApiClientContractTest {
  static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
  static final String APIKEY = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  static WireMockServer server;

  @BeforeAll static void start() { server = new WireMockServer(WireMockConfiguration.options().dynamicPort()); server.start(); }
  @AfterAll static void stop() { server.stop(); }
  @BeforeEach void reset() {
    server.resetAll();
    server.stubFor(post("/api/oauth/jwt").willReturn(okJson("{\"access_token\":\"tok\",\"expires_in\":300}")));
  }

  static String fixture(String f) {
    try { return Files.readString(Path.of("src/test/resources/itau/boleto/fixtures/" + f), StandardCharsets.UTF_8); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  static ItauCredentials creds() {
    return ItauCredentials.parse(("{\"client_id\":\"sandbox-client\",\"client_secret\":\"sandbox-secret\",\"x_itau_apikey\":\"" + APIKEY
        + "\",\"pix_key\":\"60701190000104\",\"beneficiary_id\":\"150000052061\",\"wallet_code\":\"109\",\"species_code\":\"01\"}").getBytes());
  }

  static BoletoPixApiClient client(Duration readTimeout) {
    ItauEndpoints e = ItauEndpoints.custom(URI.create(server.baseUrl() + "/v1"), URI.create(server.baseUrl() + "/api/oauth/jwt"), false);
    return new BoletoPixApiClient(new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(3)), e, null, readTimeout);
  }
  static BoletoPixApiClient client() { return client(Duration.ofSeconds(5)); }

  /** The bank's minimal example, built from our request: same payer, same number, same amount and dates. */
  static BoletoPixRequest exampleRequest() {
    return BoletoPixRequest.forIssue(new BoletoIssueRequest("12345678", Money.brl(123456), LocalDate.of(2026, 12, 31), null,
        new Payer("João da Silva", "12345678901", new Address("Rua das Flores", "Centro", "São Paulo", "SP", "01310100")), null), creds());
  }

  @Test void postSendsHeadersAndTheBanksMinimalBodyAndParses200() {
    server.stubFor(post("/v1/boletos-pix")
        .withHeader("Authorization", equalTo("Bearer tok"))
        .withHeader("x-itau-apikey", equalTo(APIKEY))
        .withHeader("x-itau-correlationID", matching(UUID_RE))
        .withHeader("Content-Type", containing("application/json"))
        .withRequestBody(equalToJson(fixture("post_boletos_pix_request_min.json"), false, true))
        .willReturn(okJson(fixture("post_boletos_pix_200.json"))));

    BoletoPixResponse r = client().post(creds(), exampleRequest());

    BoletoPixResponse.Individual i = r.first();
    assertThat(i.idBoletoIndividual()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    assertThat(i.numeroLinhaDigitavel()).hasSize(47);
    assertThat(i.codigoBarras()).hasSize(44);
    assertThat(i.dataLimitePagamento()).isEqualTo("2027-01-31");
    assertThat(r.dadosQrcode().txid()).isEqualTo("BL1234567890123456789012345678901");
    assertThat(r.dadosQrcode().emv()).startsWith("000201");
    assertThat(r.dadosQrcode().chave()).isEqualTo("12345678000190");
  }

  @Test void sanitizedTextsAreWhatGoesOnTheWire() {
    server.stubFor(post("/v1/boletos-pix").willReturn(okJson(fixture("post_boletos_pix_200.json"))));
    BoletoPixRequest dirty = BoletoPixRequest.forIssue(new BoletoIssueRequest("12345678", Money.brl(100), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 30),
        new Payer("Ana & Cia (Ltda)", "12345678000190", new Address("Av. Paulista, 1000 / 10", "Bela Vista <x>", "São Paulo", "SP", "01310100")), "javascript Pedido #42"), creds());
    client().post(creds(), dirty);
    server.verify(postRequestedFor(urlEqualTo("/v1/boletos-pix"))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.pessoa.nome_pessoa", equalTo("Ana Cia Ltda")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.pessoa.tipo_pessoa.codigo_tipo_pessoa", equalTo("J")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.pessoa.tipo_pessoa.numero_cadastro_nacional_pessoa_juridica", equalTo("12345678000190")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.endereco.nome_logradouro", equalTo("Av. Paulista, 1000 10")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.endereco.nome_bairro", equalTo("Bela Vista x")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.dados_individuais_boleto[0].data_limite_pagamento", equalTo("2027-01-30")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.dados_individuais_boleto[0].texto_uso_beneficiario", equalTo("Pedido 42")))
        .withRequestBody(matchingJsonPath("$.etapa_processo_boleto", equalTo("efetivacao"))));
  }

  @Test void accepted202IsATimeout() {
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody(fixture("post_boletos_pix_202.json"))));
    assertThatThrownBy(() -> client().post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.TIMEOUT);
      assertThat(e.httpStatus()).isEqualTo(202);
    });
  }

  @Test void businessRejectionIsDeclinedWithFieldsButNeverValues() {
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json").withBody(fixture("post_boletos_pix_422.json"))));
    assertThatThrownBy(() -> client().post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.DECLINED);
      assertThat(e.httpStatus()).isEqualTo(422);
      assertThat(e.providerType()).isEqualTo("422");
      assertThat(e.getMessage()).contains("data_vencimento").contains("Vencimento menor").doesNotContain("2024-01-01");
    });
  }

  @Test void validation400IsDeclined() {
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(400).withBody("{\"codigo\":\"400\",\"mensagem\":\"Erro na validação de Campos\",\"campos\":[{\"campo\":\"data.dado_boleto.tipo_boleto\",\"mensagem\":\"Tipo de boleto inválido\"}]}")));
    assertThatThrownBy(() -> client().post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.DECLINED));
  }

  @Test void unauthorizedEvictsTheTokenAndIsUnauthenticated() {
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(401)));
    BoletoPixApiClient c = client();
    assertThatThrownBy(() -> c.post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNAUTHENTICATED));
    assertThatThrownBy(() -> c.post(creds(), exampleRequest())).isInstanceOf(ProviderException.class);
    server.verify(2, postRequestedFor(urlEqualTo("/api/oauth/jwt")));
  }

  @Test void gatewayTimeoutAndUnavailable() {
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(504)));
    assertThatThrownBy(() -> client().post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.TIMEOUT));
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> client().post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNAVAILABLE));
  }

  @Test void socketTimeoutIsTimeout() {
    server.stubFor(post("/v1/boletos-pix").willReturn(okJson(fixture("post_boletos_pix_200.json")).withFixedDelay(3000)));
    assertThatThrownBy(() -> client(Duration.ofSeconds(1)).post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.TIMEOUT));
  }

  @Test void sandboxWithoutApiKeyOmitsTheHeader() {
    server.stubFor(post("/v1/boletos-pix").willReturn(okJson(fixture("post_boletos_pix_200.json"))));
    ItauCredentials noKey = ItauCredentials.parse("{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\"}".getBytes());
    client().post(noKey, BoletoPixRequest.forIssue(new BoletoIssueRequest("12345678", Money.brl(100), LocalDate.of(2026, 12, 31), null,
        new Payer("João da Silva", "12345678901", new Address("Rua das Flores", "Centro", "São Paulo", "SP", "01310100")), null), noKey));
    server.verify(postRequestedFor(urlEqualTo("/v1/boletos-pix")).withoutHeader("x-itau-apikey").withHeader("Authorization", equalTo("Bearer tok")));
  }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-providers -Dtest='Boleto*Test' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compilação.

- [ ] **Step 4: Implementar**

`BoletoAmounts.java`:
```java
package com.gateway.providers.itau.boleto;

import com.gateway.kernel.money.Money;
import java.util.regex.Pattern;

/** The one place that turns cents into the boleto string ({@code ^\d+\.\d{2}$}, 15 integer digits) and back. */
public final class BoletoAmounts {
  private static final Pattern BANK = Pattern.compile("\\d{1,15}\\.\\d{2}");
  private static final long MAX_CENTS = 99_999_999_999_999_999L;

  private BoletoAmounts() {}

  public static String toItau(Money m) {
    if (!"BRL".equals(m.currency())) throw new IllegalArgumentException("boleto is BRL only: " + m.currency());
    if (m.cents() <= 0 || m.cents() > MAX_CENTS) throw new IllegalArgumentException("amount outside the boleto range: " + m.cents());
    return m.cents() / 100 + "." + String.format("%02d", m.cents() % 100);
  }

  public static Money fromItau(String s) {
    if (s == null || !BANK.matcher(s).matches()) throw new IllegalArgumentException("not a boleto amount: " + s);
    String[] p = s.split("\\.");
    return Money.brl(Long.parseLong(p[0]) * 100 + Long.parseLong(p[1]));
  }
}
```

`BoletoText.java`:
```java
package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import java.util.regex.Pattern;

/**
 * The issue OpenAPI forbids {@code [ : < > & ; ' " ` ( ) # * / | ü} and the words http/javascript/alert
 * anywhere in the payload, and each text field has its own character class. Filtering by the
 * field's class (a whitelist) is safer than removing the listed characters: it also drops what the
 * schema's pattern would reject. {@code max} is the mainframe limit from the field's description,
 * smaller than the schema's maxLength (nome_pessoa: 50 vs 100).
 */
public final class BoletoText {
  private static final String ACCENTS = "áàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ";
  private static final Pattern NOT_NAME = Pattern.compile("[^a-zA-Z\\s" + ACCENTS + "]");
  private static final Pattern NOT_TEXT = Pattern.compile("[^a-zA-Z0-9\\s\\-.," + ACCENTS + "]");
  private static final Pattern FORBIDDEN_WORDS = Pattern.compile("(?i)http|javascript|alert");
  private static final Pattern SPACES = Pattern.compile("\\s+");

  private BoletoText() {}

  /** {@code pessoa.nome_pessoa}: letters and spaces only. */
  public static String name(String s, int max) { return clean(s, NOT_NAME, max); }

  /** Address lines, city, district, {@code texto_uso_beneficiario}: letters, digits, space, {@code - . ,}. */
  public static String text(String s, int max) { return clean(s, NOT_TEXT, max); }

  private static String clean(String s, Pattern notAllowed, int max) {
    if (s == null) return null;
    String out = FORBIDDEN_WORDS.matcher(s).replaceAll("");
    out = notAllowed.matcher(out).replaceAll("");
    out = SPACES.matcher(out.trim()).replaceAll(" ");
    if (out.isEmpty()) {
      // INVALID, not DECLINED: the bank never saw this; it is our input that has nothing usable.
      throw new ProviderException(ProviderException.Code.INVALID, 0, null, "text has no characters the bank accepts");
    }
    return out.length() <= max ? out : out.substring(0, max).trim();
  }
}
```
(Confira o caso `"http://evil javascript alert Pedido 42"` → após remover as palavras sobra `"://evil   Pedido 42"`; a whitelist tira `:` e `/` → `"evil Pedido 42"`. É o valor esperado no teste.)

`dto/BoletoProblem.java`:
```java
package com.gateway.providers.itau.boleto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** The three boleto APIs' error body ({@code codigo, mensagem, campos[]}) — not RFC 7807 like the Pix API. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BoletoProblem(String codigo, String mensagem, List<Campo> campos) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Campo(String campo, String mensagem, String valor) {}
}
```

`BoletoErrors.java`:
```java
package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderException.Code;
import com.gateway.providers.itau.boleto.dto.BoletoProblem;
import java.text.Normalizer;
import java.util.Locale;
import tools.jackson.databind.ObjectMapper;

/**
 * Non-2xx from the boleto APIs → {@link ProviderException}. 400 and 422 are both DECLINED: the bank
 * validated the business (wallet rules, dates, payer) and said no, and nothing on our side changes
 * that. {@code campos[].valor} is deliberately left out of the message — it echoes what we sent,
 * which includes the payer's document — and the message goes to provider_requests and the log.
 */
public final class BoletoErrors {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_RAW = 300;

  private BoletoErrors() {}

  public static ProviderException from(int status, String body) {
    BoletoProblem p = parse(body);
    String message;
    if (p != null && p.mensagem() != null) {
      StringBuilder sb = new StringBuilder(p.mensagem());
      if (p.campos() != null && !p.campos().isEmpty()) {
        sb.append(" [");
        for (int i = 0; i < p.campos().size(); i++) {
          if (i > 0) sb.append("; ");
          sb.append(p.campos().get(i).campo()).append(": ").append(p.campos().get(i).mensagem());
        }
        sb.append(']');
      }
      message = sb.toString();
    } else {
      message = "Itaú boleto HTTP " + status + (body == null || body.isBlank() ? "" : ": " + truncate(body));
    }
    return new ProviderException(code(status), status, p == null ? null : p.codigo(), message);
  }

  static Code code(int status) {
    if (status == 202) return Code.TIMEOUT; // "operação em andamento": the bank has not decided yet, treat like a lost answer
    if (status == 400 || status == 422) return Code.DECLINED;
    if (status == 401 || status == 403) return Code.UNAUTHENTICATED;
    if (status == 404 || status == 410) return Code.NOT_FOUND;
    if (status == 504) return Code.TIMEOUT;
    if (status >= 500) return Code.UNAVAILABLE;
    return Code.UNKNOWN;
  }

  /** The 422 of a baixa on a boleto the bank already settled says "pago"/"liquidado" in its text (no schema, no code). */
  public static boolean mentionsAlreadyPaid(String body) {
    if (body == null) return false;
    String plain = Normalizer.normalize(body, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    return plain.contains("pago") || plain.contains("liquidado");
  }

  private static BoletoProblem parse(String body) {
    if (body == null || body.isBlank()) return null;
    try {
      return MAPPER.readValue(body, BoletoProblem.class);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String truncate(String s) { return s.length() <= MAX_RAW ? s : s.substring(0, MAX_RAW) + "…"; }
}
```

`BoletoHttp.java`:
```java
package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * The transport the three boleto clients share: the credential's HttpClient (mTLS in production),
 * the Bearer token for THIS API's token URL, the Itaú headers, and the timeout mapping. Status
 * handling stays in each client because the three APIs disagree on what 200/202/204/404 mean.
 */
final class BoletoHttp {
  private static final Pattern ITAU_UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

  private final ItauTokenClient tokens;
  private final ItauEndpoints endpoints;
  private final KeyStore trustStore;
  private final Duration readTimeout;

  BoletoHttp(ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout) {
    this.tokens = tokens; this.endpoints = endpoints; this.trustStore = trustStore; this.readTimeout = readTimeout;
  }

  HttpRequest.Builder request(String pathAndQuery) {
    return HttpRequest.newBuilder(URI.create(endpoints.apiBase() + pathAndQuery)).timeout(readTimeout);
  }

  HttpResponse<String> send(ItauCredentials creds, HttpRequest.Builder b) {
    HttpClient http = tokens.httpClientFor(creds, endpoints, trustStore);
    b.header("Authorization", "Bearer " + tokens.tokenFor(creds, endpoints, trustStore).value())
        .header("x-itau-correlationID", correlationId())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json");
    // The query and instruction OpenAPIs declare x-itau-apikey required; the sandbox credential has none (NOTES.md).
    if (creds.apiKey() != null) b.header("x-itau-apikey", creds.apiKey());
    HttpRequest req = b.build();
    HttpResponse<String> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (HttpTimeoutException e) {
      throw new ProviderException(ProviderException.Code.TIMEOUT, "Itaú " + req.method() + " timed out", e);
    } catch (IOException e) {
      throw new ProviderException(ProviderException.Code.UNAVAILABLE, "Itaú " + req.method() + " failed: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ProviderException(ProviderException.Code.UNAVAILABLE, "interrupted calling Itaú", e);
    }
    // A rejected token must not be reused: the next call fetches a fresh one (and a fresh HttpClient).
    if (res.statusCode() == 401) tokens.evict(creds.fingerprint());
    return res;
  }

  private static String correlationId() {
    String fromMdc = MDC.get("correlationId");
    return fromMdc != null && ITAU_UUID.matcher(fromMdc).matches() ? fromMdc : UUID.randomUUID().toString();
  }

  static String seg(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
  static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
```

`dto/BoletoPixRequest.java`:
```java
package com.gateway.providers.itau.boleto.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.boleto.BoletoAmounts;
import com.gateway.providers.itau.boleto.BoletoText;
import java.util.List;

/**
 * POST /boletos-pix body (issue OpenAPI, schema {@code boletoPix}). Field names are the bank's; the
 * gateway never sees them. NON_NULL: the payer's document goes in exactly one of two properties and
 * the optional limit date is left out rather than sent as null. Always {@code efetivacao}: the
 * {@code simulacao} step is a smoke-test tool (spec §1), never part of the product flow.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BoletoPixRequest(
    @JsonProperty("etapa_processo_boleto") String etapaProcessoBoleto,
    Beneficiario beneficiario,
    @JsonProperty("dado_boleto") DadoBoleto dadoBoleto) {

  public record Beneficiario(@JsonProperty("id_beneficiario") String idBeneficiario) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DadoBoleto(
      @JsonProperty("descricao_instrumento_cobranca") String descricaoInstrumentoCobranca,
      @JsonProperty("tipo_boleto") String tipoBoleto,
      @JsonProperty("codigo_carteira") String codigoCarteira,
      @JsonProperty("codigo_especie") String codigoEspecie,
      @JsonProperty("valor_total_titulo") String valorTotalTitulo,
      Pagador pagador,
      @JsonProperty("dados_individuais_boleto") List<DadoIndividual> dadosIndividuaisBoleto) {}

  public record Pagador(Pessoa pessoa, Endereco endereco) {}

  public record Pessoa(@JsonProperty("nome_pessoa") String nomePessoa, @JsonProperty("tipo_pessoa") TipoPessoa tipoPessoa) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TipoPessoa(
      @JsonProperty("codigo_tipo_pessoa") String codigoTipoPessoa,
      @JsonProperty("numero_cadastro_pessoa_fisica") String cpf,
      @JsonProperty("numero_cadastro_nacional_pessoa_juridica") String cnpj) {}

  public record Endereco(
      @JsonProperty("nome_logradouro") String nomeLogradouro,
      @JsonProperty("nome_bairro") String nomeBairro,
      @JsonProperty("nome_cidade") String nomeCidade,
      @JsonProperty("sigla_UF") String siglaUf,
      @JsonProperty("numero_CEP") String numeroCep) {}

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record DadoIndividual(
      @JsonProperty("numero_nosso_numero") String numeroNossoNumero,
      @JsonProperty("data_vencimento") String dataVencimento,
      @JsonProperty("valor_titulo") String valorTitulo,
      @JsonProperty("data_limite_pagamento") String dataLimitePagamento,
      @JsonProperty("texto_uso_beneficiario") String textoUsoBeneficiario) {}

  public static BoletoPixRequest forIssue(BoletoIssueRequest r, ItauCredentials c) {
    String digits = r.payer().document().replaceAll("\\D", "");
    TipoPessoa tipo = digits.length() == 14 ? new TipoPessoa("J", null, digits) : new TipoPessoa("F", digits, null);
    var address = r.payer().address();
    String amount = BoletoAmounts.toItau(r.amount());
    return new BoletoPixRequest(
        "efetivacao",
        new Beneficiario(c.beneficiaryId()),
        new DadoBoleto(
            "boleto_pix",
            "a vista",
            c.walletCode(),
            c.speciesCode(),
            amount,
            new Pagador(
                new Pessoa(BoletoText.name(r.payer().name(), 50), tipo),
                new Endereco(
                    BoletoText.text(address.street(), 45), BoletoText.text(address.district(), 15), BoletoText.text(address.city(), 20),
                    address.state().toUpperCase(), address.zip().replaceAll("\\D", ""))),
            List.of(new DadoIndividual(
                r.nossoNumero(), r.dueDate().toString(), amount,
                r.paymentLimitDate() == null ? null : r.paymentLimitDate().toString(),
                r.description() == null ? null : BoletoText.text(r.description(), 25)))));
  }
}
```

`dto/BoletoPixResponse.java`:
```java
package com.gateway.providers.itau.boleto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** POST /boletos-pix 200 (schema {@code boletoPixResponse}); only the fields the gateway keeps. ignoreUnknown: the bank echoes the whole request plus juros/multa/etc. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BoletoPixResponse(@JsonProperty("dado_boleto") DadoBoleto dadoBoleto, @JsonProperty("dados_qrcode") DadosQrcode dadosQrcode) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DadoBoleto(@JsonProperty("dados_individuais_boleto") List<Individual> dadosIndividuaisBoleto) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Individual(
      @JsonProperty("id_boleto_individual") String idBoletoIndividual,
      @JsonProperty("numero_nosso_numero") String numeroNossoNumero,
      @JsonProperty("dac_titulo") String dacTitulo,
      @JsonProperty("codigo_barras") String codigoBarras,
      @JsonProperty("numero_linha_digitavel") String numeroLinhaDigitavel,
      @JsonProperty("data_limite_pagamento") String dataLimitePagamento) {}

  /** {@code base64} (the QR image) is not modelled: the merchant renders the EMV. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DadosQrcode(String chave, String txid, String emv, String location) {}

  public Individual first() {
    if (dadoBoleto == null || dadoBoleto.dadosIndividuaisBoleto() == null || dadoBoleto.dadosIndividuaisBoleto().isEmpty()) {
      throw new IllegalStateException("boletos-pix response without dados_individuais_boleto");
    }
    return dadoBoleto.dadosIndividuaisBoleto().getFirst();
  }
}
```

`BoletoPixApiClient.java`:
```java
package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.dto.BoletoPixRequest;
import com.gateway.providers.itau.boleto.dto.BoletoPixResponse;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import tools.jackson.databind.ObjectMapper;

/**
 * Itaú "Boleto com Pix" v1, one environment per instance. No retry: the nosso número is ours, so
 * the caller (payments) can ask the query API whether the boleto exists before deciding.
 */
class BoletoPixApiClient {
  private final BoletoHttp http;
  private final ObjectMapper mapper = new ObjectMapper();

  BoletoPixApiClient(ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout) {
    this.http = new BoletoHttp(tokens, endpoints, trustStore, readTimeout);
  }

  /** 200 (and 201) is the issued boleto; 202 "operação em andamento" becomes a TIMEOUT in BoletoErrors: the bank has not decided. */
  BoletoPixResponse post(ItauCredentials c, BoletoPixRequest body) {
    HttpRequest.Builder b = http.request("/boletos-pix").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8));
    HttpResponse<String> res = http.send(c, b);
    int status = res.statusCode();
    if (status == 200 || status == 201) {
      try {
        return mapper.readValue(res.body(), BoletoPixResponse.class);
      } catch (RuntimeException e) {
        throw new ProviderException(ProviderException.Code.UNKNOWN, "unreadable provider response", e);
      }
    }
    throw BoletoErrors.from(status, res.body());
  }
}
```

- [ ] **Step 5: Rodar e ver passar**

Run: comando do Step 3.
Expected: PASS — 5 classes verdes. Se o `equalToJson(..., false, true)` do request mínimo falhar por `etapa_processo_boleto` (o exemplo diz `efetivacao`, nós também) ou pelos extras `texto_uso_beneficiario`/`data_limite_pagamento`: o terceiro argumento (`ignoreExtraElements`) cobre extras; se falhar por acentos, confira que o fixture foi gravado em UTF-8 (`ensure_ascii=False` no script).

- [ ] **Step 6: Commit**

```bash
git add gateway-providers
git commit -m "feat(providers): boletos-pix issue client with the bank's own examples and schema validation

The boleto APIs answer errors as {codigo, mensagem, campos[]}, not RFC
7807, so they get their own mapper; campos[].valor never reaches a
message because it echoes the payer's document. Texts are filtered by
the schema's own character classes (a whitelist) rather than by the
portal's list of forbidden characters. 202 is a timeout: the bank has
not decided yet and the caller must ask before retrying.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: `BoletoQueryClient` — `GET /boletos` com mapeamento de situação (com e sem acento)

**Files:**
- Create: `gateway-providers/src/main/java/com/gateway/providers/itau/boleto/{BoletoQueryClient,BoletoSituations,ItauDates}.java`, `gateway-providers/src/main/java/com/gateway/providers/itau/boleto/dto/{BoletoQueryResponse,BoletoQueryItem}.java`
- Create: `gateway-providers/src/test/resources/itau/boleto/query.openapi.json` (cópia verbatim de `docs/providers/itau/itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws.openapi.json`), `gateway-providers/src/test/resources/itau/boleto/fixtures/{get_boletos_200.json,get_boletos_200_paid.json,get_boletos_200_canceled.json,get_boletos_200_rejected.json,get_boletos_200_awaiting.json,get_boletos_200_empty.json,get_boletos_404.json}`
- Modify: `gateway-providers/src/test/java/com/gateway/providers/itau/boleto/BoletoFixturesFromOpenApiTest.java` (+1 caso)
- Test: `gateway-providers/src/test/java/com/gateway/providers/itau/boleto/{BoletoSituationsTest,ItauDatesTest,BoletoQueryClientContractTest}.java`

**Interfaces:**
- Consumes: `BoletoHttp`, `BoletoErrors` (Task 3), `BoletoSituation` (Task 1).
- Produces:
  ```java
  public final class BoletoSituations { public static BoletoSituation parse(String situacaoGeralBoleto); }   // NFD-strips accents, case-insensitive; unknown -> ProviderException(UNKNOWN)
  public final class ItauDates {
    public static final ZoneId SAO_PAULO;
    public static Instant paidAt(String dateTime, String date);      // ISO instant | ISO local date-time (SP) | "yyyy-MM-dd" (start of day SP) | null
    public static LocalDate date(String yyyyMmDd);                   // null-safe
  }
  public record BoletoQueryResponse(List<BoletoQueryItem> data) {}
  public record BoletoQueryItem(String idBoleto, DadoBoleto dadoBoleto) { records DadoBoleto, Individual, Pagamento, Baixa, QrcodePix; Optional<Individual> individual(String nossoNumero); Optional<Pagamento> lastPayment(); }
  class BoletoQueryClient { BoletoQueryClient(ItauTokenClient, ItauEndpoints, KeyStore, Duration); Optional<BoletoQueryItem> find(ItauCredentials c, String nossoNumero); }
     // GET /boletos?id_beneficiario=&codigo_carteira=&nosso_numero=; 200 -> the item whose dados_individuais_boleto[].numero_nosso_numero == nossoNumero (none -> empty); 404 -> empty; else BoletoErrors
  ```

- [ ] **Step 1: OpenAPI e fixtures**

```bash
cp docs/providers/itau/itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws.openapi.json gateway-providers/src/test/resources/itau/boleto/query.openapi.json
python - <<'EOF'
import json, copy
d = 'gateway-providers/src/test/resources/itau/boleto/fixtures/'
api = json.load(open('gateway-providers/src/test/resources/itau/boleto/query.openapi.json', encoding='utf-8'))
def dump(name, obj):
    json.dump(obj, open(d + name, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
full = api['components']['examples']['query_200_boletos_get_response']['value']
dump('get_boletos_200.json', full)
def derived(situacao, keep_payment):
    item = copy.deepcopy(full['data'][1])            # carteira 109, has pagamentos_cobranca and baixa
    ind = item['dado_boleto']['dados_individuais_boleto'][0]
    ind['numero_nosso_numero'] = '00000001'
    ind['situacao_geral_boleto'] = situacao
    if not keep_payment:
        item['dado_boleto'].pop('pagamentos_cobranca', None)
        item['dado_boleto'].pop('baixa', None)
    return {'data': [item], 'page': {'page': 0, 'total_pages': 1, 'total_elements': 1, 'page_size': 1, 'links': {}}}
dump('get_boletos_200_paid.json', derived('Pago', True))
dump('get_boletos_200_canceled.json', derived('Baixado', False))
dump('get_boletos_200_rejected.json', derived('Pagamento Rejeitado', False))
dump('get_boletos_200_awaiting.json', derived('Aguardando Crédito', False))
dump('get_boletos_200_empty.json', {'data': [], 'page': {'page': 0, 'total_pages': 0, 'total_elements': 0, 'page_size': 0, 'links': {}}})
dump('get_boletos_404.json', api['components']['responses']['404']['content']['application/json']['examples']['404']['value'])
EOF
```
Acrescente a `BoletoFixturesFromOpenApiTest`:
```java
  @Test void query200() throws Exception { assertThat(fixture("get_boletos_200.json")).isEqualTo(example("query", "/components/examples/query_200_boletos_get_response/value")); }
  @Test void query404() throws Exception { assertThat(fixture("get_boletos_404.json")).isEqualTo(example("query", "/components/responses/404/content/application~1json/examples/404/value")); }
```

- [ ] **Step 2: Testes**

`BoletoSituationsTest.java`:
```java
package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The query OpenAPI enum has accents ("Aguardando Crédito"); the mainframe may not. Both spellings map. */
class BoletoSituationsTest {
  @ParameterizedTest
  @CsvSource({
      "Em Aberto,OPEN", "em aberto,OPEN", "EM ABERTO,OPEN",
      "Pago,PAID", "Liquidado,SETTLED", "Pagamento Rejeitado,PAYMENT_REJECTED",
      "Aguardando Crédito,AWAITING_CREDIT", "Aguardando Credito,AWAITING_CREDIT", "AGUARDANDO CRÉDITO,AWAITING_CREDIT",
      "Creditado,CREDITED", "Baixado,CANCELED"})
  void mapsEverySpelling(String in, String out) {
    assertThat(BoletoSituations.parse(in)).isEqualTo(BoletoSituation.valueOf(out));
  }

  @Test void unknownIsAProviderError() {
    assertThatThrownBy(() -> BoletoSituations.parse("Protestado")).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNKNOWN));
    assertThatThrownBy(() -> BoletoSituations.parse(null)).isInstanceOf(ProviderException.class);
  }
}
```

`ItauDatesTest.java`:
```java
package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** The bank gives a date, sometimes a date-time, never a zone: every boleto date is a São Paulo day. */
class ItauDatesTest {
  @Test void dateOnlyIsStartOfTheSaoPauloDay() {
    assertThat(ItauDates.paidAt(null, "2020-01-20")).isEqualTo(Instant.parse("2020-01-20T03:00:00Z"));
  }

  @Test void dateTimeWinsAndIsReadAsSaoPaulo() {
    assertThat(ItauDates.paidAt("2020-01-20T14:30:00", "2020-01-20")).isEqualTo(Instant.parse("2020-01-20T17:30:00Z"));
    assertThat(ItauDates.paidAt("2020-01-20T14:30:00Z", "2020-01-20")).isEqualTo(Instant.parse("2020-01-20T14:30:00Z"));
    assertThat(ItauDates.paidAt("garbage", "2020-01-20")).isEqualTo(Instant.parse("2020-01-20T03:00:00Z"));
  }

  @Test void nullsStayNull() {
    assertThat(ItauDates.paidAt(null, null)).isNull();
    assertThat(ItauDates.date(null)).isNull();
    assertThat(ItauDates.date("2030-08-06")).isEqualTo(LocalDate.of(2030, 8, 6));
  }
}
```

`BoletoQueryClientContractTest.java`:
```java
package com.gateway.providers.itau.boleto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.dto.BoletoQueryItem;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.*;

/** GET /boletos (consulta de detalhe) against the bank's own example and states derived from it. */
class BoletoQueryClientContractTest {
  static final String APIKEY = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  static WireMockServer server;

  @BeforeAll static void start() { server = new WireMockServer(WireMockConfiguration.options().dynamicPort()); server.start(); }
  @AfterAll static void stop() { server.stop(); }
  @BeforeEach void reset() {
    server.resetAll();
    server.stubFor(post("/api/oauth/jwt").willReturn(okJson("{\"access_token\":\"tok\",\"expires_in\":300}")));
  }

  static String fixture(String f) {
    try { return Files.readString(Path.of("src/test/resources/itau/boleto/fixtures/" + f), StandardCharsets.UTF_8); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  static ItauCredentials creds() {
    return ItauCredentials.parse(("{\"client_id\":\"c\",\"client_secret\":\"s\",\"x_itau_apikey\":\"" + APIKEY
        + "\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\",\"wallet_code\":\"109\"}").getBytes());
  }

  static BoletoQueryClient client() {
    ItauEndpoints e = ItauEndpoints.custom(URI.create(server.baseUrl() + "/v2"), URI.create(server.baseUrl() + "/api/oauth/jwt"), false);
    return new BoletoQueryClient(new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(3)), e, null, Duration.ofSeconds(5));
  }

  @Test void queriesByBeneficiaryWalletAndNumberWithTheRequiredHeaders() {
    server.stubFor(get(urlPathEqualTo("/v2/boletos"))
        .withQueryParam("id_beneficiario", equalTo("150000052061"))
        .withQueryParam("codigo_carteira", equalTo("109"))
        .withQueryParam("nosso_numero", equalTo("00000000"))
        .withHeader("Authorization", equalTo("Bearer tok"))
        .withHeader("x-itau-apikey", equalTo(APIKEY))
        .withHeader("x-itau-correlationID", matching("[0-9a-f-]{36}"))
        .willReturn(okJson(fixture("get_boletos_200.json"))));

    Optional<BoletoQueryItem> found = client().find(creds(), "00000000");

    assertThat(found).isPresent();
    BoletoQueryItem.Individual i = found.get().individual("00000000").orElseThrow();
    assertThat(i.situacaoGeralBoleto()).isEqualTo("Em Aberto");
    assertThat(i.codigoBarras()).hasSize(44);
    assertThat(i.numeroLinhaDigitavel()).hasSize(47);
    assertThat(i.dataLimitePagamento()).isEqualTo("2030-08-06");
    assertThat(found.get().lastPayment()).isPresent().get().satisfies(p -> {
      assertThat(p.valorPagoTotalCobranca()).isEqualTo("2100.00");
      assertThat(p.dataInclusaoPagamento()).isEqualTo("2020-01-20");
    });
    assertThat(found.get().dadoBoleto().qrcodePix()).isNull();
    assertThat(found.get().dadoBoleto().baixa().motivo()).isEqualTo("Baixa por ter sido liquidado");
  }

  /** The bank filters by nosso_numero; a mock (the sandbox) may not. The client picks the item that carries the number it asked for. */
  @Test void aListWithoutTheNumberIsEmpty() {
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(okJson(fixture("get_boletos_200.json"))));
    assertThat(client().find(creds(), "99999999")).isEmpty();
  }

  @Test void derivedStatesParse() {
    for (String[] f : new String[][] {{"get_boletos_200_paid.json", "Pago"}, {"get_boletos_200_canceled.json", "Baixado"},
        {"get_boletos_200_rejected.json", "Pagamento Rejeitado"}, {"get_boletos_200_awaiting.json", "Aguardando Crédito"}}) {
      server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(okJson(fixture(f[0]))));
      BoletoQueryItem item = client().find(creds(), "00000001").orElseThrow();
      assertThat(item.individual("00000001").orElseThrow().situacaoGeralBoleto()).as(f[0]).isEqualTo(f[1]);
    }
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(okJson(fixture("get_boletos_200_paid.json"))));
    assertThat(client().find(creds(), "00000001").orElseThrow().lastPayment()).isPresent();
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(okJson(fixture("get_boletos_200_canceled.json"))));
    assertThat(client().find(creds(), "00000001").orElseThrow().lastPayment()).isEmpty();
  }

  @Test void emptyListAnd404AreBothEmpty() {
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(okJson(fixture("get_boletos_200_empty.json"))));
    assertThat(client().find(creds(), "00000001")).isEmpty();
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json").withBody(fixture("get_boletos_404.json"))));
    assertThat(client().find(creds(), "00000001")).isEmpty();
  }

  @Test void unauthorizedAndUnavailableAreErrors() {
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(aResponse().withStatus(401).withBody("{\"codigo\":\"401\",\"mensagem\":\"Unauthorized\"}")));
    assertThatThrownBy(() -> client().find(creds(), "00000001")).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNAUTHENTICATED));
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> client().find(creds(), "00000001")).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNAVAILABLE));
  }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-providers -Dtest='BoletoSituationsTest,ItauDatesTest,BoletoQueryClientContractTest,BoletoFixturesFromOpenApiTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compilação.

- [ ] **Step 4: Implementar**

`BoletoSituations.java`:
```java
package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import java.text.Normalizer;
import java.util.Locale;

/**
 * {@code situacao_geral_boleto} → {@link BoletoSituation}. The OpenAPI enum spells "Aguardando
 * Crédito" with the accent; mainframe-fed text often does not. Accents are stripped (NFD, drop the
 * marks) and case is ignored before matching, so both spellings land on the same value.
 */
public final class BoletoSituations {
  private BoletoSituations() {}

  public static BoletoSituation parse(String raw) {
    if (raw == null) throw new ProviderException(ProviderException.Code.UNKNOWN, 200, null, "boleto without situacao_geral_boleto");
    String plain = Normalizer.normalize(raw, Normalizer.Form.NFD).replaceAll("\\p{M}", "").trim().toLowerCase(Locale.ROOT);
    return switch (plain) {
      case "em aberto" -> BoletoSituation.OPEN;
      case "pago" -> BoletoSituation.PAID;
      case "liquidado" -> BoletoSituation.SETTLED;
      case "pagamento rejeitado" -> BoletoSituation.PAYMENT_REJECTED;
      case "aguardando credito" -> BoletoSituation.AWAITING_CREDIT;
      case "creditado" -> BoletoSituation.CREDITED;
      case "baixado" -> BoletoSituation.CANCELED;
      default -> throw new ProviderException(ProviderException.Code.UNKNOWN, 200, null, "unknown situacao_geral_boleto: " + raw);
    };
  }
}
```

`ItauDates.java`:
```java
package com.gateway.providers.itau.boleto;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** The bank's dates carry no zone; a boleto day is a São Paulo day (spec 2026-09-25, global constraint). */
public final class ItauDates {
  public static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

  private ItauDates() {}

  /** {@code data_hora_inclusao_pagamento} first (ISO instant or ISO local date-time read as São Paulo); {@code data_inclusao_pagamento} as start of that day; null when neither parses. */
  public static Instant paidAt(String dateTime, String date) {
    if (dateTime != null) {
      try { return Instant.parse(dateTime); } catch (DateTimeParseException ignored) { /* not an instant */ }
      try { return LocalDateTime.parse(dateTime).atZone(SAO_PAULO).toInstant(); } catch (DateTimeParseException ignored) { /* not a local date-time either */ }
    }
    LocalDate d = date(date);
    return d == null ? null : d.atStartOfDay(SAO_PAULO).toInstant();
  }

  public static LocalDate date(String yyyyMmDd) {
    if (yyyyMmDd == null || yyyyMmDd.isBlank()) return null;
    try { return LocalDate.parse(yyyyMmDd); } catch (DateTimeParseException e) { return null; }
  }
}
```

`dto/BoletoQueryItem.java`:
```java
package com.gateway.providers.itau.boleto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Optional;

/**
 * One boleto of GET /boletos (query OpenAPI, schema {@code boleto}). The payment block is the list
 * {@code pagamentos_cobranca} (the spec called it {@code pagamento}); the last entry is the one that
 * settled. {@code qrcode_pix.emv} is the Bolecode's EMV, present when the bank keeps it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BoletoQueryItem(@JsonProperty("id_boleto") String idBoleto, @JsonProperty("dado_boleto") DadoBoleto dadoBoleto) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record DadoBoleto(
      @JsonProperty("dados_individuais_boleto") List<Individual> dadosIndividuaisBoleto,
      @JsonProperty("pagamentos_cobranca") List<Pagamento> pagamentosCobranca,
      Baixa baixa,
      @JsonProperty("qrcode_pix") QrcodePix qrcodePix) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Individual(
      @JsonProperty("situacao_geral_boleto") String situacaoGeralBoleto,
      @JsonProperty("numero_nosso_numero") String numeroNossoNumero,
      @JsonProperty("id_boleto_individual") String idBoletoIndividual,
      @JsonProperty("codigo_barras") String codigoBarras,
      @JsonProperty("numero_linha_digitavel") String numeroLinhaDigitavel,
      @JsonProperty("data_vencimento") String dataVencimento,
      @JsonProperty("data_limite_pagamento") String dataLimitePagamento,
      @JsonProperty("valor_titulo") String valorTitulo) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Pagamento(
      @JsonProperty("valor_pago_total_cobranca") String valorPagoTotalCobranca,
      @JsonProperty("data_inclusao_pagamento") String dataInclusaoPagamento,
      @JsonProperty("data_hora_inclusao_pagamento") String dataHoraInclusaoPagamento,
      @JsonProperty("codigo_meio_pagamento_boleto_cobranca") String codigoMeioPagamento,
      @JsonProperty("descricao_meio_pagamento") String descricaoMeioPagamento) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Baixa(@JsonProperty("data_inclusao_alteracao_baixa") String data, @JsonProperty("motivo_baixa") String motivo) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record QrcodePix(String emv) {}

  public Optional<Individual> individual(String nossoNumero) {
    if (dadoBoleto == null || dadoBoleto.dadosIndividuaisBoleto() == null) return Optional.empty();
    return dadoBoleto.dadosIndividuaisBoleto().stream().filter(i -> nossoNumero.equals(i.numeroNossoNumero())).findFirst();
  }

  public Optional<Pagamento> lastPayment() {
    if (dadoBoleto == null || dadoBoleto.pagamentosCobranca() == null || dadoBoleto.pagamentosCobranca().isEmpty()) return Optional.empty();
    return Optional.of(dadoBoleto.pagamentosCobranca().getLast());
  }
}
```

`dto/BoletoQueryResponse.java`:
```java
package com.gateway.providers.itau.boleto.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** GET /boletos 200: {@code data[]} plus paging ({@code page} in the schema, {@code pagination} in the example — ignored either way). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BoletoQueryResponse(List<BoletoQueryItem> data) {}
```

`BoletoQueryClient.java`:
```java
package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.dto.BoletoQueryItem;
import com.gateway.providers.itau.boleto.dto.BoletoQueryResponse;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** Itaú "Consulta de detalhe" (boletoscash v2): the only way to learn that a Bolecode was paid by barcode (no boleto webhook in scope, spec §8). */
class BoletoQueryClient {
  private final BoletoHttp http;
  private final ObjectMapper mapper = new ObjectMapper();

  BoletoQueryClient(ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout) {
    this.http = new BoletoHttp(tokens, endpoints, trustStore, readTimeout);
  }

  /**
   * Empty for a 404 and for a list that does not carry the number — the bank filters by
   * nosso_numero, but a static mock (the sandbox) answers its example whatever we ask, and
   * treating that as "our boleto" would complete the wrong payment.
   */
  Optional<BoletoQueryItem> find(ItauCredentials c, String nossoNumero) {
    String q = "?id_beneficiario=" + BoletoHttp.enc(c.beneficiaryId()) + "&codigo_carteira=" + BoletoHttp.enc(c.walletCode()) + "&nosso_numero=" + BoletoHttp.enc(nossoNumero);
    HttpResponse<String> res = http.send(c, http.request("/boletos" + q).GET());
    int status = res.statusCode();
    if (status == 404) return Optional.empty();
    if (status != 200) throw BoletoErrors.from(status, res.body());
    BoletoQueryResponse body;
    try {
      body = mapper.readValue(res.body(), BoletoQueryResponse.class);
    } catch (RuntimeException e) {
      throw new ProviderException(ProviderException.Code.UNKNOWN, "unreadable provider response", e);
    }
    if (body == null || body.data() == null) return Optional.empty();
    return body.data().stream().filter(item -> item.individual(nossoNumero).isPresent()).findFirst();
  }
}
```

- [ ] **Step 5: Rodar e ver passar**

Run: comando do Step 3.
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add gateway-providers
git commit -m "feat(providers): boleto query client with situation mapping that ignores accents

GET /boletos is the barcode-settlement signal (no boleto webhook in
scope). The client keeps only the item carrying the number it asked
for, because the static sandbox answers its example for any query;
situacao_geral_boleto is matched after stripping accents since the
OpenAPI and the mainframe spell it differently.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: `BoletoInstructionClient` (baixa), `ItauBoletoProvider` e wiring em `ProvidersConfiguration`

**Files:**
- Create: `gateway-providers/src/main/java/com/gateway/providers/itau/boleto/{BoletoInstructionClient,ItauBoletoProvider}.java`
- Create: `gateway-providers/src/test/resources/itau/boleto/instruction.openapi.json` (cópia verbatim de `docs/providers/itau/itau-ep9-gtw-cash-management-ext-v2.openapi.json`), `gateway-providers/src/test/resources/itau/boleto/fixtures/{patch_baixa_200.json,patch_baixa_422_paid.json}`
- Modify: `gateway-providers/src/main/java/com/gateway/providers/ProvidersConfiguration.java`, `gateway-providers/src/test/java/com/gateway/providers/itau/boleto/BoletoFixturesFromOpenApiTest.java` (+1)
- Test: `gateway-providers/src/test/java/com/gateway/providers/itau/boleto/{BoletoInstructionClientContractTest,ItauBoletoProviderTest}.java`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces:
  ```java
  class BoletoInstructionClient { BoletoInstructionClient(ItauTokenClient, ItauEndpoints, KeyStore, Duration); void baixa(ItauCredentials c, String idBoleto); }
     // PATCH /boletos/{id_boleto}/baixa, no body. 200/202/204 -> ok; 422 whose body mentions pago/liquidado -> CONFLICT; other 422/400 -> DECLINED; 404 -> NOT_FOUND; else BoletoErrors
  public class ItauBoletoProvider implements BoletoProvider {
    public ItauBoletoProvider(ItauTokenClient tokens, KeyStore trustStore, Duration readTimeout, ItauBoletoEndpoints live, ItauBoletoEndpoints test);
    static String baixaId(ItauCredentials c, String nossoNumero);   // beneficiaryId + walletCode + nossoNumero  (23 chars)
    static String pixTxid(ItauCredentials c, String nossoNumero);   // "BL" + beneficiaryId[0..11) + walletCode + nossoNumero left-padded to 15 -> BL + 31 digits
    static BoletoStatus toStatus(BoletoQueryItem item, String nossoNumero);
  }
  ProvidersConfiguration.ProvidersProperties += Boleto boleto (nested record, 12 optional URL strings); ItauBoletoEndpoints boletoLive(); ItauBoletoEndpoints boletoTest()
  @Bean BoletoProvider itauBoletoProvider(ItauTokenClient tokens, ProvidersProperties props)
  ```

- [ ] **Step 1: OpenAPI e fixtures**

```bash
cp docs/providers/itau/itau-ep9-gtw-cash-management-ext-v2.openapi.json gateway-providers/src/test/resources/itau/boleto/instruction.openapi.json
python - <<'EOF'
import json
d = 'gateway-providers/src/test/resources/itau/boleto/fixtures/'
api = json.load(open('gateway-providers/src/test/resources/itau/boleto/instruction.openapi.json', encoding='utf-8'))
def dump(name, obj):
    json.dump(obj, open(d + name, 'w', encoding='utf-8'), ensure_ascii=False, indent=2)
dump('patch_baixa_200.json', api['components']['examples']['200_instrucao']['value']['value'])
dump('patch_baixa_422_paid.json', {"codigo": "422", "mensagem": "Boleto já liquidado", "campos": []})
EOF
```
Acrescente a `BoletoFixturesFromOpenApiTest`:
```java
  @Test void baixa200() throws Exception { assertThat(fixture("patch_baixa_200.json")).isEqualTo(example("instruction", "/components/examples/200_instrucao/value/value")); }
```

- [ ] **Step 2: Testes**

`BoletoInstructionClientContractTest.java`:
```java
package com.gateway.providers.itau.boleto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.*;

/** PATCH /boletos/{id_boleto}/baixa, whose id is agencia+conta+DAC+carteira+nosso numero (cash_management OpenAPI, path parameter description). */
class BoletoInstructionClientContractTest {
  static final String ID = "15000005206110900000001"; // 150000052061 + 109 + 00000001
  static WireMockServer server;

  @BeforeAll static void start() { server = new WireMockServer(WireMockConfiguration.options().dynamicPort()); server.start(); }
  @AfterAll static void stop() { server.stop(); }
  @BeforeEach void reset() {
    server.resetAll();
    // The instruction API authenticates at its own token URL (spec §1): the stub is on /api/oauth/token, not /jwt.
    server.stubFor(post("/api/oauth/token").willReturn(okJson("{\"access_token\":\"tok-cash\",\"expires_in\":300}")));
  }

  static String fixture(String f) {
    try { return Files.readString(Path.of("src/test/resources/itau/boleto/fixtures/" + f), StandardCharsets.UTF_8); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  static ItauCredentials creds() {
    return ItauCredentials.parse("{\"client_id\":\"c\",\"client_secret\":\"s\",\"x_itau_apikey\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\"}".getBytes());
  }

  static BoletoInstructionClient client() {
    ItauEndpoints e = ItauEndpoints.custom(URI.create(server.baseUrl() + "/cash_management/v2"), URI.create(server.baseUrl() + "/api/oauth/token"), false);
    return new BoletoInstructionClient(new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(3)), e, null, Duration.ofSeconds(5));
  }

  @Test void patchesWithoutBodyAndAccepts200() {
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa"))
        .withHeader("Authorization", equalTo("Bearer tok-cash"))
        .withHeader("x-itau-apikey", equalTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        .willReturn(okJson(fixture("patch_baixa_200.json"))));
    client().baixa(creds(), ID);
    server.verify(patchRequestedFor(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).withRequestBody(absent()));
    server.verify(1, postRequestedFor(urlEqualTo("/api/oauth/token")));
  }

  @Test void accepts204And202() {
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).willReturn(aResponse().withStatus(204)));
    client().baixa(creds(), ID);
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).willReturn(aResponse().withStatus(202)));
    client().baixa(creds(), ID);
  }

  @Test void alreadyPaidIsConflict() {
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).willReturn(aResponse().withStatus(422).withBody(fixture("patch_baixa_422_paid.json"))));
    assertThatThrownBy(() -> client().baixa(creds(), ID)).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.CONFLICT);
      assertThat(e.httpStatus()).isEqualTo(422);
    });
  }

  @Test void other422IsDeclinedAnd404IsNotFound() {
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).willReturn(aResponse().withStatus(422).withBody("{\"codigo\":\"422\",\"mensagem\":\"Instrução não permitida para a carteira\",\"campos\":[]}")));
    assertThatThrownBy(() -> client().baixa(creds(), ID)).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.DECLINED));
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).willReturn(aResponse().withStatus(404)));
    assertThatThrownBy(() -> client().baixa(creds(), ID)).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.NOT_FOUND));
  }
}
```

`ItauBoletoProviderTest.java` (um WireMock, três bases por prefixo de caminho):
```java
package com.gateway.providers.itau.boleto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.*;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.*;

class ItauBoletoProviderTest {
  static WireMockServer server;
  static final byte[] CREDS = "{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\",\"wallet_code\":\"109\",\"species_code\":\"01\"}".getBytes();
  static final byte[] PIX_ONLY = "{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\"}".getBytes();

  @BeforeAll static void start() { server = new WireMockServer(WireMockConfiguration.options().dynamicPort()); server.start(); }
  @AfterAll static void stop() { server.stop(); }
  @BeforeEach void reset() {
    server.resetAll();
    server.stubFor(post("/api/oauth/jwt").willReturn(okJson("{\"access_token\":\"tok\",\"expires_in\":300}")));
  }

  static String fixture(String f) {
    try { return Files.readString(Path.of("src/test/resources/itau/boleto/fixtures/" + f), StandardCharsets.UTF_8); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  static ItauBoletoProvider provider() {
    String base = server.baseUrl();
    URI token = URI.create(base + "/api/oauth/jwt");
    ItauBoletoEndpoints test = new ItauBoletoEndpoints(
        ItauEndpoints.custom(URI.create(base + "/issue/v1"), token, false),
        ItauEndpoints.custom(URI.create(base + "/query/v2"), token, false),
        ItauEndpoints.custom(URI.create(base + "/instruction/v2"), token, false));
    return new ItauBoletoProvider(new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(3)), null, Duration.ofSeconds(5),
        ItauBoletoEndpoints.forEnvironment(ProviderEnvironment.LIVE), test);
  }

  static ProviderCredentials test(byte[] payload) { return new ProviderCredentials(payload, ProviderEnvironment.TEST); }

  static BoletoIssueRequest request() {
    return new BoletoIssueRequest("00000001", Money.brl(123456), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 30),
        new Payer("João da Silva", "12345678901", new Address("Rua das Flores", "Centro", "São Paulo", "SP", "01310100")), "Pedido 42");
  }

  @Test void idIsItau() { assertThat(provider().id()).isEqualTo("ITAU"); }

  @Test void requireIssueCredentialsNamesTheMissingField() {
    provider().requireIssueCredentials(test(CREDS));
    assertThatThrownBy(() -> provider().requireIssueCredentials(test(PIX_ONLY))).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.CREDENTIALS_INCOMPLETE);
      assertThat(e.providerType()).isEqualTo("beneficiary_id");
    });
    assertThat(server.findAll(postRequestedFor(urlMatching(".*")))).isEmpty();
  }

  @Test void issueMapsTheBanksAnswer() {
    server.stubFor(post("/issue/v1/boletos-pix").willReturn(okJson(fixture("post_boletos_pix_200.json"))));
    IssuedBoleto b = provider().issue(test(CREDS), request());
    assertThat(b.idBoletoIndividual()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    assertThat(b.linhaDigitavel()).isEqualTo("34101234567890123456789012345678901234567890123");
    assertThat(b.codigoBarras()).isEqualTo("34191234567890123456789012345678901234567890");
    assertThat(b.paymentLimitDate()).isEqualTo(LocalDate.of(2027, 1, 31));
    assertThat(b.pixTxid()).isEqualTo("BL1234567890123456789012345678901");
    assertThat(b.pixCopiaECola()).startsWith("000201");
    assertThat(b.pixKey()).isEqualTo("12345678000190");
  }

  @Test void findMapsSituationPaymentAndIdentity() {
    server.stubFor(get(urlPathEqualTo("/query/v2/boletos")).withQueryParam("nosso_numero", equalTo("00000001")).willReturn(okJson(fixture("get_boletos_200_paid.json"))));
    BoletoStatus s = provider().find(test(CREDS), "00000001").orElseThrow();
    assertThat(s.situation()).isEqualTo(BoletoSituation.PAID);
    assertThat(s.paid()).isTrue();
    assertThat(s.paidAmount()).isEqualTo(Money.brl(210000));
    assertThat(s.paidAt()).isEqualTo(Instant.parse("2020-01-20T03:00:00Z"));
    assertThat(s.paidChannel()).isEqualTo("DÉBITO EM CONTA");
    assertThat(s.linhaDigitavel()).hasSize(47);
    assertThat(s.codigoBarras()).hasSize(44);
    assertThat(s.paymentLimitDate()).isEqualTo(LocalDate.of(2030, 8, 6));
    assertThat(s.pixCopiaECola()).isNull();
  }

  @Test void findOpenHasNoPaymentAndEmptyIsEmpty() {
    server.stubFor(get(urlPathEqualTo("/query/v2/boletos")).willReturn(okJson(fixture("get_boletos_200_awaiting.json"))));
    BoletoStatus s = provider().find(test(CREDS), "00000001").orElseThrow();
    assertThat(s.situation()).isEqualTo(BoletoSituation.AWAITING_CREDIT);
    assertThat(s.paidAmount()).isNull();
    server.stubFor(get(urlPathEqualTo("/query/v2/boletos")).willReturn(okJson(fixture("get_boletos_200_empty.json"))));
    assertThat(provider().find(test(CREDS), "00000001")).isEmpty();
  }

  @Test void cancelUsesTheCompositeId() {
    server.stubFor(patch(urlEqualTo("/instruction/v2/boletos/15000005206110900000001/baixa")).willReturn(aResponse().withStatus(204)));
    provider().cancel(test(CREDS), "00000001");
    server.verify(1, patchRequestedFor(urlEqualTo("/instruction/v2/boletos/15000005206110900000001/baixa")));
  }

  /** Issue OpenAPI, dados_qrcode.txid: BL + agência (4) + conta (7) + carteira (3) + nosso número (15) — the DAC is not part of it. */
  @Test void pixTxidFollowsTheBanksFormula() {
    assertThat(provider().pixTxidFor(test(CREDS), "00000001")).isEqualTo("BL" + "15000005206" + "109" + "000000000000001").matches("^BL[0-9]{31}$");
  }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-providers -Dtest='BoletoInstructionClientContractTest,ItauBoletoProviderTest,BoletoFixturesFromOpenApiTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compilação.

- [ ] **Step 4: Implementar**

`BoletoInstructionClient.java`:
```java
package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.time.Duration;

/**
 * Itaú "Emissão e Instrução" (cash_management v2), only the baixa. It authenticates at its own STS
 * URL ({@code ItauEndpoints.CASH_MANAGEMENT_TOKEN_URL}), which is why it gets its own endpoints.
 */
class BoletoInstructionClient {
  private final BoletoHttp http;

  BoletoInstructionClient(ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout) {
    this.http = new BoletoHttp(tokens, endpoints, trustStore, readTimeout);
  }

  /**
   * 200 (the mainframe's {@code Aguardando aprovação} answer), 202 and 204 are all "done"; the
   * boleto is invalid from the bank's side either way. A 422 that talks about pago/liquidado is a
   * CONFLICT the caller turns into "already paid" after asking the query API; the 422 has no schema
   * and no code, so the text is all there is.
   */
  void baixa(ItauCredentials c, String idBoleto) {
    HttpRequest.Builder b = http.request("/boletos/" + BoletoHttp.seg(idBoleto) + "/baixa").method("PATCH", HttpRequest.BodyPublishers.noBody());
    HttpResponse<String> res = http.send(c, b);
    int status = res.statusCode();
    if (status == 200 || status == 202 || status == 204) return;
    if (status == 422 && BoletoErrors.mentionsAlreadyPaid(res.body())) {
      ProviderException declined = BoletoErrors.from(status, res.body());
      throw new ProviderException(ProviderException.Code.CONFLICT, status, declined.providerType(), declined.getMessage());
    }
    throw BoletoErrors.from(status, res.body());
  }
}
```

`ItauBoletoProvider.java`:
```java
package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.kernel.provider.boleto.BoletoProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.boleto.IssuedBoleto;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.dto.BoletoPixRequest;
import com.gateway.providers.itau.boleto.dto.BoletoPixResponse;
import com.gateway.providers.itau.boleto.dto.BoletoQueryItem;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Optional;

/** The only class that knows the three boleto APIs and the gateway's vocabulary at the same time. */
public class ItauBoletoProvider implements BoletoProvider {
  private record Clients(BoletoPixApiClient issue, BoletoQueryClient query, BoletoInstructionClient instruction) {}

  private final Clients live, test;

  public ItauBoletoProvider(ItauTokenClient tokens, KeyStore trustStore, Duration readTimeout, ItauBoletoEndpoints liveEndpoints, ItauBoletoEndpoints testEndpoints) {
    this.live = clients(tokens, trustStore, readTimeout, liveEndpoints);
    this.test = clients(tokens, trustStore, readTimeout, testEndpoints);
  }

  private static Clients clients(ItauTokenClient tokens, KeyStore trustStore, Duration readTimeout, ItauBoletoEndpoints e) {
    return new Clients(
        new BoletoPixApiClient(tokens, e.issue(), trustStore, readTimeout),
        new BoletoQueryClient(tokens, e.query(), trustStore, readTimeout),
        new BoletoInstructionClient(tokens, e.instruction(), trustStore, readTimeout));
  }

  @Override public String id() { return "ITAU"; }

  private Clients clients(ProviderCredentials c) { return c.environment() == ProviderEnvironment.LIVE ? live : test; }

  /** A credential without the beneficiary is the merchant's configuration problem, not the bank's: CREDENTIALS_INCOMPLETE names the field. */
  private static ItauCredentials boletoCreds(ProviderCredentials c) {
    ItauCredentials ic = ItauCredentials.parse(c.payload());
    try {
      ic.requireBoletoShape();
    } catch (IllegalArgumentException e) {
      throw new ProviderException(ProviderException.Code.CREDENTIALS_INCOMPLETE, 0, e.getMessage(), "ITAU credential is missing " + e.getMessage());
    }
    return ic;
  }

  @Override public void requireIssueCredentials(ProviderCredentials c) { boletoCreds(c); }

  @Override public IssuedBoleto issue(ProviderCredentials c, BoletoIssueRequest r) {
    ItauCredentials ic = boletoCreds(c);
    BoletoPixResponse res = clients(c).issue().post(ic, BoletoPixRequest.forIssue(r, ic));
    BoletoPixResponse.Individual i = res.first();
    BoletoPixResponse.DadosQrcode qr = res.dadosQrcode();
    return new IssuedBoleto(i.idBoletoIndividual(), i.numeroLinhaDigitavel(), i.codigoBarras(), ItauDates.date(i.dataLimitePagamento()),
        qr == null ? null : qr.txid(), qr == null ? null : qr.emv(), qr == null ? null : qr.chave());
  }

  @Override public Optional<BoletoStatus> find(ProviderCredentials c, String nossoNumero) {
    ItauCredentials ic = boletoCreds(c);
    return clients(c).query().find(ic, nossoNumero).map(item -> toStatus(item, nossoNumero));
  }

  @Override public void cancel(ProviderCredentials c, String nossoNumero) {
    ItauCredentials ic = boletoCreds(c);
    clients(c).instruction().baixa(ic, baixaId(ic, nossoNumero));
  }

  @Override public String pixTxidFor(ProviderCredentials c, String nossoNumero) { return pixTxid(boletoCreds(c), nossoNumero); }

  /** cash_management OpenAPI, path {id_boleto}: agência (4) + conta (7) + DAC (1) + carteira (3) + nosso número (8-16). */
  static String baixaId(ItauCredentials c, String nossoNumero) { return c.beneficiaryId() + c.walletCode() + nossoNumero; }

  /** Issue OpenAPI, dados_qrcode.txid: "BL" + agência (4) + conta (7) + carteira (3) + nosso número (15) — beneficiary id without its DAC. */
  static String pixTxid(ItauCredentials c, String nossoNumero) {
    return "BL" + c.beneficiaryId().substring(0, 11) + c.walletCode() + "0".repeat(15 - nossoNumero.length()) + nossoNumero;
  }

  static BoletoStatus toStatus(BoletoQueryItem item, String nossoNumero) {
    BoletoQueryItem.Individual i = item.individual(nossoNumero).orElseThrow();
    Optional<BoletoQueryItem.Pagamento> last = item.lastPayment();
    return new BoletoStatus(
        BoletoSituations.parse(i.situacaoGeralBoleto()),
        last.map(p -> p.valorPagoTotalCobranca() == null ? null : BoletoAmounts.fromItau(p.valorPagoTotalCobranca())).orElse(null),
        last.map(p -> ItauDates.paidAt(p.dataHoraInclusaoPagamento(), p.dataInclusaoPagamento())).orElse(null),
        last.map(p -> p.codigoMeioPagamento() != null ? p.codigoMeioPagamento() : p.descricaoMeioPagamento()).orElse(null),
        i.idBoletoIndividual(),
        i.numeroLinhaDigitavel(),
        i.codigoBarras(),
        ItauDates.date(i.dataLimitePagamento()),
        item.dadoBoleto().qrcodePix() == null ? null : item.dadoBoleto().qrcodePix().emv());
  }
}
```

`ProvidersConfiguration.java` — substitua o arquivo:
```java
package com.gateway.providers;

import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.boleto.BoletoProvider;
import com.gateway.kernel.provider.pix.PixProvider;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.ItauBoletoEndpoints;
import com.gateway.providers.itau.boleto.ItauBoletoProvider;
import com.gateway.providers.itau.pix.ItauPixProvider;
import java.net.URI;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProvidersConfiguration.ProvidersProperties.class)
// Clock is injected, never defined here: the app owns it (a conditional bean in a plain
// @Configuration would depend on registration order).
public class ProvidersConfiguration {

  /**
   * Every field optional: the defaults are the URLs in docs/providers/itau/NOTES.md and the
   * Bolecode spec. Overriding them is how the app's tests point the provider at WireMock.
   * Mutual-TLS flags are boxed so an unset property keeps the environment's real auth model.
   * The boleto block has one base and one token URL per API because the three products live on
   * three hosts and cash_management authenticates at a different STS path.
   */
  @ConfigurationProperties("gateway.providers.itau")
  public record ProvidersProperties(String liveApiBase, String liveTokenUrl, Boolean liveMutualTls,
                                    String testApiBase, String testTokenUrl, Boolean testMutualTls,
                                    String trustStorePem, Duration readTimeout, Boleto boleto) {
    public ProvidersProperties {
      // Itaú recommends a 30 s client timeout for refunds (NOTES.md); charges answer well within it.
      if (readTimeout == null) readTimeout = Duration.ofSeconds(30);
      if (boleto == null) boleto = new Boleto(null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public record Boleto(String liveIssueApiBase, String liveIssueTokenUrl, String liveQueryApiBase, String liveQueryTokenUrl,
                         String liveInstructionApiBase, String liveInstructionTokenUrl,
                         String testIssueApiBase, String testIssueTokenUrl, String testQueryApiBase, String testQueryTokenUrl,
                         String testInstructionApiBase, String testInstructionTokenUrl) {}

    ItauEndpoints live() { return merge(ItauEndpoints.forEnvironment(ProviderEnvironment.LIVE), liveApiBase, liveTokenUrl, liveMutualTls); }
    ItauEndpoints test() { return merge(ItauEndpoints.forEnvironment(ProviderEnvironment.TEST), testApiBase, testTokenUrl, testMutualTls); }

    ItauBoletoEndpoints boletoLive() {
      ItauBoletoEndpoints d = ItauBoletoEndpoints.forEnvironment(ProviderEnvironment.LIVE);
      return new ItauBoletoEndpoints(
          merge(d.issue(), boleto.liveIssueApiBase(), boleto.liveIssueTokenUrl(), liveMutualTls),
          merge(d.query(), boleto.liveQueryApiBase(), boleto.liveQueryTokenUrl(), liveMutualTls),
          merge(d.instruction(), boleto.liveInstructionApiBase(), boleto.liveInstructionTokenUrl(), liveMutualTls));
    }

    ItauBoletoEndpoints boletoTest() {
      ItauBoletoEndpoints d = ItauBoletoEndpoints.forEnvironment(ProviderEnvironment.TEST);
      return new ItauBoletoEndpoints(
          merge(d.issue(), boleto.testIssueApiBase(), boleto.testIssueTokenUrl(), testMutualTls),
          merge(d.query(), boleto.testQueryApiBase(), boleto.testQueryTokenUrl(), testMutualTls),
          merge(d.instruction(), boleto.testInstructionApiBase(), boleto.testInstructionTokenUrl(), testMutualTls));
    }

    private static ItauEndpoints merge(ItauEndpoints d, String api, String token, Boolean mtls) {
      return ItauEndpoints.custom(api == null || api.isBlank() ? d.apiBase() : URI.create(api),
          token == null || token.isBlank() ? d.tokenUrl() : URI.create(token), mtls == null ? d.mutualTls() : mtls);
    }
  }

  @Bean
  ItauTokenClient itauTokenClient(Clock clock, ProvidersProperties props) {
    return new ItauTokenClient(clock, Duration.ofSeconds(3), props.readTimeout());
  }

  /**
   * {@code trustStorePem} unset → JDK default trust (Itaú's CA is public and usually in cacerts);
   * set it to the PEM from the portal's {@code ca-cert.zip} when it is not.
   */
  @Bean
  PixProvider itauPixProvider(ItauTokenClient tokens, Clock clock, ProvidersProperties props) {
    return new ItauPixProvider(tokens, trustStore(props), props.readTimeout(), clock, props.live(), props.test());
  }

  /** Same token client and trust store as Pix: one credential, one cache, one CA. */
  @Bean
  BoletoProvider itauBoletoProvider(ItauTokenClient tokens, ProvidersProperties props) {
    return new ItauBoletoProvider(tokens, trustStore(props), props.readTimeout(), props.boletoLive(), props.boletoTest());
  }

  private static KeyStore trustStore(ProvidersProperties props) {
    return props.trustStorePem() == null || props.trustStorePem().isBlank() ? null : ItauPixProvider.trustStoreFromPem(props.trustStorePem());
  }
}
```

- [ ] **Step 5: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-providers test` (módulo inteiro: os testes de Pix continuam verdes com o `ProvidersProperties` maior).
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add gateway-providers
git commit -m "feat(providers): baixa client and itau boleto provider composing issue, query and instruction

The baixa id is the beneficiary id + wallet + nosso numero, so cancel
takes the number we already store instead of the boleto UUID. A 422
that talks about pago/liquidado is CONFLICT: the 422 has no schema and
no code, and the text is the only signal the bank gives. The Pix txid
of a Bolecode is derived by the bank's documented formula, verified
later against GET /cob before anything depends on it.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Domínio — `PaymentMethod`, `BoletoDetails`, `PROVIDER_POLL`, transições, `POLL_BOLETO`, `Payment` com dois lados

**Files:**
- Create: `gateway-payments/src/main/java/com/gateway/payments/payment/PaymentMethod.java`, `gateway-payments/src/main/java/com/gateway/payments/payment/boleto/{BoletoDetails,BoletoDetailsJson,PaidVia,BoletoDates}.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/payment/EventSource.java`, `payment/PaymentTransitions.java:16-25`, `payment/Payment.java`, `jobs/JobType.java`, `jobs/Job.java`
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/boleto/{BoletoDetailsJsonTest,BoletoDatesTest}.java`, `payment/PaymentTransitionsTest.java` (+1), `payment/PaymentTest.java` (+3)

**Interfaces:**
- Consumes: `Money`, `MerchantId`, `Ulid`, `ProviderEnvironment`, `PixDetails`.
- Produces:
  ```java
  enum PaymentMethod { PIX, BOLECODE }
  enum PaidVia { PIX, BOLETO }                                             // payment/boleto
  record BoletoDetails(String nossoNumero, String idBoletoIndividual, String linhaDigitavel, String codigoBarras, LocalDate dueDate, LocalDate paymentLimitDate, PaidVia paidVia) {
    BoletoDetails withIssued(String idBoletoIndividual, String linhaDigitavel, String codigoBarras, LocalDate paymentLimitDate);   // null limit keeps ours
    BoletoDetails withPaidVia(PaidVia via);
  }
  final class BoletoDetailsJson { static String write(BoletoDetails b) /* "null" when b == null */; static BoletoDetails read(String json) /* null when absent or null */; }
  final class BoletoDates { static final ZoneId SAO_PAULO; static LocalDate today(Clock c); static Instant endOfDay(LocalDate d); }
  enum EventSource { API, PROVIDER_WEBHOOK, RECONCILIATION, EXPIRATION_JOB, SYSTEM, PROVIDER_POLL }
  PaymentTransitions: PENDING -> COMPLETED by {PROVIDER_WEBHOOK, RECONCILIATION, PROVIDER_POLL}; EXPIRED -> COMPLETED by the same three
  enum JobType { PROCESS_WEBHOOK, EXPIRE_PAYMENT, POLL_REFUND, RECONCILE, POLL_BOLETO }
  Job.pollBoleto(String paymentId, Instant firstAt, Clock clock)
  Payment:
    static Payment createBolecode(MerchantId, ProviderEnvironment, String provider, Money amount, String reference, String description, String customerDocumentHash, BoletoDetails boleto, Instant expiresAt, Clock)   // method BOLECODE, pix = PixDetails(null,null,null,null), version 1, "created" event
    PaymentMethod method(); BoletoDetails boleto();                        // boleto null for PIX
    PaymentEvent markPendingBolecode(PixDetails pix, BoletoDetails boleto, Instant expiresAt, EventSource by)
    PaymentEvent markCompleted(String endToEndId, Money paidAmount, Instant paidAt, EventSource by)   // unchanged signature; on BOLECODE also sets boleto.paidVia = PIX; payload gains "paidVia":"PIX"
    PaymentEvent markCompletedByBoleto(Money paidAmount, Instant paidAt, String paidChannel, EventSource by)   // BOLECODE only; boleto.paidVia = BOLETO; payload {"paidVia":"BOLETO","paidAmount":n,"paidChannel":…}
    static Payment rehydrate(... existing 18 args ...)                    // kept: method PIX, boleto null
    static Payment rehydrate(String id, MerchantId, ProviderEnvironment, String provider, PaymentMethod method, PaymentStatus, Money amount, String reference, String description, String customerDocumentHash, PixDetails pix, BoletoDetails boleto, Instant expiresAt, Instant paidAt, Money paidAmount, Money refundedAmount, long version, Instant createdAt, Instant updatedAt, Clock)
  ```

- [ ] **Step 1: Testes**

`payment/boleto/BoletoDetailsJsonTest.java`:
```java
package com.gateway.payments.payment.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BoletoDetailsJsonTest {
  @Test void roundTrips() {
    BoletoDetails b = new BoletoDetails("00000042", "550e8400-e29b-41d4-a716-446655440000", "3".repeat(47), "3".repeat(44), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), PaidVia.BOLETO);
    assertThat(BoletoDetailsJson.read(BoletoDetailsJson.write(b))).isEqualTo(b);
  }

  @Test void nullsSurvive() {
    BoletoDetails b = new BoletoDetails("00000042", null, null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
    assertThat(BoletoDetailsJson.read(BoletoDetailsJson.write(b))).isEqualTo(b);
    assertThat(BoletoDetailsJson.write(null)).isEqualTo("null");
    assertThat(BoletoDetailsJson.read("null")).isNull();
    assertThat(BoletoDetailsJson.read(null)).isNull();
  }

  /** Postgres reformats jsonb (a space after ':'); the reader must not depend on our own spacing. */
  @Test void readsPostgresSpacing() {
    String pg = "{\"dueDate\": \"2026-10-01\", \"paidVia\": null, \"nossoNumero\": \"00000042\", \"codigoBarras\": null, \"linhaDigitavel\": null, \"idBoletoIndividual\": null, \"paymentLimitDate\": \"2026-10-31\"}";
    BoletoDetails b = BoletoDetailsJson.read(pg);
    assertThat(b.nossoNumero()).isEqualTo("00000042");
    assertThat(b.dueDate()).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(b.paidVia()).isNull();
  }
}
```

`payment/boleto/BoletoDatesTest.java`:
```java
package com.gateway.payments.payment.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/** A boleto day is a São Paulo day: at 01:00Z on the 2nd it is still the 1st in São Paulo, and the 1st ends at 02:59:59Z on the 2nd. */
class BoletoDatesTest {
  @Test void todayAndEndOfDayAreSaoPaulo() {
    Clock c = Clock.fixed(Instant.parse("2026-10-02T01:00:00Z"), ZoneOffset.UTC);
    assertThat(BoletoDates.today(c)).isEqualTo(LocalDate.of(2026, 10, 1));
    assertThat(BoletoDates.endOfDay(LocalDate.of(2026, 10, 1))).isEqualTo(Instant.parse("2026-10-02T02:59:59Z"));
  }
}
```

`PaymentTransitionsTest.java` — acrescente:
```java
  /** Polling the boleto query is a provider-side fact, like a webhook: it may complete PENDING and a late-paid EXPIRED, never anything else. */
  @org.junit.jupiter.api.Test
  void providerPollCompletesLikeAWebhook() {
    assertThat(PaymentTransitions.allowed(PaymentStatus.PENDING, PaymentStatus.COMPLETED, EventSource.PROVIDER_POLL)).isTrue();
    assertThat(PaymentTransitions.allowed(PaymentStatus.EXPIRED, PaymentStatus.COMPLETED, EventSource.PROVIDER_POLL)).isTrue();
    assertThat(PaymentTransitions.allowed(PaymentStatus.PENDING, PaymentStatus.CANCELED, EventSource.PROVIDER_POLL)).isFalse();
    assertThat(PaymentTransitions.allowed(PaymentStatus.PENDING, PaymentStatus.EXPIRED, EventSource.PROVIDER_POLL)).isFalse();
  }
```

`PaymentTest.java` — acrescente (imports: `com.gateway.payments.payment.boleto.*`, `java.time.LocalDate`):
```java
  Payment bolecode() {
    BoletoDetails b = new BoletoDetails("00000042", null, null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
    return Payment.createBolecode(MerchantId.next(), ProviderEnvironment.TEST, "ITAU", Money.brl(12990), "order-42", "Pedido 42", null, b,
        BoletoDates.endOfDay(LocalDate.of(2026, 10, 31)), clock);
  }

  @Test void aBolecodeStartsWithItsNumberAndNoTxid() {
    Payment p = bolecode();
    assertThat(p.method()).isEqualTo(PaymentMethod.BOLECODE);
    assertThat(p.boleto().nossoNumero()).isEqualTo("00000042");
    assertThat(p.pix().txid()).isNull();
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    assertThat(p.version()).isEqualTo(1);
    assertThat(p.createdEvent().payload()).contains("\"method\":\"BOLECODE\"").contains("\"nossoNumero\":\"00000042\"");
    assertThat(fresh().method()).isEqualTo(PaymentMethod.PIX);
    assertThat(fresh().boleto()).isNull();
  }

  @Test void pendingBolecodeCarriesBothSidesAndPaidByBoletoSetsPaidVia() {
    Payment p = bolecode();
    PixDetails pix = new PixDetails("BL15000005206109000000000000042", "000201…", null, null);
    BoletoDetails issued = p.boleto().withIssued("uuid-1", "1".repeat(47), "1".repeat(44), LocalDate.of(2026, 10, 30));
    PaymentEvent pending = p.markPendingBolecode(pix, issued, BoletoDates.endOfDay(LocalDate.of(2026, 10, 30)), EventSource.API);
    assertThat(pending.type()).isEqualTo("pending");
    assertThat(p.boleto().paymentLimitDate()).isEqualTo(LocalDate.of(2026, 10, 30));
    assertThat(p.boleto().linhaDigitavel()).hasSize(47);
    assertThat(p.pix().txid()).startsWith("BL");

    PaymentEvent done = p.markCompletedByBoleto(Money.brl(12990), Instant.parse("2026-10-05T12:00:00Z"), "01", EventSource.PROVIDER_POLL);
    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(p.boleto().paidVia()).isEqualTo(PaidVia.BOLETO);
    assertThat(p.paidAmount()).isEqualTo(Money.brl(12990));
    assertThat(p.pix().endToEndId()).isNull();
    assertThat(done.payload()).contains("\"paidVia\":\"BOLETO\"").contains("\"paidChannel\":\"01\"");
  }

  @Test void pixOnABolecodeSetsPaidViaPixAndBoletoCompletionIsRefusedOnPix() {
    Payment p = bolecode();
    p.markPendingBolecode(new PixDetails("BL1", "emv", null, null), p.boleto(), Instant.parse("2026-11-01T02:59:59Z"), EventSource.API);
    PaymentEvent e = p.markCompleted("E123", Money.brl(12990), Instant.now(), EventSource.PROVIDER_WEBHOOK);
    assertThat(p.boleto().paidVia()).isEqualTo(PaidVia.PIX);
    assertThat(e.payload()).contains("\"paidVia\":\"PIX\"");

    Payment pix = fresh();
    pix.markPending(new PixDetails(pix.id(), "x", "y", null), Instant.now());
    assertThatThrownBy(() -> pix.markCompletedByBoleto(Money.brl(1), Instant.now(), null, EventSource.PROVIDER_POLL)).isInstanceOf(IllegalStateException.class);
  }
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -am -Dtest='BoletoDetailsJsonTest,BoletoDatesTest,PaymentTransitionsTest,PaymentTest,JobTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compilação.

- [ ] **Step 3: Implementar**

`payment/PaymentMethod.java`:
```java
package com.gateway.payments.payment;

/** How the payer pays. BOLECODE is one method with two settlement paths (QR or barcode); there is no boleto without Pix (spec 2026-09-25). */
public enum PaymentMethod { PIX, BOLECODE }
```

`payment/boleto/PaidVia.java`:
```java
package com.gateway.payments.payment.boleto;

/** Which side of a Bolecode the payer used. Decides whether a refund is possible: only a Pix settlement has a devolução at the bank. */
public enum PaidVia { PIX, BOLETO }
```

`payment/boleto/BoletoDetails.java`:
```java
package com.gateway.payments.payment.boleto;

import java.time.LocalDate;

/**
 * The boleto side of a Bolecode. {@code nossoNumero} is ours and is the key of every bank query;
 * the rest arrives from the bank at issue (or from the query, when the issue's answer was lost).
 * {@code paymentLimitDate} is the day the charge expires — the due date only starts interest.
 */
public record BoletoDetails(String nossoNumero, String idBoletoIndividual, String linhaDigitavel, String codigoBarras,
                            LocalDate dueDate, LocalDate paymentLimitDate, PaidVia paidVia) {

  /** A null limit from the bank keeps the one we asked for. */
  public BoletoDetails withIssued(String idBoletoIndividual, String linhaDigitavel, String codigoBarras, LocalDate paymentLimitDate) {
    return new BoletoDetails(nossoNumero, idBoletoIndividual, linhaDigitavel, codigoBarras, dueDate,
        paymentLimitDate == null ? this.paymentLimitDate : paymentLimitDate, paidVia);
  }

  public BoletoDetails withPaidVia(PaidVia via) {
    return new BoletoDetails(nossoNumero, idBoletoIndividual, linhaDigitavel, codigoBarras, dueDate, paymentLimitDate, via);
  }
}
```

`payment/boleto/BoletoDetailsJson.java`:
```java
package com.gateway.payments.payment.boleto;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-rolled codec for {@code details.boleto}, the same style as {@code PixDetailsJson}: no
 * Jackson in the domain, and it only reads back what {@link #write} produced. Keys are chosen not
 * to collide with the pix keys, so both readers can scan the whole {@code details} document.
 */
public final class BoletoDetailsJson {
  private BoletoDetailsJson() {}

  public static String write(BoletoDetails b) {
    if (b == null) return "null";
    return "{\"nossoNumero\":" + str(b.nossoNumero())
        + ",\"idBoletoIndividual\":" + str(b.idBoletoIndividual())
        + ",\"linhaDigitavel\":" + str(b.linhaDigitavel())
        + ",\"codigoBarras\":" + str(b.codigoBarras())
        + ",\"dueDate\":" + str(b.dueDate() == null ? null : b.dueDate().toString())
        + ",\"paymentLimitDate\":" + str(b.paymentLimitDate() == null ? null : b.paymentLimitDate().toString())
        + ",\"paidVia\":" + str(b.paidVia() == null ? null : b.paidVia().name())
        + "}";
  }

  /** Null for {@code null}, an absent block, or a document without {@code nossoNumero} (a Pix payment). */
  public static BoletoDetails read(String json) {
    if (json == null || json.isBlank() || json.trim().equals("null")) return null;
    String nossoNumero = field(json, "nossoNumero");
    if (nossoNumero == null) return null;
    String via = field(json, "paidVia");
    return new BoletoDetails(nossoNumero, field(json, "idBoletoIndividual"), field(json, "linhaDigitavel"), field(json, "codigoBarras"),
        date(field(json, "dueDate")), date(field(json, "paymentLimitDate")), via == null ? null : PaidVia.valueOf(via));
  }

  private static LocalDate date(String s) { return s == null ? null : LocalDate.parse(s); }

  // \s* after ':' because Postgres reformats jsonb on the way out (see PixDetailsJson).
  private static String field(String json, String key) {
    Matcher m = Pattern.compile("\"" + key + "\":\\s*(null|\"((?:\\\\.|[^\"\\\\])*)\")").matcher(json);
    if (!m.find()) return null;
    return m.group(2) == null ? null : unescape(m.group(2));
  }

  private static String str(String s) {
    if (s == null) return "null";
    StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  private static String unescape(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char next = s.charAt(++i);
        switch (next) {
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          default -> sb.append(next);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
```

`payment/boleto/BoletoDates.java`:
```java
package com.gateway.payments.payment.boleto;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Boleto dates are calendar days at the bank, which lives in São Paulo; the gateway's clock is UTC, so every conversion goes through here. */
public final class BoletoDates {
  public static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

  private BoletoDates() {}

  public static LocalDate today(Clock clock) { return LocalDate.ofInstant(clock.instant(), SAO_PAULO); }

  /** 23:59:59 of that day in São Paulo: the last instant the bank still takes the payment. */
  public static Instant endOfDay(LocalDate day) { return day.atTime(23, 59, 59).atZone(SAO_PAULO).toInstant(); }
}
```

`EventSource.java`:
```java
package com.gateway.payments.payment;

/** Who triggered a payment event — the transition table decides which source may cause which transition. */
public enum EventSource {
  API,
  PROVIDER_WEBHOOK,
  RECONCILIATION,
  EXPIRATION_JOB,
  SYSTEM,
  /** The boleto query (GET /boletos) run by the POLL_BOLETO job: a provider-side fact, like a webhook. */
  PROVIDER_POLL
}
```

`PaymentTransitions.java` — troque as duas linhas de `COMPLETED`:
```java
          new Transition(PENDING, COMPLETED, EnumSet.of(PROVIDER_WEBHOOK, RECONCILIATION, PROVIDER_POLL)),
          new Transition(PENDING, EXPIRED, EnumSet.of(EXPIRATION_JOB)),
          new Transition(PENDING, CANCELED, EnumSet.of(API)),
          // PROVIDER_POLL also here: the boleto poll runs until the limit date plus a grace, i.e. after the expiry job.
          new Transition(EXPIRED, COMPLETED, EnumSet.of(PROVIDER_WEBHOOK, RECONCILIATION, PROVIDER_POLL)));
```

`JobType.java`: acrescente `POLL_BOLETO` ao final. `Job.java` — acrescente a fábrica:
```java
  /** Bolecode: ask GET /boletos whether the barcode was paid; the runner reschedules it every 6 h until the limit date plus a grace. */
  public static Job pollBoleto(String paymentId, Instant firstAt, Clock clock) {
    return new Job(Ulid.next(), JobType.POLL_BOLETO, paymentId, firstAt, 0, "PENDING", null, null, clock.instant());
  }
```

`Payment.java` — mudanças (o resto do arquivo fica igual):

1. Imports: `com.gateway.payments.payment.boleto.BoletoDetails`, `com.gateway.payments.payment.boleto.PaidVia`.
2. Campos novos, ao lado de `pix`:
```java
  private final PaymentMethod method;
  private BoletoDetails boleto;
```
3. O construtor privado ganha `PaymentMethod method` como segundo parâmetro (depois de `id`) e faz `this.method = method;`. Todos os `new Payment(...)` passam `PaymentMethod.PIX` — exceto `createBolecode` e o novo `rehydrate`.
4. `create(...)` (Pix) chama `new Payment(id, PaymentMethod.PIX, ...)`; o payload do evento `created` fica `"{\"amount\":" + amount.cents() + ",\"method\":\"PIX\"}"`.
5. Fábrica nova:
```java
  /**
   * A Bolecode starts with its nosso número reserved (the bank's query key) and no txid: the Pix
   * side only exists once the bank answers the issue. {@code expiresAt} is the limit date's end of
   * day in São Paulo, computed by the caller.
   */
  public static Payment createBolecode(
      MerchantId merchantId, ProviderEnvironment environment, String provider, Money amount, String reference, String description,
      String customerDocumentHash, BoletoDetails boleto, Instant expiresAt, Clock clock) {
    String id = Ulid.next();
    Payment p = new Payment(id, PaymentMethod.BOLECODE, merchantId, environment, provider, amount, reference, description, customerDocumentHash, clock.instant(), clock);
    p.pix = new PixDetails(null, null, null, null);
    p.boleto = boleto;
    p.expiresAt = expiresAt;
    p.version = 1;
    p.createdEvent = new PaymentEvent(Ulid.next(), id, p.version, "created", EventSource.API,
        "{\"amount\":" + amount.cents() + ",\"method\":\"BOLECODE\",\"nossoNumero\":" + json(boleto.nossoNumero()) + "}", p.createdAt);
    return p;
  }
```
6. Transição para PENDING com os dois lados:
```java
  public PaymentEvent markPendingBolecode(PixDetails pixDetails, BoletoDetails boletoDetails, Instant expiresAt, EventSource by) {
    if (method != PaymentMethod.BOLECODE) throw new IllegalStateException("markPendingBolecode on a " + method + " payment");
    PaymentEvent event = transition(PaymentStatus.PENDING, by, "pending",
        "{\"txid\":" + json(pixDetails.txid()) + ",\"nossoNumero\":" + json(boletoDetails.nossoNumero()) + "}");
    this.pix = pixDetails;
    this.boleto = boletoDetails;
    this.expiresAt = expiresAt;
    return event;
  }
```
7. `markCompleted` (Pix) passa a registrar o lado:
```java
  public PaymentEvent markCompleted(String endToEndId, Money paidAmount, Instant paidAt, EventSource by) {
    PaymentEvent event = transition(PaymentStatus.COMPLETED, by, "completed",
        "{\"endToEndId\":" + json(endToEndId) + ",\"paidAmount\":" + paidAmount.cents() + ",\"paidVia\":\"PIX\"}");
    this.pix = pix.withEndToEndId(endToEndId);
    if (boleto != null) this.boleto = boleto.withPaidVia(PaidVia.PIX);
    this.paidAmount = paidAmount;
    this.paidAt = paidAt;
    return event;
  }

  /** The barcode path: no endToEndId exists, the bank's payment record is the evidence. {@code paidChannel} is its codigo_meio_pagamento. */
  public PaymentEvent markCompletedByBoleto(Money paidAmount, Instant paidAt, String paidChannel, EventSource by) {
    if (method != PaymentMethod.BOLECODE) throw new IllegalStateException("markCompletedByBoleto on a " + method + " payment");
    PaymentEvent event = transition(PaymentStatus.COMPLETED, by, "completed",
        "{\"paidVia\":\"BOLETO\",\"paidAmount\":" + paidAmount.cents() + ",\"paidChannel\":" + json(paidChannel) + "}");
    this.boleto = boleto.withPaidVia(PaidVia.BOLETO);
    this.paidAmount = paidAmount;
    this.paidAt = paidAt;
    return event;
  }
```
8. Getters: `public PaymentMethod method() { return method; }` e `public BoletoDetails boleto() { return boleto; }`.
9. `rehydrate`: o existente (18 argumentos) passa a delegar ao novo com `PaymentMethod.PIX` e `boleto = null`; o novo:
```java
  public static Payment rehydrate(
      String id, MerchantId merchantId, ProviderEnvironment environment, String provider, PaymentMethod method, PaymentStatus status,
      Money amount, String reference, String description, String customerDocumentHash, PixDetails pix, BoletoDetails boleto,
      Instant expiresAt, Instant paidAt, Money paidAmount, Money refundedAmount, long version, Instant createdAt, Instant updatedAt, Clock clock) {
    Payment p = new Payment(id, method, merchantId, environment, provider, amount, reference, description, customerDocumentHash, createdAt, clock);
    p.status = status;
    p.pix = pix;
    p.boleto = boleto;
    p.expiresAt = expiresAt;
    p.paidAt = paidAt;
    p.paidAmount = paidAmount;
    p.refundedAmount = refundedAmount == null ? Money.ZERO_BRL : refundedAmount;
    p.version = version;
    p.updatedAt = updatedAt;
    return p;
  }
```
Atualize o javadoc da classe: "A Pix charge" → "A Pix charge or a Bolecode (a registered boleto with a Pix QR on it)".

- [ ] **Step 4: Rodar e ver passar**

Run: comando do Step 2.
Expected: PASS (`everythingOutsideTheTableIsRefused` cresce com o enum, continua verde).

- [ ] **Step 5: Commit**

```bash
git add gateway-payments
git commit -m "feat(payments): bolecode in the domain: method, boleto details, provider poll source, poll job

A Bolecode starts with its nosso numero and no txid; the Pix side is
filled by the bank's answer. PROVIDER_POLL may complete PENDING and
EXPIRED because the boleto query runs past the expiry job. paidVia
lives on the boleto details and in every completed event, since it
decides whether a refund exists at the bank.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Persistência — V203 (`details.pix`/`details.boleto`, `boleto_numbers`), entidade, `findByMerchantAndTxid`, JSON público com bloco `boleto`

**Files:**
- Create: `gateway-payments/src/main/resources/db/migration/payments/V203__bolecode.sql`, `gateway-payments/src/main/java/com/gateway/payments/payment/PaymentDetailsJson.java`
- Modify: `payment/persistence/PaymentEntity.java:26-28` (comentário), `payment/persistence/PaymentJpaRepository.java`, `payment/persistence/PaymentRepository.java`, `payment/persistence/PaymentRepositoryImpl.java`, `payment/PaymentEvents.java:52-60`
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/PaymentDetailsJsonTest.java`, `payment/persistence/PaymentRepositoryIntegrationTest.java` (+2)

**Interfaces:**
- Produces:
  ```java
  final class PaymentDetailsJson { static String write(PixDetails pix, BoletoDetails boleto); static PixDetails readPix(String details); static BoletoDetails readBoleto(String details); }   // {"pix":{…},"boleto":{…}|null}
  PaymentRepository += Optional<Payment> findByMerchantAndTxid(MerchantId merchantId, String provider, String txid);   // details->'pix'->>'txid'
  PaymentEvents.paymentJson(p): "method" = p.method().name(); + "boleto": null | {linha_digitavel, codigo_barras, due_date, payment_limit_date, paid_via}
  ```
  Migration: existing rows are wrapped (`{"pix": <old>}`), the txid/e2eid indexes move to `details->'pix'->>…`, a new index on `(merchant_id, details->'boleto'->>'nossoNumero')`, and the counter table `payments.boleto_numbers(merchant_id CHAR(26) PK, next_value BIGINT)`.

- [ ] **Step 1: Migration**

`V203__bolecode.sql`:
```sql
-- Bolecode (spec 2026-09-25 §2): details becomes {"pix": {...}, "boleto": {...}|null}. Existing rows carry the
-- flat pix shape from V200; they are nested so every row reads the same way, and the two expression
-- indexes follow the keys. `details ? 'pix'` is Postgres' key-exists operator (Flyway passes the statement
-- through untouched; there is no JDBC placeholder here).

UPDATE payments.payments SET details = jsonb_build_object('pix', details) WHERE NOT (details ? 'pix');

DROP INDEX payments.uq_payments_provider_txid;
CREATE UNIQUE INDEX uq_payments_provider_txid ON payments.payments (provider, (details->'pix'->>'txid'));

DROP INDEX payments.idx_payments_e2eid;
CREATE INDEX idx_payments_e2eid ON payments.payments ((details->'pix'->>'endToEndId'));

-- The poll and the cancel look a boleto up by its number; the merchant lists by it in support.
CREATE INDEX idx_payments_merchant_nosso_numero ON payments.payments (merchant_id, (details->'boleto'->>'nossoNumero')) WHERE details ? 'boleto';

-- Nosso número: sequential per merchant, 8 digits, allocated with UPDATE ... RETURNING in the CREATED
-- transaction. One row per merchant; 10^8 numbers is more than enough that reuse (45 days after
-- baixa/liquidação, spec §8) is not handled.
CREATE TABLE payments.boleto_numbers (
    merchant_id CHAR(26) PRIMARY KEY,
    next_value  BIGINT   NOT NULL
);
```

- [ ] **Step 2: Testes**

`payment/PaymentDetailsJsonTest.java`:
```java
package com.gateway.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.boleto.PaidVia;
import com.gateway.payments.payment.pix.PixDetails;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PaymentDetailsJsonTest {
  @Test void writesBothBlocksAndReadsThemBack() {
    PixDetails pix = new PixDetails("BL1", "emv", null, "E1");
    BoletoDetails boleto = new BoletoDetails("00000001", "u", "1".repeat(47), "1".repeat(44), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), PaidVia.PIX);
    String json = PaymentDetailsJson.write(pix, boleto);
    assertThat(json).startsWith("{\"pix\":{").contains(",\"boleto\":{");
    assertThat(PaymentDetailsJson.readPix(json)).isEqualTo(pix);
    assertThat(PaymentDetailsJson.readBoleto(json)).isEqualTo(boleto);
  }

  @Test void pixOnlyHasANullBoleto() {
    String json = PaymentDetailsJson.write(new PixDetails("t", null, null, null), null);
    assertThat(json).isEqualTo("{\"pix\":{\"txid\":\"t\",\"pixCopiaECola\":null,\"location\":null,\"endToEndId\":null},\"boleto\":null}");
    assertThat(PaymentDetailsJson.readBoleto(json)).isNull();
    assertThat(PaymentDetailsJson.readPix(json).txid()).isEqualTo("t");
  }

  /** The keys of the two blocks must stay disjoint: both readers scan the whole document (see BoletoDetailsJson). */
  @Test void theTwoBlocksShareNoKey() {
    String pixJson = com.gateway.payments.payment.pix.PixDetailsJson.write(new PixDetails("a", "b", "c", "d"));
    String boletoJson = com.gateway.payments.payment.boleto.BoletoDetailsJson.write(new BoletoDetails("a", "b", "c", "d", LocalDate.EPOCH, LocalDate.EPOCH, PaidVia.BOLETO));
    java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"([A-Za-z]+)\":").matcher(pixJson);
    while (m.find()) assertThat(boletoJson).doesNotContain("\"" + m.group(1) + "\":");
  }
}
```

`PaymentRepositoryIntegrationTest.java` — acrescente (imports `com.gateway.payments.payment.boleto.*`, `com.gateway.payments.payment.PaymentMethod`, `java.time.LocalDate`):
```java
  @Test
  void aBolecodeRoundTripsWithBothBlocksAndIsFoundByTxid() {
    BoletoDetails b = new BoletoDetails("00000007", null, null, null, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null);
    Payment p = Payment.createBolecode(MerchantId.next(), ProviderEnvironment.TEST, "ITAU", Money.brl(500), "o-7", null, null, b, Instant.parse("2026-11-01T02:59:59Z"), clock);
    tx.executeWithoutResult(s -> repo.save(p, List.of(p.createdEvent())));
    tx.executeWithoutResult(s -> {
      Payment loaded = repo.findById(p.id()).orElseThrow();
      PaymentEvent ev = loaded.markPendingBolecode(new PixDetails("BL15000005206109000000000000007", "emv", null, null),
          loaded.boleto().withIssued("uuid-7", "7".repeat(47), "7".repeat(44), null), Instant.parse("2026-11-01T02:59:59Z"), EventSource.API);
      repo.save(loaded, List.of(ev));
    });

    Payment back = repo.findById(p.id()).orElseThrow();
    assertThat(back.method()).isEqualTo(PaymentMethod.BOLECODE);
    assertThat(back.boleto().nossoNumero()).isEqualTo("00000007");
    assertThat(back.boleto().linhaDigitavel()).isEqualTo("7".repeat(47));
    assertThat(back.boleto().paymentLimitDate()).isEqualTo(LocalDate.of(2026, 10, 31));
    assertThat(back.pix().txid()).isEqualTo("BL15000005206109000000000000007");
    assertThat(jdbc.queryForObject("SELECT method FROM payments.payments WHERE id = ?", String.class, p.id())).isEqualTo("BOLECODE");
    assertThat(repo.findByMerchantAndTxid(p.merchantId(), "ITAU", "BL15000005206109000000000000007")).isPresent();
    assertThat(repo.findByMerchantAndTxid(MerchantId.next(), "ITAU", "BL15000005206109000000000000007")).isEmpty();
  }

  @Test
  void aPixPaymentStillReadsBackWithANullBoletoAndItsNestedTxid() {
    Payment p = fresh();
    tx.executeWithoutResult(s -> repo.save(p, List.of(p.createdEvent())));
    Payment back = repo.findById(p.id()).orElseThrow();
    assertThat(back.method()).isEqualTo(PaymentMethod.PIX);
    assertThat(back.boleto()).isNull();
    assertThat(back.pix().txid()).isEqualTo(p.id());
    assertThat(jdbc.queryForObject("SELECT details->'pix'->>'txid' FROM payments.payments WHERE id = ?", String.class, p.id())).isEqualTo(p.id());
    assertThat(repo.findByMerchantAndTxid(p.merchantId(), "ITAU", p.id())).isPresent();
  }
```
(`fresh()`, `repo`, `tx`, `jdbc` e `clock` já existem nessa classe; se `jdbc` não existir, injete `@Autowired JdbcTemplate jdbc`.)

- [ ] **Step 3: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -Dtest='PaymentDetailsJsonTest,PaymentRepositoryIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compilação.

- [ ] **Step 4: Implementar**

`payment/PaymentDetailsJson.java`:
```java
package com.gateway.payments.payment;

import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.boleto.BoletoDetailsJson;
import com.gateway.payments.payment.pix.PixDetails;
import com.gateway.payments.payment.pix.PixDetailsJson;

/**
 * The {@code details} column: {@code {"pix": {...}, "boleto": {...}|null}} (spec 2026-09-25 §2). Both
 * block readers scan the whole document by key, which works because the two key sets are disjoint
 * (PaymentDetailsJsonTest pins that) — no JSON parser in the domain, same as PixDetailsJson.
 */
public final class PaymentDetailsJson {
  private PaymentDetailsJson() {}

  public static String write(PixDetails pix, BoletoDetails boleto) {
    return "{\"pix\":" + PixDetailsJson.write(pix) + ",\"boleto\":" + BoletoDetailsJson.write(boleto) + "}";
  }

  public static PixDetails readPix(String details) { return PixDetailsJson.read(details); }

  public static BoletoDetails readBoleto(String details) { return BoletoDetailsJson.read(details); }
}
```

`PaymentEntity.java` — só o comentário da coluna `details`: `// details is {"pix":{txid,pixCopiaECola,location,endToEndId},"boleto":{nossoNumero,...}|null} (V203); the domain has no Jackson dependency (see Payment's javadoc), so PaymentDetailsJson builds/parses this by hand.`

`PaymentJpaRepository.java` — acrescente:
```java
  /**
   * Native: the txid lives inside jsonb and the unique index (V203) is on this exact expression.
   * Provider first because the index is (provider, txid); the merchant is checked by the caller.
   */
  @Query(value = "SELECT * FROM payments.payments WHERE provider = :provider AND details->'pix'->>'txid' = :txid", nativeQuery = true)
  Optional<PaymentEntity> findByProviderAndTxid(@Param("provider") String provider, @Param("txid") String txid);
```

`PaymentRepository.java` — acrescente:
```java
  /**
   * By the bank's txid, scoped to the merchant. For Pix the txid is the payment id; for a Bolecode it
   * is the bank's {@code BL…} — the webhook and the reconciliation must resolve both the same way.
   */
  Optional<Payment> findByMerchantAndTxid(MerchantId merchantId, String provider, String txid);
```

`PaymentRepositoryImpl.java`:
- remova `METHOD_PIX`; em `save`: `e.method = p.method().name();` e `String details = PaymentDetailsJson.write(p.pix(), p.boleto());` (import `com.gateway.payments.payment.PaymentDetailsJson`; remova o import de `PixDetailsJson`).
- `toDomain` passa a usar o `rehydrate` de 20 argumentos: `Payment.rehydrate(e.id, new MerchantId(e.merchantId), ProviderEnvironment.valueOf(e.environment), e.provider, PaymentMethod.valueOf(e.method), PaymentStatus.valueOf(e.status), new Money(e.amount, e.currency), e.reference, e.description, e.customerDocumentHash, PaymentDetailsJson.readPix(e.details), PaymentDetailsJson.readBoleto(e.details), e.expiresAt, e.paidAt, e.paidAmount == null ? null : new Money(e.paidAmount, e.currency), new Money(e.refundedAmount, e.currency), e.version, e.createdAt, e.updatedAt, Clock.systemUTC())`.
- método novo:
```java
  @Override
  public Optional<Payment> findByMerchantAndTxid(MerchantId merchantId, String provider, String txid) {
    return jpa.findByProviderAndTxid(provider, txid).filter(e -> e.merchantId.equals(merchantId.value())).map(PaymentRepositoryImpl::toDomain);
  }
```

`PaymentEvents.paymentJson` — troque `m.put("method", "PIX");` por `m.put("method", p.method().name());` e, depois do bloco `pix`, acrescente:
```java
    // Same keys as PaymentResponse.Boleto (gateway-app); null for a Pix payment so the key set is stable.
    BoletoDetails boleto = p.boleto();
    if (boleto == null) {
      m.put("boleto", null);
    } else {
      Map<String, Object> boletoJson = new LinkedHashMap<>();
      boletoJson.put("linha_digitavel", boleto.linhaDigitavel());
      boletoJson.put("codigo_barras", boleto.codigoBarras());
      boletoJson.put("due_date", boleto.dueDate() == null ? null : boleto.dueDate().toString());
      boletoJson.put("payment_limit_date", boleto.paymentLimitDate() == null ? null : boleto.paymentLimitDate().toString());
      boletoJson.put("paid_via", boleto.paidVia() == null ? null : boleto.paidVia().name());
      m.put("boleto", boletoJson);
    }
```
(import `com.gateway.payments.payment.boleto.BoletoDetails`.) O `PaymentJsonContractTest` do `app` **vai quebrar** até a Task 12 alinhar `PaymentResponse`; é esperado e o commit desta task só roda o módulo `payments`.

- [ ] **Step 5: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments test` (o módulo inteiro: o Flyway aplica a V203 num banco limpo e todos os testes de Pix precisam continuar verdes com o `details` aninhado).
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add gateway-payments
git commit -m "feat(payments): nest pix under details.pix, add details.boleto, boleto_numbers and lookup by txid

V203 wraps the existing flat pix shape so every row reads the same
way and moves the txid/e2eid indexes with it. findByMerchantAndTxid
exists because a Bolecode's txid is the bank's BL..., never the
payment id, and the webhook and reconciliation must resolve both
methods the same way. The public JSON gains a boleto block (null for
Pix) so the merchant's key set never changes per method.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Alocador de nosso número (`boleto_numbers`, `UPDATE … RETURNING`, dois threads sem repetição)

**Files:**
- Create: `gateway-payments/src/main/java/com/gateway/payments/payment/boleto/persistence/{BoletoNumberRepository,BoletoNumberRepositoryImpl}.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/PaymentsConfiguration.java:53-62` (`@Import` + `BoletoNumberRepositoryImpl.class`)
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/boleto/persistence/BoletoNumberRepositoryIntegrationTest.java`

**Interfaces:**
- Produces:
  ```java
  public interface BoletoNumberRepository { String next(MerchantId merchantId); }   // MANDATORY tx; "00000001", "00000002", ...; IllegalStateException past 99999999
  ```

- [ ] **Step 1: Teste**

```java
package com.gateway.payments.payment.boleto.persistence;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class BoletoNumberRepositoryIntegrationTest extends ServiceIntegrationTestBase {
  @Autowired BoletoNumberRepository numbers;
  @Autowired PlatformTransactionManager txManager;

  @Test
  void startsAtOnePerMerchantWithEightDigits() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    MerchantId other = MerchantId.next();
    assertThat(tx.execute(s -> numbers.next(merchant))).isEqualTo("00000001");
    assertThat(tx.execute(s -> numbers.next(merchant))).isEqualTo("00000002");
    assertThat(tx.execute(s -> numbers.next(other))).isEqualTo("00000001");
  }

  @Test
  void refusesToRunOutsideATransaction() {
    assertThatThrownBy(() -> numbers.next(merchant)).isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  /** Two threads, one row: the UPDATE ... RETURNING serializes on the row lock, so no number repeats and none is skipped. */
  @Test
  void concurrentCallersNeverShareANumber() throws Exception {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    CountDownLatch go = new CountDownLatch(1);
    Callable<String> take = () -> { go.await(); return tx.execute(s -> numbers.next(merchant)); };
    try (var pool = Executors.newFixedThreadPool(2)) {
      List<Future<String>> futures = IntStream.range(0, 20).mapToObj(i -> pool.submit(take)).toList();
      go.countDown();
      Set<String> got = new java.util.HashSet<>();
      for (Future<String> f : futures) got.add(f.get());
      assertThat(got).hasSize(20).contains("00000001", "00000020");
    }
  }

  @Test
  void theCounterIsCappedAtEightDigits() {
    TransactionTemplate tx = new TransactionTemplate(txManager);
    jdbc.update("INSERT INTO payments.boleto_numbers (merchant_id, next_value) VALUES (?, 99999999)", merchant.value());
    assertThatThrownBy(() -> tx.execute(s -> numbers.next(merchant))).isInstanceOf(IllegalStateException.class).hasMessageContaining("exhausted");
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -Dtest=BoletoNumberRepositoryIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compilação.

- [ ] **Step 3: Implementar**

`BoletoNumberRepository.java`:
```java
package com.gateway.payments.payment.boleto.persistence;

import com.gateway.kernel.ids.MerchantId;

public interface BoletoNumberRepository {
  /**
   * The next nosso número for the merchant, 8 digits zero-padded, sequential from 00000001. Runs
   * in the caller's transaction (MANDATORY): the number is reserved together with the CREATED row,
   * so a rolled-back create never leaves a hole the bank would later reject as a repeat.
   */
  String next(MerchantId merchantId);
}
```

`BoletoNumberRepositoryImpl.java`:
```java
package com.gateway.payments.payment.boleto.persistence;

import com.gateway.kernel.ids.MerchantId;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class BoletoNumberRepositoryImpl implements BoletoNumberRepository {
  /** 8 digits: the issue OpenAPI says "máximo 08 caracteres" and the query schema pins minLength = maxLength = 8. */
  private static final long MAX = 99_999_999L;

  @PersistenceContext private EntityManager em;

  /**
   * One statement: the upsert takes the row lock, increments and returns, so two concurrent
   * callers are serialized by Postgres and neither reads a stale value (a SELECT-then-UPDATE
   * would). The first call inserts 1; the excluded row's value is what ON CONFLICT adds to.
   */
  @Override
  @Transactional(propagation = Propagation.MANDATORY)
  public String next(MerchantId merchantId) {
    Number value =
        (Number) em.createNativeQuery(
                """
                INSERT INTO payments.boleto_numbers (merchant_id, next_value) VALUES (:merchantId, 1)
                ON CONFLICT (merchant_id) DO UPDATE SET next_value = payments.boleto_numbers.next_value + 1
                RETURNING next_value
                """)
            .setParameter("merchantId", merchantId.value())
            .getSingleResult();
    long n = value.longValue();
    if (n > MAX) throw new IllegalStateException("nosso numero exhausted for merchant " + merchantId.value());
    return String.format("%08d", n);
  }
}
```
`PaymentsConfiguration`: acrescente `BoletoNumberRepositoryImpl.class` ao `@Import` (import `com.gateway.payments.payment.boleto.persistence.BoletoNumberRepositoryImpl`).

Se o Hibernate recusar `RETURNING` em `getSingleResult()` de uma `INSERT` nativa (alguns dialetos tratam como update), troque para `em.createNativeQuery(sql, Long.class)`; se ainda assim falhar, use `jdbc`-style via `em.unwrap(org.hibernate.Session.class).doReturningWork(conn -> { try (var ps = conn.prepareStatement(sql)) { ps.setString(1, merchantId.value()); try (var rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); } } })` com `?` no lugar de `:merchantId`. O teste é o árbitro; registre o que funcionou no comentário.

- [ ] **Step 4: Rodar e ver passar**

Run: comando do Step 2.
Expected: PASS (4 testes).

- [ ] **Step 5: Commit**

```bash
git add gateway-payments
git commit -m "feat(payments): sequential nosso numero per merchant with an upsert-returning counter

One statement takes the row lock, increments and returns, so two
concurrent creates never share a number; a rolled-back create rolls
the counter back with it. Eight digits is the bank's limit for
carteira 109; 10^8 per merchant makes reuse a non-problem.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 9: `PaymentService.createBolecode`, adoção após timeout/202, `ProviderGateway.Resolved.boleto`, `RecordingBoletoProvider`

**Files:**
- Modify: `gateway-payments/src/main/java/com/gateway/payments/provider/ProviderGateway.java`, `gateway-payments/src/main/java/com/gateway/payments/PaymentsProperties.java`, `gateway-payments/src/main/java/com/gateway/payments/PaymentsConfiguration.java`, `gateway-payments/src/main/java/com/gateway/payments/payment/PaymentService.java`
- Modify (test support): `gateway-payments/src/test/java/com/gateway/payments/support/{RecordingPixProvider,ServiceTestConfig,ServiceIntegrationTestBase}.java`
- Create (test support): `gateway-payments/src/test/java/com/gateway/payments/support/RecordingBoletoProvider.java`
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/BolecodeServiceIntegrationTest.java`

**Interfaces:**
- Consumes: Tasks 1, 6, 7, 8.
- Produces:
  ```java
  ProviderGateway:
    public record Resolved(PixProvider provider, Optional<BoletoProvider> boleto, ProviderCredentials credentials) {}
    public ProviderGateway(List<PixProvider> providers, List<BoletoProvider> boletoProviders, CredentialLookup credentials, ProviderRequestRepository requests)
  PaymentsProperties += Duration boletoPollEvery /*PT6H*/, int boletoPollMaxAttempts /*15000*/, Duration boletoPollGraceAfterLimit /*P2D*/, int boletoDefaultDueInDays /*3*/, int boletoDefaultPaymentLimitDays /*30*/, int boletoMaxPaymentLimitDays /*3650*/
  PaymentService:
    public record CreateBolecode(MerchantId merchantId, ProviderEnvironment env, Money amount, String reference, String description, Payer payer, LocalDate dueDate, Integer paymentLimitDays) {}
    public Payment createBolecode(CreateBolecode cmd);
    Payment adoptPendingBolecode(String paymentId, IssuedBoleto issued, EventSource by);                       // CREATED -> PENDING + jobs + outbox
    Payment adoptBolecodeFromStatus(String paymentId, ProviderGateway.Resolved r, BoletoStatus status, EventSource by);   // builds the IssuedBoleto from the query (+ GET /cob for the EMV)
    // codes: CUSTOMER_REQUIRED (message names the field), INVALID_DUE_DATE, INVALID_PAYMENT_LIMIT, PROVIDER_CREDENTIALS_MISSING, PROVIDER_DECLINED, PROVIDER_UNAVAILABLE, PROVIDER_TIMEOUT
  RecordingBoletoProvider (test): failNextIssueWith(e), landNextIssueThenFailWith(e), refuseNextIssueCredentials(), failNextFindWith(nossoNumero, e), markPaid(nossoNumero, Money, Instant), setSituation(nossoNumero, BoletoSituation), remove(nossoNumero), status(nossoNumero), callsFor(key)   // calls: issueBoleto:<nn>, findBoleto:<nn>, cancelBoleto:<nn>
  RecordingPixProvider += public void register(Charge c)
  ServiceIntegrationTestBase += @Autowired protected RecordingBoletoProvider boletos; protected Payment newBolecode(long cents); protected static Payer payer()
  ```

- [ ] **Step 1: Suporte de teste**

`RecordingPixProvider.java` — acrescente (a `RecordingBoletoProvider` registra o lado Pix do Bolecode aqui, porque `PaymentService` confirma o txid reconstruído com `findCharge`):
```java
  /** A charge the bank created on its own (the Pix side of a Bolecode); findCharge and listCharges see it like any other. */
  public void register(Charge c) {
    charges.put(c.txid(), c);
  }
```

`RecordingBoletoProvider.java`:
```java
package com.gateway.payments.support;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.kernel.provider.boleto.BoletoProvider;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.boleto.IssuedBoleto;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.ChargeStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory implementation of the kernel's {@link BoletoProvider}, for the payments module's tests
 * only (never a product provider, never leaves src/test). Issuing also registers the Pix side of the
 * Bolecode in {@link RecordingPixProvider}, the way the bank creates both at once.
 */
public class RecordingBoletoProvider implements BoletoProvider {
  /** The test credential's account: agência 1500, conta 0000520, DAC 6, carteira 109 — the same shape ItauBoletoProvider derives from. */
  static final String BENEFICIARY = "150000052061";
  static final String WALLET = "109";

  private final Clock clock;
  private final RecordingPixProvider pix;
  private final Map<String, BoletoStatus> boletos = new ConcurrentHashMap<>();
  private final Map<String, ProviderException> failFind = new ConcurrentHashMap<>();
  private final List<String> calls = new CopyOnWriteArrayList<>();
  private volatile ProviderException failNextIssue;
  private volatile ProviderException landThenFail;
  private volatile boolean refuseCredentials;

  public RecordingBoletoProvider(Clock clock, RecordingPixProvider pix) {
    this.clock = clock;
    this.pix = pix;
  }

  @Override public String id() { return "ITAU"; }

  public void failNextIssueWith(ProviderException e) { this.failNextIssue = e; }

  /** The POST reached the bank and issued the boleto, but the caller sees {@code e} (a timeout, a 503, a 202). */
  public void landNextIssueThenFailWith(ProviderException e) { this.landThenFail = e; }

  public void refuseNextIssueCredentials() { this.refuseCredentials = true; }

  public void failNextFindWith(String nossoNumero, ProviderException e) { failFind.put(nossoNumero, e); }

  public void markPaid(String nossoNumero, Money amount, Instant at) {
    BoletoStatus s = boletos.get(nossoNumero);
    boletos.put(nossoNumero, new BoletoStatus(BoletoSituation.PAID, amount, at, "01", s.idBoletoIndividual(), s.linhaDigitavel(), s.codigoBarras(), s.paymentLimitDate(), s.pixCopiaECola()));
  }

  public void setSituation(String nossoNumero, BoletoSituation situation) {
    BoletoStatus s = boletos.get(nossoNumero);
    boletos.put(nossoNumero, new BoletoStatus(situation, s.paidAmount(), s.paidAt(), s.paidChannel(), s.idBoletoIndividual(), s.linhaDigitavel(), s.codigoBarras(), s.paymentLimitDate(), s.pixCopiaECola()));
  }

  public void remove(String nossoNumero) { boletos.remove(nossoNumero); }

  public BoletoStatus status(String nossoNumero) { return boletos.get(nossoNumero); }

  public List<String> callsFor(String key) { return calls.stream().filter(c -> c.endsWith(":" + key)).toList(); }

  @Override public void requireIssueCredentials(ProviderCredentials c) {
    if (refuseCredentials) {
      refuseCredentials = false;
      throw new ProviderException(ProviderException.Code.CREDENTIALS_INCOMPLETE, 0, "beneficiary_id", "ITAU credential is missing beneficiary_id");
    }
  }

  @Override public IssuedBoleto issue(ProviderCredentials c, BoletoIssueRequest r) {
    calls.add("issueBoleto:" + r.nossoNumero());
    ProviderException fail = failNextIssue;
    if (fail != null) {
      failNextIssue = null;
      throw fail;
    }
    String txid = pixTxidFor(c, r.nossoNumero());
    String emv = "00020101021226" + txid;
    String linha = ("3419" + r.nossoNumero()).repeat(5).substring(0, 47);
    String barras = ("3419" + r.nossoNumero()).repeat(4).substring(0, 44);
    boletos.put(r.nossoNumero(), new BoletoStatus(BoletoSituation.OPEN, null, null, null, "uuid-" + r.nossoNumero(), linha, barras, r.paymentLimitDate(), emv));
    pix.register(new Charge(txid, ChargeStatus.ACTIVE, r.amount(), emv, "pix.example/qr/" + txid, clock.instant(), 0, List.of()));
    ProviderException after = landThenFail;
    if (after != null) {
      landThenFail = null;
      throw after;
    }
    return new IssuedBoleto("uuid-" + r.nossoNumero(), linha, barras, r.paymentLimitDate(), txid, emv, "60701190000104");
  }

  @Override public Optional<BoletoStatus> find(ProviderCredentials c, String nossoNumero) {
    calls.add("findBoleto:" + nossoNumero);
    ProviderException fail = failFind.remove(nossoNumero);
    if (fail != null) throw fail;
    return Optional.ofNullable(boletos.get(nossoNumero));
  }

  @Override public void cancel(ProviderCredentials c, String nossoNumero) {
    calls.add("cancelBoleto:" + nossoNumero);
    BoletoStatus s = boletos.get(nossoNumero);
    if (s == null) throw new ProviderException(ProviderException.Code.NOT_FOUND, 404, "404", "not found");
    if (s.paid()) throw new ProviderException(ProviderException.Code.CONFLICT, 422, "422", "Boleto já liquidado");
    setSituation(nossoNumero, BoletoSituation.CANCELED);
  }

  /** Same formula as the Itaú provider: BL + beneficiary without DAC + wallet + number padded to 15. */
  @Override public String pixTxidFor(ProviderCredentials c, String nossoNumero) {
    return "BL" + BENEFICIARY.substring(0, 11) + WALLET + "0".repeat(15 - nossoNumero.length()) + nossoNumero;
  }
}
```

`ServiceTestConfig.java` — acrescente o bean:
```java
  @Bean
  RecordingBoletoProvider recordingBoletoProvider(MutableClock clock, RecordingPixProvider pix) {
    return new RecordingBoletoProvider(clock, pix);
  }
```

`ServiceIntegrationTestBase.java` — acrescente (imports `com.gateway.kernel.provider.boleto.Address`, `com.gateway.kernel.provider.boleto.Payer`):
```java
  @Autowired protected RecordingBoletoProvider boletos;

  protected static Payer payer() {
    return new Payer("Joao da Silva", "12345678901", new Address("Rua das Flores 10", "Centro", "Sao Paulo", "SP", "01310100"));
  }

  protected Payment newBolecode(long cents) {
    return paymentService.createBolecode(
        new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(cents), "order-1", "Pedido 1", payer(), null, null));
  }
```

- [ ] **Step 2: Teste de serviço**

`payment/BolecodeServiceIntegrationTest.java`:
```java
package com.gateway.payments.payment;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.Address;
import com.gateway.kernel.provider.boleto.Payer;
import com.gateway.payments.jobs.JobType;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.boleto.BoletoDates;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BolecodeServiceIntegrationTest extends ServiceIntegrationTestBase {
  @Autowired PaymentRepository payments;
  @Autowired JobRepository jobs;

  @Test
  void createsABolecodeWithBothSidesTwoJobsAndPending() {
    Payment p = newBolecode(12990);

    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(p.method()).isEqualTo(PaymentMethod.BOLECODE);
    assertThat(p.boleto().nossoNumero()).isEqualTo("00000001");
    assertThat(p.boleto().linhaDigitavel()).hasSize(47);
    assertThat(p.boleto().codigoBarras()).hasSize(44);
    LocalDate today = BoletoDates.today(clock);
    assertThat(p.boleto().dueDate()).isEqualTo(today.plusDays(3));
    assertThat(p.boleto().paymentLimitDate()).isEqualTo(today.plusDays(33));
    assertThat(p.boleto().paidVia()).isNull();
    assertThat(p.expiresAt()).isEqualTo(BoletoDates.endOfDay(today.plusDays(33)));
    assertThat(p.pix().txid()).isEqualTo(boletos.pixTxidFor(null, "00000001"));
    assertThat(p.pix().pixCopiaECola()).startsWith("00020101021226BL");
    assertThat(p.customerDocumentHash()).hasSize(64);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
    assertThat(outboxPayload(p.id(), "payment.pending")).contains("\"method\":\"BOLECODE\"").contains("\"linha_digitavel\":").contains("\"paid_via\":null");
    assertThat(jobs.findByTypeAndRef(JobType.EXPIRE_PAYMENT, p.id())).isPresent().get().satisfies(j -> assertThat(j.nextRunAt()).isEqualTo(p.expiresAt().plus(Duration.ofMinutes(5))));
    assertThat(jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id())).isPresent().get().satisfies(j -> assertThat(j.nextRunAt()).isEqualTo(clock.instant().plus(Duration.ofHours(6))));
    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending");
    assertThat(boletos.callsFor("00000001")).containsExactly("issueBoleto:00000001");
    assertThat(jdbc.queryForList("SELECT status FROM payments.provider_requests WHERE payment_id = ? AND operation = 'issueBoleto'", Integer.class, p.id())).containsExactly(200);
  }

  @Test
  void numbersAreSequentialPerMerchantAndEachIsItsOwnTxid() {
    Payment a = newBolecode(100);
    Payment b = newBolecode(200);
    assertThat(a.boleto().nossoNumero()).isEqualTo("00000001");
    assertThat(b.boleto().nossoNumero()).isEqualTo("00000002");
    assertThat(a.pix().txid()).isNotEqualTo(b.pix().txid());
  }

  @Test
  void explicitDueDateAndLimitDaysAreHonoured() {
    LocalDate due = BoletoDates.today(clock).plusDays(10);
    Payment p = paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, payer(), due, 5));
    assertThat(p.boleto().dueDate()).isEqualTo(due);
    assertThat(p.boleto().paymentLimitDate()).isEqualTo(due.plusDays(5));
  }

  @Test
  void dueDateInThePastAndAbsurdLimitAreRefused() {
    LocalDate yesterday = BoletoDates.today(clock).minusDays(1);
    assertThatThrownBy(() -> paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, payer(), yesterday, null)))
        .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("INVALID_DUE_DATE");
    assertThatThrownBy(() -> paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, payer(), null, 3651)))
        .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("INVALID_PAYMENT_LIMIT");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.payments WHERE merchant_id = ?", Long.class, merchant.value())).isZero();
  }

  @Test
  void incompletePayerIsRefusedNamingTheField() {
    Payer noZip = new Payer("Joao", "12345678901", new Address("Rua A", "Centro", "Sao Paulo", "SP", null));
    assertThatThrownBy(() -> paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, noZip, null, null)))
        .isInstanceOfSatisfying(DomainException.class, e -> {
          assertThat(e.code()).isEqualTo("CUSTOMER_REQUIRED");
          assertThat(e.getMessage()).contains("customer.address.zip");
        });
    assertThatThrownBy(() -> paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, null, null, null)))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getMessage()).contains("customer"));
    Payer badDoc = new Payer("Joao", "123", new Address("Rua A", "Centro", "Sao Paulo", "SP", "01310100"));
    assertThatThrownBy(() -> paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, badDoc, null, null)))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getMessage()).contains("customer.document"));
    Payer badState = new Payer("Joao", "12345678901", new Address("Rua A", "Centro", "Sao Paulo", "SPX", "01310100"));
    assertThatThrownBy(() -> paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, badState, null, null)))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.getMessage()).contains("customer.address.state"));
    assertThat(boletos.callsFor("00000001")).isEmpty();
  }

  @Test
  void missingBeneficiaryFailsBeforeAnyRow() {
    boletos.refuseNextIssueCredentials();
    assertThatThrownBy(() -> newBolecode(100)).isInstanceOfSatisfying(DomainException.class, e -> {
      assertThat(e.code()).isEqualTo("PROVIDER_CREDENTIALS_MISSING");
      assertThat(e.getMessage()).contains("beneficiary_id");
    });
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.payments WHERE merchant_id = ?", Long.class, merchant.value())).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.boleto_numbers WHERE merchant_id = ?", Long.class, merchant.value())).isZero();
  }

  @Test
  void declineMarksFailedAndEmits() {
    boletos.failNextIssueWith(new ProviderException(ProviderException.Code.DECLINED, 422, "422", "Vencimento menor que prazo mínimo"));
    assertThatThrownBy(() -> newBolecode(100)).isInstanceOf(DomainException.class).hasMessage("The bank declined the request.")
        .extracting(e -> ((DomainException) e).code()).isEqualTo("PROVIDER_DECLINED");
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(outboxTypes(p.id())).containsExactly("payment.failed");
  }

  /** The POST timed out (or answered 202) but the bank issued the boleto: the query finds it and the payment is adopted, EMV confirmed via GET /cob. */
  @Test
  void timeoutThenQueryAdoptsTheIssuedBoleto() {
    boletos.landNextIssueThenFailWith(new ProviderException(ProviderException.Code.TIMEOUT, 202, "202", "Operação em andamento"));
    Payment p = newBolecode(700);
    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(p.boleto().linhaDigitavel()).hasSize(47);
    assertThat(p.pix().txid()).isEqualTo(boletos.pixTxidFor(null, "00000001"));
    assertThat(p.pix().pixCopiaECola()).startsWith("00020101021226BL");
    assertThat(boletos.callsFor("00000001")).containsExactly("issueBoleto:00000001", "findBoleto:00000001");
    assertThat(bank.callsFor(p.pix().txid())).containsExactly("findCharge:" + p.pix().txid());
    assertThat(jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id())).isPresent();
  }

  /** A timeout and no boleto at the bank: the payment stays CREATED for the stuck-CREATED sweeper (the 202 says the bank may still be working). */
  @Test
  void timeoutWithNothingAtTheBankLeavesCreatedForTheSweeper() {
    boletos.failNextIssueWith(new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    assertThatThrownBy(() -> newBolecode(700)).isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("PROVIDER_TIMEOUT");
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    assertThat(outboxTypes(p.id())).isEmpty();
    assertThat(boletos.callsFor("00000001")).containsExactly("issueBoleto:00000001", "findBoleto:00000001");
  }

  @Test
  void unavailableThatLandedIsAdoptedLikeATimeout() {
    boletos.landNextIssueThenFailWith(new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "proxy said 503"));
    Payment p = newBolecode(700);
    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void missingCredentialsFailsBeforeCallingTheBank() {
    assertThatThrownBy(() -> paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.LIVE, Money.brl(100), null, null, payer(), null, null)))
        .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("PROVIDER_CREDENTIALS_MISSING");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.payments WHERE merchant_id = ?", Long.class, merchant.value())).isZero();
  }
}
```

- [ ] **Step 3: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -Dtest=BolecodeServiceIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compilação (`CreateBolecode`, `Resolved.boleto()`).

- [ ] **Step 4: Implementar**

`ProviderGateway.java` — `Resolved`, construtor e `resolve`:
```java
  /** {@code boleto} is empty for a provider without a boleto product (none today, but the contract allows it). */
  public record Resolved(PixProvider provider, Optional<BoletoProvider> boleto, ProviderCredentials credentials) {}

  private final List<PixProvider> providers;
  private final List<BoletoProvider> boletoProviders;
  private final CredentialLookup credentials;
  private final ProviderRequestRepository requests;

  public ProviderGateway(List<PixProvider> providers, List<BoletoProvider> boletoProviders, CredentialLookup credentials, ProviderRequestRepository requests) {
    this.providers = providers;
    this.boletoProviders = boletoProviders;
    this.credentials = credentials;
    this.requests = requests;
  }

  public Resolved resolve(MerchantId merchantId, ProviderEnvironment env, String provider) {
    PixProvider p = provider(provider);
    Optional<BoletoProvider> b = boletoProviders.stream().filter(x -> x.id().equalsIgnoreCase(provider)).findFirst();
    ProviderCredentials c =
        credentials
            .find(merchantId, provider, env)
            .orElseThrow(() -> new DomainException("PROVIDER_CREDENTIALS_MISSING", "no " + provider + " " + env + " credentials for this merchant"));
    return new Resolved(p, b, c);
  }
```
(imports `com.gateway.kernel.provider.boleto.BoletoProvider`, `java.util.Optional`; e acrescente `"issueBoleto"` **não** a `CREATING` — a emissão responde 200, não 201.)

`PaymentsConfiguration.java` — bean do gateway (import `org.springframework.beans.factory.ObjectProvider` e `com.gateway.kernel.provider.boleto.BoletoProvider`):
```java
  /** ObjectProvider: a context without any BoletoProvider (the payments tests before Task 9's support existed) must still start. */
  @Bean
  ProviderGateway providerGateway(List<PixProvider> providers, ObjectProvider<BoletoProvider> boletoProviders, CredentialLookup credentials, ProviderRequestRepository requests) {
    return new ProviderGateway(providers, boletoProviders.orderedStream().toList(), credentials, requests);
  }
```
e o bean do `PaymentService` ganha `BoletoNumberRepository boletoNumbers` (import `com.gateway.payments.payment.boleto.persistence.BoletoNumberRepository`):
```java
  @Bean
  PaymentService paymentService(
      PaymentRepository payments, ReconciliationDivergenceRepository divergences, JobRepository jobs, BoletoNumberRepository boletoNumbers,
      ProviderGateway providers, PaymentEvents events, PaymentsProperties props, TransactionTemplate paymentsTransactionTemplate, Clock clock) {
    return new PaymentService(payments, divergences, jobs, boletoNumbers, providers, events, props, paymentsTransactionTemplate, clock);
  }
```

`PaymentsProperties.java` — substitua o record:
```java
package com.gateway.payments;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables of the payments module. Every field has a default so the module runs with no
 * {@code gateway.payments.*} keys at all — a missing key must not turn into a zero-second expiry or
 * a zero-length lease.
 */
@ConfigurationProperties("gateway.payments")
public record PaymentsProperties(
    int defaultExpiresInSeconds,
    Duration expirationGrace,
    Duration reconciliationLookback,
    Duration reconciliationMinAge,
    Duration idempotencyTtl,
    int jobMaxAttempts,
    Duration jobLease,
    Duration outboxLease,
    Duration stuckCreatedAfter,
    int refundPollMaxAttempts,
    Duration refundNotFoundGrace,
    Duration reconcileLease,
    Duration boletoPollEvery,
    int boletoPollMaxAttempts,
    Duration boletoPollGraceAfterLimit,
    int boletoDefaultDueInDays,
    int boletoDefaultPaymentLimitDays,
    int boletoMaxPaymentLimitDays) {

  public PaymentsProperties {
    if (defaultExpiresInSeconds <= 0) defaultExpiresInSeconds = 3600;
    if (expirationGrace == null) expirationGrace = Duration.ofMinutes(5);
    if (reconciliationLookback == null) reconciliationLookback = Duration.ofHours(48);
    if (reconciliationMinAge == null) reconciliationMinAge = Duration.ofMinutes(10);
    if (idempotencyTtl == null) idempotencyTtl = Duration.ofHours(24);
    if (jobMaxAttempts <= 0) jobMaxAttempts = 8;
    if (jobLease == null) jobLease = Duration.ofMinutes(2);
    if (outboxLease == null) outboxLease = Duration.ofMinutes(1);
    // Well above the bank's recommended 30 s client timeout: a CREATED younger than this may still
    // have its createCharge call in flight.
    if (stuckCreatedAfter == null) stuckCreatedAfter = Duration.ofMinutes(10);
    // 288 polls x 5 min = 24 h, the bank's own horizon for settling a devolucao.
    if (refundPollMaxAttempts <= 0) refundPollMaxAttempts = 288;
    // A refund PUT that timed out or got a 503 may or may not have landed. The bank answers GET
    // /devolucao within seconds of accepting one, so 30 min of "not found" means it never landed.
    if (refundNotFoundGrace == null) refundNotFoundGrace = Duration.ofMinutes(30);
    // RECONCILE walks up to 1000 payments with a bank call per merchant, each with a 30 s timeout;
    // the 2 min jobLease let a second worker reclaim a run still in progress.
    if (reconcileLease == null) reconcileLease = Duration.ofMinutes(10);
    // A barcode payment clears in D+1; six hours keeps the merchant within the same business day
    // without hammering an API that charges per query (spec 2026-09-25 §7, §10).
    if (boletoPollEvery == null) boletoPollEvery = Duration.ofHours(6);
    // 10 years (the bank's maximum limit date) / 6 h = 14 610 polls; the date check in the service
    // is what actually stops a poll, this cap only keeps a runaway job from living forever.
    if (boletoPollMaxAttempts <= 0) boletoPollMaxAttempts = 15000;
    // Two days after the limit date: a payment made at the last minute of the last day is credited
    // by the bank on the next business day.
    if (boletoPollGraceAfterLimit == null) boletoPollGraceAfterLimit = Duration.ofDays(2);
    if (boletoDefaultDueInDays <= 0) boletoDefaultDueInDays = 3;
    if (boletoDefaultPaymentLimitDays <= 0) boletoDefaultPaymentLimitDays = 30;
    if (boletoMaxPaymentLimitDays <= 0) boletoMaxPaymentLimitDays = 3650;
  }

  public static PaymentsProperties defaults() {
    return new PaymentsProperties(0, null, null, null, null, 0, null, null, null, 0, null, null, null, 0, null, 0, 0, 0);
  }
}
```

`PaymentService.java` — acrescente (imports: `com.gateway.kernel.provider.boleto.*`, `com.gateway.payments.payment.boleto.BoletoDates`, `com.gateway.payments.payment.boleto.BoletoDetails`, `com.gateway.payments.payment.boleto.persistence.BoletoNumberRepository`, `java.time.LocalDate`, `java.util.regex.Pattern`):

1. Campo e construtor: `private final BoletoNumberRepository boletoNumbers;` como 4.º parâmetro (depois de `jobs`).
2. Comando e validação:
```java
  public record CreateBolecode(
      MerchantId merchantId, ProviderEnvironment env, Money amount, String reference, String description, Payer payer, LocalDate dueDate, Integer paymentLimitDays) {}

  private static final Pattern DIGITS_11_OR_14 = Pattern.compile("\\d{11}|\\d{14}");
  private static final Pattern UF = Pattern.compile("[A-Z]{2}");
  private static final Pattern CEP = Pattern.compile("\\d{8}");
  private static final Pattern HAS_LETTER = Pattern.compile(".*\\p{L}.*");

  /**
   * A registered boleto needs a complete payer (issue OpenAPI: pessoa and endereco required, every
   * address line required). Checked here, not at the edge: the 422 names the field in the API's own
   * spelling and no row exists yet. Returns the payer with digits-only document and zip.
   */
  static Payer validatePayer(Payer payer) {
    if (payer == null) throw new DomainException("CUSTOMER_REQUIRED", "customer is required for a BOLECODE payment");
    if (payer.name() == null || !HAS_LETTER.matcher(payer.name()).matches()) throw new DomainException("CUSTOMER_REQUIRED", "customer.name is required");
    String document = payer.document() == null ? "" : payer.document().replaceAll("\\D", "");
    if (!DIGITS_11_OR_14.matcher(document).matches()) throw new DomainException("CUSTOMER_REQUIRED", "customer.document must be a CPF (11 digits) or CNPJ (14 digits)");
    Address a = payer.address();
    if (a == null) throw new DomainException("CUSTOMER_REQUIRED", "customer.address is required");
    if (a.street() == null || a.street().isBlank()) throw new DomainException("CUSTOMER_REQUIRED", "customer.address.street is required");
    if (a.district() == null || a.district().isBlank()) throw new DomainException("CUSTOMER_REQUIRED", "customer.address.district is required");
    if (a.city() == null || a.city().isBlank()) throw new DomainException("CUSTOMER_REQUIRED", "customer.address.city is required");
    if (a.state() == null || !UF.matcher(a.state()).matches()) throw new DomainException("CUSTOMER_REQUIRED", "customer.address.state must be a two-letter UF");
    String zip = a.zip() == null ? "" : a.zip().replaceAll("\\D", "");
    if (!CEP.matcher(zip).matches()) throw new DomainException("CUSTOMER_REQUIRED", "customer.address.zip must be 8 digits");
    return new Payer(payer.name(), document, new Address(a.street(), a.district(), a.city(), a.state(), zip));
  }
```
3. O fluxo de criação:
```java
  /**
   * Spec 2026-09-25 §7. Order matters: credentials (incl. beneficiary) and payer are checked before
   * a row exists; the number is reserved in the same transaction as CREATED; the bank call runs
   * outside any transaction; PENDING lands with both sides and both jobs.
   *
   * <p>On TIMEOUT/UNAVAILABLE/202 the query decides: found → adopted; not found → the payment stays
   * CREATED and the caller gets PROVIDER_TIMEOUT. Unlike Pix, it is NOT failed on the spot: the
   * bank's 202 means "operação em andamento", so an empty query a second later proves nothing.
   * ExpirationService.sweepStuckCreated asks again after stuckCreatedAfter and decides.
   */
  public Payment createBolecode(CreateBolecode cmd) {
    ProviderGateway.Resolved r = providers.resolve(cmd.merchantId(), cmd.env(), PROVIDER);
    BoletoProvider boleto = r.boleto().orElseThrow(() -> new DomainException("METHOD_NOT_SUPPORTED", PROVIDER + " has no boleto product"));
    try {
      boleto.requireIssueCredentials(r.credentials());
    } catch (ProviderException e) {
      if (e.code() == ProviderException.Code.CREDENTIALS_INCOMPLETE) {
        throw new DomainException("PROVIDER_CREDENTIALS_MISSING", "the " + PROVIDER + " " + cmd.env() + " credential is missing " + e.providerType());
      }
      throw e;
    }
    Payer payer = validatePayer(cmd.payer());
    LocalDate today = BoletoDates.today(clock);
    LocalDate due = cmd.dueDate() == null ? today.plusDays(props.boletoDefaultDueInDays()) : cmd.dueDate();
    if (due.isBefore(today)) throw new DomainException("INVALID_DUE_DATE", "due_date must be today or later (America/Sao_Paulo)");
    int limitDays = cmd.paymentLimitDays() == null ? props.boletoDefaultPaymentLimitDays() : cmd.paymentLimitDays();
    if (limitDays < 0 || limitDays > props.boletoMaxPaymentLimitDays()) {
      throw new DomainException("INVALID_PAYMENT_LIMIT", "payment_limit_days must be between 0 and " + props.boletoMaxPaymentLimitDays());
    }
    LocalDate limit = due.plusDays(limitDays);

    Payment payment =
        tx.execute(s -> {
          String nossoNumero = boletoNumbers.next(cmd.merchantId());
          BoletoDetails details = new BoletoDetails(nossoNumero, null, null, null, due, limit, null);
          Payment p = Payment.createBolecode(cmd.merchantId(), cmd.env(), PROVIDER, cmd.amount(), cmd.reference(), cmd.description(),
              hashDocument(payer.document()), details, BoletoDates.endOfDay(limit), clock);
          return payments.save(p, List.of(p.createdEvent()));
        });
    String nossoNumero = payment.boleto().nossoNumero();
    BoletoIssueRequest request = new BoletoIssueRequest(nossoNumero, cmd.amount(), due, limit, payer, cmd.description());

    IssuedBoleto issued;
    try {
      issued = providers.call(payment.id(), "issueBoleto", r, x -> boleto.issue(x.credentials(), request));
    } catch (ProviderException e) {
      boolean mayHaveLanded = e.code() == ProviderException.Code.TIMEOUT || e.code() == ProviderException.Code.UNAVAILABLE;
      if (!mayHaveLanded) {
        boolean declined = e.code() == ProviderException.Code.INVALID || e.code() == ProviderException.Code.DECLINED;
        throw fail(payment.id(), declined ? "PROVIDER_DECLINED" : "PROVIDER_UNAVAILABLE", e, null);
      }
      String code = e.code() == ProviderException.Code.TIMEOUT ? "PROVIDER_TIMEOUT" : "PROVIDER_UNAVAILABLE";
      Optional<BoletoStatus> existing;
      try {
        existing = providers.call(payment.id(), "findBoleto", r, x -> boleto.find(x.credentials(), nossoNumero));
      } catch (ProviderException again) {
        throw ProviderErrors.toDomain(code, again, log, "issueBoleto", payment.id());
      }
      if (existing.isEmpty()) {
        log.warn("boleto {} for payment {} not at the bank after {}; left CREATED for the sweeper", nossoNumero, payment.id(), e.code());
        throw ProviderErrors.toDomain(code, e, log, "issueBoleto", payment.id());
      }
      return adoptBolecodeFromStatus(payment.id(), r, existing.get(), EventSource.API);
    }
    return adoptPendingBolecode(payment.id(), issued, EventSource.API);
  }

  /** CREATED -> PENDING with both sides, the expire job (limit date's end of day + grace) and the poll job (+boletoPollEvery), plus the outbox row. */
  Payment adoptPendingBolecode(String paymentId, IssuedBoleto issued, EventSource by) {
    return tx.execute(s -> {
      Payment p = payments.findById(paymentId).orElseThrow();
      if (p.status() != PaymentStatus.CREATED) {
        return p; // the sweeper and a slow create may race to adopt the same boleto; the loser must not fail
      }
      BoletoDetails details = p.boleto().withIssued(issued.idBoletoIndividual(), issued.linhaDigitavel(), issued.codigoBarras(), issued.paymentLimitDate());
      PixDetails pix = new PixDetails(issued.pixTxid(), issued.pixCopiaECola(), null, null);
      Payment saved = payments.save(p, List.of(p.markPendingBolecode(pix, details, BoletoDates.endOfDay(details.paymentLimitDate()), by)));
      if (!jobs.enqueue(Job.expireAt(saved.id(), saved.expiresAt().plus(props.expirationGrace()), clock))) {
        log.debug("expire job for payment {} was already queued", saved.id());
      }
      if (!jobs.enqueue(Job.pollBoleto(saved.id(), clock.instant().plus(props.boletoPollEvery()), clock))) {
        log.debug("poll job for payment {} was already queued", saved.id());
      }
      events.emit(saved.merchantId(), "payment.pending", saved);
      return saved;
    });
  }

  /**
   * The issue's answer was lost; the query has the boleto's identity but not the Pix side. The txid
   * follows the bank's documented formula (BoletoProvider.pixTxidFor) and is confirmed with
   * GET /cob/{txid}, which also yields the EMV; if the bank does not know that txid, the query's
   * own qrcode_pix.emv is used and a divergence records that the formula did not match.
   */
  Payment adoptBolecodeFromStatus(String paymentId, ProviderGateway.Resolved r, BoletoStatus status, EventSource by) {
    Payment p = payments.findById(paymentId).orElseThrow();
    String nossoNumero = p.boleto().nossoNumero();
    String txid = r.boleto().orElseThrow().pixTxidFor(r.credentials(), nossoNumero);
    Optional<Charge> charge = providers.call(paymentId, "findCharge", r, x -> x.provider().findCharge(x.credentials(), txid));
    String emv = charge.map(Charge::pixCopiaECola).orElse(status.pixCopiaECola());
    Payment adopted = adoptPendingBolecode(paymentId,
        new IssuedBoleto(status.idBoletoIndividual(), status.linhaDigitavel(), status.codigoBarras(), status.paymentLimitDate(), txid, emv, null), by);
    if (charge.isEmpty()) {
      tx.executeWithoutResult(s -> openDivergence(adopted, "PIX_TXID_UNCONFIRMED", "GET /cob/" + txid + " empty while adopting boleto " + nossoNumero + " from the query"));
    }
    return adopted;
  }
```
`fail(...)` já existe (com `r == null` não tenta cancelar nada — um `DECLINED` de emissão não deixou boleto).

- [ ] **Step 5: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments test` (módulo inteiro: os construtores de `ProviderGateway` e `PaymentService` mudaram).
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add gateway-payments
git commit -m "feat(payments): create a bolecode: reserve the number, issue outside the transaction, adopt on timeout

Credentials (including the beneficiary) and the payer are checked
before any row exists; the number is reserved with CREATED. A lost
answer is resolved by the query, with the Pix txid rebuilt by the
bank's formula and confirmed against GET /cob before the merchant
sees it. Nothing at the bank after a timeout leaves the payment
CREATED for the sweeper: the bank's 202 means it may still be working.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 10: `BoletoPollingService` — cada linha da tabela de situações, divergências, job `POLL_BOLETO` no `JobRunner`

**Files:**
- Create: `gateway-payments/src/main/java/com/gateway/payments/payment/boleto/BoletoPollingService.java`
- Modify: `gateway-payments/src/main/java/com/gateway/payments/payment/PaymentService.java` (+`settleBoleto`), `gateway-payments/src/main/java/com/gateway/payments/jobs/JobRunner.java`, `gateway-payments/src/main/java/com/gateway/payments/PaymentsConfiguration.java`
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/boleto/BoletoPollingIntegrationTest.java`

**Interfaces:**
- Consumes: `PaymentService.openDivergence(Payment, String, String)`, `Payment.markCompletedByBoleto`, `Payment.recordIgnored`, `BoletoStatus.paid()`, `PaymentsProperties.boleto*` (Task 9).
- Produces:
  ```java
  PaymentService += public Settlement settleBoleto(MerchantId merchantId, String paymentId, BoletoStatus status, EventSource by)
     // PENDING|EXPIRED: amount == charge -> COMPLETED (markCompletedByBoleto, payment.completed); amount differs -> "ignored" + AMOUNT_MISMATCH, IGNORED
     // COMPLETED via PIX -> "ignored" + DOUBLE_PAYMENT; COMPLETED via BOLETO -> "ignored" (duplicate); FAILED|CANCELED -> "ignored" + BOLETO_PAID; CREATED -> IllegalStateException
  public class BoletoPollingService {
    public BoletoPollingService(PaymentRepository payments, ProviderGateway providers, PaymentService paymentService, PaymentsProperties props, TransactionTemplate tx, Clock clock);
    public boolean check(String paymentId, EventSource by);   // true = the job is finished; false = look again later
  }
  JobRunner(…, RefundPollingService polling, BoletoPollingService boletoPolling, RefundService refunds, …)   // new 5th parameter
     // POLL_BOLETO: done -> DONE; not yet -> +boletoPollEvery; failure -> min(backoff(attempts), boletoPollEvery); DEAD after boletoPollMaxAttempts
  ```
  Tabela (spec §7) → `check`:

  | bank says | payment | effect | returns |
  |---|---|---|---|
  | OPEN, AWAITING_CREDIT | PENDING/EXPIRED | nothing | `pastWindow` (true after limit date + `boletoPollGraceAfterLimit`) |
  | PAID, SETTLED, CREDITED | PENDING/EXPIRED | `settleBoleto` → COMPLETED `paidVia=BOLETO`, or AMOUNT_MISMATCH | true |
  | PAYMENT_REJECTED | PENDING/EXPIRED | divergence BOLETO_REJECTED, stays | `pastWindow` |
  | CANCELED | PENDING/EXPIRED | divergence CANCELED_AT_BANK, stays | true |
  | (empty) | PENDING/EXPIRED | "ignored" event; on the 2nd consecutive empty answer divergence NOT_FOUND_AT_BANK | `pastWindow` |
  | paid | COMPLETED via PIX | `settleBoleto` → DOUBLE_PAYMENT | true |
  | anything else | COMPLETED | "ignored" event | true |
  | — | CANCELED, FAILED, CREATED | nothing (CREATED belongs to the sweeper) | true |

- [ ] **Step 1: Teste**

`payment/boleto/BoletoPollingIntegrationTest.java`:
```java
package com.gateway.payments.payment.boleto;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import com.gateway.payments.jobs.JobRunner;
import com.gateway.payments.jobs.JobType;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BoletoPollingIntegrationTest extends ServiceIntegrationTestBase {
  @Autowired BoletoPollingService polling;
  @Autowired PaymentRepository payments;
  @Autowired JobRepository jobs;
  @Autowired JobRunner jobRunner;

  private String nn(Payment p) { return p.boleto().nossoNumero(); }

  private List<Map<String, Object>> divergences(String paymentId) {
    return jdbc.queryForList("SELECT provider_status, status FROM payments.reconciliation_divergences WHERE payment_id = ? ORDER BY created_at", paymentId);
  }

  @Test
  void openAndAwaitingCreditKeepPolling() {
    Payment p = newBolecode(12990);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    boletos.setSituation(nn(p), BoletoSituation.AWAITING_CREDIT);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(divergences(p.id())).isEmpty();
  }

  @Test
  void paidSettledAndCreditedCompleteWithPaidViaBoleto() {
    for (BoletoSituation s : List.of(BoletoSituation.PAID, BoletoSituation.SETTLED, BoletoSituation.CREDITED)) {
      Payment p = newBolecode(12990);
      Instant paidAt = clock.instant().minus(Duration.ofHours(1));
      boletos.markPaid(nn(p), Money.brl(12990), paidAt);
      boletos.setSituation(nn(p), s);

      assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).as("%s", s).isTrue();

      Payment done = payments.findById(p.id()).orElseThrow();
      assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
      assertThat(done.boleto().paidVia()).isEqualTo(PaidVia.BOLETO);
      assertThat(done.paidAmount()).isEqualTo(Money.brl(12990));
      assertThat(done.paidAt()).isEqualTo(paidAt);
      assertThat(done.pix().endToEndId()).isNull();
      assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
      assertThat(outboxPayload(p.id(), "payment.completed")).contains("\"paid_via\":\"BOLETO\"").contains("\"status\":\"COMPLETED\"");
      assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending", "completed");
      assertThat(payments.events(p.id()).getLast().source()).isEqualTo(EventSource.PROVIDER_POLL);
      assertThat(payments.events(p.id()).getLast().payload()).contains("\"paidVia\":\"BOLETO\"");
    }
  }

  @Test
  void differentAmountIsADivergenceNotACompletion() {
    Payment p = newBolecode(12990);
    boletos.markPaid(nn(p), Money.brl(12000), clock.instant());
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    Payment still = payments.findById(p.id()).orElseThrow();
    assertThat(still.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("AMOUNT_MISMATCH");
    assertThat(payments.events(p.id()).getLast().type()).isEqualTo("ignored");
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
  }

  @Test
  void rejectedOpensADivergenceAndKeepsPolling() {
    Payment p = newBolecode(100);
    boletos.setSituation(nn(p), BoletoSituation.PAYMENT_REJECTED);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("BOLETO_REJECTED");
    // idempotent: a second look does not open a second row
    polling.check(p.id(), EventSource.PROVIDER_POLL);
    assertThat(divergences(p.id())).hasSize(1);
  }

  @Test
  void canceledAtTheBankIsADivergenceAndStopsPolling() {
    Payment p = newBolecode(100);
    boletos.setSituation(nn(p), BoletoSituation.CANCELED);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("CANCELED_AT_BANK");
  }

  @Test
  void twoEmptyAnswersOpenNotFoundAtBank() {
    Payment p = newBolecode(100);
    boletos.remove(nn(p));
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    assertThat(divergences(p.id())).isEmpty();
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("NOT_FOUND_AT_BANK");
    assertThat(payments.events(p.id()).stream().filter(e -> "ignored".equals(e.type())).count()).isEqualTo(2);
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void completedViaPixThenPaidByBoletoIsADoublePayment() {
    Payment p = newBolecode(12990);
    bank.markPaid(p.pix().txid(), "E2E-QR", Money.brl(12990));
    assertThat(paymentService.settle(merchant, p.id(), bank.findCharge(null, p.pix().txid()).orElseThrow().firstPix().orElseThrow(), EventSource.PROVIDER_WEBHOOK))
        .isEqualTo(PaymentService.Settlement.COMPLETED);
    assertThat(payments.findById(p.id()).orElseThrow().boleto().paidVia()).isEqualTo(PaidVia.PIX);

    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();

    Payment still = payments.findById(p.id()).orElseThrow();
    assertThat(still.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(still.boleto().paidVia()).isEqualTo(PaidVia.PIX);
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("DOUBLE_PAYMENT");
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
  }

  @Test
  void completedViaPixWithTheBoletoStillOpenIsIgnored() {
    Payment p = newBolecode(12990);
    bank.markPaid(p.pix().txid(), "E2E-QR2", Money.brl(12990));
    paymentService.settle(merchant, p.id(), bank.findCharge(null, p.pix().txid()).orElseThrow().firstPix().orElseThrow(), EventSource.PROVIDER_WEBHOOK);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertThat(payments.events(p.id()).getLast().type()).isEqualTo("ignored");
    assertThat(divergences(p.id())).isEmpty();
  }

  @Test
  void stopsAfterTheLimitDatePlusTheGrace() {
    Payment p = paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, payer(), BoletoDates.today(clock), 0));
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    clock.advance(Duration.ofDays(3));
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void theRunnerPollsAndReschedulesSixHoursLater() {
    Payment p = newBolecode(100);
    Instant later = clock.instant().plus(Duration.ofHours(6)).plusSeconds(1);
    // The job is the only due one for this payment; other tests' jobs are not due at `later` unless they are, so filter by ref.
    assertThat(jobRunner.runDue(later)).isGreaterThanOrEqualTo(1);
    var job = jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id()).orElseThrow();
    assertThat(job.status()).isEqualTo("PENDING");
    assertThat(job.attempts()).isEqualTo(1);
    assertThat(job.nextRunAt()).isEqualTo(later.plus(Duration.ofHours(6)));
    assertThat(boletos.callsFor(nn(p))).contains("findBoleto:" + nn(p));

    boletos.markPaid(nn(p), Money.brl(100), clock.instant());
    jdbc.update("UPDATE payments.jobs SET next_run_at = ? WHERE type = 'POLL_BOLETO' AND ref_id = ?", Timestamp.from(later), p.id());
    jobRunner.runDue(later);
    assertThat(jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id()).orElseThrow().status()).isEqualTo("DONE");
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  void aBankFailureBacksOffButNeverBeyondThePollPeriod() {
    Payment p = newBolecode(100);
    boletos.failNextFindWith(nn(p), new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "down"));
    Instant later = clock.instant().plus(Duration.ofHours(6)).plusSeconds(1);
    jobRunner.runDue(later);
    var job = jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id()).orElseThrow();
    assertThat(job.status()).isEqualTo("PENDING");
    assertThat(job.nextRunAt()).isEqualTo(later.plus(Duration.ofMinutes(1)));
    assertThat(job.lastError()).contains("UNAVAILABLE");
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -Dtest=BoletoPollingIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compilação (`BoletoPollingService`).

- [ ] **Step 3: Implementar**

`PaymentService.java` — acrescente (imports `com.gateway.payments.payment.boleto.PaidVia`, `com.gateway.kernel.provider.boleto.BoletoStatus`):
```java
  /**
   * The bank's boleto query says "paid" for {@code paymentId}, from whichever path saw it first
   * (poll, expiration's pre-check, reconciliation, a cancel that lost to the payer). The same rules
   * as {@link #settle} for Pix: PENDING or EXPIRED completes, with the bank's amount and date and
   * {@code paidVia = BOLETO}; a different amount is a divergence, never a completion; a payment
   * already COMPLETED via Pix means the payer paid twice (DOUBLE_PAYMENT) — a human decides.
   */
  public Settlement settleBoleto(MerchantId merchantId, String paymentId, BoletoStatus status, EventSource by) {
    return tx.execute(s -> {
      Optional<Payment> found = payments.findByMerchantAndId(merchantId, paymentId);
      if (found.isEmpty()) {
        return Settlement.UNKNOWN_PAYMENT;
      }
      Payment p = found.get();
      if (p.method() != PaymentMethod.BOLECODE || p.boleto() == null) {
        throw new IllegalStateException("settleBoleto on a " + p.method() + " payment " + paymentId);
      }
      String nn = p.boleto().nossoNumero();
      long paidCents = status.paidAmount() == null ? -1 : status.paidAmount().cents();
      if (p.status() == PaymentStatus.PENDING || p.status() == PaymentStatus.EXPIRED) {
        if (paidCents != p.amount().cents()) {
          payments.save(p, List.of(p.recordIgnored("boleto " + nn + " paid " + paidCents + " cents, charge is " + p.amount().cents(), by).orElseThrow()));
          openDivergence(p, "AMOUNT_MISMATCH", "boleto " + nn + " paid " + paidCents + " cents at the bank, charge is " + p.amount().cents());
          return Settlement.IGNORED;
        }
        Instant paidAt = status.paidAt() == null ? clock.instant() : status.paidAt();
        Payment saved = payments.save(p, List.of(p.markCompletedByBoleto(status.paidAmount(), paidAt, status.paidChannel(), by)));
        events.emit(saved.merchantId(), "payment.completed", saved);
        return Settlement.COMPLETED;
      }
      if (p.status() == PaymentStatus.COMPLETED) {
        if (p.boleto().paidVia() == PaidVia.BOLETO) {
          payments.save(p, List.of(p.recordIgnored("boleto " + nn + " already settled", by).orElseThrow()));
          return Settlement.IGNORED;
        }
        payments.save(p, List.of(p.recordIgnored("boleto " + nn + " paid at the bank on a payment completed via PIX", by).orElseThrow()));
        openDivergence(p, "DOUBLE_PAYMENT", "paid via PIX (e2eid " + (p.pix() == null ? null : p.pix().endToEndId()) + ") and boleto " + nn + " paid " + paidCents + " cents");
        return Settlement.IGNORED;
      }
      if (p.status().terminal()) {
        // Money arrived for a charge the merchant will never hear about again (FAILED/CANCELED).
        payments.save(p, List.of(p.recordIgnored("boleto " + nn + " paid at the bank while " + p.status(), by).orElseThrow()));
        openDivergence(p, "BOLETO_PAID", "boleto " + nn + " paid " + paidCents + " cents at the bank while " + p.status());
        return Settlement.IGNORED;
      }
      throw new IllegalStateException("boleto settlement for payment " + paymentId + " still in " + p.status());
    });
  }
```
(import `java.time.Instant` se ainda não existir.)

`payment/boleto/BoletoPollingService.java`:
```java
package com.gateway.payments.payment.boleto;

import com.gateway.kernel.provider.boleto.BoletoProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentMethod;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderGateway;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The barcode side of a Bolecode has no webhook (spec 2026-09-25 §8): this is how the gateway
 * learns that a boleto was paid. One decision table (spec §7) shared by the POLL_BOLETO job
 * (PROVIDER_POLL) and reconciliation (RECONCILIATION); the bank call runs outside any transaction.
 * {@link #check} answers "is this job finished?", not "did anything change": the runner reschedules
 * a {@code false} every {@code boletoPollEvery} until the limit date plus a grace.
 */
public class BoletoPollingService {
  private static final Logger log = LoggerFactory.getLogger(BoletoPollingService.class);
  static final String NOT_FOUND_MARK = "boleto not found at the bank";

  private final PaymentRepository payments;
  private final ProviderGateway providers;
  private final PaymentService paymentService;
  private final PaymentsProperties props;
  private final TransactionTemplate tx;
  private final Clock clock;

  public BoletoPollingService(
      PaymentRepository payments, ProviderGateway providers, PaymentService paymentService, PaymentsProperties props, TransactionTemplate tx, Clock clock) {
    this.payments = payments;
    this.providers = providers;
    this.paymentService = paymentService;
    this.props = props;
    this.tx = tx;
    this.clock = clock;
  }

  public boolean check(String paymentId, EventSource by) {
    Payment p = payments.findById(paymentId).orElse(null);
    if (p == null || p.method() != PaymentMethod.BOLECODE || p.boleto() == null) {
      return true;
    }
    if (p.status() == PaymentStatus.CANCELED || p.status() == PaymentStatus.FAILED || p.status() == PaymentStatus.CREATED) {
      return true; // CREATED is the stuck-CREATED sweeper's business, not the poll's
    }
    ProviderGateway.Resolved r = providers.resolve(p.merchantId(), p.environment(), p.provider());
    BoletoProvider boleto = r.boleto().orElseThrow();
    String nn = p.boleto().nossoNumero();
    Optional<BoletoStatus> atBank = providers.call(p.id(), "findBoleto", r, x -> boleto.find(x.credentials(), nn));
    if (atBank.isEmpty()) {
      return notFound(p, nn, by);
    }
    BoletoStatus status = atBank.get();
    if (p.status() == PaymentStatus.COMPLETED) {
      if (status.paid()) {
        paymentService.settleBoleto(p.merchantId(), p.id(), status, by); // duplicate or DOUBLE_PAYMENT, decided there
      } else {
        record(p.id(), "poll after completion: bank says " + status.situation(), by);
      }
      return true;
    }
    return switch (status.situation()) {
      case OPEN, AWAITING_CREDIT -> pastWindow(p);
      case PAID, SETTLED, CREDITED -> {
        paymentService.settleBoleto(p.merchantId(), p.id(), status, by);
        yield true;
      }
      case PAYMENT_REJECTED -> {
        // The bank refused a payment attempt (wrong amount, closed account, ...); the boleto is still open for the payer.
        paymentService.openDivergence(p, "BOLETO_REJECTED", "bank rejected a payment of boleto " + nn + "; still open for the payer");
        yield pastWindow(p);
      }
      case CANCELED -> {
        // Someone did a baixa outside the gateway (bankline, another system). The state is not moved: the merchant did not ask for it.
        paymentService.openDivergence(p, "CANCELED_AT_BANK", "boleto " + nn + " baixado at the bank while the gateway has " + p.status());
        yield true;
      }
    };
  }

  /**
   * An empty answer once is the bank still processing (its 202); twice in a row is a boleto that
   * does not exist there. The count is the "ignored" events carrying the mark, so it survives
   * restarts and a second worker.
   */
  private boolean notFound(Payment p, String nn, EventSource by) {
    long previous = payments.events(p.id()).stream().filter(e -> "ignored".equals(e.type()) && e.payload().contains(NOT_FOUND_MARK)).count();
    record(p.id(), NOT_FOUND_MARK + ": " + nn, by);
    if (previous + 1 >= 2) {
      paymentService.openDivergence(p, "NOT_FOUND_AT_BANK", "boleto " + nn + " unknown to the bank on " + (previous + 1) + " consecutive polls");
    } else {
      log.info("boleto {} of payment {} not at the bank yet", nn, p.id());
    }
    return pastWindow(p);
  }

  private void record(String paymentId, String what, EventSource by) {
    tx.executeWithoutResult(s -> {
      Payment loaded = payments.findById(paymentId).orElseThrow();
      payments.save(loaded, List.of(loaded.recordIgnored(what, by).orElseThrow()));
    });
  }

  /** Two days past the limit date's end (São Paulo): a last-minute payment is credited on the next business day. */
  private boolean pastWindow(Payment p) {
    Instant end = BoletoDates.endOfDay(p.boleto().paymentLimitDate()).plus(props.boletoPollGraceAfterLimit());
    return clock.instant().isAfter(end);
  }
}
```

`JobRunner.java`:
- campo `private final BoletoPollingService boletoPolling;` e o construtor ganha `BoletoPollingService boletoPolling` logo depois de `RefundPollingService polling` (import `com.gateway.payments.payment.boleto.BoletoPollingService`, `com.gateway.payments.payment.EventSource`).
- em `runDue`: `next = done ? job.done() : retry(job, now, "not settled yet", false);` e no `catch`: `next = retry(job, now, truncate(e.getClass().getSimpleName() + ": " + e.getMessage()), true);`
- em `run`: `case POLL_BOLETO -> boletoPolling.check(job.refId(), EventSource.PROVIDER_POLL);`
- `retry`:
```java
  /**
   * POLL_REFUND polls every 5 minutes for {@code refundPollMaxAttempts} (288 = 24 h). POLL_BOLETO
   * looks again every {@code boletoPollEvery} (6 h) while the bank says open; a failure (bank
   * unreachable) backs off like any job but never waits longer than the poll period itself.
   * Everything else backs off exponentially up to {@code jobMaxAttempts}.
   */
  private Job retry(Job job, Instant now, String error, boolean failed) {
    if (job.type() == JobType.POLL_REFUND) {
      return job.reschedule(now.plus(RefundService.POLL_EVERY), error, props.refundPollMaxAttempts());
    }
    if (job.type() == JobType.POLL_BOLETO) {
      Duration wait = failed && backoff(job.attempts()).compareTo(props.boletoPollEvery()) < 0 ? backoff(job.attempts()) : props.boletoPollEvery();
      return job.reschedule(now.plus(wait), error, props.boletoPollMaxAttempts());
    }
    return job.reschedule(now.plus(backoff(job.attempts())), error, props.jobMaxAttempts());
  }
```

`PaymentsConfiguration.java` — bean novo e o `jobRunner`:
```java
  @Bean
  BoletoPollingService boletoPollingService(
      PaymentRepository payments, ProviderGateway providers, PaymentService paymentService, PaymentsProperties props,
      TransactionTemplate paymentsTransactionTemplate, Clock clock) {
    return new BoletoPollingService(payments, providers, paymentService, props, paymentsTransactionTemplate, clock);
  }

  @Bean
  JobRunner jobRunner(
      JobRepository jobs, WebhookInboxService inbox, ExpirationService expiration, RefundPollingService polling, BoletoPollingService boletoPolling,
      RefundService refunds, ReconciliationService reconciliation, PaymentsProperties props, TransactionTemplate paymentsTransactionTemplate, Clock clock) {
    return new JobRunner(jobs, inbox, expiration, polling, boletoPolling, refunds, reconciliation, props, paymentsTransactionTemplate, clock);
  }
```
(import `com.gateway.payments.payment.boleto.BoletoPollingService`.)

- [ ] **Step 4: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments test`
Expected: PASS. Se `theRunnerPollsAndReschedulesSixHoursLater` reclamar de outros jobs vencidos (`runDue` devolve quantos reivindicou, e a base é compartilhada), o teste já usa `isGreaterThanOrEqualTo(1)` e filtra por `ref_id`; não afrouxe mais que isso.

- [ ] **Step 5: Commit**

```bash
git add gateway-payments
git commit -m "feat(payments): boleto polling with one decision per bank situation and a six-hour job

Every row of the spec's table is a test: open keeps polling until the
limit date plus two days, paid completes with paidVia BOLETO and the
bank's amount and date, a different amount or a paid boleto on a
payment already completed via Pix opens a divergence instead of
moving money, canceled at the bank and two empty answers are flagged
for a human. A failed poll backs off but never beyond the poll period.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 11: Cancelar (baixa), expirar na data limite, sweeper de `CREATED`, reconciliação, webhook por txid, devolução recusada

**Files:**
- Modify: `gateway-payments/src/main/java/com/gateway/payments/payment/PaymentService.java` (`cancel`, `settleFromWebhook`), `payment/ExpirationService.java` (`expireOne`, `sweepStuckCreated`), `reconciliation/ReconciliationService.java`, `refund/RefundService.java:89-91`, `PaymentsConfiguration.java` (bean da reconciliação)
- Modify (test support): `gateway-payments/src/test/java/com/gateway/payments/support/RecordingBoletoProvider.java` (+`markPaidAfterNextFind`)
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/boleto/BolecodeLifecycleIntegrationTest.java`

**Interfaces:**
- Produces:
  ```java
  PaymentService.cancel(MerchantId, String id)          // BOLECODE: find first; paid -> settleBoleto(RECONCILIATION) + DomainException("ALREADY_PAID"); open -> boleto.cancel; CONFLICT -> find again and decide; NOT_FOUND -> proceed
  PaymentService.settleFromWebhook(MerchantId, String txid, ReceivedPix)   // resolves the payment by txid (findByMerchantAndTxid), then GET /cob/{p.pix().txid()}
  ExpirationService.expireOne / sweepStuckCreated       // BOLECODE branches
  ReconciliationService(…, BoletoPollingService boletoPolling, …)   // new last-but-two parameter; Pix window matched by txid
  RefundService.request                                  // DomainException("REFUND_NOT_SUPPORTED") for paidVia == BOLETO
  RecordingBoletoProvider += public void markPaidAfterNextFind(String nossoNumero, Money amount, Instant at)   // the race a CONFLICT on baixa comes from
  ```

- [ ] **Step 1: Suporte e teste**

`RecordingBoletoProvider.java` — acrescente:
```java
  private final Map<String, Runnable> afterNextFind = new ConcurrentHashMap<>();

  /** The payer pays between the cancel's pre-check and the baixa: the next find answers OPEN, then the boleto is paid. */
  public void markPaidAfterNextFind(String nossoNumero, Money amount, Instant at) {
    afterNextFind.put(nossoNumero, () -> markPaid(nossoNumero, amount, at));
  }
```
e no fim de `find(...)`, antes do `return`:
```java
    Optional<BoletoStatus> answer = Optional.ofNullable(boletos.get(nossoNumero));
    Runnable then = afterNextFind.remove(nossoNumero);
    if (then != null) then.run();
    return answer;
```
(substituindo o `return Optional.ofNullable(boletos.get(nossoNumero));` existente).

`payment/boleto/BolecodeLifecycleIntegrationTest.java`:
```java
package com.gateway.payments.payment.boleto;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import com.gateway.payments.inbox.WebhookInboxService;
import com.gateway.payments.payment.ExpirationService;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.ReconciliationService;
import com.gateway.payments.refund.Refund;
import com.gateway.payments.refund.RefundService;
import com.gateway.payments.refund.RefundState;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BolecodeLifecycleIntegrationTest extends ServiceIntegrationTestBase {
  @Autowired PaymentRepository payments;
  @Autowired ExpirationService expiration;
  @Autowired ReconciliationService reconciliation;
  @Autowired WebhookInboxService webhookInbox;
  @Autowired RefundService refunds;
  @Autowired BoletoPollingService polling;

  private String nn(Payment p) { return p.boleto().nossoNumero(); }

  private java.util.List<String> divergences(String paymentId) {
    return jdbc.queryForList("SELECT provider_status FROM payments.reconciliation_divergences WHERE payment_id = ? ORDER BY created_at", String.class, paymentId);
  }

  // --- cancel (baixa) ---

  @Test
  void cancelOpenBolecodeAsksTheBankThenIssuesTheBaixa() {
    Payment p = newBolecode(100);
    Payment canceled = paymentService.cancel(merchant, p.id());
    assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
    assertThat(boletos.callsFor(nn(p))).containsExactly("issueBoleto:" + nn(p), "findBoleto:" + nn(p), "cancelBoleto:" + nn(p));
    assertThat(boletos.status(nn(p)).situation()).isEqualTo(BoletoSituation.CANCELED);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.canceled");
  }

  @Test
  void cancelOfAPaidBolecodeCompletesItAndIsAlreadyPaid() {
    Payment p = newBolecode(12990);
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    assertThatThrownBy(() -> paymentService.cancel(merchant, p.id())).isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("ALREADY_PAID");
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.boleto().paidVia()).isEqualTo(PaidVia.BOLETO);
    assertThat(boletos.callsFor(nn(p))).doesNotContain("cancelBoleto:" + nn(p));
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
  }

  @Test
  void cancelThatLosesTheRaceToThePayerAsksAgainAndCompletes() {
    Payment p = newBolecode(12990);
    boletos.markPaidAfterNextFind(nn(p), Money.brl(12990), clock.instant());
    assertThatThrownBy(() -> paymentService.cancel(merchant, p.id())).isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("ALREADY_PAID");
    assertThat(boletos.callsFor(nn(p))).containsExactly("issueBoleto:" + nn(p), "findBoleto:" + nn(p), "cancelBoleto:" + nn(p), "findBoleto:" + nn(p));
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  void cancelIsRefusedWhenNotPending() {
    Payment p = newBolecode(100);
    paymentService.cancel(merchant, p.id());
    assertThatThrownBy(() -> paymentService.cancel(merchant, p.id())).isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("INVALID_STATE");
  }

  // --- expiration on the limit date ---

  private Payment bolecodeExpiringToday() {
    return paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(12990), null, null, payer(), BoletoDates.today(clock), 0));
  }

  @Test
  void expirationWaitsForTheLimitDateNotTheDueDate() {
    Payment p = paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, payer(), BoletoDates.today(clock), 5));
    clock.advance(Duration.ofDays(2));
    assertThat(expiration.expireOne(p.id(), clock.instant())).isFalse();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(boletos.callsFor(nn(p))).containsExactly("issueBoleto:" + nn(p));
  }

  @Test
  void expirationAfterTheLimitDateAsksTheBankAndExpiresAnOpenBoletoWithoutABaixa() {
    Payment p = bolecodeExpiringToday();
    clock.advance(Duration.between(clock.instant(), p.expiresAt()).plus(Duration.ofMinutes(6)));
    assertThat(expiration.expireOne(p.id(), clock.instant())).isTrue();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.EXPIRED);
    assertThat(boletos.callsFor(nn(p))).containsExactly("issueBoleto:" + nn(p), "findBoleto:" + nn(p));
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.expired");
  }

  @Test
  void expirationCompletesABoletoPaidOnTheLastDay() {
    Payment p = bolecodeExpiringToday();
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    clock.advance(Duration.between(clock.instant(), p.expiresAt()).plus(Duration.ofMinutes(6)));
    assertThat(expiration.expireOne(p.id(), clock.instant())).isTrue();
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.boleto().paidVia()).isEqualTo(PaidVia.BOLETO);
  }

  @Test
  void expirationLeavesAnAwaitingCreditBoletoPending() {
    Payment p = bolecodeExpiringToday();
    boletos.setSituation(nn(p), BoletoSituation.AWAITING_CREDIT);
    clock.advance(Duration.between(clock.instant(), p.expiresAt()).plus(Duration.ofMinutes(6)));
    assertThat(expiration.expireOne(p.id(), clock.instant())).isFalse();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void aLatePaymentAfterExpiryCompletesThroughThePoll() {
    Payment p = bolecodeExpiringToday();
    clock.advance(Duration.between(clock.instant(), p.expiresAt()).plus(Duration.ofMinutes(6)));
    expiration.expireOne(p.id(), clock.instant());
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.EXPIRED);
    reconciliation.reconcileAll(clock.instant());
    // The reconciliation's boleto pass looks at PENDING only; the poll job (still within limit + 2 days) is what completes an EXPIRED one.
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.EXPIRED);
    assertThat(polling.check(p.id(), com.gateway.payments.payment.EventSource.PROVIDER_POLL)).isTrue();
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payments.events(p.id()).getLast().source()).isEqualTo(com.gateway.payments.payment.EventSource.PROVIDER_POLL);
  }

  // --- stuck CREATED ---

  @Test
  void stuckCreatedBolecodeUnknownToTheBankFails() {
    boletos.failNextIssueWith(new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    assertThatThrownBy(() -> newBolecode(100)).isInstanceOf(DomainException.class);
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    clock.advance(Duration.ofMinutes(11));
    assertThat(expiration.sweepStuckCreated(clock.instant())).isGreaterThanOrEqualTo(1);
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(outboxTypes(p.id())).containsExactly("payment.failed");
  }

  @Test
  void stuckCreatedBolecodeTheBankIssuedIsAdoptedAndSettledIfPaid() {
    boletos.landNextIssueThenFailWith(new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    boletos.failNextFindWith("00000001", new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "down"));
    assertThatThrownBy(() -> newBolecode(12990)).isInstanceOf(DomainException.class);
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    boletos.markPaid("00000001", Money.brl(12990), clock.instant());
    clock.advance(Duration.ofMinutes(11));
    expiration.sweepStuckCreated(clock.instant());
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.boleto().linhaDigitavel()).hasSize(47);
    assertThat(done.pix().txid()).isEqualTo(boletos.pixTxidFor(null, "00000001"));
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending", "completed");
  }

  // --- reconciliation ---

  @Test
  void reconciliationChecksPendingBolecodesOlderThanMinAge() {
    Payment p = newBolecode(12990);
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    reconciliation.reconcileAll(clock.instant());
    assertThat(payments.findById(p.id()).orElseThrow().status()).as("too young").isEqualTo(PaymentStatus.PENDING);
    clock.advance(Duration.ofMinutes(11));
    reconciliation.reconcileAll(clock.instant());
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payments.events(p.id()).getLast().source()).isEqualTo(com.gateway.payments.payment.EventSource.RECONCILIATION);
  }

  // --- the QR side ---

  @Test
  void aPixWebhookOnABolecodeIsMatchedByTxidAndCompletesWithPaidViaPix() {
    Payment p = newBolecode(12990);
    String txid = p.pix().txid();
    bank.markPaid(txid, "E2E-QR-" + nn(p), Money.brl(12990));
    String inboxId = webhookInbox.accept("ITAU", merchant, "{}", ("E2E-QR-" + nn(p) + " " + txid + " 12990").getBytes(StandardCharsets.UTF_8));
    webhookInbox.process(inboxId);
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.boleto().paidVia()).isEqualTo(PaidVia.PIX);
    assertThat(done.pix().endToEndId()).isEqualTo("E2E-QR-" + nn(p));
    assertThat(bank.callsFor(txid)).contains("findCharge:" + txid);
    assertThat(jdbc.queryForObject("SELECT status FROM payments.webhook_inbox WHERE id = ?", String.class, inboxId)).isEqualTo("PROCESSED");

    Refund r = refunds.request(merchant, p.id(), Money.brl(1000));
    assertThat(r.state()).isEqualTo(RefundState.PROCESSING);
  }

  @Test
  void aRefundOfABoletoSettlementIsRefused() {
    Payment p = newBolecode(12990);
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    paymentService.settleBoleto(merchant, p.id(), boletos.status(nn(p)), com.gateway.payments.payment.EventSource.PROVIDER_POLL);
    assertThatThrownBy(() -> refunds.request(merchant, p.id(), Money.brl(1000))).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("REFUND_NOT_SUPPORTED"));
    assertThat(bank.callsFor(p.id())).noneMatch(c -> c.startsWith("requestRefund"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.refunds WHERE payment_id = ?", Long.class, p.id())).isZero();
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments -Dtest=BolecodeLifecycleIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — `markPaidAfterNextFind` não compila; depois, `cancelOpenBolecode…` falha porque `cancel` chama `cancelCharge` do Pix.

- [ ] **Step 3: Implementar**

`PaymentService.cancel` — substitua o método:
```java
  public Payment cancel(MerchantId merchantId, String id) {
    Payment current = get(merchantId, id);
    if (current.status() != PaymentStatus.PENDING) {
      throw new DomainException("INVALID_STATE", "only a pending payment can be canceled, this one is " + current.status());
    }
    ProviderGateway.Resolved r = providers.resolve(merchantId, current.environment(), current.provider());
    if (current.method() == PaymentMethod.BOLECODE) {
      cancelBoletoAtBank(current, r);
    } else {
      try {
        providers.run(id, "cancelCharge", r, x -> x.provider().cancelCharge(x.credentials(), id));
      } catch (ProviderException e) {
        // The bank refuses to remove a charge that is no longer ATIVA — most likely it was just paid
        // and the webhook is on its way. Cancelling here would contradict the bank.
        if (e.code() == ProviderException.Code.INVALID) {
          throw new DomainException("INVALID_STATE", "the bank no longer accepts cancelling this charge");
        }
        throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", e, log, "cancelCharge", id);
      }
    }
    return tx.execute(s -> {
      Payment p = payments.findByMerchantAndId(merchantId, id).orElseThrow();
      if (p.status() != PaymentStatus.PENDING) {
        throw new DomainException("INVALID_STATE", "payment changed to " + p.status() + " while cancelling");
      }
      Payment saved = payments.save(p, List.of(p.markCanceled(EventSource.API)));
      events.emit(saved.merchantId(), "payment.canceled", saved);
      return saved;
    });
  }

  /**
   * The bank first (spec §7): a barcode payment is only visible through the query, and a baixa on
   * a paid boleto would contradict money that already arrived. Paid → the payment completes here
   * and the caller gets ALREADY_PAID (a 409 at the edge). Open → baixa; the bank's CONFLICT means
   * it was paid between the two calls, so the query is asked once more and decides. The QR dies
   * with the boleto (product docs); if it does not, expiration covers it.
   */
  private void cancelBoletoAtBank(Payment p, ProviderGateway.Resolved r) {
    BoletoProvider boleto = r.boleto().orElseThrow(() -> new DomainException("METHOD_NOT_SUPPORTED", PROVIDER + " has no boleto product"));
    String nn = p.boleto().nossoNumero();
    Optional<BoletoStatus> before = providers.call(p.id(), "findBoleto", r, x -> boleto.find(x.credentials(), nn));
    if (before.isPresent() && before.get().paid()) {
      throw alreadyPaid(p, before.get());
    }
    try {
      providers.run(p.id(), "cancelBoleto", r, x -> boleto.cancel(x.credentials(), nn));
    } catch (ProviderException e) {
      if (e.code() == ProviderException.Code.CONFLICT) {
        Optional<BoletoStatus> after = providers.call(p.id(), "findBoleto", r, x -> boleto.find(x.credentials(), nn));
        if (after.isPresent() && after.get().paid()) {
          throw alreadyPaid(p, after.get());
        }
        throw new DomainException("INVALID_STATE", "the bank no longer accepts cancelling this boleto");
      }
      if (e.code() == ProviderException.Code.NOT_FOUND) {
        return; // nothing to invalidate at the bank; the gateway side is still canceled below
      }
      throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", e, log, "cancelBoleto", p.id());
    }
  }

  private DomainException alreadyPaid(Payment p, BoletoStatus status) {
    settleBoleto(p.merchantId(), p.id(), status, EventSource.RECONCILIATION);
    return new DomainException("ALREADY_PAID", "the bank shows this boleto paid; the payment is now COMPLETED");
  }
```

`PaymentService.settleFromWebhook` — o parâmetro passa a ser o txid e a busca é por ele; o `GET /cob` usa o txid guardado:
```java
  public Settlement settleFromWebhook(MerchantId merchantId, String txid, ReceivedPix hinted) {
    // By txid, not by id: a Bolecode's txid is the bank's BL..., and the webhook only knows the txid.
    Optional<Payment> found = payments.findByMerchantAndTxid(merchantId, PROVIDER, txid);
    if (found.isEmpty()) {
      return Settlement.UNKNOWN_PAYMENT;
    }
    Payment p = found.get();
    if (p.status() == PaymentStatus.CREATED) {
      throw new IllegalStateException("pix for payment " + p.id() + " still in " + p.status());
    }
    ProviderGateway.Resolved r = providers.resolve(merchantId, p.environment(), p.provider());
    Optional<Charge> atBank = providers.call(p.id(), "findCharge", r, x -> x.provider().findCharge(x.credentials(), p.pix().txid()));
    Optional<ReceivedPix> confirmed =
        atBank
            .filter(c -> c.status() == ChargeStatus.COMPLETED && c.received() != null)
            .flatMap(c -> c.received().stream().filter(x -> Objects.equals(x.endToEndId(), hinted.endToEndId())).findFirst());
    if (confirmed.isPresent()) {
      return settle(merchantId, p.id(), confirmed.get(), EventSource.PROVIDER_WEBHOOK);
    }
    String bankSays = atBank.map(c -> c.status().name()).orElse("NOT_FOUND");
    tx.executeWithoutResult(s -> {
      Payment loaded = payments.findById(p.id()).orElseThrow();
      payments.save(loaded, List.of(loaded.recordIgnored("unconfirmed webhook: e2eid " + hinted.endToEndId() + ", bank says " + bankSays, EventSource.PROVIDER_WEBHOOK).orElseThrow()));
      openDivergence(loaded, "UNCONFIRMED_WEBHOOK", "webhook said e2eid " + hinted.endToEndId() + " paid " + hinted.amount().cents() + " cents; bank says " + bankSays);
    });
    return Settlement.IGNORED;
  }
```
(O javadoc existente fica. `WebhookInboxService.process` já passa o txid do evento — nada muda lá.)

`ExpirationService.java` (imports `com.gateway.kernel.provider.boleto.BoletoProvider`, `BoletoSituation`, `BoletoStatus`):

`expireOne` — depois de resolver `r`, antes do `findCharge` do Pix:
```java
    if (p.method() == PaymentMethod.BOLECODE) {
      return expireBolecode(p, r);
    }
```
e o bloco final `tx.execute(...)` que marca EXPIRED vira um método privado `markExpired(String paymentId)` usado pelos dois ramos:
```java
  /**
   * A Bolecode expires on its payment limit date (spec 2026-09-25 §7, §10): after it the bank refuses
   * both the barcode and the QR, so no baixa is sent — it would only add a call that can fail. The
   * query still runs first: a payment made on the last day is credited on the next business day.
   */
  private boolean expireBolecode(Payment p, ProviderGateway.Resolved r) {
    BoletoProvider boleto = r.boleto().orElseThrow();
    String nn = p.boleto().nossoNumero();
    Optional<BoletoStatus> atBank = providers.call(p.id(), "findBoleto", r, x -> boleto.find(x.credentials(), nn));
    if (atBank.isPresent() && atBank.get().paid()) {
      return paymentService.settleBoleto(p.merchantId(), p.id(), atBank.get(), EventSource.RECONCILIATION) == PaymentService.Settlement.COMPLETED;
    }
    if (atBank.isPresent() && atBank.get().situation() == BoletoSituation.AWAITING_CREDIT) {
      log.info("boleto {} of payment {} awaiting credit at the bank; not expiring yet", nn, p.id());
      return false;
    }
    return markExpired(p.id());
  }

  private boolean markExpired(String paymentId) {
    return Boolean.TRUE.equals(
        tx.execute(s -> {
          Payment loaded = payments.findById(paymentId).orElseThrow();
          if (loaded.status() != PaymentStatus.PENDING) {
            return false; // a webhook or a poll got there while we were asking the bank
          }
          Payment saved = payments.save(loaded, List.of(loaded.markExpired(EventSource.EXPIRATION_JOB)));
          events.emit(saved.merchantId(), "payment.expired", saved);
          return true;
        }));
  }
```

`sweepStuckCreated` — dentro do `try`, depois de resolver `r`:
```java
        if (p.method() == PaymentMethod.BOLECODE) {
          // Same idea as Pix, with the query: the number is ours, so the bank can say whether the
          // issue landed. Empty after stuckCreatedAfter (the bank's 202 long past) is FAILED.
          BoletoProvider boleto = r.boleto().orElseThrow();
          String nn = p.boleto().nossoNumero();
          Optional<BoletoStatus> atBank = providers.call(p.id(), "findBoleto", r, x -> boleto.find(x.credentials(), nn));
          if (atBank.isEmpty()) {
            paymentService.markFailed(p.id(), "PROVIDER_TIMEOUT", EventSource.SYSTEM);
          } else {
            paymentService.adoptBolecodeFromStatus(p.id(), r, atBank.get(), EventSource.SYSTEM);
            if (atBank.get().paid()) {
              paymentService.settleBoleto(p.merchantId(), p.id(), atBank.get(), EventSource.RECONCILIATION);
            }
          }
          changed++;
          continue;
        }
```

`ReconciliationService.java`:
- campo e construtor: `BoletoPollingService boletoPolling` entre `paymentService` e `props` (import `com.gateway.payments.payment.boleto.BoletoPollingService`, `com.gateway.payments.payment.PaymentMethod`).
- em `reconcileAll`, antes do loop de escopos do Pix:
```java
    // Bolecode, barcode side: there is no listing API for boletos, so each PENDING one older than
    // minAge is checked one by one — the same decision table as the poll (BoletoPollingService).
    for (Payment p : payments.findByStatusIn(EnumSet.of(PaymentStatus.PENDING), from, CANDIDATES)) {
      if (p.method() != PaymentMethod.BOLECODE || p.createdAt().isAfter(youngCutoff)) {
        continue;
      }
      try {
        PaymentStatus before = p.status();
        boletoPolling.check(p.id(), EventSource.RECONCILIATION);
        if (payments.findById(p.id()).map(x -> x.status() != before).orElse(false)) {
          changed++;
        }
      } catch (RuntimeException e) {
        log.warn("boleto reconciliation failed for payment {}", p.id(), e);
      }
    }
```
(`int changed = 0;` precisa ser declarado antes deste loop; mova a declaração para cima.)
- em `reconcile(...)`: `Optional<Payment> found = payments.findByMerchantAndTxid(merchantId, PaymentService.PROVIDER, charge.txid());` no lugar de `findByMerchantAndId(merchantId, charge.txid())` — o comentário `// not ours, or another merchant's with the same bank account` continua valendo.

`PaymentsConfiguration.reconciliationService` — acrescente o parâmetro `BoletoPollingService boletoPolling` e passe-o: `new ReconciliationService(payments, divergences, providers, paymentService, boletoPolling, props, clock)`.

`RefundService.request` — dentro do `tx.execute`, logo depois do check de `COMPLETED` (imports `com.gateway.payments.payment.PaymentMethod`, `com.gateway.payments.payment.boleto.PaidVia`):
```java
          if (locked.method() == PaymentMethod.BOLECODE && locked.boleto() != null && locked.boleto().paidVia() == PaidVia.BOLETO) {
            // The bank has no devolução for a boleto settlement (spec §8); faking one with a transfer
            // would be money leaving by a path the gateway does not control.
            throw new DomainException("REFUND_NOT_SUPPORTED", "a payment settled by boleto cannot be refunded through the bank");
          }
```

- [ ] **Step 4: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-payments test`
Expected: PASS — inclusive `WebhookInboxServiceIntegrationTest` e `ExpirationAndReconciliationIntegrationTest` (o Pix continua casando por txid == id).

- [ ] **Step 5: Commit**

```bash
git add gateway-payments
git commit -m "feat(payments): bolecode cancel, expiry on the limit date, stuck-created sweep, reconciliation and refund refusal

Cancel asks the query before the baixa and completes the payment when
the bank says paid (ALREADY_PAID); expiry runs on the limit date and
sends no baixa because the bank refuses payment after it anyway. The
webhook and the Pix reconciliation now resolve payments by txid, the
only way a BL... txid can be matched. A boleto settlement has no
devolucao at the bank, so a refund of it is refused.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 12: `app` — request/response `BOLECODE`, `ALREADY_PAID` → 409, `application.yml`, contrato JSON e fluxo ponta a ponta

**Files:**
- Modify: `gateway-app/src/main/java/com/gateway/app/api/dto/CreatePaymentRequest.java`, `gateway-app/src/main/java/com/gateway/app/api/dto/PaymentResponse.java`, `gateway-app/src/main/java/com/gateway/app/api/PaymentsController.java:38-46`, `gateway-app/src/main/java/com/gateway/app/api/ErrorHandler.java:29-32`, `gateway-app/src/main/resources/application.yml`
- Modify (test): `gateway-app/src/test/java/com/gateway/app/api/dto/PaymentJsonContractTest.java`
- Create (test): `gateway-app/src/test/java/com/gateway/app/BolecodeFlowIntegrationTest.java`, `gateway-app/src/test/resources/itau/fixtures/{post_boletos_pix_200.json,get_boletos_200_paid.json,get_boletos_200_canceled.json}` (cópias dos fixtures de `gateway-providers`, como os de Pix já são)
- Sem mudança, e por quê: `providers/ProviderWiring.java` (já importa `ProvidersConfiguration`, de onde o bean `BoletoProvider` vem; `PaymentsConfiguration` o injeta por `ObjectProvider`), `jobs/JobScheduler.java` (`POLL_BOLETO` roda pelo `JobRunner.runDue` que o scheduler já chama a cada `jobs-poll-ms`), `IdempotencyFilter` (o `POST /v1/payments` já é coberto).

**Interfaces:**
- Produces:
  ```java
  public record CreatePaymentRequest(Long amount, String currency, String method, String reference, String description, Customer customer, Integer expiresIn, LocalDate dueDate, Integer paymentLimitDays) {
    public record Customer(String name, String document, Address address) {}
    public record Address(String street, String district, String city, String state, String zip) {}
    public void validate();     // method PIX|BOLECODE; due_date/payment_limit_days only with BOLECODE; expires_in only with PIX
    public Payer payer();       // null when customer is null
  }
  public record PaymentResponse(..., Pix pix, Boleto boleto, ...) { public record Boleto(String linhaDigitavel, String codigoBarras, LocalDate dueDate, LocalDate paymentLimitDate, String paidVia) {} }
     // JSON (SNAKE_CASE global): boleto{linha_digitavel, codigo_barras, due_date, payment_limit_date, paid_via}; null for PIX
  ErrorHandler: DomainException("ALREADY_PAID") -> 409, every other DomainException -> 422 (unchanged)
  application.yml: gateway.providers.itau.boleto.* (12 URLs), gateway.payments.boleto-* (6 tunables)
  ```

- [ ] **Step 1: Fixtures e testes**

```bash
cp gateway-providers/src/test/resources/itau/boleto/fixtures/post_boletos_pix_200.json gateway-app/src/test/resources/itau/fixtures/
cp gateway-providers/src/test/resources/itau/boleto/fixtures/get_boletos_200_paid.json gateway-app/src/test/resources/itau/fixtures/
cp gateway-providers/src/test/resources/itau/boleto/fixtures/get_boletos_200_canceled.json gateway-app/src/test/resources/itau/fixtures/
```

`PaymentJsonContractTest.java` — substitua o arquivo:
```java
package com.gateway.app.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentMethod;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.boleto.PaidVia;
import com.gateway.payments.payment.pix.PixDetails;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/**
 * One resource, one vocabulary: the payment a merchant GETs from the REST API and the one a
 * {@code payment.*} webhook carries are built by two different pieces of code (PaymentResponse
 * serialized by the app's mapper, PaymentEvents' hand-built map). Nothing tied their key sets
 * together, so a field added to one silently went missing from the other. Now with the boleto block.
 */
class PaymentJsonContractTest {
  /** Same naming as application.yml's spring.jackson.property-naming-strategy. */
  private final JsonMapper appMapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();
  private final Instant now = Instant.parse("2026-09-24T12:00:00Z");

  private Payment pix() {
    return Payment.rehydrate("01J00000000000000000000000", MerchantId.next(), ProviderEnvironment.TEST, "ITAU", PaymentMethod.PIX, PaymentStatus.COMPLETED,
        Money.brl(15990), "order-42", "Pedido 42", "hash", new PixDetails("01J00000000000000000000000", "000201...", "pix.example/qr/1", "E123"), null,
        now.plusSeconds(3600), now, Money.brl(15990), Money.brl(0), 3, now, now, Clock.systemUTC());
  }

  private Payment bolecode() {
    return Payment.rehydrate("01J00000000000000000000001", MerchantId.next(), ProviderEnvironment.TEST, "ITAU", PaymentMethod.BOLECODE, PaymentStatus.COMPLETED,
        Money.brl(12990), "order-43", "Pedido 43", "hash", new PixDetails("BL15000005206109000000000000001", "000201...", null, null),
        new BoletoDetails("00000001", "uuid", "1".repeat(47), "1".repeat(44), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), PaidVia.BOLETO),
        Instant.parse("2026-11-01T02:59:59Z"), now, Money.brl(12990), Money.brl(0), 3, now, now, Clock.systemUTC());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> rest(Payment p) {
    return appMapper.readValue(appMapper.writeValueAsString(PaymentResponse.from(p)), new TypeReference<Map<String, Object>>() {});
  }

  @Test
  void restAndWebhookPaymentsHaveTheSameKeysForBothMethods() {
    for (Payment p : new Payment[] {pix(), bolecode()}) {
      Map<String, Object> rest = rest(p);
      Map<String, Object> webhook = PaymentEvents.paymentJson(p);
      assertThat(rest.keySet()).as(p.method().name()).containsExactlyInAnyOrderElementsOf(webhook.keySet());
      assertThat(Set.copyOf(((Map<?, ?>) rest.get("pix")).keySet())).isEqualTo(Set.copyOf(((Map<?, ?>) webhook.get("pix")).keySet()));
      assertThat(rest.keySet()).doesNotContain("customer_document_hash");
      assertThat(rest).containsEntry("method", p.method().name());
    }
  }

  @Test
  void theBoletoBlockIsNullForPixAndFullForBolecode() {
    assertThat(rest(pix())).containsEntry("boleto", null);
    assertThat(PaymentEvents.paymentJson(pix())).containsEntry("boleto", null);
    Map<?, ?> rest = (Map<?, ?>) rest(bolecode()).get("boleto");
    Map<?, ?> webhook = (Map<?, ?>) PaymentEvents.paymentJson(bolecode()).get("boleto");
    assertThat(rest.keySet()).containsExactlyInAnyOrder("linha_digitavel", "codigo_barras", "due_date", "payment_limit_date", "paid_via");
    assertThat(Set.copyOf(rest.keySet())).isEqualTo(Set.copyOf(webhook.keySet()));
    assertThat(rest).containsEntry("paid_via", "BOLETO").containsEntry("due_date", "2026-10-01").containsEntry("payment_limit_date", "2026-10-31");
    assertThat(webhook).containsEntry("paid_via", "BOLETO").containsEntry("due_date", "2026-10-01");
  }
}
```

`BolecodeFlowIntegrationTest.java`:
```java
package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * The Bolecode path through the real app: create with a payer (201 with boleto and pix blocks),
 * refuse without one (422), cancel (baixa at the bank), a barcode payment found by the poll
 * (paid_via BOLETO, webhook delivered), and the refund refusal. WireMock plays the three Itaú
 * APIs under one host with three path prefixes; sandbox-shaped credentials, no mTLS.
 *
 * <p>One test method on purpose, like PaymentsFlowIntegrationTest: every step builds on the previous.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "webhook-delivery.retry-delay-ms=200",
      "gateway.payments.outbox-relay-ms=200",
      "gateway.payments.jobs-poll-ms=200",
      "gateway.rate-limit.requests-per-minute=1000"
    })
@ActiveProfiles("test")
@Testcontainers
class BolecodeFlowIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer ITAU = new WireMockServer(options().dynamicPort());
  static final String BAIXA_ID_1 = "15000005206110900000001";
  static final List<String> received = new CopyOnWriteArrayList<>();
  static HttpServer sink;

  static { ITAU.start(); }

  @DynamicPropertySource
  static void itau(DynamicPropertyRegistry r) {
    r.add("gateway.providers.itau.test-mutual-tls", () -> "false");
    r.add("gateway.providers.itau.test-api-base", () -> ITAU.baseUrl() + "/pix");
    r.add("gateway.providers.itau.test-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    r.add("gateway.providers.itau.boleto.test-issue-api-base", () -> ITAU.baseUrl() + "/issue");
    r.add("gateway.providers.itau.boleto.test-issue-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    r.add("gateway.providers.itau.boleto.test-query-api-base", () -> ITAU.baseUrl() + "/query");
    r.add("gateway.providers.itau.boleto.test-query-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    r.add("gateway.providers.itau.boleto.test-instruction-api-base", () -> ITAU.baseUrl() + "/instruction");
    r.add("gateway.providers.itau.boleto.test-instruction-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
  }

  @BeforeAll
  static void start() throws IOException {
    sink = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    sink.createContext("/hook", ex -> {
      received.add(ex.getRequestHeaders().getFirst("X-Gateway-Event-Type") + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });
    sink.start();
    ITAU.stubFor(post(urlEqualTo("/api/oauth/jwt")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
        .withBody("{\"access_token\":\"tok-123\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    ITAU.stubFor(post(urlEqualTo("/issue/boletos-pix")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(fixture("post_boletos_pix_200.json"))));
  }

  @AfterAll
  static void stop() { sink.stop(0); ITAU.stop(); }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private RestTestClient http() { return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build(); }

  private static String fixture(String name) {
    try (InputStream in = BolecodeFlowIntegrationTest.class.getResourceAsStream("/itau/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> adminPost(String uri, Object body) {
    return http().post().uri(uri).header("X-Admin-Key", "test-admin").contentType(MediaType.APPLICATION_JSON).body(body).exchange()
        .expectStatus().is2xxSuccessful().expectBody(Map.class).returnResult().getResponseBody();
  }

  @SuppressWarnings("unchecked")
  private EntityExchangeResult<Map> postPayment(String apiKey, String key, Map<String, Object> body) {
    return http().post().uri("/v1/payments").header("Authorization", "Bearer " + apiKey).header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).body(body).exchange().expectBody(Map.class).returnResult();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getJson(String apiKey, String uri) {
    return http().get().uri(uri).header("Authorization", "Bearer " + apiKey).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
  }

  private static Map<String, Object> bolecodeRequest(String reference) {
    Map<String, Object> customer = Map.of("name", "Joao da Silva", "document", "12345678901",
        "address", Map.of("street", "Rua das Flores 10", "district", "Centro", "city", "Sao Paulo", "state", "SP", "zip", "01310100"));
    Map<String, Object> body = new HashMap<>();
    body.put("amount", 12990);
    body.put("currency", "BRL");
    body.put("method", "BOLECODE");
    body.put("reference", reference);
    body.put("description", "Pedido 42");
    body.put("customer", customer);
    return body;
  }

  @Test
  @SuppressWarnings("unchecked")
  void issueRefuseCancelPollAndRefuseRefund() {
    // 1. Merchant, TEST key, credential with the boleto account, webhook endpoint.
    String merchantId = (String) adminPost("/v1/admin/merchants", Map.of("name", "Boleto Store")).get("id");
    String testKey = (String) adminPost("/v1/admin/merchants/" + merchantId + "/api-keys", Map.of("environment", "TEST")).get("key");
    http().put().uri("/v1/admin/merchants/" + merchantId + "/providers/ITAU/credentials").header("X-Admin-Key", "test-admin").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("environment", "TEST", "payload", Map.of("client_id", "sandbox-client", "client_secret", "sandbox-secret", "pix_key", "a1f4102e-a446-4a57-bcce-6fa48899c1d1",
            "beneficiary_id", "150000052061", "wallet_code", "109", "species_code", "01")))
        .exchange().expectStatus().is2xxSuccessful();
    http().post().uri("/v1/webhooks/endpoints").header("Authorization", "Bearer " + testKey).contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("url", "http://localhost:" + sink.getAddress().getPort() + "/hook", "events", List.of("payment.*"))).exchange().expectStatus().isCreated();

    // 2. Create: 201 with both blocks.
    EntityExchangeResult<Map> created = postPayment(testKey, "b1", bolecodeRequest("order-42"));
    assertThat(created.getStatus().value()).isEqualTo(201);
    Map<String, Object> payment = created.getResponseBody();
    String paymentId = (String) payment.get("id");
    LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    assertThat(payment).containsEntry("status", "PENDING").containsEntry("method", "BOLECODE").containsEntry("amount", 12990).containsKey("expires_at");
    Map<String, Object> boleto = (Map<String, Object>) payment.get("boleto");
    assertThat(boleto).containsEntry("linha_digitavel", "34101234567890123456789012345678901234567890123")
        .containsEntry("codigo_barras", "34191234567890123456789012345678901234567890")
        .containsEntry("due_date", today.plusDays(3).toString())
        .containsEntry("payment_limit_date", "2027-01-31")   // the bank's answer wins over our due + 30
        .containsEntry("paid_via", null);
    Map<String, Object> pix = (Map<String, Object>) payment.get("pix");
    assertThat(pix).containsEntry("txid", "BL1234567890123456789012345678901").containsEntry("end_to_end_id", null);
    assertThat((String) pix.get("copia_e_cola")).startsWith("000201");
    ITAU.verify(1, postRequestedFor(urlEqualTo("/issue/boletos-pix"))
        .withHeader("Authorization", equalTo("Bearer tok-123"))
        .withRequestBody(matchingJsonPath("$.beneficiario.id_beneficiario", equalTo("150000052061")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.pessoa.nome_pessoa", equalTo("Joao da Silva")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.dados_individuais_boleto[0].numero_nosso_numero", equalTo("00000001")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.dados_individuais_boleto[0].valor_titulo", equalTo("129.90")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.dados_individuais_boleto[0].data_vencimento", equalTo(today.plusDays(3).toString()))));
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> received.stream().anyMatch(r -> r.startsWith("payment.pending") && r.contains(paymentId)));

    // 3. No customer: 422 naming the field, nothing sent to the bank.
    Map<String, Object> noCustomer = new HashMap<>(bolecodeRequest("order-43"));
    noCustomer.remove("customer");
    EntityExchangeResult<Map> refused = postPayment(testKey, "b2", noCustomer);
    assertThat(refused.getStatus().value()).isEqualTo(422);
    assertThat(refused.getResponseBody()).containsEntry("type", "urn:gateway:CUSTOMER_REQUIRED");
    Map<String, Object> noZip = bolecodeRequest("order-44");
    noZip.put("customer", Map.of("name", "Joao", "document", "12345678901", "address", Map.of("street", "Rua A", "district", "Centro", "city", "Sao Paulo", "state", "SP")));
    EntityExchangeResult<Map> refusedZip = postPayment(testKey, "b3", noZip);
    assertThat(refusedZip.getStatus().value()).isEqualTo(422);
    assertThat((String) refusedZip.getResponseBody().get("detail")).contains("customer.address.zip");
    ITAU.verify(1, postRequestedFor(urlEqualTo("/issue/boletos-pix")));

    // 4. Cancel: the query says open, the baixa goes out with the composite id.
    ITAU.stubFor(get(urlPathEqualTo("/query/boletos")).withQueryParam("nosso_numero", equalTo("00000001"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(fixture("get_boletos_200_canceled.json").replace("\"Baixado\"", "\"Em Aberto\""))));
    ITAU.stubFor(patch(urlEqualTo("/instruction/boletos/" + BAIXA_ID_1 + "/baixa")).willReturn(aResponse().withStatus(204)));
    Map<String, Object> canceled = http().post().uri("/v1/payments/" + paymentId + "/cancel").header("Authorization", "Bearer " + testKey).header("Idempotency-Key", "c1")
        .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    assertThat(canceled).containsEntry("status", "CANCELED");
    ITAU.verify(1, patchRequestedFor(urlEqualTo("/instruction/boletos/" + BAIXA_ID_1 + "/baixa")).withHeader("Authorization", equalTo("Bearer tok-123")));

    // 5. Second Bolecode (a different txid from the bank, or the unique index refuses it), paid by barcode and found by the poll.
    ITAU.stubFor(post(urlEqualTo("/issue/boletos-pix")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
        .withBody(fixture("post_boletos_pix_200.json").replace("BL1234567890123456789012345678901", "BL1234567890123456789012345678902"))));
    EntityExchangeResult<Map> second = postPayment(testKey, "b4", bolecodeRequest("order-45"));
    assertThat(second.getStatus().value()).isEqualTo(201);
    String secondId = (String) second.getResponseBody().get("id");
    ITAU.stubFor(get(urlPathEqualTo("/query/boletos")).withQueryParam("nosso_numero", equalTo("00000002"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody(fixture("get_boletos_200_paid.json").replace("\"00000001\"", "\"00000002\"").replace("\"2100.00\"", "\"129.90\""))));
    // The poll is due in 6 hours; bring it forward instead of waiting.
    jdbc.update("UPDATE payments.jobs SET next_run_at = now() WHERE type = 'POLL_BOLETO' AND ref_id = ?", secondId);
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> "COMPLETED".equals(getJson(testKey, "/v1/payments/" + secondId).get("status")));
    Map<String, Object> paid = getJson(testKey, "/v1/payments/" + secondId);
    assertThat(paid).containsEntry("paid_amount", 12990);
    assertThat((Map<String, Object>) paid.get("boleto")).containsEntry("paid_via", "BOLETO");
    assertThat((Map<String, Object>) paid.get("pix")).containsEntry("end_to_end_id", null);
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> received.stream().anyMatch(r -> r.startsWith("payment.completed") && r.contains(secondId)));
    assertThat(received.stream().filter(r -> r.startsWith("payment.completed") && r.contains(secondId)).findFirst().orElseThrow()).contains("\"paid_via\":\"BOLETO\"");
    List<Map<String, Object>> events = http().get().uri("/v1/payments/" + secondId + "/events").header("Authorization", "Bearer " + testKey)
        .exchange().expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    assertThat(events).extracting(e -> e.get("type")).containsExactly("created", "pending", "completed");
    assertThat(events.getLast()).containsEntry("source", "PROVIDER_POLL");

    // 6. A boleto settlement has no refund.
    EntityExchangeResult<Map> refund = http().post().uri("/v1/payments/" + secondId + "/refunds").header("Authorization", "Bearer " + testKey)
        .header("Idempotency-Key", "r1").contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", 1000)).exchange().expectBody(Map.class).returnResult();
    assertThat(refund.getStatus().value()).isEqualTo(422);
    assertThat(refund.getResponseBody()).containsEntry("type", "urn:gateway:REFUND_NOT_SUPPORTED");

    // 7. Request-shape errors are 400s from the DTO, before any service.
    Map<String, Object> mixed = bolecodeRequest("order-46");
    mixed.put("expires_in", 600);
    assertThat(postPayment(testKey, "b5", mixed).getStatus().value()).isEqualTo(400);
    Map<String, Object> pixWithDue = new HashMap<>(Map.of("amount", 100, "currency", "BRL", "method", "PIX", "due_date", "2026-12-31"));
    assertThat(postPayment(testKey, "b6", pixWithDue).getStatus().value()).isEqualTo(400);
  }
}
```

- [ ] **Step 2: Rodar e ver falhar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-app -am -Dtest='PaymentJsonContractTest,BolecodeFlowIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false test`
Expected: FAIL — compilação (`PaymentResponse.Boleto`, `CreatePaymentRequest.payer()`).

- [ ] **Step 3: Implementar**

`CreatePaymentRequest.java`:
```java
package com.gateway.app.api.dto;

import com.gateway.kernel.provider.boleto.Payer;
import java.time.LocalDate;

/**
 * {@code amount} is integer cents and boxed: a missing amount must be a 400, not a silent charge of
 * zero. {@code method} chooses the shape: PIX takes {@code expires_in}; BOLECODE takes a complete
 * {@code customer} (checked by the service, which names the missing field), {@code due_date} and
 * {@code payment_limit_days}. Mixing the two is a 400 here — a Bolecode has no expiry other than
 * its payment limit date.
 */
public record CreatePaymentRequest(
    Long amount, String currency, String method, String reference, String description, Customer customer, Integer expiresIn,
    LocalDate dueDate, Integer paymentLimitDays) {

  public record Customer(String name, String document, Address address) {}

  public record Address(String street, String district, String city, String state, String zip) {}

  public void validate() {
    if (amount == null || amount <= 0) throw new IllegalArgumentException("amount must be a positive number of cents");
    if (!"BRL".equals(currency)) throw new IllegalArgumentException("currency must be BRL");
    if (!"PIX".equals(method) && !"BOLECODE".equals(method)) throw new IllegalArgumentException("method must be PIX or BOLECODE");
    if (expiresIn != null && expiresIn <= 0) throw new IllegalArgumentException("expires_in must be positive seconds");
    if ("PIX".equals(method) && (dueDate != null || paymentLimitDays != null)) {
      throw new IllegalArgumentException("due_date and payment_limit_days apply to BOLECODE only");
    }
    if ("BOLECODE".equals(method) && expiresIn != null) {
      throw new IllegalArgumentException("expires_in applies to PIX only; a BOLECODE expires on its payment_limit_date");
    }
    if (paymentLimitDays != null && paymentLimitDays < 0) throw new IllegalArgumentException("payment_limit_days must not be negative");
  }

  public boolean isBolecode() { return "BOLECODE".equals(method); }

  /** The kernel's shape; null when there is no customer at all (the service answers CUSTOMER_REQUIRED). */
  public Payer payer() {
    if (customer == null) return null;
    Address a = customer.address();
    return new Payer(customer.name(), customer.document(),
        a == null ? null : new com.gateway.kernel.provider.boleto.Address(a.street(), a.district(), a.city(), a.state(), a.zip()));
  }
}
```

`PaymentsController.create`:
```java
  @PostMapping
  public ResponseEntity<PaymentResponse> create(@RequestBody CreatePaymentRequest req) {
    req.validate();
    MerchantContext.Current who = MerchantContext.current();
    ProviderEnvironment env = providerEnvironment(who.environment());
    Money amount = new Money(req.amount(), req.currency());
    Payment p =
        req.isBolecode()
            ? payments.createBolecode(new PaymentService.CreateBolecode(
                who.merchantId(), env, amount, req.reference(), req.description(), req.payer(), req.dueDate(), req.paymentLimitDays()))
            : payments.createCharge(new PaymentService.CreateCharge(
                who.merchantId(), env, amount, req.reference(), req.description(), req.customer() == null ? null : req.customer().document(), req.expiresIn()));
    return withResource(HttpStatus.CREATED, p);
  }
```

`PaymentResponse.java`:
```java
package com.gateway.app.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.pix.PixDetails;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The merchant's view of a payment, field by field: the aggregate also carries the customer
 * document hash, which must never reach a response. Money is integer cents, like the request.
 * {@code boleto} is null for a Pix payment so the key set is the same for every method
 * (PaymentJsonContractTest holds it to the webhook's).
 */
public record PaymentResponse(
    String id,
    String status,
    String method,
    String provider,
    String environment,
    long amount,
    String currency,
    String reference,
    String description,
    Pix pix,
    Boleto boleto,
    Instant expiresAt,
    Instant paidAt,
    Long paidAmount,
    long refundedAmount,
    Instant createdAt) {

  /** Explicit name: the global SNAKE_CASE strategy does not split the single-letter "E" of copiaECola. */
  public record Pix(String txid, @JsonProperty("copia_e_cola") String copiaECola, String location, String endToEndId) {}

  public record Boleto(String linhaDigitavel, String codigoBarras, LocalDate dueDate, LocalDate paymentLimitDate, String paidVia) {}

  public static PaymentResponse from(Payment p) {
    PixDetails pix = p.pix();
    BoletoDetails boleto = p.boleto();
    return new PaymentResponse(
        p.id(),
        p.status().name(),
        p.method().name(),
        p.provider(),
        p.environment().name(),
        p.amount().cents(),
        p.amount().currency(),
        p.reference(),
        p.description(),
        pix == null ? new Pix(p.id(), null, null, null) : new Pix(pix.txid(), pix.pixCopiaECola(), pix.location(), pix.endToEndId()),
        boleto == null ? null : new Boleto(boleto.linhaDigitavel(), boleto.codigoBarras(), boleto.dueDate(), boleto.paymentLimitDate(), boleto.paidVia() == null ? null : boleto.paidVia().name()),
        p.expiresAt(),
        p.paidAt(),
        p.paidAmount() == null ? null : p.paidAmount().cents(),
        p.refundedAmount().cents(),
        p.createdAt());
  }
}
```

`ErrorHandler.domainError`:
```java
  /** ALREADY_PAID is a conflict, not a validation error: the cancel lost to the payer and the resource moved to COMPLETED. */
  @ExceptionHandler(DomainException.class)
  public ProblemDetail domainError(DomainException e) {
    HttpStatus status = "ALREADY_PAID".equals(e.code()) ? HttpStatus.CONFLICT : HttpStatus.UNPROCESSABLE_ENTITY;
    return problem(status, e.code(), e.getMessage());
  }
```

`application.yml` — dentro de `gateway.providers.itau` e `gateway.payments`:
```yaml
  providers:
    itau:
      live-api-base: https://pix-pj.api.itau.com/regulatorio-pix/v2
      live-token-url: https://sts.itau.com.br/as/token.oauth2
      test-api-base: https://sandbox.devportal.itau.com.br/itau-ep9-api-regulatorio-pix-v2-externo/v2
      test-token-url: https://sandbox.devportal.itau.com.br/api/oauth/jwt
      test-mutual-tls: false
      live-mutual-tls: true
      trust-store-pem: ${ITAU_CA_PEM:}         # Itau CA chain (ca-cert.zip from the portal); empty = JDK truststore
      read-timeout: PT30S                      # the bank's recommended client timeout (NOTES.md); 10 s cut off slow-but-successful PUTs
      # Bolecode: three products, three hosts (spec 2026-09-25 §1). Issue and query authenticate at the Pix STS
      # path; cash_management declares its own tokenUrl in its OpenAPI. No evidence yet of which STS path each
      # LIVE API really honours, so every one is configurable. Sandbox: all behind the portal, /api/oauth/jwt.
      boleto:
        live-issue-api-base: https://pix-pj.api.itau.com/recebimentos-pix/v1
        live-issue-token-url: https://sts.itau.com.br/as/token.oauth2
        live-query-api-base: https://secure.api.cloud.itau.com.br/boletoscash/v2
        live-query-token-url: https://sts.itau.com.br/as/token.oauth2
        live-instruction-api-base: https://api.gateway.itau.com.br/cash_management/v2
        live-instruction-token-url: https://sts.itau.com.br/api/oauth/token
        test-issue-api-base: https://sandbox.devportal.itau.com.br/itau-ep9-api-recebimentos-v1-externo/v1
        test-issue-token-url: https://sandbox.devportal.itau.com.br/api/oauth/jwt
        test-query-api-base: https://sandbox.devportal.itau.com.br/itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws/v1
        test-query-token-url: https://sandbox.devportal.itau.com.br/api/oauth/jwt
        test-instruction-api-base: https://sandbox.devportal.itau.com.br/itau-ep9-gtw-cash-management-ext-v2/v2
        test-instruction-token-url: https://sandbox.devportal.itau.com.br/api/oauth/jwt
  payments:
    default-expires-in-seconds: 3600
    expiration-grace: PT5M
    reconciliation-lookback: PT48H
    reconciliation-min-age: PT10M
    idempotency-ttl: PT24H
    refund-not-found-grace: PT30M              # a refund PUT that timed out / got a 503 and the bank still does not know is FAILED after this
    reconcile-lease: PT10M                     # a RECONCILE run lasts minutes; the 2 min job lease let a second worker start another
    job-max-attempts: 8
    job-lease: PT2M
    outbox-lease: PT1M
    outbox-relay-ms: 1000
    jobs-poll-ms: 2000
    stuck-sweep-ms: 60000
    boleto-poll-every: PT6H                    # barcode payments clear in D+1; six hours keeps the merchant inside the business day
    boleto-poll-max-attempts: 15000            # 10 years / 6 h; the date check in BoletoPollingService is what really stops a poll
    boleto-poll-grace-after-limit: P2D         # a last-minute payment is credited on the next business day
    boleto-default-due-in-days: 3
    boleto-default-payment-limit-days: 30
    boleto-max-payment-limit-days: 3650        # the bank's maximum (issue OpenAPI, data_limite_pagamento)
```
(mantendo as demais chaves como estão.)

- [ ] **Step 4: Rodar e ver passar**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -q -B -o -pl gateway-app -am test` (o `app` inteiro com os módulos a montante: `PaymentsFlowIntegrationTest`, `ItauWebhookMtlsIntegrationTest` e `ArchitectureTest` precisam continuar verdes).
Expected: PASS. Se `BolecodeFlowIntegrationTest` falhar em `payment_limit_date`: o fixture da emissão devolve `2027-01-31` e o gateway grava o que o banco respondeu (`withIssued`); o teste espera exatamente isso. Se falhar no passo 5 com violação de `uq_payments_provider_txid`, o `replace` do txid no stub não pegou — o valor tem de ser o do fixture `dados_qrcode.txid`.

- [ ] **Step 5: Commit**

```bash
git add gateway-app
git commit -m "feat(app): bolecode in the payments api: payer and dates in the request, boleto block in the response, three itau bases in the config

The response carries a boleto block for every method (null for Pix)
so the merchant's key set never changes, and the contract test holds
it equal to the webhook's. ALREADY_PAID is a 409: the cancel lost to
the payer and the resource moved. Each Itau API gets its own base and
token URL because the three products live on three hosts.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 13: README, `.env.example`, `NOTES.md` (smoke no sandbox), `DECISOES.md`, ArchUnit e suíte inteira

**Files:**
- Modify: `README.md` (seção "Payments (Pix / Itaú)": subseções novas), `.env.example`, `docs/providers/itau/NOTES.md` (seção "Bolecode"), `docs/superpowers/DECISOES.md` (append), `gateway-app/src/test/java/com/gateway/app/architecture/ArchitectureTest.java:16-20` (guarda de vacuidade)
- Test: suíte inteira (`./mvnw -B -o verify`).

- [ ] **Step 1: README**

Acrescente, dentro de "Payments (Pix / Itaú)", depois de "Registering a credential":

```markdown
### Bolecode (boleto with Pix)

`POST /v1/payments` with `"method": "BOLECODE"` issues a registered boleto **and** a Pix QR in one call
(Itaú `POST /boletos-pix`). The payer chooses: the QR settles at once and arrives by the Pix webhook; the
barcode clears in D+1 and is found by a poll of Itaú's boleto query every 6 hours (there is no boleto
webhook in this version — see DECISOES). The response carries both:

```json
{
  "id": "…", "status": "PENDING", "method": "BOLECODE",
  "boleto": {"linha_digitavel": "…47 digits", "codigo_barras": "…44 digits", "due_date": "2026-10-01",
             "payment_limit_date": "2026-10-31", "paid_via": null},
  "pix": {"txid": "BL…", "copia_e_cola": "…", "location": null, "end_to_end_id": null},
  "expires_at": "2026-11-01T02:59:59Z"
}
```

Request: `customer` is required and complete — `name`, `document` (CPF 11 digits or CNPJ 14 digits) and
`address{street, district, city, state (UF), zip (8 digits)}`; a missing field is `422 CUSTOMER_REQUIRED`
naming it. `due_date` defaults to today + 3 days (São Paulo) and must not be in the past; `payment_limit_days`
defaults to 30 (max 3650). `expires_in` is Pix-only: a Bolecode expires at the end of its payment limit date,
never at the due date (a late boleto still pays, with the bank's interest rules out of scope).

`payment.completed` says how it was paid: `boleto.paid_via` is `PIX` or `BOLETO`. `POST …/cancel` does the
bank's baixa; if the bank already shows the boleto paid, the payment completes and the cancel answers
`409 ALREADY_PAID`. `POST …/refunds` on a payment settled by boleto is `422 REFUND_NOT_SUPPORTED` (the bank has
no refund for a boleto); one settled by the QR refunds like any Pix.

The credential needs the boleto account. Add to the `ITAU` payload (TEST or LIVE):

```json
{ "client_id": "...", "client_secret": "...", "pix_key": "...",
  "beneficiary_id": "<agencia 4 + conta 7 + DAC 1>", "wallet_code": "109", "species_code": "01" }
```

`wallet_code` and `species_code` default to `109`/`01` when omitted; without `beneficiary_id` a Bolecode is
refused with `422 PROVIDER_CREDENTIALS_MISSING` before anything is written. Nosso número is allocated by the
gateway, sequential per merchant from `00000001`.

Divergences a boleto can open (`reconciliation_divergences`, for a human): `AMOUNT_MISMATCH` (bank paid a
different amount), `DOUBLE_PAYMENT` (paid by QR and by barcode), `BOLETO_REJECTED`, `CANCELED_AT_BANK` (a baixa
done outside the gateway), `NOT_FOUND_AT_BANK` (two consecutive empty queries), `PIX_TXID_UNCONFIRMED` (a boleto
adopted from the query whose derived Pix txid the bank does not know).
```

E na subseção "Background jobs":
```markdown
- **Boleto polling.** A `POLL_BOLETO` job per Bolecode asks Itaú's boleto query every 6 hours
  (`gateway.payments.boleto-poll-every`) until the payment limit date plus 2 days; paid completes the payment
  with `paid_via = BOLETO`, anything the gateway cannot act on becomes a divergence. Reconciliation runs the same
  check for every `PENDING` Bolecode older than `reconciliation-min-age`.
```

- [ ] **Step 2: `.env.example`**

Acrescente às duas seções do Itaú:
```
# --- Itaú sandbox (portal "criar credenciais"); TEST environment, no certificate ---
ITAU_SANDBOX_CLIENT_ID=
ITAU_SANDBOX_CLIENT_SECRET=
ITAU_SANDBOX_PIX_KEY=          # the receiving Pix key the sandbox accepts, e.g. the CNPJ used in the docs examples
ITAU_SANDBOX_BENEFICIARY_ID=   # Bolecode: agencia (4) + conta (7) + DAC (1); the docs' example is 150000052061
ITAU_SANDBOX_WALLET_CODE=109   # Bolecode carteira; 109 is the one the product documents
ITAU_SANDBOX_SPECIES_CODE=01   # Bolecode especie; 01 = DM (duplicata mercantil)

# --- Itaú production (dynamic certificate flow); LIVE environment ---
ITAU_LIVE_CLIENT_ID=
ITAU_LIVE_CLIENT_SECRET=
ITAU_LIVE_APIKEY=              # x-itau-apikey (uuid)
ITAU_LIVE_CERT_PEM_PATH=
ITAU_LIVE_KEY_PEM_PATH=
ITAU_LIVE_PIX_KEY=
ITAU_LIVE_BENEFICIARY_ID=      # Bolecode: the account's agencia+conta+DAC, 12 digits
ITAU_LIVE_WALLET_CODE=109
ITAU_LIVE_SPECIES_CODE=01
ITAU_CA_PEM=                   # CA chain from the portal's ca-cert.zip (used by the app as gateway.providers.itau.trust-store-pem)
```
(as linhas já existentes ficam; só as `*_BENEFICIARY_ID`, `*_WALLET_CODE`, `*_SPECIES_CODE` são novas.)

- [ ] **Step 3: `NOTES.md` — seção Bolecode e o procedimento de smoke**

Acrescente ao final de `docs/providers/itau/NOTES.md`:

```markdown
## Bolecode (boleto with Pix) — read 2026-09-25

Four products on the portal; the OpenAPIs are next to this file.

| role | product | version | operation |
|---|---|---|---|
| issue | `itau-ep9-api-recebimentos-v1-externo` | 1.0.7 | `POST /boletos-pix` |
| query | `itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws` | 1.2.9 | `GET /boletos?id_beneficiario&codigo_carteira&nosso_numero` |
| baixa | `itau-ep9-gtw-cash-management-ext-v2` | 2.75.147 | `PATCH /boletos/{id_boleto}/baixa` |
| webhook | `itau-ep9-gtw-boletos-boletos-v3-ext-aws` | 1.34.1 | out of scope (needs an OAuth2 server on our side; payload not in the OpenAPI) |

Base URLs: production `https://pix-pj.api.itau.com/recebimentos-pix/v1`, `https://secure.api.cloud.itau.com.br/boletoscash/v2`,
`https://api.gateway.itau.com.br/cash_management/v2`; sandbox `https://sandbox.devportal.itau.com.br/<product>/v1|v1|v2`.
Auth as Pix (STS + mTLS + `x-itau-apikey`; sandbox `/api/oauth/jwt` without mTLS), except that cash_management's OpenAPI
declares `tokenUrl: https://sts.itau.com.br/api/oauth/token` — configured per API in `application.yml` until production says which.

Facts that shaped the code (all from the JSON, not the prose):

- Errors are `{codigo, mensagem, campos[{campo, mensagem, valor}]}`, not RFC 7807. `campos[].valor` echoes what we sent (the
  payer's document included) and never reaches a log line.
- The baixa's `id_boleto` is `agência(4)+conta(7)+DAC(1)+carteira(3)+nosso número(8)` (23 chars), not the boleto UUID.
- The Pix `txid` of a Bolecode is `BL` + agência(4) + conta(7) + carteira(3) + nosso número left-padded to 15 (`^BL[0-9]{31}$`);
  the gateway derives it when the issue's answer was lost and confirms it with `GET /cob/{txid}`.
- `situacao_geral_boleto` ∈ `Em Aberto | Pago | Liquidado | Pagamento Rejeitado | Aguardando Crédito | Creditado | Baixado`;
  the payment record is the list `pagamentos_cobranca[]` (`valor_pago_total_cobranca`, `data_inclusao_pagamento`, …).
- Forbidden anywhere in the issue payload: `[ : < > & ; ' " ` ( ) # * / | ü` and the words `http`, `javascript`, `alert`; each text
  field also has its own character class — the gateway filters by the class (`BoletoText`).
- `etapa_processo_boleto = "simulacao"` validates without issuing. Used only in the smoke below.

### Sandbox smoke (to run after the plan; record the outcome here)

Never paste credential values anywhere but the running request. `<...>` stays `<...>`.

1. Portal: subscribe the app to the three products above; the sandbox `client_id`/`client_secret` are the same as for Pix.
   `<beneficiary_id>` for the sandbox is whatever the portal shows for the sandbox account (the docs' example is `150000052061`).
2. Token: `curl -s -XPOST https://sandbox.devportal.itau.com.br/api/oauth/jwt -H 'Content-Type: application/x-www-form-urlencoded' -d 'grant_type=client_credentials&client_id=<client_id>&client_secret=<client_secret>'`
3. Simulação (validates, does not issue): take `gateway-providers/src/test/resources/itau/boleto/fixtures/post_boletos_pix_request_min.json`,
   set `etapa_processo_boleto` to `simulacao` and `beneficiario.id_beneficiario` to `<beneficiary_id>`, then
   `curl -s -XPOST https://sandbox.devportal.itau.com.br/itau-ep9-api-recebimentos-v1-externo/v1/boletos-pix -H 'Authorization: Bearer <token>' -H 'x-itau-correlationID: <uuid>' -H 'Content-Type: application/json' -d @body.json`.
   Expected: 200 with `dados_individuais_boleto[0]` and `dados_qrcode`, or a `{codigo, mensagem, campos}` body — either way, record status and field names.
4. Efetivação through the gateway: register the credential with `beneficiary_id`, `wallet_code`, `species_code`
   (`PUT /v1/admin/merchants/<id>/providers/ITAU/credentials`, `README.md`), then `POST /v1/payments` with `method: BOLECODE` and a
   complete `customer` using a `gk_test_` key; `GET /v1/payments/<id>`; `POST /v1/payments/<id>/cancel`.
5. Query directly: `curl -s 'https://sandbox.devportal.itau.com.br/itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws/v1/boletos?id_beneficiario=<beneficiary_id>&codigo_carteira=109&nosso_numero=<nosso_numero>' -H 'Authorization: Bearer <token>' -H 'x-itau-correlationid: <uuid>'`.
6. Record here, like the Pix table above: which calls answered what, whether the sandbox echoed our nosso número and txid or its
   example's (the Pix sandbox did not echo; if this one does not either, a second Bolecode in the same database will hit
   `uq_payments_provider_txid` on the example's fixed `BL…` txid — expected, note it), and whether `x-itau-apikey` was required.
```

- [ ] **Step 4: `DECISOES.md`**

Acrescente (português, mesma forma das entradas existentes):

```markdown
## 2026-09-25 — Bolecode: polling da consulta de detalhe em vez de webhook de boleto
Rejeitado: o webhook de boleto (Boletos v3). Motivo: exige que o gateway exponha um servidor OAuth2 client
credentials para o banco pegar token, e o payload da notificação não consta no OpenAPI — só entra com
homologação. O `POLL_BOLETO` consulta `GET /boletos` a cada 6 h até a data limite + 2 dias; a reconciliação
faz a mesma pergunta para todo `PENDING` com mais de `reconciliation-min-age`. Custo: até 6 h de atraso para
saber de um pagamento em código de barras, que já compensa em D+1. Custo se errado: nenhum dinheiro perdido,
só latência; o webhook pode entrar depois sem mudar o modelo (o polling vira rede de segurança).

## 2026-09-25 — Bolecode expira pela data limite, não pelo vencimento
Rejeitado: expirar no `due_date`. Motivo: boleto vencido paga (com juros, fora de escopo); expirar no vencimento
cancelaria cobranças que ainda entram. `expires_at` é 23:59:59 America/Sao_Paulo da `payment_limit_date`; a
expiração pergunta ao banco antes e não manda baixa (após a data limite o banco recusa sozinho). Custo se
errado: um pagamento no último dia, creditado no dia útil seguinte — coberto pelos 2 dias de folga do poll e
por `EXPIRED → COMPLETED` via `PROVIDER_POLL`/`RECONCILIATION`.

## 2026-09-25 — Nosso número sequencial por merchant, alocado com o CREATED
Rejeitado: derivar do ULID (não cabe em 8 dígitos); um contador em memória. Motivo: `boleto_numbers` com
`INSERT … ON CONFLICT DO UPDATE … RETURNING` na mesma transação do `CREATED` — dois threads nunca repetem, um
rollback devolve o número. Reutilização (45 dias após baixa/liquidação) não é tratada: 10^8 por merchant.
Custo se errado: um número repetido é 4xx do banco na emissão, não dinheiro.

## 2026-09-25 — Sem devolução por boleto
Rejeitado: fingir a devolução com uma transferência. Motivo: a API não tem devolução de boleto; uma
transferência seria dinheiro saindo por um caminho que o gateway não controla nem concilia. `paid_via = BOLETO`
→ `422 REFUND_NOT_SUPPORTED`; pago pelo QR devolve como Pix. Custo se errado: o merchant resolve fora do gateway,
como já faz hoje para qualquer boleto.

## 2026-09-25 — O id da baixa e o txid do Pix vêm da fórmula do OpenAPI, não do UUID
Registrado: a spec dizia `cancel(idBoletoIndividual)` e `txid = BL + agência + 00 + conta + carteira + 0000000 +
nosso número`. O OpenAPI da cash_management define `id_boleto` como agência+conta+DAC+carteira+nosso número
(23 chars, `minLength 23`), e o da emissão define o txid como `BL` + agência(4) + conta(7) + carteira(3) +
nosso número(15). O código segue o JSON; o txid derivado só é usado quando a resposta da emissão se perdeu, e
é confirmado com `GET /cob/{txid}` antes de valer (divergência `PIX_TXID_UNCONFIRMED` se não bater). Custo se
errado: um cancelamento 404 no banco (visível em `provider_requests`) e uma divergência — nunca um pagamento
casado errado, porque o webhook Pix casa pelo txid que o banco devolveu na emissão.

## 2026-09-25 — Timeout na emissão sem boleto na consulta deixa CREATED, não FAILED
Rejeitado: marcar `FAILED` na hora, como o Pix faz. Motivo: a emissão responde 202 "operação em andamento";
uma consulta vazia um segundo depois não prova nada. O `sweepStuckCreated` pergunta de novo após
`stuck-created-after` e decide (adota ou falha). Custo: o merchant recebe 422 `PROVIDER_TIMEOUT` e precisa
consultar por `reference` antes de tentar com outra chave — o mesmo protocolo do 409 `IN_PROGRESS`. Custo se
errado (falhar na hora): um boleto emitido e pagável que o gateway chamou de falho.
```

- [ ] **Step 5: ArchUnit**

`ArchitectureTest.importSeesTheModules`: atualize o comentário e o número — `find gateway-*/src/main -name '*.java' | wc -l` depois desta task (≈200) e a guarda para metade: `isGreaterThan(90)`.

- [ ] **Step 6: Suíte inteira**

Run: `export JAVA_HOME=~/.jdks/corretto-25.0.4.1 PATH=~/.jdks/corretto-25.0.4.1/bin:$PATH && ./mvnw -B -o verify`
Expected: BUILD SUCCESS; cole no relatório a contagem de testes por módulo (`Tests run:` de cada `surefire-reports`). Confira à mão que `ArchitectureTest` passou (as regras `itauVocabularyStaysInProviders`, `paymentsDoesNotImportProviders`, `jpaOnlyInPersistence`, `modelsHaveNoSpring`): `BoletoPollingService` termina em `Service`, `BoletoNumberRepositoryImpl` está em `..persistence..`, nenhum nome com `Itau` fora de `providers`.

- [ ] **Step 7: Commit**

```bash
git add README.md .env.example docs/providers/itau/NOTES.md docs/superpowers/DECISOES.md gateway-app/src/test/java/com/gateway/app/architecture/ArchitectureTest.java
git commit -m "docs: bolecode setup, sandbox smoke procedure, plan c decisions, archunit guard raised

README documents the request, the response, the credential fields and
the divergences a boleto can open; NOTES.md keeps the OpenAPI facts
that contradicted the spec (baixa id, txid formula, error shape) and
the smoke steps with placeholders only; DECISOES records the five
choices with their cost if wrong.

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

## Self-review

**Cobertura da spec (2026-09-25).**
§1 (APIs, bases, auth, token URL da cash_management, fatos do schema, caracteres proibidos, resposta 200, `simulacao` só no smoke, 202 como timeout) → T2 (endpoints e token URL por API), T3 (request/response/erros/sanitização/202), T13 (smoke com `simulacao`).
§2 (`BOLECODE`, `details.pix`+`details.boleto`, `BoletoDetails`/`BoletoDetailsJson`, índice por nosso número, `uq_payments_provider_txid` mantido, tabela de transições com `PROVIDER_POLL`, `EXPIRED → COMPLETED`, `PENDING → CANCELED` via baixa, `paidVia` no `markCompleted` e no evento, `REFUND_NOT_SUPPORTED`, `boleto_numbers` com `UPDATE … RETURNING`) → T6, T7, T8, T10, T11.
§3 (contrato do kernel; `Resolved` com `boleto`) → T1, T9. Desvios documentados na tabela inicial e nas Interfaces de T1.
§4 (`beneficiary_id`/`wallet_code`/`species_code`, regexes, `PROVIDER_CREDENTIALS_MISSING` com `beneficiary_id`, README/.env) → T2, T9, T13.
§5 (request com `customer` completo, `due_date`, `payment_limit_days`, regras e defaults, resposta 201 com `boleto` e `pix`, cancel, refund 422, 422 `CUSTOMER_REQUIRED` com o campo) → T9 (validação e defaults), T12 (DTOs, controller, 409 `ALREADY_PAID`, fluxo).
§6 (três clientes, `ItauBoletoProvider`, `ItauEndpoints` com as fábricas e `CASH_MANAGEMENT_TOKEN_URL`, schema validation e fixtures pinados) → T3, T4, T5.
§7 (criar: validar → reservar número + `CREATED` numa transação → `issue` fora → `PENDING` + outbox + `POLL_BOLETO` +6 h + `EXPIRE_PAYMENT` na data limite; timeout/202: `find` → adota (EMV via `GET /cob` com txid reconstruído) / `stuckCreatedAfter` → `FAILED`; pago pelo QR via webhook com `paidVia = PIX`; tabela do poll linha a linha incluindo `DOUBLE_PAYMENT`, `AMOUNT_MISMATCH`, `BOLETO_REJECTED`, `CANCELED_AT_BANK`, `NOT_FOUND_AT_BANK`; job sem efeito em `COMPLETED` via Pix; cancelar com `find` antes e 409; expirar na data limite com `find` antes; reconciliar `PENDING` de `BOLECODE` e a janela Pix enxergando `BL…`) → T9, T10, T11.
§8 (fora de escopo) — nenhuma task cria webhook de boleto, juros/multa/desconto, devolução de boleto ou reutilização de número. O `BoletoPixRequest` não envia `juros`, `multa`, `desconto`, `forma_envio`, `sacador_avalista`.
§9 (testes) — `BoletoPixApiClientContractTest`, `BoletoQueryClientContractTest`, `BoletoInstructionClientContractTest`, `BoletoTextTest`, `BoletoSituationsTest`, `BoletoRequestSchemaValidationTest`, `ItauCredentialsTest` (novos casos), `ItauTokenClientPerApiTest`; `PaymentTransitionsTest` com `PROVIDER_POLL`; `BoletoDetailsJsonTest`; `RecordingBoletoProvider`; `BolecodeServiceIntegrationTest` + `BoletoPollingIntegrationTest` + `BolecodeLifecycleIntegrationTest` (criar, timeout + adoção, cada situação, cancelar aberto/pago/corrida, expirar, reconciliar, refund recusado, double payment); `BoletoNumberRepositoryIntegrationTest` (dois threads); `BolecodeFlowIntegrationTest` (201, 422, cancel, refund 422); contrato JSON; smoke documentado em `NOTES.md`.
§10 (decisões) → T13.

**Placeholders.** Nenhum "TBD"/"TODO"/"similar to Task N"; cada step de código traz o código. Os três pontos de incerteza de API (validador de schema com `$ref` de `allOf`; `RETURNING` numa `INSERT` nativa pelo Hibernate; `equalToJson` com acentos) nomeiam o teste como árbitro e a alternativa concreta a usar.

**Consistência de tipos.**
`BoletoProvider.{requireIssueCredentials, issue, find, cancel(nossoNumero), pixTxidFor}` (T1) é o que `ItauBoletoProvider` (T5), `RecordingBoletoProvider` (T9) e `PaymentService`/`BoletoPollingService`/`ExpirationService` (T9–T11) chamam.
`BoletoStatus(situation, paidAmount, paidAt, paidChannel, idBoletoIndividual, linhaDigitavel, codigoBarras, paymentLimitDate, pixCopiaECola)` — 9 componentes em T1, T5 (`toStatus`), T9 (`RecordingBoletoProvider`, `adoptBolecodeFromStatus`), T10.
`IssuedBoleto(idBoletoIndividual, linhaDigitavel, codigoBarras, paymentLimitDate, pixTxid, pixCopiaECola, pixKey)` — T1, T5, T9.
`ProviderException.Code.{CONFLICT, CREDENTIALS_INCOMPLETE}` — T1, T3 (`BoletoErrors`), T5 (`baixa`, `boletoCreds`), T9, T11.
`ItauCredentials(…, beneficiaryId, walletCode, speciesCode, fingerprint)` com `requireBoletoShape()` — T2, T3 (`forIssue`), T4 (`find`), T5.
`ItauEndpoints.{boletoIssue, boletoQuery, boletoInstruction, CASH_MANAGEMENT_TOKEN_URL}` e `ItauBoletoEndpoints(issue, query, instruction)` — T2, T5 (`ProvidersProperties.boletoLive/boletoTest`), T12 (yml).
`Payment.{createBolecode, markPendingBolecode, markCompletedByBoleto, method(), boleto()}` e o `rehydrate` de 20 argumentos — T6, T7 (`PaymentRepositoryImpl`), T9, T10, T12 (`PaymentJsonContractTest`).
`BoletoDetails(nossoNumero, idBoletoIndividual, linhaDigitavel, codigoBarras, dueDate, paymentLimitDate, paidVia)` com `withIssued`/`withPaidVia` — T6, T7, T9, T12.
`PaymentDetailsJson.{write(pix, boleto), readPix, readBoleto}` — T7.
`PaymentRepository.findByMerchantAndTxid(MerchantId, String provider, String txid)` — T7, T11.
`BoletoNumberRepository.next(MerchantId)` — T8, T9.
`ProviderGateway.Resolved(provider, boleto, credentials)` e o construtor com `List<BoletoProvider>` — T9 (`PaymentsConfiguration` via `ObjectProvider`), usado em T10/T11 como `r.boleto().orElseThrow()`.
`PaymentsProperties.{boletoPollEvery, boletoPollMaxAttempts, boletoPollGraceAfterLimit, boletoDefaultDueInDays, boletoDefaultPaymentLimitDays, boletoMaxPaymentLimitDays}` — T9, T10 (`JobRunner`, `BoletoPollingService`), T12 (yml `boleto-*`).
`PaymentService.{CreateBolecode, createBolecode, adoptPendingBolecode, adoptBolecodeFromStatus, settleBoleto, validatePayer}` — T9, T10, T11, T12 (controller).
`BoletoPollingService.check(String, EventSource)` — T10 (`JobRunner`), T11 (`ReconciliationService`).
`Job.pollBoleto(paymentId, firstAt, clock)` e `JobType.POLL_BOLETO` — T6, T9, T10.
`CreatePaymentRequest.{payer(), isBolecode(), dueDate(), paymentLimitDays()}` e `PaymentResponse.Boleto` — T12.
Códigos de erro usados e onde nascem: `CUSTOMER_REQUIRED`, `INVALID_DUE_DATE`, `INVALID_PAYMENT_LIMIT`, `PROVIDER_CREDENTIALS_MISSING`, `METHOD_NOT_SUPPORTED` (T9); `ALREADY_PAID` (T11 → 409 em T12); `REFUND_NOT_SUPPORTED` (T11); divergências `AMOUNT_MISMATCH`, `DOUBLE_PAYMENT`, `BOLETO_PAID`, `BOLETO_REJECTED`, `CANCELED_AT_BANK`, `NOT_FOUND_AT_BANK` (T10), `PIX_TXID_UNCONFIRMED` (T9) — todos citados no README (T13).
