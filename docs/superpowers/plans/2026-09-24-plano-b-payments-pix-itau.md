# Payment Gateway — Plano B: payments Pix com o Itaú

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Um merchant cria uma cobrança Pix pela API do gateway, o Itaú emite o QR, o webhook mTLS do Itaú (ou a reconciliation) marca o pagamento como `COMPLETED`, o merchant recebe `payment.completed` no webhook dele, pode cancelar e devolver — com idempotência que garante **uma cobrança no banco por chave**, sem provider fake, contra a API Pix regulatória v2 do Itaú (`docs/providers/itau/NOTES.md`).

**Architecture:** Dois módulos novos. `gateway-providers` fala com o Itaú: `ItauTokenClient` (OAuth2 client-credentials sobre mTLS, token 300 s cacheado por credencial), `PixApiClient` (contrato Bacen/Itaú, JDK `HttpClient` com `SSLContext` montado do PEM da credencial), `ItauPixProvider` implementando `PixProvider`; tudo testado em WireMock com fixtures copiadas dos exemplos do OpenAPI oficial e com cada request validado contra o schema. `gateway-payments` é o domínio: `Payment` com tabela de transições, `PaymentEvent` com `sequence`, `Refund` assíncrono, `IdempotencyKey` gravada antes de qualquer chamada externa, `txid` = id do payment, outbox + jobs em Postgres (`SKIP LOCKED`), `webhook_inbox`, expiração com `GET` antes, reconciliation, polling de devolução. O `app` liga: `POST /v1/payments` e afins, um **segundo connector Tomcat com mTLS** para `/v1/providers/itau/webhooks/{token}`, e o relay do outbox que chama `MerchantEvents`. `payments` só conhece `PixProvider` e `CredentialLookup` (interfaces do `kernel`); nunca `ItauPixProvider`.

**Tech Stack:** o do Plano A, mais WireMock 3 (`org.wiremock:wiremock-standalone`), `com.networknt:json-schema-validator` (validar nossos requests contra o OpenAPI do Itaú), BouncyCastle `bcpkix-jdk18on` (ler PEM da chave privada → `KeyStore` em memória), Awaitility.

**Spec:** `docs/superpowers/specs/2026-09-23-payment-gateway-design.md` (§1.5, §1.6, §3, §4, §6, §7, §9 `payments.*`, §10, §11) e `docs/providers/itau/NOTES.md` (fatos do Itaú). Executores leem os três.

## Global Constraints

- **Todo o código em inglês** (identificadores, colunas, comentários, mensagens, commits, README). Vocabulário canônico do gateway em inglês; o vocabulário do Itaú (`cob`, `txid`, `e2eid`, `devolucao`, `pixCopiaECola`) aparece **só** em `gateway-providers` e nos JSONs.
- Módulos: `gateway-providers` (`com.gateway.providers`) e `gateway-payments` (`com.gateway.payments`). Regras ArchUnit já escritas no Plano A passam a morder: `payments` não importa `providers` além das interfaces de `kernel`… **correção**: `payments` importa `providers`? Não. A regra da spec é "`payments` só conhece a interface `PixProvider`". Por isso as interfaces `PixProvider`, `CredentialLookup` e os tipos canônicos (`Charge`, `ChargeStatus`, `RefundResult`, `ProviderWebhookEvent`, `ProviderException`) moram em **`gateway-kernel`** (`com.gateway.kernel.provider`). `providers` implementa; `payments` consome; `app` injeta. `onlyPaymentsKnowsProviders` continua verdadeira (ninguém além de `app` importa `com.gateway.providers`).
- **Sem provider fake.** `TEST` → sandbox do Itaú (token em `sandbox.devportal.itau.com.br/api/oauth/jwt`, **sem mTLS**, credencial `{client_id, client_secret, pix_key}`); `LIVE` → produção (STS + mTLS + `x-itau-apikey`, credencial de seis valores). Credencial ausente → `DomainException("PROVIDER_CREDENTIALS_MISSING")` antes de qualquer chamada. As credenciais de sandbox da conta do dono existem desde 2026-09-24 e **nunca** entram em teste, commit ou log — só no `PUT …/credentials` de um ambiente rodando.
- **Nenhum teste fala com a rede.** WireMock em `localhost` (HTTP para o `PixApiClient`; HTTPS com keystore de teste para o `ItauTokenClient`, que precisa provar o mTLS). Fixtures em `gateway-providers/src/test/resources/itau/fixtures/*.json`, copiadas verbatim de `docs/providers/itau/itau-ep9-api-regulatorio-pix-v2-externo.openapi.json` (`components.examples`). Todo request que o gateway monta é validado contra o schema do OpenAPI num teste (`networknt`).
- Flyway: `schemas: merchants,payments` **nesta ordem** (o histórico mora no primeiro schema), `locations: classpath:db/migration/merchants,classpath:db/migration/payments`; migrations de payments `V200__*`. Entidades `@Table(schema = "payments")`, package-private. `PaymentsConfiguration` declara `@EntityScan`/`@EnableJpaRepositories` **só do próprio pacote** (`com.gateway.payments.repository`); não repetir pacotes de outros.
- `payments` e `providers` não importam `com.gateway.app` (`nobodyImportsApp`). O relay do outbox mora em `app` e lê `OutboxRepository` de `payments`.
- Ambiente (`LIVE`/`TEST`) vem **sempre** de `MerchantContext.current().environment()`, nunca do corpo.
- Valores: `Money` em centavos; o Itaú fala string `\d{1,10}\.\d{2}` — a conversão vive em um único lugar (`PixAmounts`) com teste de ida e volta.
- Idempotência: PK `(merchant_id, key)`; `IN_PROGRESS` gravado **antes** da chamada ao banco; `txid = payment id` (ULID); timeout no `PUT /cob` → `GET /cob/{txid}` antes de recriar.
- Comentários registram POR QUÊ com evidência. Commits `type(scope): lowercase subject`, corpo em inglês, terminando com `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Ambiente local: `export JAVA_HOME="$HOME/.jdks/corretto-25.0.4.1"`; Docker para Testcontainers; suíte inteira `./mvnw -B test` em foreground (600000 ms).

---

## Estrutura de arquivos

```
gateway-kernel/src/main/java/com/gateway/kernel/provider/
  PixProvider.java                 interface que payments consome
  CredentialLookup.java            interface que merchants implementa (bytes decifrados por (merchant, provider, env))
  ProviderEnvironment.java         enum LIVE, TEST (kernel não pode importar ApiKeyEnvironment de merchants)
  Charge.java, ChargeStatus.java   canônicos: ACTIVE, COMPLETED, REMOVED_BY_MERCHANT, REMOVED_BY_PSP
  ReceivedPix.java                 e2eid, amount, paidAt, payerInfo
  RefundRequest.java, RefundResult.java, RefundStatus.java   PROCESSING, COMPLETED, FAILED
  ProviderWebhookEvent.java        lista de ReceivedPix + refund updates
  ProviderException.java           código normalizado: DECLINED, UNAVAILABLE, INVALID, TIMEOUT, NOT_FOUND, UNAUTHENTICATED, UNKNOWN
  ProviderCredentials.java         record bytes+env (o payload cifrado já decifrado)
gateway-providers/
  pom.xml
  src/main/java/com/gateway/providers/itau/
    ItauCredentials.java           parse do JSON {client_id, client_secret, x_itau_apikey, certificate_pem, private_key_pem, pix_key}
    PemKeyStores.java              PEM → KeyStore/SSLContext (BouncyCastle)
    ItauEndpoints.java             base URLs por ambiente + STS URL
    ItauTokenClient.java           POST sts /as/token.oauth2 (mTLS), cache 240 s por hash da credencial
    PixApiClient.java              /cob, /pix, /devolucao com JDK HttpClient; erros RFC 7807 → ProviderException
    PixAmounts.java                Money ↔ "123.45"
    ItauPixProvider.java           PixProvider
    dto/*.java                     records Jackson dos payloads do Itaú (package-private)
  src/main/java/com/gateway/providers/ProvidersConfiguration.java
  src/test/resources/itau/fixtures/*.json, itau/openapi.json (cópia), test-ca/* (gerados no teste, não commitados)
  src/test/java/com/gateway/providers/itau/...   PemKeyStoresTest, ItauTokenClientMtlsTest, PixApiClientContractTest, ItauPixProviderTest, RequestSchemaValidationTest
gateway-payments/
  pom.xml
  src/main/java/com/gateway/payments/
    domain/Payment.java, PaymentStatus.java, PaymentTransitions.java, PaymentEvent.java, EventSource.java
    domain/Refund.java, RefundState.java, IdempotencyKey.java, IdempotencyStatus.java
    domain/PixDetails.java          txid, pixCopiaECola, e2eid, location
    domain/OutboxMessage.java, Job.java, JobType.java, WebhookInboxEntry.java, ReconciliationDivergence.java
    repository/*Entity, *JpaRepository, *Repository, *RepositoryImpl (payments, payment_events, refunds, idempotency_keys, outbox, jobs, webhook_inbox, provider_requests, reconciliation_divergences)
    service/PaymentService.java     createCharge, get, cancel, list
    service/RefundService.java      request, get, list
    service/IdempotencyService.java begin/finish/replay
    service/ProviderGateway.java    resolve provider + credenciais; wraps calls; grava provider_requests
    service/WebhookInboxService.java accept (raw) + process job
    service/ExpirationService.java, ReconciliationService.java, RefundPollingService.java
    service/JobRunner.java          claim + dispatch por tipo
    service/PaymentsProperties.java
    PaymentsConfiguration.java
  src/main/resources/db/migration/payments/V200__payments.sql
  src/test/java/com/gateway/payments/...  domain (puro), repository (Postgres), service (Postgres + PixProvider de teste em memória — implementação da INTERFACE do kernel usada só em testes de payments; não é um provider do produto)
gateway-app/
  src/main/java/com/gateway/app/
    api/PaymentsController.java, RefundsController.java, dto/*
    api/providers/ItauWebhookController.java      POST /v1/providers/itau/webhooks/{token}/pix → 202
    providers/ProviderWiring.java                 @Import(ProvidersConfiguration, PaymentsConfiguration); CredentialLookup adapter sobre ProviderCredentialService
    outbox/OutboxRelay.java                       @Scheduled: claim outbox → MerchantEvents.emit
    jobs/JobScheduler.java                        @Scheduled: JobRunner.runDue()
    mtls/MtlsWebhookConnector.java                segundo connector Tomcat (porta gateway.webhooks.mtls.port, client-auth=need, truststore)
    security/ProtectedRoutes (já exclui /v1/providers/)
  src/test/java/com/gateway/app/PaymentsFlowIntegrationTest.java, ItauWebhookMtlsIntegrationTest.java
```

---

### Task 1: Contratos no `kernel`, módulos novos, Flyway e ArchUnit

**Files:**
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/provider/{PixProvider,CredentialLookup,ProviderEnvironment,Charge,ChargeStatus,ReceivedPix,RefundRequest,RefundResult,RefundStatus,ProviderWebhookEvent,ProviderException,ProviderCredentials}.java`
- Create: `gateway-providers/pom.xml`, `gateway-payments/pom.xml`, `gateway-payments/src/main/resources/db/migration/payments/V200__payments.sql` (vazio nesta task: só `-- payments schema; tables arrive with Task 6`), `gateway-providers/src/main/java/com/gateway/providers/ProvidersConfiguration.java` e `gateway-payments/src/main/java/com/gateway/payments/PaymentsConfiguration.java` (vazias, `@Configuration(proxyBeanMethods = false)`)
- Modify: `pom.xml` (módulos + dependencyManagement), `gateway-app/pom.xml` (depende dos dois), `gateway-app/src/main/resources/application.yml` e `src/test/resources/application-test.yml` (`schemas: merchants,payments`, `locations` com os dois), `gateway-app/src/test/java/com/gateway/app/architecture/ArchitectureTest.java` (regra nova)
- Test: `gateway-kernel/src/test/java/com/gateway/kernel/provider/ProviderExceptionTest.java`

**Interfaces:**
- Produces (todas em `com.gateway.kernel.provider`):
  ```java
  enum ProviderEnvironment { LIVE, TEST }
  enum ChargeStatus { ACTIVE, COMPLETED, REMOVED_BY_MERCHANT, REMOVED_BY_PSP }
  record ReceivedPix(String endToEndId, Money amount, Instant paidAt, String payerInfo) {}
  record Charge(String txid, ChargeStatus status, Money amount, String pixCopiaECola, String location, Instant createdAt, int expiresInSeconds, List<ReceivedPix> received) { Optional<ReceivedPix> firstPix() }
  record RefundRequest(String endToEndId, String refundId, Money amount) {}
  enum RefundStatus { PROCESSING, COMPLETED, FAILED }
  record RefundResult(String refundId, RefundStatus status, Money amount, String reason, Instant requestedAt, Instant settledAt) {}
  record ProviderWebhookEvent(List<ReceivedPix> received, List<RefundResult> refundUpdates, Map<String,String> txidByEndToEndId) {}
  class ProviderException extends RuntimeException { enum Code { DECLINED, UNAVAILABLE, INVALID, TIMEOUT, NOT_FOUND, UNAUTHENTICATED, UNKNOWN } Code code(); int httpStatus(); String providerType(); }
  record ProviderCredentials(byte[] payload, ProviderEnvironment environment)   // toString masks
  interface CredentialLookup { Optional<ProviderCredentials> find(MerchantId merchantId, String provider, ProviderEnvironment env); }
  interface PixProvider {
    String id();                                                                  // "ITAU"
    Charge createCharge(ProviderCredentials c, String txid, Money amount, int expiresInSeconds, String payerDocument, String payerName, String description);
    Optional<Charge> findCharge(ProviderCredentials c, String txid);
    void cancelCharge(ProviderCredentials c, String txid);
    RefundResult requestRefund(ProviderCredentials c, RefundRequest r);
    Optional<RefundResult> findRefund(ProviderCredentials c, String endToEndId, String refundId);
    List<Charge> listCharges(ProviderCredentials c, Instant from, Instant to);
    ProviderWebhookEvent parseWebhook(byte[] body);
  }
  ```
  `payerDocument`/`payerName` podem ser `null` (a cobrança sem devedor é válida no Bacen).

- [ ] **Step 1: Teste do kernel**

```java
package com.gateway.kernel.provider;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class ProviderExceptionTest {
  @Test void carriesNormalizedCodeAndHttpStatus() {
    ProviderException e = new ProviderException(ProviderException.Code.INVALID, 400, "https://pix.bcb.gov.br/api/v2/error/CobOperacaoInvalida", "schema violation");
    assertThat(e.code()).isEqualTo(ProviderException.Code.INVALID);
    assertThat(e.httpStatus()).isEqualTo(400);
    assertThat(e.providerType()).contains("CobOperacaoInvalida");
    assertThat(e.getMessage()).contains("schema violation");
  }
  @Test void credentialsNeverPrintThePayload() {
    ProviderCredentials c = new ProviderCredentials("{\"client_secret\":\"x\"}".getBytes(), ProviderEnvironment.LIVE);
    assertThat(c.toString()).doesNotContain("client_secret").contains("LIVE");
  }
}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-kernel test` → compilação.

- [ ] **Step 3: Tipos do kernel**

`ProviderException.java`:
```java
package com.gateway.kernel.provider;

/**
 * A provider call failed. {@code code} is the gateway's normalized vocabulary; the raw provider
 * error (RFC 7807 {@code type}) travels in {@code providerType} so support can read what the bank
 * said without the gateway having to model every bank error.
 */
public class ProviderException extends RuntimeException {
  public enum Code { DECLINED, UNAVAILABLE, INVALID, TIMEOUT, NOT_FOUND, UNAUTHENTICATED, UNKNOWN }
  private final Code code;
  private final int httpStatus;
  private final String providerType;

  public ProviderException(Code code, int httpStatus, String providerType, String message) {
    super(message);
    this.code = code; this.httpStatus = httpStatus; this.providerType = providerType;
  }
  public ProviderException(Code code, String message, Throwable cause) {
    super(message, cause);
    this.code = code; this.httpStatus = 0; this.providerType = null;
  }
  public Code code() { return code; }
  public int httpStatus() { return httpStatus; }
  public String providerType() { return providerType; }
}
```
`ProviderCredentials.java`:
```java
package com.gateway.kernel.provider;

public record ProviderCredentials(byte[] payload, ProviderEnvironment environment) {
  @Override public String toString() { return "ProviderCredentials[" + environment + ", payload=***]"; }
}
```
`Charge.java`:
```java
package com.gateway.kernel.provider;

import com.gateway.kernel.money.Money;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The gateway's view of a Pix charge, whatever bank issued it. */
public record Charge(String txid, ChargeStatus status, Money amount, String pixCopiaECola, String location,
                     Instant createdAt, int expiresInSeconds, List<ReceivedPix> received) {
  public Optional<ReceivedPix> firstPix() { return received == null || received.isEmpty() ? Optional.empty() : Optional.of(received.getFirst()); }
}
```
Os demais records/enums/interfaces exatamente como na seção Interfaces (sem lógica). `PixProvider` e `CredentialLookup` com javadoc de uma linha cada dizendo quem implementa e quem consome.

- [ ] **Step 4: Módulos novos e reactor**

`gateway-providers/pom.xml`:
```xml
<project ...>
    <modelVersion>4.0.0</modelVersion>
    <parent><groupId>com.gateway</groupId><artifactId>payment-gateway-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
    <artifactId>gateway-providers</artifactId>
    <dependencies>
        <dependency><groupId>com.gateway</groupId><artifactId>gateway-kernel</artifactId></dependency>
        <dependency><groupId>org.springframework</groupId><artifactId>spring-context</artifactId></dependency>
        <dependency><groupId>tools.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
        <dependency><groupId>org.bouncycastle</groupId><artifactId>bcpkix-jdk18on</artifactId></dependency>
        <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId></dependency>
        <dependency><groupId>org.wiremock</groupId><artifactId>wiremock-standalone</artifactId><scope>test</scope></dependency>
        <dependency><groupId>com.networknt</groupId><artifactId>json-schema-validator</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```
`gateway-payments/pom.xml`: como `gateway-merchants` (kernel + data-jpa + postgres + test deps) **sem** dependência de `gateway-providers`.

Parent `pom.xml`: módulos `gateway-providers`, `gateway-payments` (antes de `gateway-app`); `dependencyManagement` com `com.gateway:gateway-providers`, `com.gateway:gateway-payments`, `org.wiremock:wiremock-standalone:3.13.0`, `com.networknt:json-schema-validator:1.5.6`, `org.bouncycastle:bcpkix-jdk18on:1.80` (se uma versão não resolver, use a mais recente do Central e registre). O `artifactId` do Jackson 3 em Boot 4.0.7: confira em `~/.m2/repository/tools/jackson/core/` (`jackson-databind`); a versão vem do BOM do Boot.

`gateway-app/pom.xml`: adicione `gateway-providers` e `gateway-payments`.

`application.yml` (e `application-test.yml`):
```yaml
  flyway:
    schemas: merchants,payments          # history table lives in the FIRST schema; never reorder
    locations: classpath:db/migration/merchants,classpath:db/migration/payments
```

- [ ] **Step 5: ArchUnit**

Adicione a `ArchitectureTest`:
```java
  @ArchTest
  static final ArchRule paymentsDoesNotImportProviders =
      noClasses().that().resideInAPackage("com.gateway.payments..").should().dependOnClassesThat().resideInAPackage("com.gateway.providers..");

  @ArchTest
  static final ArchRule providersOnlyKnowsKernel =
      noClasses().that().resideInAPackage("com.gateway.providers..")
          .should().dependOnClassesThat().resideInAnyPackage("com.gateway.merchants..", "com.gateway.payments..", "com.gateway.orders..", "com.gateway.app..");

  @ArchTest
  static final ArchRule itauVocabularyStaysInProviders =
      noClasses().that().resideOutsideOfPackage("com.gateway.providers..")
          .should().haveSimpleNameContaining("Itau");
```
Suba o vacuity guard para `> 60` quando os módulos existirem (nesta task ainda não há classes suficientes: deixe `> 30`, e a Task 10 sobe).

- [ ] **Step 6: Compilar tudo e rodar a suíte** — `./mvnw -B test` → verde (o Flyway cria o schema `payments` vazio).

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(kernel): provider contracts, providers and payments modules, flyway for payments

PixProvider and CredentialLookup live in the kernel so payments never
sees ItauPixProvider and providers never sees merchants. Flyway lists
merchants first because the history table lives in the first schema."
```

---

### Task 2: `providers` — credenciais do Itaú, PEM → `SSLContext`, endpoints, `PixAmounts`

**Files:**
- Create: `gateway-providers/src/main/java/com/gateway/providers/itau/{ItauCredentials,PemKeyStores,ItauEndpoints,PixAmounts}.java`
- Test: `gateway-providers/src/test/java/com/gateway/providers/itau/{ItauCredentialsTest,PemKeyStoresTest,PixAmountsTest}.java`, `src/test/java/com/gateway/providers/itau/TestCertificates.java` (helper que gera CA + cert cliente/servidor com BouncyCastle, em memória)

**Interfaces:**
- Produces:
  ```java
  record ItauCredentials(String clientId, Secret clientSecret, String apiKey /* nullable */, String certificatePem /* nullable */, Secret privateKeyPem /* nullable */, String pixKey) {
      static ItauCredentials parse(byte[] json);        // JSON: client_id, client_secret, pix_key obrigatórios; x_itau_apikey, certificate_pem, private_key_pem opcionais (o sandbox não usa; produção exige os três) → IllegalArgumentException com o nome do campo
      boolean hasCertificate();                           // cert E chave presentes; um sem o outro → IllegalArgumentException no parse
      void requireProductionShape();                      // lança se faltar apiKey/cert/chave — chamado quando o endpoint exige mTLS (LIVE)
      String fingerprint();                               // SHA-256 hex de clientId + (certificatePem ou "") (chave de cache do token; nunca o secret)
  }
  final class PemKeyStores { static SSLContext mutualTls(String certificatePem, String privateKeyPem, KeyStore trustStore /* null = JDK default */); static KeyStore trustStoreFrom(List<X509Certificate>); }
  record ItauEndpoints(URI apiBase, URI tokenUrl, boolean mutualTls) { static ItauEndpoints forEnvironment(ProviderEnvironment env); static ItauEndpoints custom(URI apiBase, URI tokenUrl, boolean mutualTls); }
     // LIVE: https://pix-pj.api.itau.com/regulatorio-pix/v2 + https://sts.itau.com.br/as/token.oauth2, mutualTls=true
     // TEST: https://sandbox.devportal.itau.com.br/itau-ep9-api-regulatorio-pix-v2-externo/v2 + https://sandbox.devportal.itau.com.br/api/oauth/jwt, mutualTls=false (NOTES.md, "Sandbox authentication")
  final class PixAmounts { static String toItau(Money m); static Money fromItau(String s); }   // "123.45" ↔ 12345 centavos; BRL
  TestCertificates.generate() -> record { KeyStore caTrust; String clientCertPem; String clientKeyPem; KeyStore serverKeyStore; char[] serverPassword }
  ```

- [ ] **Step 1: Testes**

`PixAmountsTest.java`:
```java
package com.gateway.providers.itau;

import static org.assertj.core.api.Assertions.*;
import com.gateway.kernel.money.Money;
import org.junit.jupiter.api.Test;

class PixAmountsTest {
  @Test void roundTripsCents() {
    assertThat(PixAmounts.toItau(Money.brl(12345))).isEqualTo("123.45");
    assertThat(PixAmounts.toItau(Money.brl(5))).isEqualTo("0.05");
    assertThat(PixAmounts.toItau(Money.brl(100000000000L))).isEqualTo("1000000000.00");
    assertThat(PixAmounts.fromItau("123.45")).isEqualTo(Money.brl(12345));
    assertThat(PixAmounts.fromItau("0.05")).isEqualTo(Money.brl(5));
  }
  /** The Bacen pattern is \d{1,10}\.\d{2}: anything else is a bug on our side, never sent. */
  @Test void rejectsWhatBacenRejects() {
    assertThatThrownBy(() -> PixAmounts.toItau(Money.brl(0))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PixAmounts.toItau(Money.brl(1_000_000_000_001L))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PixAmounts.fromItau("1,00")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PixAmounts.fromItau("1.5")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

`ItauCredentialsTest.java`:
```java
package com.gateway.providers.itau;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class ItauCredentialsTest {
  static final String JSON = """
      {"client_id":"11111111-2222-3333-4444-555555555555","client_secret":"s3cr3t","x_itau_apikey":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
       "certificate_pem":"-----BEGIN CERTIFICATE-----\\nMIIB\\n-----END CERTIFICATE-----","private_key_pem":"-----BEGIN PRIVATE KEY-----\\nMIIE\\n-----END PRIVATE KEY-----",
       "pix_key":"60701190000104"}""";

  @Test void parsesAllSixFields() {
    ItauCredentials c = ItauCredentials.parse(JSON.getBytes(StandardCharsets.UTF_8));
    assertThat(c.clientId()).startsWith("11111111");
    assertThat(c.clientSecret().reveal()).isEqualTo("s3cr3t");
    assertThat(c.apiKey()).isEqualTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    assertThat(c.pixKey()).isEqualTo("60701190000104");
    assertThat(c.toString()).doesNotContain("s3cr3t").doesNotContain("MIIE");
  }
  @Test void missingFieldNamesTheField() {
    assertThatThrownBy(() -> ItauCredentials.parse("{\"client_id\":\"x\"}".getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("client_secret");
  }
  @Test void apiKeyMustMatchItauRegex() {
    assertThatThrownBy(() -> ItauCredentials.parse(JSON.replace("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "not-a-uuid").getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("x_itau_apikey");
  }
  @Test void sandboxShapeHasNoCertificate() {
    ItauCredentials c = ItauCredentials.parse("{\"client_id\":\"sbx-id\",\"client_secret\":\"sbx-secret\",\"pix_key\":\"60701190000104\"}".getBytes());
    assertThat(c.hasCertificate()).isFalse();
    assertThat(c.apiKey()).isNull();
    assertThatThrownBy(c::requireProductionShape).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("certificate_pem");
    assertThatThrownBy(() -> ItauCredentials.parse("{\"client_id\":\"x\",\"client_secret\":\"y\",\"pix_key\":\"k\",\"certificate_pem\":\"c\"}".getBytes()))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("private_key_pem");
  }
  @Test void fingerprintIgnoresTheSecret() {
    ItauCredentials a = ItauCredentials.parse(JSON.getBytes());
    ItauCredentials b = ItauCredentials.parse(JSON.replace("s3cr3t", "other").getBytes());
    assertThat(a.fingerprint()).isEqualTo(b.fingerprint()).hasSize(64);
  }
}
```

`PemKeyStoresTest.java`:
```java
package com.gateway.providers.itau;

import static org.assertj.core.api.Assertions.*;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class PemKeyStoresTest {
  @Test void buildsAnSslContextFromPemStrings() throws Exception {
    TestCertificates.Bundle b = TestCertificates.generate();
    SSLContext ctx = PemKeyStores.mutualTls(b.clientCertPem(), b.clientKeyPem(), b.caTrust());
    assertThat(ctx.getProtocol()).isEqualTo("TLS");
  }
  @Test void rejectsKeyThatDoesNotMatchCertificate() throws Exception {
    TestCertificates.Bundle a = TestCertificates.generate(), b = TestCertificates.generate();
    assertThatThrownBy(() -> PemKeyStores.mutualTls(a.clientCertPem(), b.clientKeyPem(), a.caTrust())).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-providers -am test -Dtest='PixAmountsTest,ItauCredentialsTest,PemKeyStoresTest' -Dsurefire.failIfNoSpecifiedTests=false`.

- [ ] **Step 3: Implementar**

`PixAmounts.java`:
```java
package com.gateway.providers.itau;

import com.gateway.kernel.money.Money;
import java.util.regex.Pattern;

/** The one place that turns cents into the Bacen string ({@code \d{1,10}\.\d{2}}) and back. */
final class PixAmounts {
  private static final Pattern BACEN = Pattern.compile("\\d{1,10}\\.\\d{2}");
  private static final long MAX_CENTS = 999_999_999_999L; // 9999999999.99
  private PixAmounts() {}

  static String toItau(Money m) {
    if (!"BRL".equals(m.currency())) throw new IllegalArgumentException("Pix is BRL only: " + m.currency());
    if (m.cents() <= 0 || m.cents() > MAX_CENTS) throw new IllegalArgumentException("amount outside the Bacen range: " + m.cents());
    return m.cents() / 100 + "." + String.format("%02d", m.cents() % 100);
  }

  static Money fromItau(String s) {
    if (s == null || !BACEN.matcher(s).matches()) throw new IllegalArgumentException("not a Bacen amount: " + s);
    String[] p = s.split("\\.");
    return Money.brl(Long.parseLong(p[0]) * 100 + Long.parseLong(p[1]));
  }
}
```
Tornar `PixAmounts` `public` se `payments`… não: `payments` nunca vê valores do Itaú; fica package-private.

`ItauCredentials.java`: record com compact constructor validando não-vazio para os seis, `apiKey` contra `^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$` (regex do OpenAPI), `parse(byte[])` com `tools.jackson.databind.ObjectMapper` lendo um record intermediário package-private `Raw(String client_id, …)` (nomes snake_case explícitos via `@JsonProperty`), `fingerprint()` = SHA-256 hex de `clientId + "\n" + certificatePem`, `toString()` mascarado. Javadoc: "six values, see docs/providers/itau/NOTES.md; the Pix key is the receiving account's key and is configuration, but it lives here so a merchant's Itaú setup is one object".

`PemKeyStores.java`:
```java
package com.gateway.providers.itau;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.security.*;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.*;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * PEM strings from the merchant's encrypted credential → an in-memory SSLContext for mTLS. Nothing
 * touches disk: the private key exists only inside this KeyStore for the lifetime of the client.
 */
final class PemKeyStores {
  private PemKeyStores() {}

  static SSLContext mutualTls(String certificatePem, String privateKeyPem, KeyStore trustStore) {
    try {
      X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
          .generateCertificate(new ByteArrayInputStream(certificatePem.getBytes()));
      PrivateKey key = readPrivateKey(privateKeyPem);
      if (!keyMatches(cert, key)) throw new IllegalArgumentException("private key does not match the certificate");
      char[] pw = new char[0];
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(null, null);
      ks.setKeyEntry("itau", key, pw, new X509Certificate[] {cert});
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(ks, pw);
      TrustManager[] tms = null;
      if (trustStore != null) {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        tms = tmf.getTrustManagers();
      }
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), tms, null);
      return ctx;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid PEM material: " + e.getMessage(), e);
    }
  }

  static KeyStore trustStoreFrom(List<X509Certificate> cas) {
    try {
      KeyStore ts = KeyStore.getInstance("PKCS12");
      ts.load(null, null);
      for (int i = 0; i < cas.size(); i++) ts.setCertificateEntry("ca-" + i, cas.get(i));
      return ts;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static PrivateKey readPrivateKey(String pem) throws Exception {
    try (PEMParser p = new PEMParser(new StringReader(pem))) {
      Object o = p.readObject();
      JcaPEMKeyConverter c = new JcaPEMKeyConverter();
      if (o instanceof PrivateKeyInfo info) return c.getPrivateKey(info);
      if (o instanceof PEMKeyPair pair) return c.getKeyPair(pair).getPrivate();
      throw new IllegalArgumentException("unsupported private key PEM: " + (o == null ? "empty" : o.getClass().getSimpleName()));
    }
  }

  /** Sign-and-verify a nonce: the cheapest proof the key and the cert belong together. */
  private static boolean keyMatches(X509Certificate cert, PrivateKey key) throws Exception {
    byte[] nonce = new byte[32];
    new SecureRandom().nextBytes(nonce);
    String alg = key.getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
    Signature s = Signature.getInstance(alg);
    s.initSign(key); s.update(nonce);
    byte[] sig = s.sign();
    Signature v = Signature.getInstance(alg);
    v.initVerify(cert.getPublicKey()); v.update(nonce);
    return v.verify(sig);
  }
}
```

`ItauEndpoints.java`: record com as duas fábricas e as URLs do `NOTES.md` como constantes com comentário citando a fonte e a data.

`TestCertificates.java` (teste): gera com BouncyCastle (`X509v3CertificateBuilder` + `JcaContentSignerBuilder("SHA256withRSA")`) uma CA autoassinada, um cert **cliente** assinado por ela (CN = client id), um cert **servidor** para `localhost` (SAN `DNS:localhost`, `IP:127.0.0.1`) assinado pela mesma CA; devolve PEMs (via `JcaPEMWriter`) e KeyStores. É o que as Tasks 3, 4 e 9 usam.

- [ ] **Step 4: Rodar e ver passar** — comando do Step 2.

- [ ] **Step 5: Commit**

```bash
git add gateway-providers
git commit -m "feat(providers): itau credentials, pem to sslcontext, endpoints and amount conversion

Six-field credential parsed once and never printed. The key/cert
match is checked by signing a nonce so a wrong pair fails at parse
time instead of at the first TLS handshake."
```

---

### Task 3: `ItauTokenClient` — OAuth2 client-credentials sobre mTLS, cache por credencial

**Files:**
- Create: `gateway-providers/src/main/java/com/gateway/providers/itau/{ItauTokenClient,AccessToken}.java`
- Test: `gateway-providers/src/test/java/com/gateway/providers/itau/ItauTokenClientMtlsTest.java`

**Interfaces:**
- Produces:
  ```java
  record AccessToken(String value, Instant expiresAt) { boolean usableAt(Instant now) /* now < expiresAt - 60s */ }
  class ItauTokenClient {
    ItauTokenClient(Clock clock, Duration connectTimeout, Duration readTimeout);
    AccessToken tokenFor(ItauCredentials creds, ItauEndpoints endpoints, KeyStore trustStore /* null = JDK */);   // cache por creds.fingerprint()+tokenUrl; refresh quando !usableAt; endpoints.mutualTls()=false → HttpClient sem client cert (sandbox: POST /api/oauth/jwt com o mesmo form)
    void evict(String fingerprint);
  }
  ```
  Request exato (NOTES.md): `POST tokenUrl`, `Content-Type: application/x-www-form-urlencoded`, corpo `grant_type=client_credentials&client_id=…&client_secret=…` (URL-encoded). Com `mutualTls=true` (produção) o `HttpClient` usa o `SSLContext` de `PemKeyStores.mutualTls` e `creds.requireProductionShape()` é chamado antes; com `mutualTls=false` (sandbox) é um `HttpClient` normal — corpo e resposta iguais, só muda a URL (`/api/oauth/jwt`) e a ausência de certificado. Adicione ao teste da Task 3 um caso `sandboxTokenWithoutClientCertificate`: WireMock HTTP simples em `/api/oauth/jwt`, credencial sem cert, `mutualTls=false` → token obtido. Resposta `{"access_token": "...", "expires_in": 300, ...}` — `expires_in` ausente → assume 300. Erros: 401/403 → `ProviderException(UNAUTHENTICATED)`; 5xx/timeout → `UNAVAILABLE`/`TIMEOUT`.

- [ ] **Step 1: Teste com WireMock HTTPS exigindo client cert**

```java
package com.gateway.providers.itau;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.provider.ProviderException;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.time.*;
import org.junit.jupiter.api.*;

/** The token call is the one place mTLS must be proven: WireMock is started with needClientAuth. */
class ItauTokenClientMtlsTest {
  static TestCertificates.Bundle certs;
  static WireMockServer server;
  static File serverKs, trustKs;

  @BeforeAll static void start() throws Exception {
    certs = TestCertificates.generate();
    serverKs = File.createTempFile("server", ".p12"); trustKs = File.createTempFile("trust", ".p12");
    try (var o = new FileOutputStream(serverKs)) { certs.serverKeyStore().store(o, certs.serverPassword()); }
    try (var o = new FileOutputStream(trustKs)) { certs.caTrust().store(o, "changeit".toCharArray()); }
    server = new WireMockServer(WireMockConfiguration.options().dynamicHttpsPort().httpDisabled(true)
        .keystorePath(serverKs.getAbsolutePath()).keystorePassword(new String(certs.serverPassword())).keyManagerPassword(new String(certs.serverPassword()))
        .trustStorePath(trustKs.getAbsolutePath()).trustStorePassword("changeit").needClientAuth(true));
    server.start();
  }
  @AfterAll static void stop() { server.stop(); serverKs.delete(); trustKs.delete(); }

  ItauCredentials creds() {
    return ItauCredentials.parse(("{\"client_id\":\"11111111-2222-3333-4444-555555555555\",\"client_secret\":\"s3cr3t\",\"x_itau_apikey\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\","
        + "\"certificate_pem\":" + json(certs.clientCertPem()) + ",\"private_key_pem\":" + json(certs.clientKeyPem()) + ",\"pix_key\":\"60701190000104\"}").getBytes());
  }
  static String json(String s) { return "\"" + s.replace("\\", "\\\\").replace("\n", "\\n").replace("\"", "\\\"") + "\""; }
  URI tokenUrl() { return URI.create("https://localhost:" + server.httpsPort() + "/as/token.oauth2"); }

  @Test void postsClientCredentialsOverMtlsAndCachesUntilNearExpiry() {
    server.stubFor(post("/as/token.oauth2")
        .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
        .withRequestBody(containing("grant_type=client_credentials")).withRequestBody(containing("client_id=11111111-2222-3333-4444-555555555555")).withRequestBody(containing("client_secret=s3cr3t"))
        .willReturn(okJson("{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);
    ItauTokenClient client = new ItauTokenClient(clock, Duration.ofSeconds(3), Duration.ofSeconds(5));

    AccessToken t1 = client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust());
    AccessToken t2 = client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust());
    assertThat(t1.value()).isEqualTo("tok-1");
    assertThat(t2).isSameAs(t1);
    assertThat(t1.expiresAt()).isEqualTo(Instant.parse("2026-09-24T12:05:00Z"));
    server.verify(1, postRequestedFor(urlEqualTo("/as/token.oauth2")));
  }

  @Test void refreshesSixtySecondsBeforeExpiry() {
    server.stubFor(post("/as/token.oauth2").willReturn(okJson("{\"access_token\":\"tok-a\",\"expires_in\":300}")));
    MutableClock clock = new MutableClock(Instant.parse("2026-09-24T12:00:00Z"));
    ItauTokenClient client = new ItauTokenClient(clock, Duration.ofSeconds(3), Duration.ofSeconds(5));
    client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust());
    clock.advance(Duration.ofSeconds(239));
    client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust());
    server.verify(1, postRequestedFor(urlEqualTo("/as/token.oauth2")));
    clock.advance(Duration.ofSeconds(2));
    client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust());
    server.verify(2, postRequestedFor(urlEqualTo("/as/token.oauth2")));
  }

  @Test void unauthorizedBecomesUnauthenticated() {
    server.stubFor(post("/as/token.oauth2").willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid_client\"}")));
    ItauTokenClient client = new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(5));
    assertThatThrownBy(() -> client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust()))
        .isInstanceOf(ProviderException.class).extracting("code").isEqualTo(ProviderException.Code.UNAUTHENTICATED);
  }

  /** Without a client certificate the handshake fails — this is what proves needClientAuth is on. */
  @Test void serverRejectsConnectionsWithoutClientCertificate() throws Exception {
    var plain = java.net.http.HttpClient.newBuilder().sslContext(PemKeyStores.mutualTls(TestCertificates.generate().clientCertPem(), TestCertificates.generate().clientKeyPem(), certs.caTrust())).build();
    // a cert from ANOTHER CA is not trusted by the server
    assertThatThrownBy(() -> plain.send(java.net.http.HttpRequest.newBuilder(tokenUrl()).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.discarding()))
        .isInstanceOf(java.io.IOException.class);
  }

  static final class MutableClock extends Clock {
    private Instant now; MutableClock(Instant i) { now = i; }
    void advance(Duration d) { now = now.plus(d); }
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId z) { return this; }
    @Override public Instant instant() { return now; }
  }
}
```
Observação: `TestCertificates.generate()` chamado duas vezes no último teste gera **duas CAs distintas**; o `mutualTls` exige que cert e chave casem — por isso o teste precisa de um bundle só: troque as duas chamadas por `TestCertificates.Bundle other = TestCertificates.generate();` e use `other.clientCertPem(), other.clientKeyPem()`.

- [ ] **Step 2: Rodar e ver falhar** — `-Dtest=ItauTokenClientMtlsTest`.

- [ ] **Step 3: Implementar**

`AccessToken.java`:
```java
package com.gateway.providers.itau;

import java.time.Duration;
import java.time.Instant;

record AccessToken(String value, Instant expiresAt) {
  /** Refresh a minute early: a token that expires mid-request costs a 401 and a retry against the bank. */
  boolean usableAt(Instant now) { return now.isBefore(expiresAt.minus(Duration.ofSeconds(60))); }
  @Override public String toString() { return "AccessToken[***, expiresAt=" + expiresAt + "]"; }
}
```

`ItauTokenClient.java`:
```java
package com.gateway.providers.itau;

import com.gateway.kernel.provider.ProviderException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OAuth2 client credentials at Itaú's STS, over mTLS with the merchant's dynamic certificate
 * (docs/providers/itau/NOTES.md). Tokens live 300 s; we cache one per credential fingerprint and
 * refresh 60 s early. One HttpClient per fingerprint too: the SSLContext carries the merchant's
 * private key, so clients are never shared across merchants.
 */
public class ItauTokenClient {
  private record Entry(HttpClient http, AccessToken token) {}
  private final Map<String, Entry> cache = new ConcurrentHashMap<>();
  private final Clock clock;
  private final Duration connectTimeout, readTimeout;
  private final ObjectMapper mapper = new ObjectMapper();

  public ItauTokenClient(Clock clock, Duration connectTimeout, Duration readTimeout) {
    this.clock = clock; this.connectTimeout = connectTimeout; this.readTimeout = readTimeout;
  }

  public AccessToken tokenFor(ItauCredentials creds, ItauEndpoints endpoints, KeyStore trustStore) {
    URI tokenUrl = endpoints.tokenUrl();
    if (endpoints.mutualTls()) creds.requireProductionShape();
    String key = creds.fingerprint() + "|" + tokenUrl;
    Entry e = cache.get(key);
    Instant now = clock.instant();
    if (e != null && e.token() != null && e.token().usableAt(now)) return e.token();
    HttpClient http = e != null ? e.http() : newHttpClient(creds, endpoints.mutualTls(), trustStore);
    AccessToken fresh = fetch(http, creds, tokenUrl, now);
    cache.put(key, new Entry(http, fresh));
    return fresh;
  }

  /** The sandbox has no client certificate (NOTES.md "Sandbox authentication"); production always does. */
  private HttpClient newHttpClient(ItauCredentials creds, boolean mutualTls, KeyStore trustStore) {
    HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(connectTimeout);
    if (mutualTls) b.sslContext(PemKeyStores.mutualTls(creds.certificatePem(), creds.privateKeyPem().reveal(), trustStore));
    return b.build();
  }

  /** Also the HttpClient: a credential replaced by the merchant must not keep the old key alive. */
  public void evict(String fingerprint) { cache.keySet().removeIf(k -> k.startsWith(fingerprint + "|")); }

  HttpClient httpClientFor(ItauCredentials creds, ItauEndpoints endpoints, KeyStore trustStore) {
    tokenFor(creds, endpoints, trustStore);
    return cache.get(creds.fingerprint() + "|" + endpoints.tokenUrl()).http();
  }

  private AccessToken fetch(HttpClient http, ItauCredentials creds, URI tokenUrl, Instant now) {
    String form = "grant_type=client_credentials&client_id=" + enc(creds.clientId()) + "&client_secret=" + enc(creds.clientSecret().reveal());
    HttpRequest req = HttpRequest.newBuilder(tokenUrl).timeout(readTimeout)
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(form)).build();
    HttpResponse<String> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofString());
    } catch (HttpTimeoutException e) {
      throw new ProviderException(ProviderException.Code.TIMEOUT, "token request timed out", e);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new ProviderException(ProviderException.Code.UNAVAILABLE, "token request failed: " + e.getMessage(), e);
    }
    if (res.statusCode() == 401 || res.statusCode() == 403) throw new ProviderException(ProviderException.Code.UNAUTHENTICATED, res.statusCode(), null, "STS rejected the credentials");
    if (res.statusCode() >= 500) throw new ProviderException(ProviderException.Code.UNAVAILABLE, res.statusCode(), null, "STS unavailable");
    if (res.statusCode() != 200) throw new ProviderException(ProviderException.Code.UNKNOWN, res.statusCode(), null, "unexpected STS status");
    JsonNode body = mapper.readTree(res.body());
    String token = body.path("access_token").asText(null);
    if (token == null || token.isBlank()) throw new ProviderException(ProviderException.Code.UNKNOWN, 200, null, "STS response without access_token");
    long expiresIn = body.path("expires_in").asLong(300);
    return new AccessToken(token, now.plusSeconds(expiresIn));
  }

  private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
```
`httpClientFor` existe porque o `PixApiClient` (Task 4) reutiliza o mesmo `HttpClient` mTLS da credencial — o Itaú exige o certificado também nas chamadas de API.

- [ ] **Step 4: Rodar e ver passar** — 4 testes verdes.

- [ ] **Step 5: Commit**

```bash
git add gateway-providers
git commit -m "feat(providers): itau sts token client over mtls with per-credential cache

The test starts WireMock with needClientAuth, so the mTLS path is
proven, not assumed. Refresh 60 s before the 300 s expiry; one
HttpClient per credential because the SSLContext holds the key."
```

---

### Task 4: `PixApiClient` + `ItauPixProvider` — contrato v2 com fixtures oficiais e validação de schema

**Files:**
- Create: `gateway-providers/src/main/java/com/gateway/providers/itau/{PixApiClient,ItauPixProvider,ItauErrors}.java`, `dto/{CobRequest,CobResponse,PixItem,DevolucaoRequest,DevolucaoResponse,CobList,WebhookPayload,Problem}.java`, `gateway-providers/src/main/java/com/gateway/providers/ProvidersConfiguration.java` (beans)
- Create: `gateway-providers/src/test/resources/itau/openapi.json` (cópia de `docs/providers/itau/…openapi.json`), `itau/fixtures/{put_cob_request_min.json,put_cob_201.json,get_cob_200_active.json,get_cob_200_completed.json,patch_cob_cancel_request.json,put_devolucao_request.json,put_devolucao_201_processing.json,get_devolucao_200_done.json,get_cob_list_200.json,error_400_cob_operacao_invalida.json,error_404_cob_nao_encontrado.json,webhook_pix.json,webhook_pix_null_fields.json}`
- Test: `gateway-providers/src/test/java/com/gateway/providers/itau/{PixApiClientContractTest,ItauPixProviderTest,RequestSchemaValidationTest,FixturesFromOpenApiTest}.java`

**Interfaces:**
- Produces:
  ```java
  class PixApiClient {                    // package-private
    PixApiClient(ItauTokenClient tokens, ItauEndpoints endpoints, KeyStore trustStore, Duration readTimeout, Clock clock);
    CobResponse putCob(ItauCredentials c, String txid, CobRequest body);
    Optional<CobResponse> getCob(ItauCredentials c, String txid);
    CobResponse patchCob(ItauCredentials c, String txid, Map<String,Object> patch);
    DevolucaoResponse putDevolucao(ItauCredentials c, String e2eid, String id, DevolucaoRequest body);
    Optional<DevolucaoResponse> getDevolucao(ItauCredentials c, String e2eid, String id);
    CobList listCob(ItauCredentials c, Instant inicio, Instant fim, int page, int pageSize);
  }
  public class ItauPixProvider implements PixProvider { public ItauPixProvider(ItauTokenClient tokens, KeyStore trustStore, Duration readTimeout, Clock clock, ItauEndpoints liveOverride /* null = padrão */, ItauEndpoints testOverride); }
  @Configuration ProvidersConfiguration { @Bean ItauTokenClient; @Bean PixProvider itauPixProvider(ProvidersProperties); @ConfigurationProperties("gateway.providers.itau") record ProvidersProperties(String liveApiBase, String liveTokenUrl, boolean liveMutualTls /* true */, String testApiBase, String testTokenUrl, boolean testMutualTls /* false */, String trustStorePem /* CA(s) do Itaú em PEM, opcional */, Duration readTimeout) }
  ```
  Headers em toda chamada: `Authorization: Bearer <token>`, `x-itau-apikey: <creds.apiKey()>`, `x-itau-correlationID: <UUID>` (do MDC `correlationId` se for UUID, senão novo), `Content-Type: application/json`. Sem retry automático no cliente.
  Mapeamento de erro (`ItauErrors`): corpo RFC 7807 `type` termina em `NaoEncontrad*` → `NOT_FOUND`; `*OperacaoInvalida`/`*ConsultaInvalida`/`PixDevolucaoInvalida`/400/422 → `INVALID`; 401/403 → `UNAUTHENTICATED`; 503/504/5xx → `UNAVAILABLE`; timeout de socket → `TIMEOUT`; 410 → `NOT_FOUND`; resto → `UNKNOWN`. `providerType` = o `type` bruto.
  Status: `ATIVA→ACTIVE`, `CONCLUIDA→COMPLETED`, `REMOVIDA_PELO_USUARIO_RECEBEDOR`|`REMOVIDO_PELO_USUARIO_RECEBEDOR→REMOVED_BY_MERCHANT`, `REMOVIDA_PELO_PSP`|`REMOVIDO_PELO_PSP→REMOVED_BY_PSP` (as duas grafias, NOTES.md). Devolução: `EM_PROCESSAMENTO→PROCESSING`, `DEVOLVIDO→COMPLETED`, `NAO_REALIZADO→FAILED` (`motivo` → `reason`).

- [ ] **Step 1: Fixtures a partir do OpenAPI — teste que as mantém honestas**

`FixturesFromOpenApiTest.java`:
```java
package com.gateway.providers.itau;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Every fixture we replay in WireMock must be an example from Itaú's own OpenAPI. This test pins
 * that: if someone "improves" a fixture by hand, it drifts from the bank and this fails.
 */
class FixturesFromOpenApiTest {
  static final ObjectMapper M = new ObjectMapper();
  static JsonNode example(String name) throws Exception {
    JsonNode api = M.readTree(Files.readString(Path.of("src/test/resources/itau/openapi.json")));
    JsonNode ex = api.at("/components/examples/" + name + "/value");
    assertThat(ex.isMissingNode()).as("example %s exists in the OpenAPI", name).isFalse();
    return ex;
  }
  static JsonNode fixture(String file) throws Exception { return M.readTree(Files.readString(Path.of("src/test/resources/itau/fixtures/" + file))); }

  @Test void putCobRequestMin() throws Exception { assertThat(fixture("put_cob_request_min.json")).isEqualTo(example("request_put_cobranca_imediata_campos_obrigatorios")); }
  @Test void putCob201() throws Exception { assertThat(fixture("put_cob_201.json")).isEqualTo(example("response_200_cobranca_imediata_txid")); }
  @Test void getCob200Active() throws Exception { assertThat(fixture("get_cob_200_active.json")).isEqualTo(example("200_cobranca_txid").path("value")); }
  @Test void patchCancel() throws Exception { assertThat(fixture("patch_cob_cancel_request.json")).isEqualTo(example("request_patch_cobranca_imediata_status")); }
  @Test void putDevolucaoRequest() throws Exception { assertThat(fixture("put_devolucao_request.json")).isEqualTo(example("request_put_devolucao_campos_obrigatorios")); }
  @Test void getDevolucao200() throws Exception { assertThat(fixture("get_devolucao_200_done.json")).isEqualTo(example("200_devolucao")); }
  @Test void getCobList200() throws Exception { assertThat(fixture("get_cob_list_200.json")).isEqualTo(example("200_cobrancas").path("value")); }
}
```
Fixtures que **não** têm exemplo no OpenAPI e vêm da página "informações adicionais" (NOTES.md): `webhook_pix.json` e `webhook_pix_null_fields.json` (payload do webhook, o segundo com `"devolucoes": null` e sem `componentesValor`), `put_devolucao_201_processing.json` (= `201_devolucao` com `"status":"EM_PROCESSAMENTO"` e sem `horario.liquidacao` — o Itaú diz que a devolução é assíncrona), `get_cob_200_completed.json` (= `get_cob_200_active` com `"status":"CONCLUIDA"` e um `pix[]` com `endToEndId "E12345678202009091221kkkkkkkkkkk"`, `valor "567.89"`, `horario "2020-01-01T00:00:00Z"`, `txid` igual), `error_400_cob_operacao_invalida.json` (`{"type":"https://pix.bcb.gov.br/api/v2/error/CobOperacaoInvalida","title":"Operação inválida.","status":400,"detail":"A cobrança não está ATIVA.","violacoes":[]}`), `error_404_cob_nao_encontrado.json` (`type=…/CobNaoEncontrado`, `status 404`). Cada um desses leva um comentário-irmão em `fixtures/README.md` dizendo de onde veio.

- [ ] **Step 2: Validação de schema dos NOSSOS requests**

`RequestSchemaValidationTest.java`:
```java
package com.gateway.providers.itau;

import static org.assertj.core.api.Assertions.assertThat;
import com.gateway.kernel.money.Money;
import com.networknt.schema.*;
import java.nio.file.*;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** What we send must satisfy Itaú's own schema. This is the test that replaces the sandbox until we have one. */
class RequestSchemaValidationTest {
  static final ObjectMapper M = new ObjectMapper();
  static JsonSchema schemaOf(String component) throws Exception {
    JsonNode api = M.readTree(Files.readString(Path.of("src/test/resources/itau/openapi.json")));
    JsonSchemaFactory f = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);
    // OpenAPI 3.0 schemas are JSON-Schema-draft-ish; refs are resolved against the whole document
    return f.getSchema(api.at("/components/schemas/" + component), SchemaValidatorsConfig.builder().build());
  }

  @Test void createChargeBodyIsValid() throws Exception {
    CobRequest body = CobRequest.forCharge(Money.brl(15990), 3600, "60701190000104", "12345678909", "Jane Doe", "Order 8812");
    Set<ValidationMessage> errors = schemaOf("cobrancaImediataPutRequest").validate(M.valueToTree(body));
    assertThat(errors).isEmpty();
  }
  @Test void createChargeWithoutPayerIsValid() throws Exception {
    CobRequest body = CobRequest.forCharge(Money.brl(100), 3600, "60701190000104", null, null, null);
    assertThat(schemaOf("cobrancaImediataPutRequest").validate(M.valueToTree(body))).isEmpty();
  }
  @Test void refundBodyIsValid() throws Exception {
    assertThat(schemaOf("devolucaoPutRequest").validate(M.valueToTree(new DevolucaoRequest(PixAmounts.toItau(Money.brl(1000)))))).isEmpty();
  }
}
```
Se o validador tropeçar em `$ref` relativos ao documento inteiro, carregue o documento inteiro como schema raiz e valide contra `#/components/schemas/<x>` via `SchemaLocation`; o teste é o árbitro — registre o que funcionou.

- [ ] **Step 3: Contract test com WireMock (HTTP simples: o mTLS foi provado na Task 3)**

`PixApiClientContractTest.java` — pontos obrigatórios (cada um um `@Test`):
- `putCobSendsHeadersAndBodyAndParses201`: stub `PUT /cob/{txid}` exigindo `Authorization: Bearer tok`, `x-itau-apikey`, `x-itau-correlationID` (regex uuid), `Content-Type: application/json`, corpo igual a `put_cob_request_min.json` (`equalToJson`), devolvendo `put_cob_201.json` → `CobResponse.status()=="ATIVA"`, `pixCopiaECola` não vazio.
- `getCobActive`/`getCobCompletedCarriesPix`/`getCob404IsEmpty`.
- `patchCobCancelSendsStatus`: corpo `{"status":"REMOVIDA_PELO_USUARIO_RECEBEDOR"}`.
- `patchCobOnConcludedIsInvalid`: 400 com `error_400_cob_operacao_invalida.json` → `ProviderException` `INVALID`, `providerType` contém `CobOperacaoInvalida`.
- `putDevolucaoReturnsProcessing`, `getDevolucaoDone`, `listCobWindowAndPagination` (`inicio`, `fim` ISO-8601 UTC, `paginacao.paginaAtual`, `paginacao.itensPorPagina`).
- `unauthorizedEvictsTheTokenAndMaps401`: 401 → `UNAUTHENTICATED` e o próximo call pede token de novo (`server.verify(2, postRequestedFor(tokenUrl))`).
- `socketTimeoutIsTimeout`: stub com `withFixedDelay(3000)` e `readTimeout` 1 s → `TIMEOUT`.
- `serviceUnavailableIsUnavailable`: 503.
O token: um stub `POST /api/oauth/jwt` HTTP no mesmo WireMock devolvendo `tok`; `ItauEndpoints.custom(apiBase, tokenUrl, false)` apontando para o WireMock (a forma do sandbox: sem client cert), credenciais no formato sandbox (sem cert). `trustStore=null`. Um teste extra `productionShapeSendsApiKeyHeader` usa credenciais completas com `custom(..., true)` contra o WireMock HTTPS da Task 3 para provar que `x-itau-apikey` vai.

`ItauPixProviderTest.java`: sobre o mesmo WireMock, `ItauPixProvider` com credenciais das fixtures: `createCharge` devolve `Charge(txid, ACTIVE, Money.brl(56789) /* do 201 */, pixCopiaECola, …)`; `findCharge` de CONCLUIDA devolve `firstPix()` com `endToEndId`, `Money.brl(56789)`, `paidAt`; `cancelCharge`; `requestRefund` → `RefundResult(PROCESSING)`; `findRefund` → `COMPLETED` com `settledAt`; `listCharges` mapeia os dois cobs; `parseWebhook(webhook_pix.json)` → 1 `ReceivedPix` com `e2eid`, `Money.brl(11000)`, `txidByEndToEndId` contém o txid, e `refundUpdates` com 1 `COMPLETED` (do array `devolucoes`); `parseWebhook(webhook_pix_null_fields.json)` não lança e traz `refundUpdates` vazio; `parseWebhook("{}")` → `IllegalArgumentException`.

- [ ] **Step 4: Rodar e ver falhar** — os quatro testes não compilam.

- [ ] **Step 5: Implementar DTOs**

`dto/CobRequest.java`:
```java
package com.gateway.providers.itau.dto;

import com.gateway.kernel.money.Money;
import tools.jackson.databind.annotation.JsonInclude;   // if the Jackson 3 include annotation lives elsewhere, use that one
import java.util.List;

/** PUT /cob/{txid} body. Field names are the bank's; the gateway never sees them. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CobRequest(Calendario calendario, Devedor devedor, Valor valor, String chave, String solicitacaoPagador, List<InfoAdicional> infoAdicionais) {
  public record Calendario(int expiracao) {}
  public record Devedor(String cpf, String cnpj, String nome) {}
  public record Valor(String original) {}
  public record InfoAdicional(String nome, String valor) {}

  public static CobRequest forCharge(Money amount, int expiresInSeconds, String pixKey, String payerDocument, String payerName, String description) {
    Devedor devedor = null;
    if (payerDocument != null && payerName != null) {
      String digits = payerDocument.replaceAll("\\D", "");
      devedor = digits.length() == 14 ? new Devedor(null, digits, payerName) : new Devedor(digits, null, payerName);
    }
    return new CobRequest(new Calendario(expiresInSeconds), devedor, new Valor(PixAmountsBridge.toItau(amount)), pixKey,
        description == null ? null : description.substring(0, Math.min(140, description.length())), null);
  }
}
```
(`PixAmounts` é package-private em `…itau`; ou mova `PixAmounts` para `dto` ou torne-o `public` dentro do módulo — escolha tornar `PixAmounts` `public final` em `com.gateway.providers.itau` e chamá-lo direto; remova o `PixAmountsBridge` do snippet.) `solicitacaoPagador` ≤ 140 (schema).

`dto/CobResponse.java`: record com `calendario{criacao, expiracao}`, `txid`, `revisao`, `location`, `status`, `valor{original}`, `chave`, `pixCopiaECola`, `pix` (`List<PixItem>`, opcional), `@JsonIgnoreProperties(ignoreUnknown = true)` — o Itaú manda campos que não modelamos (`loc`, `devedor`, `infoAdicionais`).
`dto/PixItem.java`: `endToEndId, txid, valor, horario (Instant), infoPagador, devolucoes (List<DevolucaoResponse>)`, ignoreUnknown.
`dto/DevolucaoRequest.java`: `record DevolucaoRequest(String valor)`.
`dto/DevolucaoResponse.java`: `id, rtrId, valor, natureza, descricao, horario{solicitacao, liquidacao}, status, motivo`, ignoreUnknown.
`dto/CobList.java`: `parametros{paginacao{paginaAtual,itensPorPagina,quantidadeDePaginas,quantidadeTotalDeItens}}`, `cobs (List<CobResponse>)`.
`dto/WebhookPayload.java`: `record WebhookPayload(List<PixItem> pix)`.
`dto/Problem.java`: `type, title, status, detail, violacoes (List<Map<String,String>>)`, ignoreUnknown.

- [ ] **Step 6: `ItauErrors`, `PixApiClient`, `ItauPixProvider`, configuração**

`ItauErrors.java` (package-private): `static ProviderException from(int status, String body)` — tenta ler `Problem`; aplica a tabela da seção Interfaces; mensagem = `title + ": " + detail` (ou o corpo cru truncado a 300 chars).

`PixApiClient.java`: usa `tokens.httpClientFor(creds, endpoints, trustStore)` (mesmo `HttpClient` da credencial — com mTLS em produção, sem em sandbox) e `tokens.tokenFor(creds, endpoints, trustStore)` para o `Bearer`; o header `x-itau-apikey` só vai quando `creds.apiKey() != null` (produção exige, sandbox não tem); método privado `send(creds, HttpRequest.Builder, Class<T>)`: adiciona headers, envia, `404` → `Optional.empty()` nos `get*`, `401` → `tokens.evict(creds.fingerprint())` e `ItauErrors.from`, demais não-2xx → `ItauErrors.from`, `HttpTimeoutException` → `TIMEOUT`, `IOException` → `UNAVAILABLE`. Correlation: `MDC.get("correlationId")` se casar com o regex uuid do Itaú, senão `UUID.randomUUID()`. `listCob`: query `inicio`/`fim` em `DateTimeFormatter.ISO_INSTANT`, `paginacao.paginaAtual`, `paginacao.itensPorPagina`.

`ItauPixProvider.java`:
```java
package com.gateway.providers.itau;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.*;
import com.gateway.providers.itau.dto.*;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** The only class that knows Itaú's vocabulary and the gateway's at the same time. */
public class ItauPixProvider implements PixProvider {
  private final PixApiClient live, test;
  private final ObjectMapper mapper = new ObjectMapper();

  public ItauPixProvider(ItauTokenClient tokens, KeyStore trustStore, Duration readTimeout, Clock clock, ItauEndpoints liveEndpoints, ItauEndpoints testEndpoints) {
    this.live = new PixApiClient(tokens, liveEndpoints, trustStore, readTimeout, clock);
    this.test = new PixApiClient(tokens, testEndpoints, trustStore, readTimeout, clock);
  }

  @Override public String id() { return "ITAU"; }

  private PixApiClient client(ProviderCredentials c) { return c.environment() == ProviderEnvironment.LIVE ? live : test; }
  private static ItauCredentials creds(ProviderCredentials c) { return ItauCredentials.parse(c.payload()); }

  @Override
  public Charge createCharge(ProviderCredentials c, String txid, Money amount, int expiresInSeconds, String payerDocument, String payerName, String description) {
    ItauCredentials ic = creds(c);
    return toCharge(client(c).putCob(ic, txid, CobRequest.forCharge(amount, expiresInSeconds, ic.pixKey(), payerDocument, payerName, description)));
  }
  @Override public Optional<Charge> findCharge(ProviderCredentials c, String txid) { return client(c).getCob(creds(c), txid).map(ItauPixProvider::toCharge); }
  @Override public void cancelCharge(ProviderCredentials c, String txid) { client(c).patchCob(creds(c), txid, Map.of("status", "REMOVIDA_PELO_USUARIO_RECEBEDOR")); }
  @Override public RefundResult requestRefund(ProviderCredentials c, RefundRequest r) {
    return toRefund(client(c).putDevolucao(creds(c), r.endToEndId(), r.refundId(), new DevolucaoRequest(PixAmounts.toItau(r.amount()))));
  }
  @Override public Optional<RefundResult> findRefund(ProviderCredentials c, String e2eid, String refundId) { return client(c).getDevolucao(creds(c), e2eid, refundId).map(ItauPixProvider::toRefund); }
  @Override public List<Charge> listCharges(ProviderCredentials c, Instant from, Instant to) {
    List<Charge> out = new ArrayList<>();
    int page = 0;
    while (true) {
      CobList l = client(c).listCob(creds(c), from, to, page, 100);
      if (l.cobs() != null) l.cobs().forEach(cob -> out.add(toCharge(cob)));
      int pages = l.parametros() == null || l.parametros().paginacao() == null ? 1 : l.parametros().paginacao().quantidadeDePaginas();
      if (++page >= pages) break;
    }
    return out;
  }
  @Override public ProviderWebhookEvent parseWebhook(byte[] body) {
    WebhookPayload p;
    try { p = mapper.readValue(body, WebhookPayload.class); } catch (RuntimeException e) { throw new IllegalArgumentException("unreadable Itaú webhook", e); }
    if (p == null || p.pix() == null) throw new IllegalArgumentException("Itaú webhook without pix[]");
    List<ReceivedPix> received = new ArrayList<>(); List<RefundResult> refunds = new ArrayList<>(); Map<String,String> txids = new HashMap<>();
    for (PixItem it : p.pix()) {
      received.add(toReceived(it));
      if (it.txid() != null) txids.put(it.endToEndId(), it.txid());
      if (it.devolucoes() != null) it.devolucoes().forEach(d -> refunds.add(toRefund(d)));
    }
    return new ProviderWebhookEvent(received, refunds, txids);
  }

  static Charge toCharge(CobResponse r) {
    List<ReceivedPix> pix = r.pix() == null ? List.of() : r.pix().stream().map(ItauPixProvider::toReceived).toList();
    Instant created = r.calendario() == null || r.calendario().criacao() == null ? null : r.calendario().criacao();
    int exp = r.calendario() == null ? 0 : r.calendario().expiracao();
    return new Charge(r.txid(), toStatus(r.status()), PixAmounts.fromItau(r.valor().original()), r.pixCopiaECola(), r.location(), created, exp, pix);
  }
  static ReceivedPix toReceived(PixItem i) { return new ReceivedPix(i.endToEndId(), PixAmounts.fromItau(i.valor()), i.horario(), i.infoPagador()); }
  static RefundResult toRefund(DevolucaoResponse d) {
    RefundStatus s = switch (d.status()) { case "DEVOLVIDO" -> RefundStatus.COMPLETED; case "NAO_REALIZADO" -> RefundStatus.FAILED; default -> RefundStatus.PROCESSING; };
    return new RefundResult(d.id(), s, PixAmounts.fromItau(d.valor()), d.motivo(), d.horario() == null ? null : d.horario().solicitacao(), d.horario() == null ? null : d.horario().liquidacao());
  }
  /** Both spellings: the OpenAPI enum says REMOVIDA_…, the portal prose says REMOVIDO_… (NOTES.md). */
  static ChargeStatus toStatus(String s) {
    return switch (s) {
      case "ATIVA" -> ChargeStatus.ACTIVE;
      case "CONCLUIDA" -> ChargeStatus.COMPLETED;
      case "REMOVIDA_PELO_USUARIO_RECEBEDOR", "REMOVIDO_PELO_USUARIO_RECEBEDOR" -> ChargeStatus.REMOVED_BY_MERCHANT;
      case "REMOVIDA_PELO_PSP", "REMOVIDO_PELO_PSP" -> ChargeStatus.REMOVED_BY_PSP;
      default -> throw new ProviderException(ProviderException.Code.UNKNOWN, 200, null, "unknown charge status: " + s);
    };
  }
}
```

`ProvidersConfiguration.java`: `@EnableConfigurationProperties(ProvidersProperties)`; `@Bean ItauTokenClient(Clock, PT3S, readTimeout)`; `@Bean Clock` **não** — o `app` já pode ter um; use `@ConditionalOnMissingBean` para um `Clock.systemUTC()`; `@Bean PixProvider itauPixProvider(...)`: `trustStore` = `PemKeyStores.trustStoreFrom(parse(trustStorePem))` se configurado, senão `null` (JDK default — a CA do Itaú é pública e costuma estar no cacerts; quando não estiver, a propriedade recebe o PEM do `ca-cert.zip`). Endpoints: defaults do `NOTES.md`, sobrescritos pelas propriedades (é assim que os testes do `app` apontam para o WireMock).

- [ ] **Step 7: Rodar e ver passar** — `./mvnw -B -q -pl gateway-providers -am test`.

- [ ] **Step 8: Commit**

```bash
git add gateway-providers
git commit -m "feat(providers): pix api client and itau provider against the official openapi

Fixtures are the bank's own examples, pinned by a test that compares
them to the OpenAPI; every request we build is validated against the
bank's schema. No fake provider: this is the contract until the
sandbox is enabled."
```

---

### Task 5: `payments` — domínio puro (máquina de estados, idempotência, devolução)

**Files:**
- Create: `gateway-payments/src/main/java/com/gateway/payments/domain/{Payment,PaymentStatus,PaymentTransitions,EventSource,PaymentEvent,PixDetails,Refund,RefundState,IdempotencyKey,IdempotencyStatus,OutboxMessage,Job,JobType,WebhookInboxEntry,ReconciliationDivergence}.java`
- Test: `gateway-payments/src/test/java/com/gateway/payments/domain/{PaymentTransitionsTest,PaymentTest,RefundTest,IdempotencyKeyTest}.java`

**Interfaces:**
- Produces:
  ```java
  enum PaymentStatus { CREATED, PENDING, COMPLETED, EXPIRED, CANCELED, FAILED; boolean terminal() /* COMPLETED, CANCELED, FAILED */ }
  enum EventSource { API, PROVIDER_WEBHOOK, RECONCILIATION, EXPIRATION_JOB, SYSTEM }
  final class PaymentTransitions { static boolean allowed(PaymentStatus from, PaymentStatus to, EventSource by); static Set<Transition> table(); record Transition(PaymentStatus from, PaymentStatus to, Set<EventSource> by) }
     // tabela = a da spec §3.1: CREATED→PENDING(API), CREATED→FAILED(API,SYSTEM), PENDING→COMPLETED(PROVIDER_WEBHOOK,RECONCILIATION), PENDING→EXPIRED(EXPIRATION_JOB), PENDING→CANCELED(API), EXPIRED→COMPLETED(PROVIDER_WEBHOOK,RECONCILIATION)
  record PixDetails(String txid, String pixCopiaECola, String location, String endToEndId) { PixDetails withEndToEndId(String) }
  final class Payment { // mutable aggregate, like Delivery in webhook-delivery
     static Payment create(MerchantId, ProviderEnvironment env, String provider, Money amount, String reference, String description, String customerDocumentHash, int expiresInSeconds, Clock)
     // id = Ulid.next(); txid = id; status CREATED; version 0
     PaymentEvent markPending(PixDetails, Instant expiresAt);     PaymentEvent markFailed(String reason, EventSource);
     PaymentEvent markCompleted(String endToEndId, Money paidAmount, Instant paidAt, EventSource);   // allowed from PENDING or EXPIRED per table
     PaymentEvent markExpired(EventSource);  PaymentEvent markCanceled(EventSource);
     Optional<PaymentEvent> recordIgnored(String what, EventSource);   // webhook em estado terminal: evento registrado, sem transição
     void applyRefund(Money amount);    Money refundedAmount(); boolean fullyRefunded(); boolean partiallyRefunded();
     getters: id(), merchantId(), environment(), provider(), status(), amount(), reference(), description(), customerDocumentHash(), pix(), expiresAt(), paidAt(), paidAmount(), version(), createdAt(), updatedAt()
     static Payment rehydrate(...)  }
  record PaymentEvent(String id, String paymentId, long sequence, String type, EventSource source, String payload /* json */, Instant at)   // type: "created","pending","completed","expired","canceled","failed","ignored","refund_requested","refund_completed","refund_failed"
     // sequence = version+1 e o Payment.version passa a ser a sequence do último evento (optimistic lock)
  enum RefundState { REQUESTED, PROCESSING, COMPLETED, FAILED }
  final class Refund { static Refund request(String paymentId, MerchantId, Money amount, Clock); id = Ulid.next() (é o {id} da devolução no Itaú); markProcessing(); markCompleted(Instant settledAt); markFailed(String reason); getters + rehydrate }
  enum IdempotencyStatus { IN_PROGRESS, DONE }
  record IdempotencyKey(MerchantId merchantId, String key, String requestHash, IdempotencyStatus status, Integer responseCode, String responseBody, String resourceId, Instant createdAt) { static IdempotencyKey begin(MerchantId, String key, String requestHash, Clock); IdempotencyKey finish(int code, String body, String resourceId); static String hashOf(String canonicalBody) }
  record OutboxMessage(String id, MerchantId merchantId, String aggregateId, String partitionKey, String eventType, String payload, String status /* PENDING|SENT */, Instant claimedAt, Instant createdAt)
  enum JobType { PROCESS_WEBHOOK, EXPIRE_PAYMENT, POLL_REFUND, RECONCILE }
  record Job(String id, JobType type, String refId, Instant nextRunAt, int attempts, String status /* PENDING|DONE|DEAD */, Instant claimedAt, String lastError, Instant createdAt) { Job reschedule(Instant next, String error, int maxAttempts); Job done() }
  record WebhookInboxEntry(String id, String provider, MerchantId merchantId, String rawHeaders, byte[] rawBody, String status /* RECEIVED|PROCESSED|FAILED|IGNORED */, String error, Instant receivedAt)
  record ReconciliationDivergence(String id, String paymentId, String gatewayStatus, String providerStatus, String detail, String status /* OPEN|RESOLVED */, Instant createdAt)
  ```

- [ ] **Step 1: Testes do domínio**

`PaymentTransitionsTest.java`:
```java
package com.gateway.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.EnumSet;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** The transition table is the spec (§3.1). Every allowed row passes; every combination outside it is refused. */
class PaymentTransitionsTest {
  static Stream<Arguments> allowed() {
    return PaymentTransitions.table().stream().flatMap(t -> t.by().stream().map(s -> Arguments.of(t.from(), t.to(), s)));
  }
  @ParameterizedTest @MethodSource("allowed")
  void everyRowOfTheTableIsAllowed(PaymentStatus from, PaymentStatus to, EventSource by) {
    assertThat(PaymentTransitions.allowed(from, to, by)).isTrue();
  }
  static Stream<Arguments> everything() {
    return Stream.of(PaymentStatus.values()).flatMap(f -> Stream.of(PaymentStatus.values()).flatMap(t -> Stream.of(EventSource.values()).map(s -> Arguments.of(f, t, s))));
  }
  @ParameterizedTest @MethodSource("everything")
  void everythingOutsideTheTableIsRefused(PaymentStatus from, PaymentStatus to, EventSource by) {
    boolean inTable = PaymentTransitions.table().stream().anyMatch(t -> t.from() == from && t.to() == to && t.by().contains(by));
    assertThat(PaymentTransitions.allowed(from, to, by)).isEqualTo(inTable);
  }
  /** The bank wins: a late settlement after we expired the charge is a completion, but only from the provider side. */
  @org.junit.jupiter.api.Test void expiredCanCompleteOnlyByProviderOrReconciliation() {
    assertThat(PaymentTransitions.allowed(PaymentStatus.EXPIRED, PaymentStatus.COMPLETED, EventSource.PROVIDER_WEBHOOK)).isTrue();
    assertThat(PaymentTransitions.allowed(PaymentStatus.EXPIRED, PaymentStatus.COMPLETED, EventSource.RECONCILIATION)).isTrue();
    assertThat(PaymentTransitions.allowed(PaymentStatus.EXPIRED, PaymentStatus.COMPLETED, EventSource.API)).isFalse();
    assertThat(EnumSet.of(PaymentStatus.COMPLETED, PaymentStatus.CANCELED, PaymentStatus.FAILED)).allMatch(PaymentStatus::terminal);
  }
}
```

`PaymentTest.java`:
```java
package com.gateway.payments.domain;

import static org.assertj.core.api.Assertions.*;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.*;
import org.junit.jupiter.api.Test;

class PaymentTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);
  Payment fresh() { return Payment.create(MerchantId.next(), ProviderEnvironment.TEST, "ITAU", Money.brl(15990), "order-8812", "Order 8812", null, 3600, clock); }

  @Test void txidIsTheIdAndFitsBacen() {
    Payment p = fresh();
    assertThat(p.id()).matches("[a-zA-Z0-9]{26,35}");
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    assertThat(p.version()).isZero();
  }
  @Test void eventsCarryAMonotonicSequenceAndBumpTheVersion() {
    Payment p = fresh();
    PaymentEvent e1 = p.markPending(new PixDetails(p.id(), "000201…", "pix.example.com/x", null), Instant.parse("2026-09-24T13:00:00Z"));
    PaymentEvent e2 = p.markCompleted("E12345678202009091221kkkkkkkkkkk", Money.brl(15990), Instant.parse("2026-09-24T12:30:00Z"), EventSource.PROVIDER_WEBHOOK);
    assertThat(e1.sequence()).isEqualTo(1); assertThat(e2.sequence()).isEqualTo(2);
    assertThat(p.version()).isEqualTo(2);
    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(p.pix().endToEndId()).isEqualTo("E12345678202009091221kkkkkkkkkkk");
    assertThat(e2.type()).isEqualTo("completed");
  }
  @Test void refusedTransitionsThrow() {
    Payment p = fresh();
    assertThatThrownBy(() -> p.markCompleted("E1", Money.brl(1), Instant.now(), EventSource.PROVIDER_WEBHOOK)).isInstanceOf(IllegalStateException.class);
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    assertThatThrownBy(() -> p.markCompleted("E1", Money.brl(1), Instant.now(), EventSource.API)).isInstanceOf(IllegalStateException.class);
  }
  @Test void webhookOnTerminalStateIsRecordedAndIgnored() {
    Payment p = fresh();
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    p.markCanceled(EventSource.API);
    var ignored = p.recordIgnored("late webhook E1", EventSource.PROVIDER_WEBHOOK);
    assertThat(ignored).isPresent();
    assertThat(ignored.get().type()).isEqualTo("ignored");
    assertThat(p.status()).isEqualTo(PaymentStatus.CANCELED);
  }
  @Test void expiredThenPaidByTheBankCompletes() {
    Payment p = fresh();
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    p.markExpired(EventSource.EXPIRATION_JOB);
    p.markCompleted("E1", Money.brl(15990), Instant.now(), EventSource.RECONCILIATION);
    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
  }
  @Test void refundsAreProjectedNotTransitions() {
    Payment p = fresh();
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    p.markCompleted("E1", Money.brl(15990), Instant.now(), EventSource.PROVIDER_WEBHOOK);
    p.applyRefund(Money.brl(5000));
    assertThat(p.partiallyRefunded()).isTrue(); assertThat(p.fullyRefunded()).isFalse();
    p.applyRefund(Money.brl(10990));
    assertThat(p.fullyRefunded()).isTrue();
    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThatThrownBy(() -> p.applyRefund(Money.brl(1))).isInstanceOf(IllegalArgumentException.class);
  }
}
```

`RefundTest.java`: `request` gera id `[a-zA-Z0-9]{26,35}`, estado `REQUESTED`; `markProcessing → PROCESSING`; `markCompleted` grava `settledAt`; `markFailed` grava `reason`; `COMPLETED` não volta (`IllegalStateException`).

`IdempotencyKeyTest.java`: `begin` é `IN_PROGRESS` sem resposta; `finish` vira `DONE` com código/corpo/resource; `hashOf` é SHA-256 hex determinístico e diferente para corpos diferentes.

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-payments -am test -Dtest='PaymentTransitionsTest,PaymentTest,RefundTest,IdempotencyKeyTest' -Dsurefire.failIfNoSpecifiedTests=false`.

- [ ] **Step 3: Implementar o domínio**

`PaymentTransitions.java`:
```java
package com.gateway.payments.domain;

import static com.gateway.payments.domain.EventSource.*;
import static com.gateway.payments.domain.PaymentStatus.*;
import java.util.EnumSet;
import java.util.Set;

/**
 * The state machine as a table (spec §3.1). A transition is (from, to, who may trigger it): the
 * provider may complete an EXPIRED charge because the bank settles up to the last second and its
 * webhook arrives after our expiry job ran — the bank wins. Nobody else may.
 */
public final class PaymentTransitions {
  public record Transition(PaymentStatus from, PaymentStatus to, Set<EventSource> by) {}
  private static final Set<Transition> TABLE = Set.of(
      new Transition(CREATED, PENDING, EnumSet.of(API)),
      new Transition(CREATED, FAILED, EnumSet.of(API, SYSTEM)),
      new Transition(PENDING, COMPLETED, EnumSet.of(PROVIDER_WEBHOOK, RECONCILIATION)),
      new Transition(PENDING, EXPIRED, EnumSet.of(EXPIRATION_JOB)),
      new Transition(PENDING, CANCELED, EnumSet.of(API)),
      new Transition(EXPIRED, COMPLETED, EnumSet.of(PROVIDER_WEBHOOK, RECONCILIATION)));
  private PaymentTransitions() {}
  public static Set<Transition> table() { return TABLE; }
  public static boolean allowed(PaymentStatus from, PaymentStatus to, EventSource by) {
    return TABLE.stream().anyMatch(t -> t.from() == from && t.to() == to && t.by().contains(by));
  }
}
```

`Payment.java`: campos privados; construtor privado; `create(...)` com `id = Ulid.next()`, `pix = new PixDetails(id, null, null, null)`, `status = CREATED`, `version = 0`; cada `mark*` chama `transition(to, by, type, payloadJson)` que verifica `PaymentTransitions.allowed` (senão `IllegalStateException("transition " + status + "→" + to + " by " + by + " is not allowed")`), atualiza campos, incrementa `version`, cria `PaymentEvent(Ulid.next(), id, version, type, by, payload, clock.instant())`; `recordIgnored` só cria evento `ignored` sem mudar estado nem versão? — **muda a versão** (é um evento gravado; `sequence` precisa avançar) mas não o `status`. `applyRefund`: exige `COMPLETED`, soma ≤ `amount` senão `IllegalArgumentException`. Payload JSON dos eventos: string montada à mão com os campos relevantes (`{"endToEndId":"…","paidAmount":15990}`), sem Jackson no domínio.

`Refund`, `IdempotencyKey` (`hashOf` = SHA-256 hex via `MessageDigest`), demais records: como na seção Interfaces. `Job.reschedule(next, error, maxAttempts)`: `attempts+1`, `status = attempts+1 >= maxAttempts ? "DEAD" : "PENDING"`, `nextRunAt = next`, `lastError`, `claimedAt = null`.

- [ ] **Step 4: Rodar e ver passar** — verdes.

- [ ] **Step 5: Commit**

```bash
git add gateway-payments
git commit -m "feat(payments): payment state machine as a table, events with sequence, refund, idempotency key

Every allowed transition names who may trigger it; the test walks
the whole cross product. Refund state is a projection, not a
transition. txid is the payment id so a retry after a timeout can ask
the bank whether the charge already exists."
```

---

### Task 6: `payments` — migration V200, repositórios, outbox/jobs com `SKIP LOCKED`

**Files:**
- Create: `gateway-payments/src/main/resources/db/migration/payments/V200__payments.sql` (substitui o stub da Task 1), `repository/{PaymentEntity,PaymentEventEntity,RefundEntity,IdempotencyKeyEntity,OutboxEntity,JobEntity,WebhookInboxEntity,ProviderRequestEntity,ReconciliationDivergenceEntity}.java` + JPA repos + interfaces + impls, `PaymentsConfiguration.java` (`@EntityScan`/`@EnableJpaRepositories("com.gateway.payments.repository")`, `@Import` dos impls)
- Test: `gateway-payments/src/test/java/com/gateway/payments/TestApp.java`, `src/test/resources/application.yml`, `repository/{PaymentRepositoryIntegrationTest,IdempotencyRepositoryIntegrationTest,JobsAndOutboxClaimIntegrationTest}.java`

**Interfaces:**
- Produces:
  ```java
  interface PaymentRepository { Payment save(Payment p, List<PaymentEvent> newEvents) /* optimistic: UPDATE … WHERE version = expected; StaleStateException → OptimisticLockException */; Optional<Payment> findById(String id); Optional<Payment> findByMerchantAndId(MerchantId, String id); List<Payment> listByMerchant(MerchantId, int limit, String cursorId); List<Payment> findPendingOlderThan(Instant expiresBefore, int limit); List<Payment> findByStatusIn(Set<PaymentStatus>, Instant createdAfter); List<PaymentEvent> events(String paymentId); }
  interface RefundRepository { Refund save(Refund); Optional<Refund> findById(String); List<Refund> findByPayment(String paymentId); List<Refund> findByState(RefundState); }
  interface IdempotencyRepository { boolean insertIfAbsent(IdempotencyKey k) /* INSERT … ON CONFLICT DO NOTHING */; Optional<IdempotencyKey> find(MerchantId, String key); void finish(IdempotencyKey k); int deleteOlderThan(Instant); }
  interface OutboxRepository { void append(OutboxMessage m) /* same tx as the caller */; List<OutboxMessage> claimPending(int limit, Duration lease) /* FOR UPDATE SKIP LOCKED, requires tx */; void markSent(String id); void release(String id); }
  interface JobRepository { void enqueue(Job j); List<Job> claimDue(Instant now, int limit, Duration lease); void save(Job j); Optional<Job> findByTypeAndRef(JobType, String refId); }
  interface WebhookInboxRepository { WebhookInboxEntry save(WebhookInboxEntry e); Optional<WebhookInboxEntry> findById(String); }
  interface ProviderRequestRepository { void record(String paymentId, String provider, String operation, String request, String response, int status, long latencyMs); }
  interface ReconciliationDivergenceRepository { void save(ReconciliationDivergence d); List<ReconciliationDivergence> open(); }
  ```

- [ ] **Step 1: Migration**

```sql
-- payments module (spec §9). Money is BIGINT cents; the state machine lives in code, the columns only store it.
-- txid is the payment id (ULID): it is what lets a retry after a timeout ask the bank "does this charge exist?".

CREATE TABLE payments (
    id                     CHAR(26)     PRIMARY KEY,
    merchant_id            CHAR(26)     NOT NULL,
    environment            VARCHAR(10)  NOT NULL,           -- LIVE | TEST, always from the API key
    provider               VARCHAR(20)  NOT NULL,
    method                 VARCHAR(10)  NOT NULL,           -- PIX (BOLETO/CARD come with later plans)
    status                 VARCHAR(20)  NOT NULL,
    amount                 BIGINT       NOT NULL,
    currency               CHAR(3)      NOT NULL,
    reference              VARCHAR(140),
    description            VARCHAR(140),
    customer_document_hash CHAR(64),                        -- SHA-256 of the CPF/CNPJ, for search; the value itself is never stored in plan B
    details                JSONB        NOT NULL DEFAULT '{}'::jsonb,   -- method-specific: pix{txid,pixCopiaECola,location,endToEndId}
    expires_at             TIMESTAMPTZ,
    paid_at                TIMESTAMPTZ,
    paid_amount            BIGINT,
    refunded_amount        BIGINT       NOT NULL DEFAULT 0,
    version                BIGINT       NOT NULL DEFAULT 0, -- sequence of the last event; optimistic lock
    created_at             TIMESTAMPTZ  NOT NULL,
    updated_at             TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_payments_merchant_created ON payments (merchant_id, created_at DESC);
CREATE INDEX idx_payments_status_expires ON payments (status, expires_at);
CREATE UNIQUE INDEX uq_payments_provider_txid ON payments (provider, (details->>'txid'));
CREATE INDEX idx_payments_e2eid ON payments ((details->>'endToEndId'));

-- The log that rebuilds a payment. sequence is per payment and is the version.
CREATE TABLE payment_events (
    id         CHAR(26)    PRIMARY KEY,
    payment_id CHAR(26)    NOT NULL REFERENCES payments (id),
    sequence   BIGINT      NOT NULL,
    type       VARCHAR(30) NOT NULL,
    source     VARCHAR(20) NOT NULL,
    payload    JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_payment_events_sequence UNIQUE (payment_id, sequence)
);

CREATE TABLE refunds (
    id                 CHAR(26)     PRIMARY KEY,                 -- also the {id} at the bank
    payment_id         CHAR(26)     NOT NULL REFERENCES payments (id),
    merchant_id        CHAR(26)     NOT NULL,
    amount             BIGINT       NOT NULL,
    state              VARCHAR(20)  NOT NULL,
    provider_refund_id VARCHAR(64),
    reason             VARCHAR(140),
    requested_at       TIMESTAMPTZ  NOT NULL,
    settled_at         TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_refunds_payment ON refunds (payment_id);
CREATE INDEX idx_refunds_state ON refunds (state);

-- Written BEFORE any external call (spec §3.2): the row is the lock.
CREATE TABLE idempotency_keys (
    merchant_id   CHAR(26)     NOT NULL,
    key           VARCHAR(128) NOT NULL,
    request_hash  CHAR(64)     NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    response_code INTEGER,
    response_body TEXT,
    resource_id   CHAR(26),
    created_at    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (merchant_id, key)
);
CREATE INDEX idx_idempotency_created ON idempotency_keys (created_at);

-- Transactional outbox: written in the same transaction as the payment change; the relay in app delivers.
CREATE TABLE outbox (
    id            CHAR(26)     PRIMARY KEY,
    merchant_id   CHAR(26)     NOT NULL,
    aggregate_id  CHAR(26)     NOT NULL,
    partition_key VARCHAR(64),
    event_type    VARCHAR(60)  NOT NULL,
    payload       TEXT         NOT NULL,
    status        VARCHAR(10)  NOT NULL DEFAULT 'PENDING',
    claimed_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_outbox_pending ON outbox (status, created_at) WHERE status = 'PENDING';

-- Postgres as the job queue (spec §2): lease + SKIP LOCKED, the same shape as webhook-delivery's claim.
CREATE TABLE jobs (
    id          CHAR(26)    PRIMARY KEY,
    type        VARCHAR(30) NOT NULL,
    ref_id      VARCHAR(64) NOT NULL,
    next_run_at TIMESTAMPTZ NOT NULL,
    attempts    INTEGER     NOT NULL DEFAULT 0,
    status      VARCHAR(10) NOT NULL DEFAULT 'PENDING',
    claimed_at  TIMESTAMPTZ,
    last_error  VARCHAR(500),
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_jobs_type_ref UNIQUE (type, ref_id)
);
CREATE INDEX idx_jobs_due ON jobs (status, next_run_at, claimed_at) WHERE status = 'PENDING';

-- Raw provider webhooks, stored before anything else (the bank gives us 5 s).
CREATE TABLE webhook_inbox (
    id          CHAR(26)    PRIMARY KEY,
    provider    VARCHAR(20) NOT NULL,
    merchant_id CHAR(26)    NOT NULL,
    raw_headers TEXT        NOT NULL,
    raw_body    BYTEA       NOT NULL,
    status      VARCHAR(10) NOT NULL DEFAULT 'RECEIVED',
    error       VARCHAR(500),
    received_at TIMESTAMPTZ NOT NULL
);

-- Every call to a bank, for support and for the metrics per provider.
CREATE TABLE provider_requests (
    id         CHAR(26)    PRIMARY KEY,
    payment_id CHAR(26),
    provider   VARCHAR(20) NOT NULL,
    operation  VARCHAR(40) NOT NULL,
    request    TEXT,
    response   TEXT,
    status     INTEGER     NOT NULL,
    latency_ms BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_provider_requests_payment ON provider_requests (payment_id);

CREATE TABLE reconciliation_divergences (
    id              CHAR(26)    PRIMARY KEY,
    payment_id      CHAR(26)    NOT NULL,
    gateway_status  VARCHAR(20) NOT NULL,
    provider_status VARCHAR(30) NOT NULL,
    detail          VARCHAR(500),
    status          VARCHAR(10) NOT NULL DEFAULT 'OPEN',
    created_at      TIMESTAMPTZ NOT NULL
);
```

- [ ] **Step 2: Testes de repositório** (padrão Testcontainers de `MerchantsIntegrationTest`; `TestApp` importa `PaymentsConfiguration`; `application.yml` de teste com `schemas: payments`, `locations: classpath:db/migration/payments`):
- `PaymentRepositoryIntegrationTest`: `save` de um `Payment` novo + eventos grava `payments` e `payment_events`; `findById` reidrata com `pix`, `version` e `status`; segundo `save` com `version` desatualizada (simule carregando duas cópias, transicionando as duas, salvando as duas) → a segunda lança `OptimisticLockException` (do Spring: `ObjectOptimisticLockingFailureException`); `findPendingOlderThan` devolve só `PENDING` com `expires_at < x`; `events()` em ordem de `sequence`.
- `IdempotencyRepositoryIntegrationTest`: `insertIfAbsent` true na primeira, false na segunda (mesma chave), true para outro merchant com a mesma chave; `finish` grava resposta; concorrência: 10 threads `insertIfAbsent` mesma chave → exatamente 1 true.
- `JobsAndOutboxClaimIntegrationTest`: `claimDue` fora de transação lança (`IllegalStateException`, como na lib); dois claims concorrentes com latch pegam conjuntos disjuntos; lease vencido volta a ser reivindicável; `claimPending` do outbox idem; `markSent`.

- [ ] **Step 3: Rodar e ver falhar** — compilação.

- [ ] **Step 4: Implementar** — entidades package-private (`@Table(schema = "payments")`, `@JdbcTypeCode(SqlTypes.CHAR)` nas `CHAR(n)`, `@JdbcTypeCode(SqlTypes.JSON)` em `details`/`payload` mapeados como `String`), `PaymentEntity` com `@Version long version` (Hibernate faz o `WHERE version = ?`); `PaymentRepositoryImpl.save` grava a entidade e os eventos na mesma transação (`@Transactional(propagation = MANDATORY)` — quem chama abre a transação; o `IdempotencyService` também). `claimDue`/`claimPending`: JPQL `PESSIMISTIC_WRITE` + hint `jakarta.persistence.lock.timeout=-2` (copie o padrão de `C:\Dev\webhook-delivery\src\main\java\com\barrier\webhookdelivery\repository\DeliveryJpaRepository.java`, incluindo a verificação `TransactionSynchronizationManager.isActualTransactionActive()`); `insertIfAbsent`: `INSERT … ON CONFLICT (merchant_id, key) DO NOTHING` nativo qualificado com `payments.`; `uq_jobs_type_ref` → `enqueue` também via `ON CONFLICT DO NOTHING` (um job por (tipo, ref)).

- [ ] **Step 5: Rodar e ver passar** — `./mvnw -B -q -pl gateway-payments -am test`.

- [ ] **Step 6: Commit**

```bash
git add gateway-payments
git commit -m "feat(payments): migration V200, repositories, idempotent insert, outbox and jobs with skip locked

Optimistic lock via the event sequence; idempotency key inserted with
ON CONFLICT DO NOTHING so ten concurrent requests get exactly one
winner; claims refuse to run outside a transaction, as in
webhook-delivery, because the lock would otherwise die silently."
```

---

### Task 7: `payments` — serviços: criar cobrança com idempotência, cancelar, devolver, webhook inbox, expiração, reconciliation, polling

**Files:**
- Create: `gateway-payments/src/main/java/com/gateway/payments/service/{PaymentsProperties,ProviderGateway,IdempotencyService,PaymentService,RefundService,WebhookInboxService,ExpirationService,ReconciliationService,RefundPollingService,JobRunner,PaymentEvents}.java`
- Modify: `PaymentsConfiguration.java` (beans)
- Test: `gateway-payments/src/test/java/com/gateway/payments/service/{RecordingPixProvider.java,InMemoryCredentialLookup.java,PaymentServiceIntegrationTest,RefundServiceIntegrationTest,WebhookInboxServiceIntegrationTest,ExpirationAndReconciliationIntegrationTest}.java`

**Interfaces:**
- Produces:
  ```java
  @ConfigurationProperties("gateway.payments") record PaymentsProperties(int defaultExpiresInSeconds /*3600*/, Duration expirationGrace /*PT5M*/, Duration reconciliationLookback /*PT48H*/, Duration reconciliationMinAge /*PT10M*/, Duration idempotencyTtl /*PT24H*/, int jobMaxAttempts /*8*/, Duration jobLease /*PT2M*/, Duration outboxLease /*PT1M*/)
  class PaymentEvents { void emit(MerchantId, String type, Payment p) /* appends OutboxMessage with the public JSON of the payment; partitionKey = payment id */; void emitRefund(MerchantId, String type, Refund r, Payment p) }
  class ProviderGateway {   // resolves provider + credentials, records provider_requests, maps ProviderException → DomainException codes
     record Resolved(PixProvider provider, ProviderCredentials credentials) {}
     Resolved resolve(MerchantId, ProviderEnvironment, String provider);   // no credential → DomainException("PROVIDER_CREDENTIALS_MISSING")
     <T> T call(String paymentId, String operation, Resolved r, Function<Resolved,T> fn);   // timing + provider_requests + rethrow
  }
  class IdempotencyService {
     record Replay(int code, String body) {}
     sealed interface Outcome { record Proceed(IdempotencyKey k) implements Outcome {} record Replayed(Replay r) implements Outcome {} record InProgress() implements Outcome {} record Mismatch() implements Outcome {} }
     Outcome begin(MerchantId, String key, String requestHash);   // insertIfAbsent; on conflict compares hash/status
     void finish(IdempotencyKey k, int code, String body, String resourceId);
  }
  class PaymentService {
     record CreateCharge(MerchantId merchantId, ProviderEnvironment env, Money amount, String reference, String description, String customerDocument, Integer expiresInSeconds) {}
     Payment createCharge(CreateCharge cmd);      // Task 8's controller wraps it with IdempotencyService
     Payment get(MerchantId, String id); List<Payment> list(MerchantId, int limit, String cursor);
     Payment cancel(MerchantId, String id);       // PENDING only; provider.cancelCharge then markCanceled(API)
     List<PaymentEvent> events(MerchantId, String id);
  }
  class RefundService { Refund request(MerchantId, String paymentId, Money amountOrNull); Refund get(MerchantId, String refundId); List<Refund> list(MerchantId, String paymentId); void applyProviderUpdate(RefundResult) }
  class WebhookInboxService { String accept(String provider, MerchantId, String rawHeaders, byte[] body) /* saves RECEIVED, enqueues PROCESS_WEBHOOK job, returns inbox id */; void process(String inboxId) }
  class ExpirationService { int expireDue(Instant now) }
  class ReconciliationService { int reconcile(MerchantId, ProviderEnvironment, Instant from, Instant to); int reconcileAll(Instant now) }
  class RefundPollingService { void poll(String refundId) }
  class JobRunner { int runDue(Instant now) /* claims jobs in tx, runs each outside, reschedules with backoff 1m,2m,4m…24h */ }
  ```

- [ ] **Step 1: `RecordingPixProvider` (teste) e testes de serviço**

`RecordingPixProvider` implementa a interface do kernel **em memória, só para os testes de `payments`** (não é um provider do produto; vive em `src/test`): guarda `Map<String, Charge>`, permite programar `failNextCreateWith(ProviderException)` e `timeoutNextCreateButCreateAnyway()` (simula o caso "o PUT deu timeout mas o banco criou"), `markPaid(txid, e2eid, amount)`, `refundResult(...)`. `InMemoryCredentialLookup` devolve bytes fixos para `(merchant, "ITAU", env)` e vazio para outros.

`PaymentServiceIntegrationTest` (Testcontainers; `TestApp` importa `PaymentsConfiguration` + beans de teste para `PixProvider`/`CredentialLookup`/`Clock`):
- `createsAChargeAndEmitsPending`: `PENDING`, `pix().txid()==id`, `pixCopiaECola` presente, `expires_at = now + 3600s`, outbox tem `payment.pending`, `provider_requests` tem 1 linha `createCharge` com status 201.
- `missingCredentialsFailsBeforeCallingTheBank`: outro env → `DomainException("PROVIDER_CREDENTIALS_MISSING")`, provider não chamado, nenhum payment gravado.
- `providerDeclineMarksFailed`: `failNextCreateWith(INVALID)` → payment `FAILED` com evento `failed`, outbox `payment.failed`, `DomainException("PROVIDER_DECLINED")` para o chamador.
- `timeoutThenRetryFindsTheExistingCharge`: `timeoutNextCreateButCreateAnyway()` → primeira chamada lança `DomainException("PROVIDER_TIMEOUT")` e o payment fica `CREATED`; `PaymentService.retryCreate(paymentId)` (método público usado pelo job `RETRY_CREATE`? — **decisão**: a retentativa é síncrona dentro de `createCharge`: ao pegar `TIMEOUT`, faz `findCharge(txid)` uma vez; se existe, adota (`PENDING`); se não, `FAILED` com `PROVIDER_TIMEOUT`) — o teste asserta que o provider recebeu 1 `createCharge` + 1 `findCharge` e o payment ficou `PENDING` com o `pixCopiaECola` do banco.
- `cancelPendingCallsTheBankAndEmits`; `cancelCompletedIsRefused` (`DomainException("INVALID_STATE")`).
- `listIsScopedToTheMerchant`.

`RefundServiceIntegrationTest`: `requestOnCompletedPaymentIsProcessingAndEnqueuesPolling` (refund `PROCESSING`, job `POLL_REFUND` enfileirado, `refund.requested` no outbox); `sumAboveAmountIsRefused` (`REFUND_EXCEEDS_AMOUNT`); `afterNinetyDaysIsRefused` (`REFUND_WINDOW_CLOSED`, com `Clock` avançado); `providerUpdateCompletesAndProjects` (`applyProviderUpdate(COMPLETED)` → refund `COMPLETED`, payment `refunded_amount`, outbox `refund.completed`); `providerUpdateFailedKeepsPaymentUntouched`.

`WebhookInboxServiceIntegrationTest`: `acceptStoresRawAndEnqueues` (202 path: linha `RECEIVED`, job `PROCESS_WEBHOOK`); `processCompletesThePendingPayment` (usa `RecordingPixProvider.parseWebhook` que devolve `ReceivedPix` para o txid) → `COMPLETED`, `paid_at`, `e2eid`, outbox `payment.completed`, inbox `PROCESSED`; `duplicateWebhookIsIgnoredOnce` (mesmo e2eid duas vezes → segundo cria evento `ignored`, sem novo outbox); `unknownTxidIsIgnored` (inbox `IGNORED`, sem erro); `unreadableBodyIsFailed`.

`ExpirationAndReconciliationIntegrationTest`: `expirationAsksTheBankFirst` (payment `PENDING` vencido; provider diz `COMPLETED` → vira `COMPLETED` por `RECONCILIATION`... **decisão**: o `GET` antes de expirar é da `ExpirationService`, e se o banco diz pago, a transição usa `EventSource.RECONCILIATION`); `expirationMarksExpiredWhenBankSaysActive`; `reconciliationCompletesWhatTheWebhookMissed` (payment `EXPIRED`, `listCharges` traz `COMPLETED` → `COMPLETED`); `reconciliationOpensDivergenceForTheRest` (gateway `COMPLETED`, banco `REMOVED_BY_PSP` → `reconciliation_divergences` `OPEN`, payment intocado).

- [ ] **Step 2: Rodar e ver falhar** — compilação.

- [ ] **Step 3: Implementar**

`PaymentService.createCharge` (o coração; transações explícitas com `TransactionTemplate` porque a chamada ao banco fica **fora** de transação):
```java
  public Payment createCharge(CreateCharge cmd) {
    ProviderGateway.Resolved r = providers.resolve(cmd.merchantId(), cmd.env(), "ITAU");   // fails fast: PROVIDER_CREDENTIALS_MISSING
    int expires = cmd.expiresInSeconds() == null ? props.defaultExpiresInSeconds() : cmd.expiresInSeconds();
    Payment payment = tx.execute(s -> {
      Payment p = Payment.create(cmd.merchantId(), cmd.env(), "ITAU", cmd.amount(), cmd.reference(), cmd.description(), hash(cmd.customerDocument()), expires, clock);
      return payments.save(p, List.of(p.createdEvent()));
    });
    Charge charge;
    try {
      charge = providers.call(payment.id(), "createCharge", r, x -> x.provider().createCharge(x.credentials(), payment.id(), cmd.amount(), expires, cmd.customerDocument(), null, cmd.description()));
    } catch (ProviderException e) {
      if (e.code() == ProviderException.Code.TIMEOUT) {
        // The PUT may have landed. txid is ours, so we can ask before deciding (spec §3.2).
        Optional<Charge> existing = providers.call(payment.id(), "findCharge", r, x -> x.provider().findCharge(x.credentials(), payment.id()));
        if (existing.isPresent()) { charge = existing.get(); }
        else { return fail(payment, "PROVIDER_TIMEOUT", e); }
      } else {
        return fail(payment, e.code() == ProviderException.Code.INVALID || e.code() == ProviderException.Code.DECLINED ? "PROVIDER_DECLINED" : "PROVIDER_UNAVAILABLE", e);
      }
    }
    Charge accepted = charge;
    return tx.execute(s -> {
      Payment p = payments.findById(payment.id()).orElseThrow();
      PaymentEvent ev = p.markPending(new PixDetails(accepted.txid(), accepted.pixCopiaECola(), accepted.location(), null), clock.instant().plusSeconds(accepted.expiresInSeconds() > 0 ? accepted.expiresInSeconds() : expires));
      Payment saved = payments.save(p, List.of(ev));
      jobs.enqueue(Job.expireAt(saved.id(), saved.expiresAt().plus(props.expirationGrace()), clock));
      events.emit(saved.merchantId(), "payment.pending", saved);
      return saved;
    });
  }
```
`fail(...)`: transação que marca `FAILED` com `reason = code`, emite `payment.failed`, e lança `DomainException(code, e.getMessage())`. (`Payment.createdEvent()` devolve o evento `created` de sequence 1 — ajuste na Task 5 se ainda não existir: `create` já produz o evento inicial; `version` começa em 1. Atualize `PaymentTest.txidIsTheIdAndFitsBacen` para `version()==1`.)

`WebhookInboxService.process`: lê inbox → `resolve` provider → `parseWebhook` → para cada `ReceivedPix` com `txid` conhecido (`txidByEndToEndId`): carrega payment por id=txid do merchant, se `PENDING|EXPIRED` → `markCompleted(e2eid, amount, paidAt, PROVIDER_WEBHOOK)` + `payment.completed`; se terminal → `recordIgnored`; e2eid já gravado em outro evento `completed` → `recordIgnored("duplicate e2eid")`. Para cada `RefundResult` em `refundUpdates` → `RefundService.applyProviderUpdate`. Tudo numa transação por payment; inbox `PROCESSED`/`IGNORED`/`FAILED`.

`ExpirationService.expireDue`: `findPendingOlderThan(now - grace)`; para cada: `findCharge` → `COMPLETED` no banco → `markCompleted(..., RECONCILIATION)` + evento; `ACTIVE`/ausente → `cancelCharge` best-effort (ignora `INVALID`/`NOT_FOUND`) e `markExpired(EXPIRATION_JOB)` + `payment.expired`.

`ReconciliationService.reconcileAll`: por `(merchant, env)` com payments `PENDING` mais velhos que `minAge` ou `EXPIRED` nas últimas `lookback` horas: `listCharges(from, to)`; cruza por txid; `PENDING|EXPIRED` × `COMPLETED` → completa (`RECONCILIATION`); `COMPLETED` × `REMOVED_*` ou valor diferente → divergência `OPEN`; resto nada.

`RefundPollingService.poll(refundId)`: `findRefund` → `applyProviderUpdate`; se ainda `PROCESSING`, o job é reagendado (retorna "not done" e o `JobRunner` reagenda com backoff; máximo 8 tentativas ≈ 24 h).

`JobRunner.runDue`: `claimDue` em tx; fora da tx executa por tipo (`PROCESS_WEBHOOK → inbox.process`, `EXPIRE_PAYMENT → expiration.expireOne`, `POLL_REFUND → polling.poll`, `RECONCILE → reconcile.reconcileAll`); sucesso → `done()`; exceção → `reschedule(now + backoff(attempts), msg, maxAttempts)`; `backoff(n) = min(1min * 2^n, 24h)`.

`PaymentsConfiguration`: beans de tudo; recebe `PixProvider` e `CredentialLookup` do contexto (o `app` fornece); `Clock` `@ConditionalOnMissingBean`.

- [ ] **Step 4: Rodar e ver passar** — `./mvnw -B -q -pl gateway-payments -am test`.

- [ ] **Step 5: Commit**

```bash
git add gateway-payments
git commit -m "feat(payments): create charge with idempotent txid, cancel, async refund, webhook inbox, expiration, reconciliation

The bank call runs outside any transaction; a timeout asks the bank
whether the charge exists before failing; expiration asks the bank
before marking; reconciliation completes what the webhook missed and
opens a divergence for everything else. Tests use an in-memory
implementation of the kernel interface, never a product provider."
```

---

### Task 8: `app` — REST de payments/refunds com idempotência, wiring de providers, relay do outbox, scheduler de jobs

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/{providers/ProviderWiring.java,outbox/OutboxRelay.java,jobs/JobScheduler.java,api/PaymentsController.java,api/RefundsController.java,api/IdempotencyFilter.java,api/dto/{CreatePaymentRequest,PaymentResponse,RefundRequestBody,RefundResponse,PaymentEventResponse}.java}`
- Modify: `GatewayApplication` (`@Import` de `ProviderWiring`), `application.yml` (`gateway.providers.itau.*`, `gateway.payments.*`), `api/ErrorHandler` (`ProviderException` → 502 `urn:gateway:PROVIDER_ERROR`… **não**: `PaymentService` já converte em `DomainException`; mantenha um handler de `ProviderException` → 502 só como rede de segurança)
- Test: `gateway-app/src/test/java/com/gateway/app/PaymentsFlowIntegrationTest.java`

**Interfaces:**
- Rotas (API key; `environment` de `MerchantContext`):
  - `POST /v1/payments` `{amount, currency:"BRL", method:"PIX", reference?, description?, customer?:{document}, expires_in?}` + header `Idempotency-Key` (obrigatório → 400 `IDEMPOTENCY_KEY_REQUIRED` se ausente) → 201 `PaymentResponse{id, status, method, provider, environment, amount, currency, reference, description, pix:{txid, copia_e_cola, location, end_to_end_id}, expires_at, paid_at, paid_amount, refunded_amount, created_at}`; replay → mesma resposta com header `Idempotent-Replayed: true`; `409 IN_PROGRESS`; `422 IDEMPOTENCY_KEY_REUSED`.
  - `GET /v1/payments/{id}`, `GET /v1/payments?limit&cursor`, `GET /v1/payments/{id}/events`, `POST /v1/payments/{id}/cancel` (idempotente por chave também).
  - `POST /v1/payments/{id}/refunds` `{amount?}` → 201 `RefundResponse{id, payment_id, amount, state, reason, requested_at, settled_at}`; `GET /v1/payments/{id}/refunds`; `GET /v1/refunds/{id}`.
- `IdempotencyFilter`: `OncePerRequestFilter` (@Order 40) só para `POST /v1/payments`, `POST /v1/payments/*/cancel`, `POST /v1/payments/*/refunds`: lê `Idempotency-Key`, calcula `hash = SHA-256(method + path + body)`, chama `IdempotencyService.begin`; `Replayed` → escreve a resposta gravada e para; `InProgress` → 409; `Mismatch` → 422; `Proceed` → segue com um `ContentCachingResponseWrapper` e, ao terminar, `finish(code, body, resourceId do header X-Resource-Id que o controller põe)`. Corpo lido uma vez com `ContentCachingRequestWrapper`.
- `ProviderWiring`: `@Import({ProvidersConfiguration.class, PaymentsConfiguration.class})`; `@Bean CredentialLookup credentialLookup(ProviderCredentialService svc)` que mapeia `ProviderEnvironment` ↔ `ApiKeyEnvironment` e `String provider` ↔ `Provider.valueOf`, devolvendo `ProviderCredentials(bytes, env)`.
- `OutboxRelay`: `@Scheduled(fixedDelayString = "${gateway.payments.outbox-relay-ms:1000}")`: `claimPending(100, lease)` em tx → para cada `MerchantEvents.emit(merchantId, eventType, aggregateId, partitionKey, payloadJson)` (o payload já é JSON: passe como `Map` desserializado ou mude `MerchantEvents.emit` para aceitar `String rawJson` — adicione a sobrecarga `emitRaw`) → `markSent`; falha → `release`.
- `JobScheduler`: `@Scheduled(fixedDelayString = "${gateway.payments.jobs-poll-ms:2000}")` → `JobRunner.runDue(now)`; `@Scheduled(cron = "0 */15 * * * *")` → enfileira `RECONCILE`.

- [ ] **Step 1: Teste ponta a ponta com WireMock como Itaú**

`PaymentsFlowIntegrationTest` (`RANDOM_PORT`, Testcontainers, `@ActiveProfiles("test")`, WireMock HTTP em porta dinâmica; `@DynamicPropertySource` aponta `gateway.providers.itau.test-api-base` e `test-token-url` para o WireMock; stubs de token e de `/cob/{txid}` com as fixtures da Task 4; `gateway.payments.outbox-relay-ms=200`, `jobs-poll-ms=200`, `webhook-delivery.retry-delay-ms=200`):
1. admin cria merchant + chave `TEST` + `PUT /v1/admin/merchants/{id}/providers/ITAU/credentials {environment: TEST, payload: {client_id, client_secret, pix_key}}` (forma do sandbox: sem certificado); merchant registra webhook endpoint (`HttpServer` sink) para `payment.*`.
2. `POST /v1/payments` com `Idempotency-Key: k1` → 201 `PENDING`, `pix.copia_e_cola` = o da fixture; WireMock recebeu `POST /api/oauth/jwt` (form client_credentials) e `PUT /cob/{id}` com `Authorization: Bearer` e body com `"valor":{"original":"159.90"}`.
3. Repetir o mesmo `POST` → 201 igual, header `Idempotent-Replayed: true`, WireMock ainda com 1 `PUT`.
4. Mesmo `Idempotency-Key` com `amount` diferente → 422 `IDEMPOTENCY_KEY_REUSED`.
5. Sink recebeu `payment.pending` assinado (`X-Gateway-Signature`).
6. Simular o Itaú: `POST` no `WebhookInboxService` **via HTTP mTLS é a Task 9**; aqui, chame `webhookInboxService.accept("ITAU", merchantId, "{}", webhook_pix.json com o txid = payment id)` diretamente e espere → `GET /v1/payments/{id}` = `COMPLETED` com `end_to_end_id`; sink recebeu `payment.completed` **depois** de `payment.pending` (ordem por `partitionKey`).
7. `POST /v1/payments/{id}/refunds {amount: 5000}` (stub `PUT /pix/{e2eid}/devolucao/{refundId}` → `put_devolucao_201_processing.json`) → 201 `PROCESSING`; stub `GET …/devolucao/{id}` → `get_devolucao_200_done.json`; após o polling → `GET /v1/refunds/{id}` = `COMPLETED` e `GET /v1/payments/{id}` com `refunded_amount = 5000`.
8. `POST /v1/payments` sem `Idempotency-Key` → 400.
9. Chave `LIVE` sem credencial LIVE → 422 `PROVIDER_CREDENTIALS_MISSING`, sem chamada ao WireMock.

- [ ] **Step 2: Rodar e ver falhar** — compilação.

- [ ] **Step 3: Implementar** os arquivos da seção Files conforme as Interfaces. `PaymentResponse.from(Payment)` em snake_case (config global). `ErrorHandler`: `ProviderException` → 502 `urn:gateway:PROVIDER_ERROR` sem expor o corpo do banco (log com Masker). `application.yml`:
```yaml
gateway:
  providers:
    itau:
      live-api-base: https://pix-pj.api.itau.com/regulatorio-pix/v2
      live-token-url: https://sts.itau.com.br/as/token.oauth2
      test-api-base: https://sandbox.devportal.itau.com.br/itau-ep9-api-regulatorio-pix-v2-externo/v2
      test-token-url: https://sandbox.devportal.itau.com.br/api/oauth/jwt
      test-mutual-tls: false
      live-mutual-tls: true
      trust-store-pem: ${ITAU_CA_PEM:}         # Itaú CA chain (ca-cert.zip from the portal); empty = JDK truststore
      read-timeout: PT10S
  payments:
    default-expires-in-seconds: 3600
    expiration-grace: PT5M
    reconciliation-lookback: PT48H
    reconciliation-min-age: PT10M
    idempotency-ttl: PT24H
    job-max-attempts: 8
    job-lease: PT2M
    outbox-lease: PT1M
    outbox-relay-ms: 1000
    jobs-poll-ms: 2000
```

- [ ] **Step 4: Rodar e ver passar** — `./mvnw -B test` (suíte inteira).

- [ ] **Step 5: Commit**

```bash
git add gateway-app
git commit -m "feat(app): payments and refunds api with idempotency keys, provider wiring, outbox relay and job scheduler

The idempotency filter writes the key before the controller runs and
stores the response after; a replay never reaches the bank. The relay
is the only caller of MerchantEvents, reading the payments outbox
through its repository interface."
```

---

### Task 9: `app` — webhook de entrada do Itaú em connector mTLS

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/mtls/MtlsWebhookConnector.java`, `gateway-app/src/main/java/com/gateway/app/api/providers/ItauWebhookController.java`, `gateway-app/src/main/java/com/gateway/app/api/providers/WebhookTokenGuard.java`
- Modify: `application.yml` (`gateway.webhooks.mtls.*`), `security/ProtectedRoutes` (já exclui `/v1/providers/`), `merchants` ganha `webhook_token` por merchant? — **decisão**: o token do path é o `merchant_id` cifrado? Não: use um **token opaco por merchant** guardado em `merchants.merchants.inbound_webhook_token` (nova migration `V101__inbound_webhook_token.sql`: coluna `CHAR(26)` única, preenchida com ULID na criação e para linhas existentes; `MerchantService.findByInboundWebhookToken(String)`; exposta no admin `GET /v1/admin/merchants/{id}` como `inbound_webhook_url` = `https://<host>:<mtls-port>/v1/providers/itau/webhooks/<token>` para o merchant cadastrar no Itaú via `PUT /webhook/{chave}` — o Itaú acrescenta `/pix`).
- Test: `gateway-app/src/test/java/com/gateway/app/ItauWebhookMtlsIntegrationTest.java`

**Interfaces:**
- `MtlsWebhookConnector`: `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` que adiciona um `Connector` na porta `gateway.webhooks.mtls.port` (default 8443; `0` = desligado) com `SSLHostConfig`: keystore do servidor (`gateway.webhooks.mtls.keystore`, `keystore-password`), `certificateVerification=required`, truststore = `gateway.webhooks.mtls.truststore` (CA do Itaú). Um `Filter` (@Order -10) recusa com 404 qualquer request em `/v1/providers/**` que **não** venha pela porta mTLS (`req.getLocalPort() == mtlsPort`), e recusa com 403 qualquer request na porta mTLS fora de `/v1/providers/**` — as duas direções ficam fechadas.
- `ItauWebhookController`: `POST /v1/providers/itau/webhooks/{token}/pix` → `WebhookTokenGuard` resolve o merchant (token inválido → 404, mesma resposta para não vazar existência); lê o corpo cru; grava headers relevantes (`X-Correlation-Id`, `User-Agent`, DN do cert cliente via `req.getAttribute("jakarta.servlet.request.X509Certificate")`); `WebhookInboxService.accept(...)`; responde **202 imediatamente** (o Itaú dá 5 s). Sem API key (`ProtectedRoutes` exclui). Também aceita `POST …/{token}` sem `/pix` (o Itaú documenta que acrescenta `/pix`; aceitar os dois evita um cadastro errado virar 404).

- [ ] **Step 1: Teste**

`ItauWebhookMtlsIntegrationTest`: sobe o app com `gateway.webhooks.mtls.port=0`? Não — precisa de porta real: use `SocketUtils`-like (abra um `ServerSocket(0)` para achar porta livre) via `@DynamicPropertySource`; keystore/truststore de teste gerados por `TestCertificates` (do módulo providers — mova `TestCertificates` para um pequeno módulo `gateway-test-support` **ou** duplique a classe no `app` — decisão: duplicar é aceitável porque é código de teste e a regra de "ninguém importa providers" é sobre `main`; mas o mais limpo é `gateway-providers` publicar um `test-jar` (`maven-jar-plugin` `test-jar` goal) e o `app` depender de `<classifier>test-jar</classifier>` com escopo test. Faça o test-jar).
- `webhookOverMtlsWithItauCaIsAcceptedAndProcessed`: cliente HTTPS com cert assinado pela CA de teste → `POST https://localhost:{mtls}/v1/providers/itau/webhooks/{token}/pix` com `webhook_pix.json` (txid = payment criado antes via WireMock como na Task 8) → 202 em < 2 s; depois `GET /v1/payments/{id}` (porta normal, API key) = `COMPLETED`.
- `webhookWithoutClientCertificateIsRefusedAtHandshake`: cliente sem cert → `IOException`/`SSLHandshakeException`.
- `webhookWithCertificateFromAnotherCaIsRefused`.
- `webhookOnThePlainPortIs404`: `POST http://localhost:{port}/v1/providers/itau/webhooks/{token}/pix` → 404.
- `unknownTokenIs404`; `apiRoutesOnTheMtlsPortAre403` (`GET https://localhost:{mtls}/v1/me` com cert válido → 403).

- [ ] **Step 2: Rodar e ver falhar** — compilação.

- [ ] **Step 3: Implementar** conforme Interfaces. `MtlsWebhookConnector`:
```java
  @Override public void customize(TomcatServletWebServerFactory f) {
    if (props.port() <= 0) return;
    Connector c = new Connector(TomcatServletWebServerFactory.DEFAULT_PROTOCOL);
    c.setPort(props.port()); c.setScheme("https"); c.setSecure(true);
    SSLHostConfig ssl = new SSLHostConfig();
    ssl.setCertificateVerification("required");                 // Itaú authenticates by client certificate only (NOTES.md)
    ssl.setTruststoreFile(props.truststore()); ssl.setTruststorePassword(props.truststorePassword());
    SSLHostConfigCertificate cert = new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.UNDEFINED);
    cert.setCertificateKeystoreFile(props.keystore()); cert.setCertificateKeystorePassword(props.keystorePassword());
    ssl.addCertificate(cert);
    c.addSslHostConfig(ssl);
    ((AbstractHttp11Protocol<?>) c.getProtocolHandler()).setSSLEnabled(true);
    f.addAdditionalTomcatConnectors(c);
  }
```
(A API exata do Tomcat 11 no Boot 4.0.7 pode diferir em nomes — `setCertificateKeystoreFile` etc.; o teste é o árbitro; registre o que compilou.) `application.yml`:
```yaml
gateway:
  webhooks:
    mtls:
      port: ${WEBHOOK_MTLS_PORT:8443}
      keystore: ${WEBHOOK_MTLS_KEYSTORE:}            # PKCS12 with the server certificate for the public webhook host
      keystore-password: ${WEBHOOK_MTLS_KEYSTORE_PASSWORD:}
      truststore: ${WEBHOOK_MTLS_TRUSTSTORE:}        # PKCS12 with Itaú's CA (ca-cert.zip from the portal)
      truststore-password: ${WEBHOOK_MTLS_TRUSTSTORE_PASSWORD:}
```
Keystore vazio com porta > 0 → falha na subida com mensagem clara (readiness guard), não silêncio.

- [ ] **Step 4: Rodar e ver passar** — `./mvnw -B test`.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat(app): itau inbound webhook on a dedicated mtls connector

Itau authenticates its webhook with a client certificate, not an HMAC,
so a second Tomcat connector requires a certificate signed by Itau's
CA; the plain port answers 404 for provider paths and the mtls port
answers 403 for everything else. 202 before any processing: the bank
gives us five seconds."
```

---

### Task 10: README, decisões, ArchUnit final e verificação da suíte

**Files:**
- Modify: `README.md` (seção "Payments (Pix / Itaú)": credencial de seis campos e como obtê-la, URL do webhook para cadastrar no Itaú, variáveis do connector mTLS, `ITAU_CA_PEM`), `docs/superpowers/DECISOES.md` (append: sem fake; txid = id; devolução assíncrona por polling; mTLS por connector; AAD; expiração pergunta ao banco), `ArchitectureTest` (vacuity guard `> 60`)
- Test: suíte inteira.

- [ ] **Step 1: README e DECISOES** — conteúdo factual, em inglês no README, português no DECISOES, cada decisão com alternativa rejeitada e custo.
- [ ] **Step 2: ArchUnit guard `> 60`** e rodar `./mvnw -B verify` → tudo verde; cole a contagem de testes por módulo no relatório.
- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs: payments/itau setup, plan b decisions, archunit guard raised"
```

---

## Self-review

**Cobertura da spec.** §1.5 provider fixo (só `ITAU`, resolvido por `ProviderGateway`) → T7. §1.6 sem sandbox/fixtures do OpenAPI → T4. §3.1 tabela de transições e eventos com sequence → T5/T6. §3.2 idempotência (PK, `IN_PROGRESS` antes, txid=id, `GET` antes de recriar, TTL) → T6/T7/T8 (TTL: job de limpeza `deleteOlderThan` — **adicionar** ao `JobScheduler` da T8 um `@Scheduled` horário que chama `IdempotencyRepository.deleteOlderThan(now - ttl)`; anotado aqui e o executor da T8 inclui). §3.3 devolução assíncrona → T5/T7/T8. §4 providers (auth Itaú, `PixApiClient`, erros normalizados, `provider_requests`) → T2–T4, T7. §6 webhook entrada (inbox, 202, dedup por e2eid), reconciliation, expiração com `GET` → T7, T9. §7 eventos de saída via relay → T8. §9 `payments.*` → T6 (`webhook_inbox`, `provider_requests`, `outbox`, `jobs`, `reconciliation_divergences`, `idempotency_keys`, `refunds`, `payment_events`, `payments`). §10 rotas de payments/refunds → T8 (`POST /v1/orders` fica para o Plano C; `POST /v1/payments` entra agora). §11 testes: domínio puro, módulo com Postgres, contrato de provider com fixtures + schema, arquitetura, e2e — T4–T9; o teste de caos é o Plano D.

**Placeholders.** Nenhum "TBD". Pontos de incerteza de API (Jackson 3 `@JsonInclude`, validador de schema com `$ref`, API do `SSLHostConfig` no Tomcat 11) nomeiam o teste como árbitro e pedem registro no relatório.

**Consistência de tipos.** `PixProvider` (T1) é o que `ItauPixProvider` (T4), `RecordingPixProvider` (T7) e `ProviderGateway` (T7) usam — assinaturas idênticas. `ProviderCredentials(byte[], ProviderEnvironment)` em T1/T4/T7/T8. `Charge.firstPix()` em T4/T7. `RefundResult(refundId, status, amount, reason, requestedAt, settledAt)` em T1/T4/T7. `Payment.markCompleted(e2eid, paidAmount, paidAt, source)` em T5/T7. `IdempotencyService.Outcome` em T7/T8. `WebhookInboxService.accept(provider, merchantId, rawHeaders, body)` em T7/T8/T9. `MerchantEvents.emitRaw` (novo, T8). `CredentialLookup.find(MerchantId, String, ProviderEnvironment)` em T1/T7/T8.
