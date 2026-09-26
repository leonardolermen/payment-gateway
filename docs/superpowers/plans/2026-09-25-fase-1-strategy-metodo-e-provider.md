# Fase 1 — Strategy por método e contrato único de provider — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Tirar a decisão "qual método de pagamento" de ternários e cadeias de `if` e colocá-la em
tipos: um request body por método, um flow por método, uma interface de provider sobre a espinha
comum, e as invariantes de formato do pagador dentro de value objects.

**Architecture:** Quatro camadas, de baixo para cima. O kernel ganha `MethodProvider<ISSUE, ISSUED,
STATUS>` com `issue`/`find`/`cancel` e uma extensão estreita por método; `ProviderGateway` passa a
ter duas portas tipadas em vez de um `Optional<BoletoProvider>` que todo call site destrincha; o
domínio ganha um `PaymentFlow` por método, cada um dono do seu create inteiro, compartilhando o
tronco por composição; a borda ganha um corpo selado e polimórfico por `method`, com cada record
validando só o que é seu.

**Tech Stack:** Java 25, Spring Boot, Maven multi-módulo, Jackson 3 (`tools.jackson`), JUnit 5,
AssertJ, ArchUnit, WireMock, Testcontainers (Postgres).

**Spec:** `docs/superpowers/specs/2026-09-25-payment-method-and-provider-strategy-design.md`

## Global Constraints

- **Código em inglês**: identificadores, comentários, mensagens de erro, log e commit. Docs de spec
  e plano em português (`DECISOES.md`, 2026-09-24).
- **Padrão de código**: `CLAUDE.md` na raiz e `~/.claude/CLAUDE.md`. Em especial: nome de variável
  diz o que a coisa é (`Payment payment`, nunca `Payment p`); linha em branco entre blocos lógicos;
  chaves sempre; 120 colunas; validador com mais de três `if` está pedindo um tipo.
- **Build**: `JAVA_HOME=~/.jdks/corretto-25.0.4.1`. O `JAVA_HOME` do shell aponta para um JDK 17 que
  não compila este projeto (`release version 25 not supported`).
- **ArchUnit**: `kernel` não importa Spring, JPA, nem outro módulo; `payments` não importa
  `providers`; nenhuma classe fora de `providers` tem "Itau" no nome; modelo não vê Spring.
- **Códigos e mensagens de erro são contrato**: `CUSTOMER_REQUIRED`, `INVALID_DUE_DATE`,
  `INVALID_PAYMENT_LIMIT`, `PROVIDER_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `PROVIDER_DECLINED`,
  `PROVIDER_CREDENTIALS_MISSING`, `METHOD_NOT_SUPPORTED`, `ALREADY_PAID`, `INVALID_REQUEST` e o
  texto de cada mensagem saem deste refactor idênticos, exceto onde a spec §7 tabela a mudança.
- **Refactor não muda teste**: se um teste existente precisou mudar fora do que a spec §7 prevê, o
  refactor errou. As exceções previstas são apenas: o corpo JSON que os testes de fluxo enviam
  (Task 9) e as assinaturas que os dublês implementam (Tasks 2 e 3).
- **Verificação por task**: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -q test-compile` sempre;
  os testes do módulo tocado sempre; `./mvnw verify` (precisa de Docker) nas tasks 8, 9 e 10.

---

## Estrutura de arquivos

**`gateway-kernel`**

| arquivo | responsabilidade |
|---|---|
| `kernel/payment/PaymentMethod.java` | *movido* de `payments.payment`. Vocabulário compartilhado entre payments e providers. |
| `kernel/provider/MethodProvider.java` | **novo.** A espinha: `id`, `method`, `requireIssueCredentials`, `issue`, `find`, `cancel`. |
| `kernel/provider/pix/PixMethodProvider.java` | **novo.** Estende a espinha com devolução, listagem e webhook. |
| `kernel/provider/pix/PixIssueRequest.java` | **novo.** Substitui os 7 argumentos soltos de `createCharge`. |
| `kernel/provider/boleto/BoletoMethodProvider.java` | **novo.** Estende a espinha com `pixTxidFor`. |
| `kernel/provider/pix/PixProvider.java` | **apagado** (virou `PixMethodProvider`). |
| `kernel/provider/boleto/BoletoProvider.java` | **apagado** (virou `BoletoMethodProvider`). |
| `kernel/errors/InvalidValue.java` | **novo.** Falha de formato no nível do valor, sem código e sem caminho de campo. |
| `kernel/party/PersonName.java` `Document.java` | **novos.** Nome com pelo menos uma letra; CPF/CNPJ normalizado. |
| `kernel/party/Payer.java` `Address.java` | *movidos* de `provider/boleto/`, com componentes tipados. |
| `kernel/address/Uf.java` `ZipCode.java` | **novos.** UF de duas letras normalizada para maiúscula; CEP de 8 dígitos. |

**`gateway-payments`**

| arquivo | responsabilidade |
|---|---|
| `payments/provider/ProviderGateway.java` | *modificado.* `ResolvedProvider<P>`, `resolvePix`, `resolveBoleto`, `pixProvider`. |
| `payments/payment/create/CreatePaymentCommand.java` | **novo.** Interface selada. |
| `payments/payment/create/CreatePixPayment.java` `CreateBolecodePayment.java` | **novos.** Um record por método. |
| `payments/payment/create/PayerData.java` | **novo.** O pagador cru, antes de validado. |
| `payments/payment/create/PayerFactory.java` | **novo.** `PayerData` → `Payer`, traduzindo cada falha para `CUSTOMER_REQUIRED` com o caminho do campo. |
| `payments/payment/create/PaymentFlow.java` | **novo.** A strategy. |
| `payments/payment/create/PixPaymentFlow.java` `BolecodePaymentFlow.java` | **novos.** Cada um dono do seu create inteiro. |
| `payments/payment/create/PaymentFlows.java` | **novo.** Registry checado no boot. |
| `payments/payment/create/PaymentDraftFactory.java` | **novo.** CREATED + evento, na mesma transação; reserva do nosso número para BOLECODE. |
| `payments/payment/create/PendingAdoption.java` | **novo.** CREATED → PENDING idempotente, para os dois métodos, mais os jobs e o outbox. |
| `payments/payment/create/ProviderFailures.java` | **novo.** Classifica `ProviderException` a partir do conjunto que o flow declara. |
| `payments/payment/PaymentService.java` | *modificado.* Ganha `create(command)`, perde os dois creates e `validatePayer`. |

**`gateway-app`**

| arquivo | responsabilidade |
|---|---|
| `api/payment/dto/CreatePaymentRequest.java` | *reescrito.* Interface selada com `@JsonTypeInfo`/`@JsonSubTypes`. |
| `api/payment/dto/PixPaymentRequest.java` `BolecodePaymentRequest.java` | **novos.** Um record por método, cada um com só os seus campos. |
| `api/payment/dto/RequestedAmount.java` | **novo.** `amount` + `currency` → `Money`, com as duas mensagens de hoje. |
| `api/payment/PaymentsController.java` | *modificado.* `validate()`, `toCommand()`, `payments.create()`. Sem ternário. |
| `api/support/ErrorHandler.java` | *modificado.* `HttpMessageNotReadableException` → 400 `INVALID_REQUEST`. |

---

### Task 1: `PaymentMethod` sobe para o kernel

`MethodProvider.method()` (Task 2) precisa do enum, e `kernelImportsNothing` proíbe o kernel de
importar `payments`. Move puro: nenhum valor persistido muda.

**Files:**
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/payment/PaymentMethod.java`
- Delete: `gateway-payments/src/main/java/com/gateway/payments/payment/PaymentMethod.java`
- Modify: todo arquivo que importa `com.gateway.payments.payment.PaymentMethod`
- Test: `gateway-app/src/test/java/com/gateway/app/architecture/ArchitectureTest.java` (só roda, não muda)

**Interfaces:**
- Produces: `com.gateway.kernel.payment.PaymentMethod` com os valores `PIX` e `BOLECODE`.

- [ ] **Step 1: Listar quem importa o enum hoje**

```bash
grep -rln "com\.gateway\.payments\.payment\.PaymentMethod" --include='*.java' gateway-*/src
```

Anote a lista: ela é o escopo exato deste task.

- [ ] **Step 2: Criar o enum no kernel, com o javadoc atual**

```java
package com.gateway.kernel.payment;

/**
 * How the payer pays. BOLECODE is one method with two settlement paths (QR or barcode); there is no
 * boleto without Pix (spec 2026-09-25).
 *
 * <p>In the kernel, not in payments: it is the vocabulary payments and providers share — a provider
 * declares which method it serves ({@code MethodProvider.method()}), and the kernel may not import
 * payments.
 */
public enum PaymentMethod {
  PIX,
  BOLECODE
}
```

- [ ] **Step 3: Apagar o antigo e reescrever os imports**

```bash
rm gateway-payments/src/main/java/com/gateway/payments/payment/PaymentMethod.java
FILES=$(grep -rl "com\.gateway\.payments\.payment\.PaymentMethod" --include='*.java' gateway-*/src)
sed -i 's/com\.gateway\.payments\.payment\.PaymentMethod/com.gateway.kernel.payment.PaymentMethod/g' $FILES
```

Cuidado: arquivos no pacote `com.gateway.payments.payment` usavam o enum **sem import**. Depois do
`sed`, compile e acrescente `import com.gateway.kernel.payment.PaymentMethod;` em cada um que o
compilador acusar.

- [ ] **Step 4: Compilar tudo**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -q test-compile`
Expected: EXIT 0. Qualquer `cannot find symbol: class PaymentMethod` é um import que falta no Step 3.

- [ ] **Step 5: Rodar o ArchUnit e os testes de unidade de payments**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-app test -Dtest=ArchitectureTest`
Expected: 11 regras passando. `kernelImportsNothing` é a que prova que o enum novo não trouxe nada consigo.

- [ ] **Step 6: Commit**

```bash
git add -A gateway-kernel gateway-payments gateway-app gateway-providers
git commit -m "refactor(kernel): payment method is shared vocabulary, not payments-only

MethodProvider.method() needs the enum and the kernel may not import
payments. Pure move: the persisted values do not change."
```

---

### Task 2: A interface única de provider no kernel, e os dois Itaú a implementam

**Files:**
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/provider/MethodProvider.java`
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/provider/pix/PixMethodProvider.java`
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/provider/pix/PixIssueRequest.java`
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/provider/boleto/BoletoMethodProvider.java`
- Delete: `gateway-kernel/.../provider/pix/PixProvider.java`, `.../provider/boleto/BoletoProvider.java`
- Modify: `gateway-providers/.../itau/pix/ItauPixProvider.java`, `.../itau/boleto/ItauBoletoProvider.java`
- Test: `gateway-providers/src/test/java/com/gateway/providers/itau/pix/ItauPixProviderTest.java`

**Interfaces:**
- Consumes: `PaymentMethod` (Task 1).
- Produces:
  - `MethodProvider<ISSUE, ISSUED, STATUS>`: `String id()`, `PaymentMethod method()`,
    `void requireIssueCredentials(ProviderCredentials)`, `ISSUED issue(ProviderCredentials, ISSUE)`,
    `Optional<STATUS> find(ProviderCredentials, String bankReference)`,
    `void cancel(ProviderCredentials, String bankReference)`.
  - `PixMethodProvider extends MethodProvider<PixIssueRequest, Charge, Charge>` + `requestRefund`,
    `findRefund`, `listCharges`, `parseWebhook` (assinaturas idênticas às de `PixProvider` hoje).
  - `BoletoMethodProvider extends MethodProvider<BoletoIssueRequest, IssuedBoleto, BoletoStatus>` +
    `String pixTxidFor(ProviderCredentials, String nossoNumero)`.
  - `PixIssueRequest(String txid, Money amount, int expiresInSeconds, String payerDocument, String payerName, String description)`.

- [ ] **Step 1: Escrever o teste que falha — Pix recusa credencial sem `pix_key` antes de qualquer HTTP**

Em `ItauPixProviderTest`:

```java
@Test
void requireIssueCredentialsRefusesACredentialWithoutAPixKey() {
  ProviderCredentials withoutPixKey = credentials("{\"client_id\":\"id\",\"client_secret\":\"secret\"}");

  assertThatThrownBy(() -> provider.requireIssueCredentials(withoutPixKey))
      .isInstanceOf(ProviderException.class)
      .satisfies(thrown -> {
        ProviderException failure = (ProviderException) thrown;
        assertThat(failure.code()).isEqualTo(ProviderException.Code.CREDENTIALS_INCOMPLETE);
        assertThat(failure.providerType()).isEqualTo("pix_key");
      });
}

@Test
void requireIssueCredentialsAcceptsACompleteCredential() {
  assertThatCode(() -> provider.requireIssueCredentials(sandboxCredentials())).doesNotThrowAnyException();
}
```

Use os helpers de credencial que o teste já tem para os outros casos; não invente novos.

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-providers test -Dtest=ItauPixProviderTest`
Expected: FAIL — `cannot find symbol: method requireIssueCredentials`.

- [ ] **Step 3: Criar a espinha no kernel**

```java
package com.gateway.kernel.provider;

import com.gateway.kernel.payment.PaymentMethod;
import java.util.Optional;

/**
 * What every bank product has in common: register the charge, ask the bank about it by the bank's
 * own reference, take it down. Implemented per bank and per method in {@code gateway-providers};
 * consumed by payments through {@code ProviderGateway}.
 *
 * <p>{@code bankReference} is the bank's handle on the charge — the txid for Pix (which is ours: it
 * is the payment id), the nosso número for a boleto. Deliberately not called {@code reference}:
 * {@code Payment.reference} is the merchant's order id, and one word for both would be read wrong
 * the first time.
 *
 * <p>Every method takes the credential because the bank's account data lives inside it, and only
 * the provider knows that credential's shape.
 */
public interface MethodProvider<ISSUE, ISSUED, STATUS> {
  String id();

  PaymentMethod method();

  /**
   * Fails with {@code CREDENTIALS_INCOMPLETE} ({@code providerType} = the missing field) before any
   * HTTP and before payments writes a row: a merchant with an incomplete credential has nothing to
   * clean up.
   */
  void requireIssueCredentials(ProviderCredentials credentials);

  ISSUED issue(ProviderCredentials credentials, ISSUE request);

  /** Empty when the bank does not know the reference (404, or an empty list). */
  Optional<STATUS> find(ProviderCredentials credentials, String bankReference);

  void cancel(ProviderCredentials credentials, String bankReference);
}
```

- [ ] **Step 4: Criar `PixIssueRequest` e as duas extensões**

```java
package com.gateway.kernel.provider.pix;

import com.gateway.kernel.money.Money;

/**
 * What a Pix charge needs at the bank. {@code txid} is ours — we PUT /cob/{payment id} — so a retry
 * after a timeout can ask the bank whether the charge exists.
 */
public record PixIssueRequest(
    String txid, Money amount, int expiresInSeconds, String payerDocument, String payerName, String description) {}
```

```java
package com.gateway.kernel.provider.pix;

import com.gateway.kernel.provider.MethodProvider;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderWebhookEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** The Pix side: the spine plus what only Pix has — refunds, listing and a webhook to parse. */
public interface PixMethodProvider extends MethodProvider<PixIssueRequest, Charge, Charge> {
  RefundResult requestRefund(ProviderCredentials credentials, RefundRequest request);

  Optional<RefundResult> findRefund(ProviderCredentials credentials, String endToEndId, String refundId);

  List<Charge> listCharges(ProviderCredentials credentials, Instant from, Instant to);

  /** Parsing a webhook needs no credential: the inbox must not fail because one was rotated. */
  ProviderWebhookEvent parseWebhook(byte[] body);
}
```

```java
package com.gateway.kernel.provider.boleto;

import com.gateway.kernel.provider.MethodProvider;
import com.gateway.kernel.provider.ProviderCredentials;

/** The boleto side: the spine plus the Pix txid the bank derives for the same charge. */
public interface BoletoMethodProvider extends MethodProvider<BoletoIssueRequest, IssuedBoleto, BoletoStatus> {
  /**
   * The Pix txid the bank derives for this boleto, so a charge adopted from the query can be
   * matched to Pix webhooks.
   */
  String pixTxidFor(ProviderCredentials credentials, String nossoNumero);
}
```

- [ ] **Step 5: Apagar as duas interfaces antigas e adaptar `ItauBoletoProvider`**

```bash
rm gateway-kernel/src/main/java/com/gateway/kernel/provider/pix/PixProvider.java
rm gateway-kernel/src/main/java/com/gateway/kernel/provider/boleto/BoletoProvider.java
```

`ItauBoletoProvider` já tem `requireIssueCredentials`, `issue`, `find`, `cancel` e `pixTxidFor` com
as assinaturas certas: troque `implements BoletoProvider` por `implements BoletoMethodProvider` e
acrescente

```java
  @Override public PaymentMethod method() { return PaymentMethod.BOLECODE; }
```

- [ ] **Step 6: Adaptar `ItauPixProvider`**

Renomeie três métodos e acrescente dois. O corpo de cada um não muda — só a assinatura:

```java
public class ItauPixProvider implements PixMethodProvider {

  @Override public String id() { return "ITAU"; }

  @Override public PaymentMethod method() { return PaymentMethod.PIX; }

  /**
   * Pix needs the key the charge is collected into. Today a credential without it only fails at the
   * first HTTP call, surfacing as PROVIDER_DECLINED; checked here it is
   * PROVIDER_CREDENTIALS_MISSING before any row exists, which is what a merchant can act on.
   */
  @Override
  public void requireIssueCredentials(ProviderCredentials credentials) {
    creds(credentials);
  }

  @Override
  public Charge issue(ProviderCredentials credentials, PixIssueRequest request) {
    // was createCharge(c, txid, amount, expiresInSeconds, payerDocument, payerName, description)
  }

  @Override
  public Optional<Charge> find(ProviderCredentials credentials, String txid) {
    // was findCharge
  }

  @Override
  public void cancel(ProviderCredentials credentials, String txid) {
    // was cancelCharge
  }
}
```

`creds(credentials)` já desserializa para `ItauCredentials`, cujo construtor canônico exige
`pix_key` — mas ele lança `IllegalArgumentException`, não `ProviderException`. Envolva:

```java
  private static ItauCredentials requirePixCredentials(ProviderCredentials credentials) {
    try {
      return creds(credentials);
    } catch (IllegalArgumentException e) {
      String field = e.getMessage() == null ? null : e.getMessage().replace("missing required field: ", "");
      throw new ProviderException(ProviderException.Code.CREDENTIALS_INCOMPLETE, 0, field, e.getMessage());
    }
  }
```

e chame-o de `requireIssueCredentials`. Não troque o `creds(...)` das outras operações: elas já
falham como falhavam e mudar isso é comportamento fora do escopo desta task.

- [ ] **Step 7: Rodar os testes dos dois providers**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-providers test`
Expected: tudo verde, incluindo os dois testes novos. Os testes de contrato WireMock não mudam: o
que foi para a rede é o mesmo.

`gateway-payments` e `gateway-app` **não** compilam ainda — eles ainda falam `PixProvider`. É a
Task 3. Não tente consertar aqui.

- [ ] **Step 8: Commit**

```bash
git add -A gateway-kernel gateway-providers
git commit -m "refactor(kernel): one provider interface over the spine, one extension per method

issue/find/cancel is what Pix and boleto genuinely share; refunds,
listing and webhook parsing are Pix's, the derived Pix txid is boleto's.
A fat interface with capability flags and UnsupportedOperationException
would move the discovery of a missing product from the compiler to
production.

Pix now answers requireIssueCredentials too, so a credential without a
pix_key fails as PROVIDER_CREDENTIALS_MISSING before a row exists
instead of as PROVIDER_DECLINED after the first HTTP call."
```

---

### Task 3: `ProviderGateway` com duas portas tipadas

**Files:**
- Modify: `gateway-payments/src/main/java/com/gateway/payments/provider/ProviderGateway.java`
- Modify: `gateway-payments/.../payment/PaymentService.java`, `.../payment/ExpirationService.java`,
  `.../payment/boleto/BoletoPollingService.java`, `.../refund/RefundService.java`,
  `.../reconciliation/ReconciliationService.java`, `.../inbox/WebhookInboxService.java`,
  `.../PaymentsConfiguration.java`
- Modify: `gateway-app/.../providers/ProviderWiring.java`, `gateway-providers/.../ProvidersConfiguration.java`
- Modify: `gateway-payments/src/test/java/com/gateway/payments/support/RecordingPixProvider.java`,
  `RecordingBoletoProvider.java`, `ServiceTestConfig.java`, `ServiceIntegrationTestBase.java`
- Test: `gateway-payments/src/test/java/com/gateway/payments/provider/ProviderGatewayTest.java` (novo)

**Interfaces:**
- Consumes: `MethodProvider`, `PixMethodProvider`, `BoletoMethodProvider` (Task 2).
- Produces:
  - `ProviderGateway.ResolvedProvider<P extends MethodProvider<?, ?, ?>>(P provider, ProviderCredentials credentials)`
  - `ResolvedProvider<PixMethodProvider> resolvePix(MerchantId, ProviderEnvironment, String providerId)`
  - `ResolvedProvider<BoletoMethodProvider> resolveBoleto(MerchantId, ProviderEnvironment, String providerId)`
  - `PixMethodProvider pixProvider(String providerId)`
  - `<P extends MethodProvider<?, ?, ?>, T> T call(String paymentId, String operation, ResolvedProvider<P> resolved, Function<ResolvedProvider<P>, T> fn)`

- [ ] **Step 1: Escrever o teste que falha**

```java
class ProviderGatewayTest {

  @Test
  void resolveBoletoAnswersMethodNotSupportedWhenTheProviderHasNoBoletoProduct() {
    ProviderGateway gateway = new ProviderGateway(List.of(pixOnly), List.of(), credentials, requests);

    assertThatThrownBy(() -> gateway.resolveBoleto(merchantId, ProviderEnvironment.TEST, "ITAU"))
        .isInstanceOf(DomainException.class)
        .hasMessage("ITAU has no boleto product")
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("METHOD_NOT_SUPPORTED");
  }

  @Test
  void resolvePixAnswersCredentialsMissingWhenTheMerchantHasNone() {
    ProviderGateway gateway = new ProviderGateway(List.of(pixOnly), List.of(), noCredentials, requests);

    assertThatThrownBy(() -> gateway.resolvePix(merchantId, ProviderEnvironment.LIVE, "ITAU"))
        .isInstanceOf(DomainException.class)
        .hasMessage("no ITAU LIVE credentials for this merchant")
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_CREDENTIALS_MISSING");
  }

  @Test
  void anUnknownProviderNameIsProviderUnknown() {
    ProviderGateway gateway = new ProviderGateway(List.of(pixOnly), List.of(), credentials, requests);

    assertThatThrownBy(() -> gateway.pixProvider("BRADESCO"))
        .isInstanceOf(DomainException.class)
        .hasMessage("no provider named BRADESCO");
  }
}
```

A mensagem `"ITAU has no boleto product"` é a que `PaymentService` monta hoje
(`PROVIDER + " has no boleto product"`): ela muda de lugar, não de texto.

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-payments test -Dtest=ProviderGatewayTest`
Expected: FAIL na compilação — `resolveBoleto` não existe.

- [ ] **Step 3: Reescrever `ProviderGateway`**

```java
public class ProviderGateway {
  private static final Logger log = LoggerFactory.getLogger(ProviderGateway.class);

  /** The operations that create a resource at the bank answer 201 (PUT /cob, PUT /devolucao). */
  private static final Set<String> CREATING = Set.of("createCharge", "requestRefund");

  public record ResolvedProvider<P extends MethodProvider<?, ?, ?>>(P provider, ProviderCredentials credentials) {}

  private final List<PixMethodProvider> pixProviders;
  private final List<BoletoMethodProvider> boletoProviders;
  private final CredentialLookup credentials;
  private final ProviderRequestRepository requests;

  public ProviderGateway(List<PixMethodProvider> pixProviders, List<BoletoMethodProvider> boletoProviders,
      CredentialLookup credentials, ProviderRequestRepository requests) {
    this.pixProviders = pixProviders;
    this.boletoProviders = boletoProviders;
    this.credentials = credentials;
    this.requests = requests;
  }

  public ResolvedProvider<PixMethodProvider> resolvePix(MerchantId merchantId, ProviderEnvironment environment, String providerId) {
    return new ResolvedProvider<>(pixProvider(providerId), credential(merchantId, environment, providerId));
  }

  /**
   * The boleto product is optional in the contract (no provider lacks it today). Answering
   * METHOD_NOT_SUPPORTED here means the three callers that used to unwrap an Optional and repeat
   * this decision no longer can get it wrong.
   */
  public ResolvedProvider<BoletoMethodProvider> resolveBoleto(MerchantId merchantId, ProviderEnvironment environment, String providerId) {
    BoletoMethodProvider provider =
        boletoProviders.stream()
            .filter(candidate -> candidate.id().equalsIgnoreCase(providerId))
            .findFirst()
            .orElseThrow(() -> new DomainException("METHOD_NOT_SUPPORTED", providerId + " has no boleto product"));

    return new ResolvedProvider<>(provider, credential(merchantId, environment, providerId));
  }

  /** Parsing a webhook needs no credential — the inbox must not fail because one was rotated. */
  public PixMethodProvider pixProvider(String providerId) {
    return pixProviders.stream()
        .filter(candidate -> candidate.id().equalsIgnoreCase(providerId))
        .findFirst()
        .orElseThrow(() -> new DomainException("PROVIDER_UNKNOWN", "no provider named " + providerId));
  }

  private ProviderCredentials credential(MerchantId merchantId, ProviderEnvironment environment, String providerId) {
    return credentials
        .find(merchantId, providerId, environment)
        .orElseThrow(() -> new DomainException(
            "PROVIDER_CREDENTIALS_MISSING", "no " + providerId + " " + environment + " credentials for this merchant"));
  }

  public <P extends MethodProvider<?, ?, ?>, T> T call(
      String paymentId, String operation, ResolvedProvider<P> resolved, Function<ResolvedProvider<P>, T> fn) {
    // corpo idêntico ao de hoje: medir, gravar, relançar
  }

  public <P extends MethodProvider<?, ?, ?>> void run(
      String paymentId, String operation, ResolvedProvider<P> resolved, Consumer<ResolvedProvider<P>> fn) {
    // idêntico ao de hoje
  }
}
```

`record(...)` usa `r.provider().id()`, que continua existindo em `MethodProvider`. Não mexa na
lógica de auditoria: falha ao gravar a linha continua não virando erro do merchant.

- [ ] **Step 4: Migrar os call sites, um por vez**

Substitua em cada arquivo. O padrão é sempre o mesmo — as duas linhas viram uma:

```java
// antes
ProviderGateway.Resolved r = providers.resolve(p.merchantId(), p.environment(), p.provider());
BoletoProvider boleto = r.boleto().orElseThrow();

// depois
ResolvedProvider<BoletoMethodProvider> resolved = providers.resolveBoleto(payment.merchantId(), payment.environment(), payment.provider());
BoletoMethodProvider boleto = resolved.provider();
```

Os seis lugares: `PaymentService` linhas ~214, ~322, ~421; `ExpirationService` ~81, ~160;
`BoletoPollingService` ~61. Em `PaymentService.createCharge` e em `RefundService`, troque
`resolve(...)` por `resolvePix(...)` e `x.provider().createCharge(...)` por
`target.provider().issue(target.credentials(), new PixIssueRequest(...))`.
Em `WebhookInboxService`, `providers.provider(...)` vira `providers.pixProvider(...)`.

Aproveite o arquivo aberto para renomear `r` → `resolved`, `p` → `payment`, `x` → `target`,
`nn` → `nossoNumero` **nas linhas que você já está tocando** — não no arquivo inteiro (isso é
Fase 2).

- [ ] **Step 5: Atualizar os dublês e o wiring**

`RecordingPixProvider implements PixMethodProvider`: renomeie `createCharge` → `issue` (lendo os
campos de `PixIssueRequest`), `findCharge` → `find`, `cancelCharge` → `cancel`, e acrescente
`method()` e `requireIssueCredentials` (corpo vazio: o dublê não tem credencial para checar).
`RecordingBoletoProvider implements BoletoMethodProvider` + `method()`.
`PaymentsConfiguration`, `ProvidersConfiguration`, `ProviderWiring` e `ServiceTestConfig` trocam os
tipos dos beans e das listas.

- [ ] **Step 6: Compilar e rodar payments inteiro**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -q test-compile`
Expected: EXIT 0.

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-payments test` (precisa de Docker)
Expected: verde. **Nenhum teste de comportamento mudou** — só assinaturas nos dublês. Se um teste de
fluxo falhou, a migração de um call site perdeu um ramo.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(payments): the gateway resolves per method, callers stop unwrapping an Optional

resolveBoleto answers METHOD_NOT_SUPPORTED itself, once, with the
message the three callers used to build each on their own. Six
r.boleto().orElseThrow() are gone."
```

---

### Task 4: Value objects que carregam o formato

**Files:**
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/errors/InvalidValue.java`
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/party/PersonName.java`, `Document.java`
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/address/Uf.java`, `ZipCode.java`
- Move: `kernel/provider/boleto/Payer.java` → `kernel/party/Payer.java`,
  `kernel/provider/boleto/Address.java` → `kernel/party/Address.java`
- Modify: `kernel/provider/boleto/BoletoIssueRequest.java` (import), `gateway-providers/.../boleto/dto/BoletoPixRequest.java`
- Test: `gateway-kernel/src/test/java/com/gateway/kernel/party/DocumentTest.java`,
  `PersonNameTest.java`, `gateway-kernel/src/test/java/com/gateway/kernel/address/UfTest.java`, `ZipCodeTest.java`

**Interfaces:**
- Produces:
  - `InvalidValue extends RuntimeException` com `String reason()`
  - `Document.of(String raw) → Document`, componente `String digits()`
  - `PersonName.of(String raw) → PersonName`, componente `String value()`
  - `Uf.of(String raw) → Uf`, componente `String value()`
  - `ZipCode.of(String raw) → ZipCode`, componente `String digits()`
  - `Payer(PersonName name, Document document, Address address)`
  - `Address(String street, String district, String city, Uf state, ZipCode zip)`

- [ ] **Step 1: Escrever os testes que falham**

```java
class DocumentTest {

  @Test
  void acceptsACpfAndKeepsOnlyItsDigits() {
    assertThat(Document.of("529.982.247-25").digits()).isEqualTo("52998224725");
  }

  @Test
  void acceptsACnpj() {
    assertThat(Document.of("11.222.333/0001-81").digits()).isEqualTo("11222333000181");
  }

  @Test
  void refusesAnythingThatIsNotElevenOrFourteenDigits() {
    for (String invalid : new String[] {null, "", "123", "1234567890", "123456789012", "abcdefghijk"}) {
      assertThatThrownBy(() -> Document.of(invalid))
          .isInstanceOf(InvalidValue.class)
          .extracting(thrown -> ((InvalidValue) thrown).reason())
          .isEqualTo("must be a CPF (11 digits) or CNPJ (14 digits)");
    }
  }
}

class UfTest {

  @Test
  void normalisesToUpperCase() {
    // "sp" is a valid UF typed in lowercase, not a wrong one; the bank's enum is uppercase, so it
    // is normalized, not refused.
    assertThat(Uf.of("sp").value()).isEqualTo("SP");
    assertThat(Uf.of(" rs ").value()).isEqualTo("RS");
  }

  @Test
  void refusesAnythingThatIsNotTwoLetters() {
    for (String invalid : new String[] {null, "", "S", "SPP", "S1"}) {
      assertThatThrownBy(() -> Uf.of(invalid))
          .isInstanceOf(InvalidValue.class)
          .extracting(thrown -> ((InvalidValue) thrown).reason())
          .isEqualTo("must be a two-letter UF");
    }
  }
}

class ZipCodeTest {

  @Test
  void keepsOnlyDigits() {
    assertThat(ZipCode.of("01310-100").digits()).isEqualTo("01310100");
  }

  @Test
  void refusesAnythingThatIsNotEightDigits() {
    for (String invalid : new String[] {null, "", "1310100", "013101000"}) {
      assertThatThrownBy(() -> ZipCode.of(invalid))
          .isInstanceOf(InvalidValue.class)
          .extracting(thrown -> ((InvalidValue) thrown).reason())
          .isEqualTo("must be 8 digits");
    }
  }
}

class PersonNameTest {

  @Test
  void acceptsANameWithAtLeastOneLetter() {
    assertThat(PersonName.of("Ana Índia").value()).isEqualTo("Ana Índia");
  }

  @Test
  void refusesANameWithNoLetter() {
    for (String invalid : new String[] {null, "", "   ", "123", "---"}) {
      assertThatThrownBy(() -> PersonName.of(invalid))
          .isInstanceOf(InvalidValue.class)
          .extracting(thrown -> ((InvalidValue) thrown).reason())
          .isEqualTo("is required");
    }
  }
}
```

Os textos de `reason()` são exatamente o sufixo das mensagens de hoje, porque a Task 5 as monta
como `caminho + " " + reason`.

- [ ] **Step 2: Rodar e confirmar que falham**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-kernel test -Dtest='DocumentTest,UfTest,ZipCodeTest,PersonNameTest'`
Expected: FAIL na compilação.

- [ ] **Step 3: Implementar `InvalidValue` e os quatro tipos**

```java
package com.gateway.kernel.errors;

/**
 * A value that does not have the shape its type requires. Carries only the value-level reason
 * ("must be 8 digits"): the type does not know it is called {@code customer.address.zip} in the
 * API. Whoever builds the aggregate composes the field path (see {@code PayerFactory}).
 */
public class InvalidValue extends RuntimeException {
  private final String reason;

  public InvalidValue(String reason) {
    super(reason);
    this.reason = reason;
  }

  public String reason() {
    return reason;
  }
}
```

```java
package com.gateway.kernel.party;

import com.gateway.kernel.errors.InvalidValue;
import java.util.regex.Pattern;

/** CPF (11 digits) or CNPJ (14 digits), punctuation stripped. */
public record Document(String digits) {
  private static final Pattern CPF_OR_CNPJ = Pattern.compile("\\d{11}|\\d{14}");

  public static Document of(String raw) {
    String digits = raw == null ? "" : raw.replaceAll("\\D", "");

    if (!CPF_OR_CNPJ.matcher(digits).matches()) {
      throw new InvalidValue("must be a CPF (11 digits) or CNPJ (14 digits)");
    }

    return new Document(digits);
  }
}
```

```java
package com.gateway.kernel.address;

import com.gateway.kernel.errors.InvalidValue;
import java.util.Locale;
import java.util.regex.Pattern;

/** The two-letter Brazilian state code, upper-cased. */
public record Uf(String value) {
  private static final Pattern TWO_LETTERS = Pattern.compile("[A-Z]{2}");

  public static Uf of(String raw) {
    // "sp" is a valid UF typed in lowercase, not a wrong one; the bank's enum is uppercase, so it
    // is normalized, not refused.
    String normalised = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);

    if (!TWO_LETTERS.matcher(normalised).matches()) {
      throw new InvalidValue("must be a two-letter UF");
    }

    return new Uf(normalised);
  }
}
```

`ZipCode` e `PersonName` seguem o mesmo molde, com `Pattern.compile("\\d{8}")` e
`Pattern.compile(".*\\p{L}.*")` e as razões dos testes.

- [ ] **Step 4: Mover `Payer` e `Address` para `kernel/party/` com componentes tipados**

```java
package com.gateway.kernel.party;

import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;

/**
 * Where the payer lives, as the bank's boleto layout needs it: every line required, the state a UF
 * and the zip eight digits. The types carry that, so nothing downstream has to check it again.
 */
public record Address(String street, String district, String city, Uf state, ZipCode zip) {}
```

```java
package com.gateway.kernel.party;

/**
 * Who pays. A registered boleto cannot be issued without one, and every component validates its own
 * shape, so a Payer that exists is a Payer the bank will accept.
 */
public record Payer(PersonName name, Document document, Address address) {}
```

```bash
rm gateway-kernel/src/main/java/com/gateway/kernel/provider/boleto/Payer.java
rm gateway-kernel/src/main/java/com/gateway/kernel/provider/boleto/Address.java
FILES=$(grep -rl "com\.gateway\.kernel\.provider\.boleto\.\(Payer\|Address\)" --include='*.java' gateway-*/src)
sed -i -E 's/com\.gateway\.kernel\.provider\.boleto\.(Payer|Address)/com.gateway.kernel.party.\1/g' $FILES
```

- [ ] **Step 5: Ajustar quem lia os campos como String**

`BoletoPixRequest` (em `gateway-providers`) monta o corpo do banco a partir do `Payer`. Onde ele
escrevia `payer.address().state()` agora escreve `payer.address().state().value()`; idem
`zip().digits()`, `name().value()`, `document().digits()`. O JSON que vai para o banco não muda —
é o mesmo texto, vindo de um tipo em vez de uma String. Os testes de schema
(`BoletoRequestSchemaValidationTest`, `BoletoFixturesFromOpenApiTest`) provam isso.

- [ ] **Step 6: Rodar kernel e providers**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-kernel,gateway-providers test`
Expected: verde, com os 4 testes novos. O schema do banco continua validando.

- [ ] **Step 7: Commit**

```bash
git add -A gateway-kernel gateway-providers
git commit -m "feat(kernel): the format is the type's job, not a validator's

Document, PersonName, Uf and ZipCode validate and normalise in their own
factory, so digits-only and upper-case UF happen in one place per concept
instead of being repeated inline. Payer and Address move to kernel/party:
with typed components they were being rewritten anyway, and they are not
boleto vocabulary -- they are the payer's."
```

---

### Task 5: `PayerFactory` — os 14 `if` viram 2 checagens e 7 construções

**Files:**
- Create: `gateway-payments/src/main/java/com/gateway/payments/payment/create/PayerData.java`
- Create: `gateway-payments/src/main/java/com/gateway/payments/payment/create/PayerFactory.java`
- Modify: `gateway-payments/.../payment/PaymentService.java` (remove `validatePayer` e os 4 `Pattern`)
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/create/PayerFactoryTest.java`

**Interfaces:**
- Consumes: `Payer`, `Address`, `PersonName`, `Document`, `Uf`, `ZipCode`, `InvalidValue` (Task 4).
- Produces:
  - `PayerData(String name, String document, PayerData.AddressData address)` e
    `PayerData.AddressData(String street, String district, String city, String state, String zip)`
  - `PayerFactory.from(PayerData data) → Payer` (estático)

- [ ] **Step 1: Escrever o teste que falha, campo por campo**

```java
class PayerFactoryTest {

  private static PayerData complete() {
    return new PayerData("Ana Silva", "529.982.247-25",
        new PayerData.AddressData("Av. Paulista 1000", "Bela Vista", "São Paulo", "sp", "01310-100"));
  }

  @Test
  void buildsANormalisedPayer() {
    Payer payer = PayerFactory.from(complete());

    assertThat(payer.name().value()).isEqualTo("Ana Silva");
    assertThat(payer.document().digits()).isEqualTo("52998224725");
    assertThat(payer.address().state().value()).isEqualTo("SP");
    assertThat(payer.address().zip().digits()).isEqualTo("01310100");
  }

  @Test
  void aMissingPayerNamesTheMethodItIsRequiredFor() {
    assertThatThrownBy(() -> PayerFactory.from(null))
        .isInstanceOf(DomainException.class)
        .hasMessage("customer is required for a BOLECODE payment")
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("CUSTOMER_REQUIRED");
  }

  /** Every message is contract: the field path is spelled the way the merchant sent it. */
  @ParameterizedTest
  @MethodSource("invalidFields")
  void eachInvalidFieldIsNamedInTheMessage(PayerData data, String expectedMessage) {
    assertThatThrownBy(() -> PayerFactory.from(data))
        .isInstanceOf(DomainException.class)
        .hasMessage(expectedMessage)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("CUSTOMER_REQUIRED");
  }

  static Stream<Arguments> invalidFields() {
    return Stream.of(
        arguments(withName(null), "customer.name is required"),
        arguments(withDocument("123"), "customer.document must be a CPF (11 digits) or CNPJ (14 digits)"),
        arguments(withAddress(null), "customer.address is required"),
        arguments(withStreet(" "), "customer.address.street is required"),
        arguments(withDistrict(null), "customer.address.district is required"),
        arguments(withCity(""), "customer.address.city is required"),
        arguments(withState("XYZ"), "customer.address.state must be a two-letter UF"),
        arguments(withZip("1310100"), "customer.address.zip must be 8 digits"));
  }

  // os oito helpers with*(...) copiam complete() trocando um campo
}
```

As oito mensagens são, letra por letra, as que `PaymentService.validatePayer` produz hoje. Confira
contra o arquivo antes de rodar: elas são o contrato que este task não pode mover.

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-payments test -Dtest=PayerFactoryTest`
Expected: FAIL na compilação.

- [ ] **Step 3: Implementar `PayerData` e `PayerFactory`**

```java
package com.gateway.payments.payment.create;

/**
 * The payer as the merchant sent it, before anything is checked. It exists because {@link
 * com.gateway.kernel.party.Payer} no longer accepts bad data: with typed components a malformed
 * Payer cannot be constructed, so the command cannot carry one. {@link PayerFactory} is the door
 * between the two.
 */
public record PayerData(String name, String document, AddressData address) {
  public record AddressData(String street, String district, String city, String state, String zip) {}
}
```

```java
package com.gateway.payments.payment.create;

/**
 * Builds the validated payer, naming the field in the API's own spelling. Checked in the domain and
 * not at the edge for two reasons: the 422 names the field, and no row exists yet.
 *
 * <p>A registered boleto needs a complete payer (issue OpenAPI: pessoa and endereco required, every
 * address line required).
 */
public final class PayerFactory {

  private PayerFactory() {}

  public static Payer from(PayerData data) {
    if (data == null) {
      throw new DomainException("CUSTOMER_REQUIRED", "customer is required for a BOLECODE payment");
    }
    if (data.address() == null) {
      throw new DomainException("CUSTOMER_REQUIRED", "customer.address is required");
    }

    PayerData.AddressData address = data.address();

    return new Payer(
        at("customer.name", () -> PersonName.of(data.name())),
        at("customer.document", () -> Document.of(data.document())),
        new Address(
            required("customer.address.street", address.street()),
            required("customer.address.district", address.district()),
            required("customer.address.city", address.city()),
            at("customer.address.state", () -> Uf.of(address.state())),
            at("customer.address.zip", () -> ZipCode.of(address.zip()))));
  }

  /** The value object knows the reason; only the caller knows the field's name in the API. */
  private static <T> T at(String field, Supplier<T> build) {
    try {
      return build.get();
    } catch (InvalidValue e) {
      throw new DomainException("CUSTOMER_REQUIRED", field + " " + e.reason());
    }
  }

  private static String required(String field, String value) {
    if (value == null || value.isBlank()) {
      throw new DomainException("CUSTOMER_REQUIRED", field + " is required");
    }
    return value;
  }
}
```

- [ ] **Step 4: Apagar `validatePayer` de `PaymentService`**

Remova o método `validatePayer` e os quatro `Pattern` estáticos (`DIGITS_11_OR_14`, `UF`, `CEP`,
`HAS_LETTER`) — eles agora vivem dentro dos value objects. Em `createBolecode`, `validatePayer(cmd.payer())`
vira `PayerFactory.from(command.payer())`. `PaymentServiceTest` / `BolecodeServiceIntegrationTest`
não mudam.

- [ ] **Step 5: Rodar payments**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-payments test`
Expected: verde, com `PayerFactoryTest` novo. **`BolecodeServiceIntegrationTest` tem que passar sem
uma linha alterada** — é ele que prova que as mensagens não mudaram.

- [ ] **Step 6: Commit**

```bash
git add -A gateway-payments
git commit -m "refactor(payments): a payer factory instead of fourteen ifs

The value objects carry the format; the factory carries the field path,
which is the only part the API's vocabulary owns. Codes and messages come
out identical, which is what BolecodeServiceIntegrationTest asserts
without a single change."
```

---

### Task 6: Comando selado, a strategy e os três colaboradores do tronco

**Files:**
- Create: `gateway-payments/.../payment/create/CreatePaymentCommand.java`, `CreatePixPayment.java`,
  `CreateBolecodePayment.java`, `PaymentFlow.java`, `PaymentFlows.java`, `ProviderFailures.java`
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/create/PaymentFlowsTest.java`,
  `ProviderFailuresTest.java`

**Interfaces:**
- Consumes: `PaymentMethod`, `PayerData`.
- Produces:
  - `CreatePaymentCommand` selado: `merchantId()`, `environment()`, `amount()`, `reference()`,
    `description()`, `method()`
  - `CreatePixPayment(MerchantId, ProviderEnvironment, Money, String reference, String description, Integer expiresInSeconds)`
  - `CreateBolecodePayment(MerchantId, ProviderEnvironment, Money, String reference, String description, PayerData payer, LocalDate dueDate, Integer paymentLimitDays)`
  - `PaymentFlow`: `PaymentMethod method()`, `Payment create(CreatePaymentCommand command)`
  - `PaymentFlows(List<PaymentFlow> flows)` com `PaymentFlow forMethod(PaymentMethod method)`
  - `ProviderFailures.classify(ProviderException failure, Set<ProviderException.Code> mayHaveLanded) → Outcome`
    com `Outcome { MAY_HAVE_LANDED, DECLINED, UNAVAILABLE }`

- [ ] **Step 1: Escrever os testes que falham**

```java
class PaymentFlowsTest {

  @Test
  void aMethodWithoutAFlowFailsAtConstruction() {
    assertThatThrownBy(() -> new PaymentFlows(List.of(flowFor(PaymentMethod.PIX))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("BOLECODE");
  }

  @Test
  void twoFlowsForTheSameMethodFailAtConstruction() {
    assertThatThrownBy(() -> new PaymentFlows(List.of(flowFor(PaymentMethod.PIX), flowFor(PaymentMethod.PIX), flowFor(PaymentMethod.BOLECODE))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PIX");
  }

  @Test
  void resolvesTheFlowOfTheCommandsMethod() {
    PaymentFlow pix = flowFor(PaymentMethod.PIX);
    PaymentFlows flows = new PaymentFlows(List.of(pix, flowFor(PaymentMethod.BOLECODE)));

    assertThat(flows.forMethod(PaymentMethod.PIX)).isSameAs(pix);
  }
}

class ProviderFailuresTest {

  @Test
  void aTimeoutMayHaveLandedForBothMethods() {
    assertThat(ProviderFailures.classify(failure(Code.TIMEOUT), Set.of(Code.TIMEOUT, Code.UNAVAILABLE)))
        .isEqualTo(ProviderFailures.Outcome.MAY_HAVE_LANDED);
  }

  /** CONFLICT is the bank saying the nosso numero already exists, so only boleto counts it. */
  @Test
  void conflictMayHaveLandedOnlyWhenTheFlowSaysSo() {
    assertThat(ProviderFailures.classify(failure(Code.CONFLICT), Set.of(Code.TIMEOUT, Code.UNAVAILABLE, Code.CONFLICT)))
        .isEqualTo(ProviderFailures.Outcome.MAY_HAVE_LANDED);
    assertThat(ProviderFailures.classify(failure(Code.CONFLICT), Set.of(Code.TIMEOUT, Code.UNAVAILABLE)))
        .isEqualTo(ProviderFailures.Outcome.UNAVAILABLE);
  }

  @Test
  void invalidAndDeclinedAreDeclined() {
    for (Code code : new Code[] {Code.INVALID, Code.DECLINED}) {
      assertThat(ProviderFailures.classify(failure(code), Set.of(Code.TIMEOUT))).isEqualTo(ProviderFailures.Outcome.DECLINED);
    }
  }
}
```

- [ ] **Step 2: Rodar e confirmar que falham**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-payments test -Dtest='PaymentFlowsTest,ProviderFailuresTest'`
Expected: FAIL na compilação.

- [ ] **Step 3: Implementar o comando selado**

```java
package com.gateway.payments.payment.create;

/**
 * What the edge asks for, already in the domain's vocabulary. Sealed because a new method must not
 * be addable without the compiler pointing at every place that has to learn about it.
 */
public sealed interface CreatePaymentCommand permits CreatePixPayment, CreateBolecodePayment {
  MerchantId merchantId();

  ProviderEnvironment environment();

  Money amount();

  String reference();

  String description();

  PaymentMethod method();
}
```

```java
public record CreatePixPayment(
    MerchantId merchantId, ProviderEnvironment environment, Money amount, String reference, String description,
    Integer expiresInSeconds) implements CreatePaymentCommand {

  @Override
  public PaymentMethod method() {
    return PaymentMethod.PIX;
  }
}
```

```java
public record CreateBolecodePayment(
    MerchantId merchantId, ProviderEnvironment environment, Money amount, String reference, String description,
    PayerData payer, LocalDate dueDate, Integer paymentLimitDays) implements CreatePaymentCommand {

  @Override
  public PaymentMethod method() {
    return PaymentMethod.BOLECODE;
  }
}
```

- [ ] **Step 4: Implementar `PaymentFlow`, `PaymentFlows` e `ProviderFailures`**

```java
public interface PaymentFlow {
  PaymentMethod method();

  Payment create(CreatePaymentCommand command);
}
```

```java
/**
 * One flow per method, indexed at construction. A method with no flow is a startup failure and not
 * a 500 in production: the map is complete or the context does not come up.
 */
public class PaymentFlows {
  private final Map<PaymentMethod, PaymentFlow> byMethod;

  public PaymentFlows(List<PaymentFlow> flows) {
    Map<PaymentMethod, PaymentFlow> indexed = new EnumMap<>(PaymentMethod.class);

    for (PaymentFlow flow : flows) {
      PaymentFlow previous = indexed.put(flow.method(), flow);
      if (previous != null) {
        throw new IllegalStateException("two payment flows for " + flow.method());
      }
    }

    for (PaymentMethod method : PaymentMethod.values()) {
      if (!indexed.containsKey(method)) {
        throw new IllegalStateException("no payment flow for " + method);
      }
    }

    this.byMethod = indexed;
  }

  public PaymentFlow forMethod(PaymentMethod method) {
    return byMethod.get(method);
  }
}
```

```java
/**
 * What a failed bank call means. Which codes may mean "it landed" is the flow's call, not this
 * class's: for Pix it is TIMEOUT and UNAVAILABLE; for a boleto CONFLICT joins them, because the
 * nosso numero is ours and was reserved before the call, so "already exists" can only be our own
 * earlier attempt that arrived (ruling R4).
 */
public final class ProviderFailures {

  public enum Outcome { MAY_HAVE_LANDED, DECLINED, UNAVAILABLE }

  private ProviderFailures() {}

  public static Outcome classify(ProviderException failure, Set<ProviderException.Code> mayHaveLanded) {
    if (mayHaveLanded.contains(failure.code())) {
      return Outcome.MAY_HAVE_LANDED;
    }

    boolean declined =
        failure.code() == ProviderException.Code.INVALID || failure.code() == ProviderException.Code.DECLINED;

    return declined ? Outcome.DECLINED : Outcome.UNAVAILABLE;
  }

  /** The merchant-facing code for a failure that may have landed but could not be confirmed. */
  public static String timeoutCodeOf(ProviderException failure) {
    return failure.code() == ProviderException.Code.TIMEOUT ? "PROVIDER_TIMEOUT" : "PROVIDER_UNAVAILABLE";
  }
}
```

- [ ] **Step 5: Rodar os dois testes**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-payments test -Dtest='PaymentFlowsTest,ProviderFailuresTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add -A gateway-payments
git commit -m "feat(payments): sealed create command, a flow per method, a registry checked at boot

Which failure codes may mean the charge landed is declared by each flow,
not by a boolean parameter: Pix counts TIMEOUT and UNAVAILABLE, a boleto
also counts CONFLICT, and as a constant in the flow you can read why."
```

---

### Task 7: `PaymentDraftFactory` e `PendingAdoption` — o tronco comum, nomeado

**Files:**
- Create: `gateway-payments/.../payment/create/PaymentDraftFactory.java`, `PendingAdoption.java`
- Modify: `gateway-payments/.../payment/PaymentService.java` (delega `adoptPending` e `adoptPendingBolecode`)
- Modify: `gateway-payments/.../payment/ExpirationService.java`, `.../payment/boleto/BoletoPollingService.java`
- Test: cobertos pelos testes de integração existentes; nenhum teste novo nesta task

**Interfaces:**
- Consumes: `CreatePixPayment`, `CreateBolecodePayment`, `Payer`.
- Produces:
  - `PaymentDraftFactory.pix(CreatePixPayment command, int expiresInSeconds) → Payment`
  - `PaymentDraftFactory.bolecode(CreateBolecodePayment command, Payer payer, LocalDate due, LocalDate limit) → Payment`
  - `PendingAdoption.adoptPix(String paymentId, Charge accepted, int fallbackExpires, EventSource by) → Payment`
  - `PendingAdoption.adoptBolecode(String paymentId, IssuedBoleto issued, EventSource by) → Payment`
  - `PendingAdoption.adoptBolecode(String paymentId, IssuedBoleto issued, EventSource by, String unconfirmedDetail) → Payment`

- [ ] **Step 1: Mover os corpos, sem alterar uma linha de lógica**

`PaymentDraftFactory` recebe `PaymentRepository`, `BoletoNumberRepository`, `TransactionTemplate` e
`Clock`. Os dois métodos são os blocos `tx.execute(...)` que hoje estão dentro de `createCharge` e
`createBolecode` — incluindo, no de bolecode, a reserva do nosso número **dentro da mesma
transação** do CREATED, que é invariante da spec do Bolecode §7.

`PendingAdoption` recebe `PaymentRepository`, `JobRepository`, `PaymentEvents`,
`PaymentsProperties`, `TransactionTemplate` e `Clock`. Os três métodos são `adoptPending` e as duas
sobrecargas de `adoptPendingBolecode`, movidos inteiros. Preserve cada comentário: o do txid
ecoado pelo sandbox, o da corrida entre sweeper e create lento, o do `PIX_TXID_UNCONFIRMED` só por
quem adotou.

São públicos, não privados de um flow, porque o sweeper (`ExpirationService`) e o polling
(`BoletoPollingService`) também adotam.

- [ ] **Step 2: `PaymentService` delega em vez de implementar**

```java
  Payment adoptPending(String paymentId, Charge accepted, int fallbackExpires, EventSource by) {
    return adoption.adoptPix(paymentId, accepted, fallbackExpires, by);
  }
```

Mantenha os dois métodos de `PaymentService` como delegação nesta task: assim nenhum call site
externo muda ainda e o diff fica legível. A Task 8 os remove junto com os creates.

- [ ] **Step 3: Compilar**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -q test-compile`
Expected: EXIT 0.

- [ ] **Step 4: Rodar a suíte de payments inteira**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-payments test`
Expected: verde, **sem nenhuma alteração em teste**. Este é um move puro; qualquer teste vermelho
significa que uma linha mudou de comportamento no caminho.

- [ ] **Step 5: Commit**

```bash
git add -A gateway-payments
git commit -m "refactor(payments): name the common trunk of a create

The CREATED write (with the nosso numero reserved in the same
transaction) and the idempotent CREATED -> PENDING adoption (with the
jobs and the outbox row) move out whole, comments included. Pure move:
not one test changed."
```

---

### Task 8: `PixPaymentFlow` e `BolecodePaymentFlow`

**Files:**
- Create: `gateway-payments/.../payment/create/PixPaymentFlow.java`, `BolecodePaymentFlow.java`
- Modify: `gateway-payments/.../payment/PaymentService.java` (ganha `create`, perde os dois creates)
- Modify: `gateway-payments/.../PaymentsConfiguration.java` (beans dos flows e de `PaymentFlows`)
- Test: `gateway-payments/src/test/java/com/gateway/payments/payment/PaymentServiceIntegrationTest.java`,
  `BolecodeServiceIntegrationTest.java` (só a chamada de criação muda)

**Interfaces:**
- Consumes: tudo das tasks 3, 5, 6 e 7.
- Produces: `PaymentService.create(CreatePaymentCommand command) → Payment`

- [ ] **Step 1: Escrever `PixPaymentFlow`**

```java
/**
 * Creating a Pix charge. The order is the invariant: the credential is resolved before any row
 * exists, the CREATED row is written before the bank is called, and the bank call runs outside any
 * transaction.
 */
public class PixPaymentFlow implements PaymentFlow {
  private static final Logger log = LoggerFactory.getLogger(PixPaymentFlow.class);

  /** A timeout, and equally a 503/504 from a gateway in front of the bank, says nothing about whether the charge was created. */
  private static final Set<ProviderException.Code> MAY_HAVE_LANDED =
      Set.of(ProviderException.Code.TIMEOUT, ProviderException.Code.UNAVAILABLE);

  @Override
  public PaymentMethod method() {
    return PaymentMethod.PIX;
  }

  @Override
  public Payment create(CreatePaymentCommand command) {
    CreatePixPayment pix = (CreatePixPayment) command;

    // Fails fast, before a row exists: a merchant with no credential has nothing to clean up.
    ResolvedProvider<PixMethodProvider> resolved = providers.resolvePix(pix.merchantId(), pix.environment(), PaymentService.PROVIDER);
    resolved.provider().requireIssueCredentials(resolved.credentials());

    int expires = pix.expiresInSeconds() == null ? properties.defaultExpiresInSeconds() : pix.expiresInSeconds();
    Payment payment = drafts.pix(pix, expires);

    Charge charge = issueOrRecover(payment, pix, expires, resolved);

    return adoption.adoptPix(payment.id(), charge, expires, EventSource.API);
  }

  private Charge issueOrRecover(Payment payment, CreatePixPayment pix, int expires, ResolvedProvider<PixMethodProvider> resolved) {
    PixIssueRequest request =
        new PixIssueRequest(payment.id(), pix.amount(), expires, pix.customerDocument(), null, pix.description());

    try {
      return providers.call(payment.id(), "createCharge", resolved, target -> target.provider().issue(target.credentials(), request));
    } catch (ProviderException failure) {
      return recover(payment, resolved, failure);
    }
  }

  /**
   * The PUT may have landed. The txid is ours, so we ask before deciding (spec section 3.2) instead
   * of failing a charge the payer may be looking at. Unlike a boleto, an empty answer decides: the
   * charge is failed on the spot.
   */
  private Charge recover(Payment payment, ResolvedProvider<PixMethodProvider> resolved, ProviderException failure) {
    if (ProviderFailures.classify(failure, MAY_HAVE_LANDED) != ProviderFailures.Outcome.MAY_HAVE_LANDED) {
      boolean declined = ProviderFailures.classify(failure, MAY_HAVE_LANDED) == ProviderFailures.Outcome.DECLINED;
      throw failures.fail(payment.id(), declined ? "PROVIDER_DECLINED" : "PROVIDER_UNAVAILABLE", failure, null);
    }

    String code = ProviderFailures.timeoutCodeOf(failure);

    Optional<Charge> existing;
    try {
      existing = providers.call(payment.id(), "findCharge", resolved, target -> target.provider().find(target.credentials(), payment.id()));
    } catch (ProviderException again) {
      throw failures.fail(payment.id(), code, again, resolved);
    }

    if (existing.isEmpty()) {
      throw failures.fail(payment.id(), code, failure, resolved);
    }

    return existing.get();
  }
}
```

`failures.fail(...)` é o `fail` privado de `PaymentService` de hoje (CREATED → FAILED com evento e
outbox). Mova-o para uma classe `CreateFailures` em `payment/create/` no mesmo estilo dos outros
colaboradores, e chame-a dos dois flows.

- [ ] **Step 2: Escrever `BolecodePaymentFlow`**

Mesmo esqueleto, com as três diferenças escritas:

```java
  /**
   * CONFLICT joins the list: the bank saying "this nosso numero already exists" (ruling R4) can only
   * be our own earlier attempt that landed, because the number is ours and was reserved before the
   * call. Failing it would leave a payable boleto behind a FAILED payment.
   */
  private static final Set<ProviderException.Code> MAY_HAVE_LANDED =
      Set.of(ProviderException.Code.TIMEOUT, ProviderException.Code.UNAVAILABLE, ProviderException.Code.CONFLICT);
```

e, quando a consulta volta vazia:

```java
    if (existing.isEmpty()) {
      // NOT failed on the spot, unlike Pix: the bank's 202 means "operacao em andamento", so an
      // empty query a second later proves nothing. ExpirationService.sweepStuckCreated asks again
      // after stuckCreatedAfter and decides.
      log.warn("boleto {} for payment {} not at the bank after {}; left CREATED for the sweeper", nossoNumero, payment.id(), failure.code());
      throw ProviderErrors.toDomain(code, failure, log, "issueBoleto", payment.id());
    }
```

O resto — `requireIssueCredentials`, `PayerFactory.from`, as datas (`due`, `limitDays`,
`INVALID_DUE_DATE`, `INVALID_PAYMENT_LIMIT`), `adoptBolecodeFromStatus` e o `catch` que traduz
`confirmBoletoTxid` — sai de `createBolecode` linha por linha, com os comentários.

- [ ] **Step 3: `PaymentService.create` e os beans**

```java
  public Payment create(CreatePaymentCommand command) {
    return flows.forMethod(command.method()).create(command);
  }
```

Remova `createCharge`, `createBolecode`, `CreateCharge`, `CreateBolecode`, `adoptPending`,
`adoptPendingBolecode`, `adoptBolecodeFromStatus` e `fail` de `PaymentService`, e aponte
`ExpirationService` e `BoletoPollingService` direto para `PendingAdoption`. Registre
`PixPaymentFlow`, `BolecodePaymentFlow` e `PaymentFlows` em `PaymentsConfiguration`.

- [ ] **Step 4: Adaptar as duas chamadas nos testes de integração**

Só a construção do comando muda:

```java
// antes
payments.createCharge(new PaymentService.CreateCharge(merchantId, TEST, Money.brl(1000), "order-1", "Pedido", "52998224725", 3600));
// depois
payments.create(new CreatePixPayment(merchantId, TEST, Money.brl(1000), "order-1", "Pedido", "52998224725", 3600));
```

Nenhuma assertion muda. Se uma mudou, o flow perdeu um ramo do original.

- [ ] **Step 5: Rodar a suíte inteira**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw verify` (precisa de Docker)
Expected: verde. Os cenários de timeout dos dois métodos são o que prova a invariante 3 da spec §8.

- [ ] **Step 6: Confirmar o tamanho de `PaymentService`**

Run: `wc -l gateway-payments/src/main/java/com/gateway/payments/payment/PaymentService.java`
Expected: em torno de 300 linhas, o limite do `CLAUDE.md`. Acima de 350, algo que devia ter saído ficou.

- [ ] **Step 7: Commit**

```bash
git add -A gateway-payments
git commit -m "feat(payments): a flow per method owns its create end to end

The trunk is shared by composition, not by a template method with a
context object: what differs between the two is exactly what a boolean
flag would have hidden. On a timeout Pix fails on the spot, because the
txid is ours and an empty query decides; a bolecode stays CREATED for the
sweeper, because the bank's 202 means the operation is still running.

PaymentService drops from 664 lines to the create dispatch plus the
lifecycle it still owns."
```

---

### Task 9: A borda — corpo polimórfico por método

**Files:**
- Rewrite: `gateway-app/.../api/payment/dto/CreatePaymentRequest.java`
- Create: `gateway-app/.../api/payment/dto/PixPaymentRequest.java`, `BolecodePaymentRequest.java`, `RequestedAmount.java`
- Modify: `gateway-app/.../api/payment/PaymentsController.java`, `.../api/support/ErrorHandler.java`
- Test: `gateway-app/src/test/java/com/gateway/app/api/payment/dto/CreatePaymentRequestTest.java` (novo),
  `PaymentsFlowIntegrationTest.java`, `BolecodeFlowIntegrationTest.java` (só o corpo enviado)

**Interfaces:**
- Consumes: `CreatePixPayment`, `CreateBolecodePayment`, `PayerData` (Task 6).
- Produces:
  - `CreatePaymentRequest` selado com `PaymentMethod method()`, `void validate()`,
    `CreatePaymentCommand toCommand(MerchantId merchantId, ProviderEnvironment environment)`
  - `RequestedAmount.of(Long amount, String currency) → Money`

- [ ] **Step 1: Escrever o teste que falha**

```java
class CreatePaymentRequestTest {
  private final JsonMapper mapper = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

  @Test
  void aPixBodyDeserialisesToThePixRequest() {
    CreatePaymentRequest request = mapper.readValue(
        "{\"method\":\"PIX\",\"amount\":1000,\"currency\":\"BRL\",\"expires_in\":3600}", CreatePaymentRequest.class);

    assertThat(request).isInstanceOf(PixPaymentRequest.class);
    assertThat(request.method()).isEqualTo(PaymentMethod.PIX);
  }

  @Test
  void aBolecodeFieldInAPixBodyIsRefused() {
    assertThatThrownBy(() -> mapper.readValue(
        "{\"method\":\"PIX\",\"amount\":1000,\"currency\":\"BRL\",\"due_date\":\"2026-10-01\"}", CreatePaymentRequest.class))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void aPixFieldInABolecodeBodyIsRefused() {
    assertThatThrownBy(() -> mapper.readValue(
        "{\"method\":\"BOLECODE\",\"amount\":1000,\"currency\":\"BRL\",\"expires_in\":3600}", CreatePaymentRequest.class))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void anUnknownMethodIsRefused() {
    assertThatThrownBy(() -> mapper.readValue(
        "{\"method\":\"CARTAO\",\"amount\":1000,\"currency\":\"BRL\"}", CreatePaymentRequest.class))
        .isInstanceOf(JacksonException.class);
  }

  @Test
  void amountAndCurrencyKeepTodaysMessages() {
    PixPaymentRequest zero = new PixPaymentRequest(0L, "BRL", null, null, null);
    assertThatThrownBy(zero::validate).hasMessage("amount must be a positive number of cents");

    PixPaymentRequest usd = new PixPaymentRequest(1000L, "USD", null, null, null);
    assertThatThrownBy(usd::validate).hasMessage("currency must be BRL");

    PixPaymentRequest negativeExpiry = new PixPaymentRequest(1000L, "BRL", null, null, -1);
    assertThatThrownBy(negativeExpiry::validate).hasMessage("expires_in must be positive seconds");
  }

  @Test
  void paymentLimitDaysMustNotBeNegative() {
    BolecodePaymentRequest request = new BolecodePaymentRequest(1000L, "BRL", null, null, null, null, -1);
    assertThatThrownBy(request::validate).hasMessage("payment_limit_days must not be negative");
  }
}
```

- [ ] **Step 2: Rodar e confirmar que falha**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw -o -pl gateway-app test -Dtest=CreatePaymentRequestTest`
Expected: FAIL na compilação.

- [ ] **Step 3: Implementar a interface selada e os dois records**

```java
/**
 * The shape of a create, chosen by {@code method}: PIX takes {@code expires_in}; BOLECODE takes a
 * complete {@code customer}, {@code due_date} and {@code payment_limit_days}. Each subtype declares
 * only its own fields, so a field of the other method is refused by the deserialiser instead of by
 * a chain of string comparisons.
 *
 * <p>The environment is never a field here: it is the API key's.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = "method", visible = true)
@JsonSubTypes({
  @JsonSubTypes.Type(value = PixPaymentRequest.class, name = "PIX"),
  @JsonSubTypes.Type(value = BolecodePaymentRequest.class, name = "BOLECODE")
})
public sealed interface CreatePaymentRequest permits PixPaymentRequest, BolecodePaymentRequest {

  PaymentMethod method();

  void validate();

  CreatePaymentCommand toCommand(MerchantId merchantId, ProviderEnvironment environment);
}
```

```java
/**
 * {@code amount} is integer cents and boxed: a missing amount must be a 400, not a silent charge of
 * zero. Unknown properties are refused, not ignored: a BOLECODE field here has to be an error, and
 * Spring Boot's default would swallow it.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record PixPaymentRequest(Long amount, String currency, String reference, String description, Integer expiresIn)
    implements CreatePaymentRequest {

  @Override
  public PaymentMethod method() {
    return PaymentMethod.PIX;
  }

  @Override
  public void validate() {
    RequestedAmount.of(amount, currency);

    if (expiresIn != null && expiresIn <= 0) {
      throw new IllegalArgumentException("expires_in must be positive seconds");
    }
  }

  @Override
  public CreatePaymentCommand toCommand(MerchantId merchantId, ProviderEnvironment environment) {
    return new CreatePixPayment(
        merchantId, environment, RequestedAmount.of(amount, currency), reference, description, expiresIn);
  }
}
```

`BolecodePaymentRequest` segue o mesmo molde, com `Customer customer`, `LocalDate dueDate`,
`Integer paymentLimitDays`, o `validate()` que checa `payment_limit_days` não negativo, e um
`toCommand` que copia `customer` para `PayerData` **sem validar** — a validação é do domínio.
Mantenha os records aninhados `Customer` e `Address` com os mesmos nomes de campo de hoje.

```java
/** amount + currency, with the two messages the API has always answered. */
final class RequestedAmount {

  private RequestedAmount() {}

  static Money of(Long amount, String currency) {
    // Money accepts zero and the API does not; Money accepts any three-letter currency and the
    // gateway only settles BRL.
    if (amount == null || amount <= 0) {
      throw new IllegalArgumentException("amount must be a positive number of cents");
    }
    if (!"BRL".equals(currency)) {
      throw new IllegalArgumentException("currency must be BRL");
    }

    return new Money(amount, currency);
  }
}
```

- [ ] **Step 4: O controller**

```java
  @PostMapping
  public ResponseEntity<PaymentResponse> create(@RequestBody CreatePaymentRequest request) {
    request.validate();

    MerchantContext.Current caller = MerchantContext.current();
    CreatePaymentCommand command = request.toCommand(caller.merchantId(), providerEnvironment(caller.environment()));

    return withResource(HttpStatus.CREATED, payments.create(command));
  }
```

- [ ] **Step 5: `ErrorHandler` mantém a mensagem de método inválido**

```java
  /**
   * A body Jackson could not read. The type id is the one case with a message of its own, because
   * "method must be PIX or BOLECODE" is what this API has always answered and an error message is
   * contract. Everything else gets a fixed detail: Jackson's text carries class names and a slice of
   * the body, which are ours to read in the log and not the merchant's.
   */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail unreadableBody(HttpMessageNotReadableException e) {
    if (e.getCause() instanceof InvalidTypeIdException) {
      return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "method must be PIX or BOLECODE");
    }

    log.info("unreadable request body: {}", Masker.mask(e.getMessage()));
    return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "the request body could not be read");
  }
```

Confirme o pacote de `InvalidTypeIdException` neste projeto: o repo usa Jackson 3
(`tools.jackson.*`), então é `tools.jackson.databind.exc.InvalidTypeIdException` e não o
`com.fasterxml.*` dos exemplos da internet. Se a classe não existir com esse nome, rode o teste do
Step 1 com um `printStackTrace` e use o tipo real.

- [ ] **Step 6: Atualizar o corpo enviado nos dois testes de fluxo**

Em `PaymentsFlowIntegrationTest` e `BolecodeFlowIntegrationTest`, remova do corpo os campos do outro
método (eram tolerados, agora são 400). Acrescente dois testes: PIX com `due_date` → 400, BOLECODE
com `expires_in` → 400, e `method` inválido → 400 com `"method must be PIX or BOLECODE"`.

- [ ] **Step 7: Rodar a suíte inteira**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw verify`
Expected: verde. `PaymentJsonContractTest` passa **sem alteração**: ele assegura a resposta, que não
foi tocada.

- [ ] **Step 8: Commit**

```bash
git add -A gateway-app
git commit -m "feat(app): a request body per payment method

The sealed hierarchy is the dispatch: each record declares only its own
fields and validates only its own rules, so the seven ifs comparing
method strings are gone and a field of the other method is refused by the
deserialiser. Unknown properties are refused explicitly, because Spring
Boot's default would have swallowed the very mistake this replaces.

Breaking: a PIX body carrying due_date, or a BOLECODE body carrying
expires_in, is now 400 instead of 422 with an explanatory message. An
unknown method keeps its message, mapped in ErrorHandler."
```

---

### Task 10: Documentação, decisões e a suíte inteira

**Files:**
- Modify: `README.md` (a seção de criação de pagamento e a de Bolecode), `docs/superpowers/DECISOES.md`
- Modify: `docs/architecture.md` se algum diagrama citar `PixProvider`/`BoletoProvider`
- Test: a suíte inteira

- [ ] **Step 1: Atualizar o README**

Os exemplos de `curl` do `POST /v1/payments` passam a mostrar um corpo por método, e uma nota diz
que campo do outro método é 400. Procure o que ficou desatualizado:

```bash
grep -n "expires_in\|due_date\|payment_limit_days\|PixProvider\|BoletoProvider" README.md docs/architecture.md
```

- [ ] **Step 2: Acrescentar as cinco decisões ao `DECISOES.md`**

Append-only, no fim do arquivo, cada uma com decisão, alternativa rejeitada e custo de estar errada.
As cinco estão escritas na §10 da spec; copie-as com a data de hoje.

- [ ] **Step 3: Rodar tudo, do zero**

Run: `JAVA_HOME=~/.jdks/corretto-25.0.4.1 ./mvnw clean verify`
Expected: verde, 0 falhas, e o `ArchitectureTest` com as 11 regras — em especial
`importSeesTheModules`, cujo piso de contagem de classes continua válido (o refactor acrescenta
classes, não remove módulos).

- [ ] **Step 4: Conferir o padrão no que foi escrito**

```bash
git diff --stat main..HEAD
grep -rnE '\b[A-Z][A-Za-z0-9_.<>]*\s+(p|r|c|a|x|b|nn|cmd)\s*[=;)]' --include='*.java' \
  gateway-payments/src/main/java/com/gateway/payments/payment/create \
  gateway-app/src/main/java/com/gateway/app/api/payment
```

Expected: nenhum resultado. O código novo nasce no padrão da Fase 0; se apareceu um `Payment p`,
corrija antes de fechar.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "docs: the request body per method in the README and five decisions

Records why one provider interface over the spine, why PaymentMethod
moved to the kernel, why the contract break was accepted, why each flow
owns its create, and why the format is the type's job."
```

---

## Self-Review

**Cobertura da spec**, seção por seção:

| spec | task |
|---|---|
| §3.1 `MethodProvider` + extensões + `PixIssueRequest` | 2 |
| §3.2 `PaymentMethod` no kernel | 1 |
| §3.3 `ResolvedProvider`, `resolvePix`, `resolveBoleto`, `pixProvider` | 3 |
| §3.4 quem acompanha + `requireIssueCredentials` de Pix | 2 (providers), 3 (payments/app) |
| §4.1 corpo selado e polimórfico | 9 |
| §4.2 `validate()` por método, `RequestedAmount` | 9 |
| §4.3 método desconhecido no `ErrorHandler` | 9 |
| §4.4 o controller | 9 |
| §5.1 comando selado + `PayerData` | 6 (comando), 5 (`PayerData`) |
| §5.2 flows + colaboradores | 7 (colaboradores), 8 (flows) |
| §5.3 `PaymentFlows` checado no boot | 6 |
| §5.4 `PaymentService` enxuto | 8 |
| §6.1 value objects, `Payer`/`Address` em `kernel/party` | 4 |
| §6.2 `PayerFactory` | 5 |
| §7 mudança de contrato HTTP | 9 (código), 10 (README) |
| §8 as dez invariantes | verificadas pelos testes de integração existentes nas tasks 3, 7, 8 |
| §9 ordem dos testes | é a ordem das tasks 1→10 |
| §10 decisões | 10 |

**Consistência de tipos:** `ResolvedProvider<P>` (Task 3) é o tipo que as tasks 7 e 8 consomem;
`PayerData` é criado na Task 5 e consumido na 6 (comando) e na 9 (borda); `ProviderFailures.Outcome`
é definido na 6 e usado na 8; `PayerFactory.from` é definido na 5 e chamado na 8;
`RequestedAmount.of` é definido e usado na 9. `CreateFailures` (o `fail` de hoje) aparece na Task 8
Step 1 — está nomeado ali porque é onde os dois flows passam a precisar dele.

**Ordem:** cada task fecha com o módulo que ela toca verde. A única janela em que o repo não compila
inteiro é entre as tasks 2 e 3, e a Task 2 Step 7 avisa disso explicitamente.
