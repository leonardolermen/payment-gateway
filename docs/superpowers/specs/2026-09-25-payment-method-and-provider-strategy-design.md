# Strategy por método de pagamento e contrato único de provider — design

Data: 2026-09-25. Fase 1 do refactor decidido nesta sessão (Fase 0 = `CLAUDE.md` + `.editorconfig`,
commit `2de5600`; Fase 2 = varredura de nomes e espaçamento no resto do repo).

Decisões do usuário nesta sessão:

- Providers: **unificar numa interface só**, não manter `PixProvider` + `BoletoProvider` separadas.
- Request body: **polimórfico por `method`**, aceitando quebra do contrato atual.
- Escopo: **borda e domínio**, não só a borda.
- Pastas: feature-first, sem pasta com nome de papel técnico (já aplicado em `gateway-app/api`,
  commit `abf7f42`).

## 1. O problema, medido

`PaymentsController.create` decide o método com um ternário entre dois serviços diferentes:

```java
Payment payment = request.isBolecode()
    ? payments.createBolecode(new PaymentService.CreateBolecode(...8 argumentos...))
    : payments.createCharge(new PaymentService.CreateCharge(...7 argumentos...));
```

`CreatePaymentRequest` é um record de 9 componentes onde 3 são de um método e 1 do outro, e
`validate()` decide por comparação de string qual combinação é ilegal — 7 `if`, quatro deles
perguntando `"PIX".equals(method)` ou `"BOLECODE".equals(method)`.

`PaymentService` tem 664 linhas. `createCharge` e `createBolecode` são o mesmo esqueleto duas
vezes: resolve credencial → grava CREATED → chama o banco fora de transação → no timeout consulta
→ adota PENDING. As diferenças são reais (quais códigos de erro podem significar "chegou", o que
fazer quando a consulta vem vazia, quais jobs enfileirar), mas estão afogadas na repetição.

`PaymentService.validatePayer` são 14 `if` e dois `replaceAll("\\D","")` inline.

`ProviderGateway.Resolved` carrega `Optional<BoletoProvider>`, e `r.boleto().orElseThrow()` aparece
em 6 lugares — `PaymentService` (3), `ExpirationService` (2), `BoletoPollingService` (1). Cada um
repete a decisão de o que fazer quando o provider não tem produto de boleto.

## 2. Escopo

Dentro: o caminho de criação de pagamento, de ponta a ponta — contrato de provider no kernel,
desserialização e validação na borda, fluxo por método no domínio, validação do pagador.

Fora: cancelamento, liquidação, webhook, polling, reconciliação e refund continuam onde estão.
Eles só mudam no mínimo necessário para acompanhar a assinatura nova do provider (§3.4).

Fora também: escolha de provider. `PaymentService.PROVIDER = "ITAU"` continua constante; roteamento
por merchant é outro assunto e outra spec.

## 3. Contrato de provider (`gateway-kernel`)

### 3.1 O que é igual entre os dois métodos, e só isso

As duas interfaces de hoje têm a mesma espinha — emitir no banco, consultar pela referência do
banco, baixar — e diferem no resto: Pix tem devolução, listagem e webhook; boleto tem o txid Pix
derivado. A interface única cobre a espinha; o resto fica em extensão estreita de cada método.

```java
// kernel/provider/MethodProvider.java
public interface MethodProvider<ISSUE, ISSUED, STATUS> {
  String id();

  PaymentMethod method();

  /** Falha com CREDENTIALS_INCOMPLETE (providerType = o campo) antes de qualquer HTTP. */
  void requireIssueCredentials(ProviderCredentials credentials);

  ISSUED issue(ProviderCredentials credentials, ISSUE request);

  /** Vazio quando o banco não conhece a referência (404 ou lista vazia). */
  Optional<STATUS> find(ProviderCredentials credentials, String bankReference);

  void cancel(ProviderCredentials credentials, String bankReference);
}
```

`bankReference`, não `reference`: `Payment.reference` é o id do pedido do merchant, e usar a mesma
palavra para o txid/nosso número seria trocar os dois na primeira leitura. Para Pix a referência do
banco é o txid (que é nosso, o id do payment); para boleto é o nosso número.

```java
// kernel/provider/pix/PixMethodProvider.java
public interface PixMethodProvider extends MethodProvider<PixIssueRequest, Charge, Charge> {
  RefundResult requestRefund(ProviderCredentials credentials, RefundRequest request);
  Optional<RefundResult> findRefund(ProviderCredentials credentials, String endToEndId, String refundId);
  List<Charge> listCharges(ProviderCredentials credentials, Instant from, Instant to);
  ProviderWebhookEvent parseWebhook(byte[] body);
}

// kernel/provider/boleto/BoletoMethodProvider.java
public interface BoletoMethodProvider extends MethodProvider<BoletoIssueRequest, IssuedBoleto, BoletoStatus> {
  /** O txid Pix que o banco deriva para este boleto. */
  String pixTxidFor(ProviderCredentials credentials, String nossoNumero);
}
```

`PixProvider.createCharge` hoje recebe 7 argumentos soltos. Passa a receber um record, que é
também o que a regra de factory pede:

```java
// kernel/provider/pix/PixIssueRequest.java
public record PixIssueRequest(
    String txid, Money amount, int expiresInSeconds, String payerDocument, String payerName, String description) {}
```

`BoletoIssueRequest` já existe e não muda.

### 3.2 `PaymentMethod` sobe para o kernel

`MethodProvider.method()` precisa do enum, e o kernel não pode importar `payments` (ArchUnit
`kernelImportsNothing`). `PaymentMethod` sai de `com.gateway.payments.payment` para
`com.gateway.kernel.payment` — é vocabulário compartilhado entre payments e providers, que é
exatamente o que o kernel existe para guardar. Os valores persistidos (`PIX`, `BOLECODE`) não
mudam, então não há migração.

Rejeitado: um segundo enum no kernel (`ProviderProduct`) traduzido de e para `PaymentMethod`. Uma
tabela de tradução entre dois enums com os mesmos dois valores é custo sem retorno.

### 3.3 `ProviderGateway`: duas portas tipadas, zero `Optional` circulando

```java
public record ResolvedProvider<P extends MethodProvider<?, ?, ?>>(P provider, ProviderCredentials credentials) {}

public ResolvedProvider<PixMethodProvider> resolvePix(MerchantId merchantId, ProviderEnvironment environment, String providerId);

public ResolvedProvider<BoletoMethodProvider> resolveBoleto(MerchantId merchantId, ProviderEnvironment environment, String providerId);
```

`resolveBoleto` lança `METHOD_NOT_SUPPORTED` ela mesma, uma vez, com a mensagem que hoje está
repetida em três lugares. Os 6 `r.boleto().orElseThrow()` desaparecem.

`call(paymentId, operation, resolved, fn)` passa a ser genérico em `P`; o resto do gateway (medir,
gravar `provider_requests`, truncar, não deixar falha de auditoria virar erro do merchant) não muda.

`provider(String)` — a porta sem credencial que `WebhookInboxService` usa para `parseWebhook`, e que
não pede credencial justamente para que a inbox não falhe porque uma credencial foi rotacionada —
passa a se chamar `pixProvider(String providerId)` e a devolver `PixMethodProvider`. O nome novo diz
qual produto ela resolve, que é o que `provider` sozinho deixou de dizer quando passaram a existir
dois.

Rejeitado: uma interface gorda com `Set<PaymentMethod> capabilities()` e métodos que lançam
`UnsupportedOperationException`. Unificação de fachada: o compilador deixa passar e você descobre
em produção. Rejeitado também: records neutros (`IssueRequest`/`IssueResult`) que Pix e boleto
preenchem pela metade — é o mesmo DTO misturado que esta spec está tirando do request body, um
andar abaixo.

### 3.4 Quem acompanha

`ItauPixProvider` e `ItauBoletoProvider` passam a implementar as interfaces novas (mudança de
assinatura, nenhuma de comportamento). `ProvidersConfiguration`, `PaymentsConfiguration`,
`ProviderWiring` trocam os tipos dos beans. `ExpirationService`, `BoletoPollingService`,
`RefundService`, `ReconciliationService`, `WebhookInboxService` trocam `resolve(...)` +
`r.boleto().orElseThrow()` por `resolveBoleto(...)` / `resolvePix(...)`. Os dublês de teste
`RecordingPixProvider` e `RecordingBoletoProvider` acompanham.

**Uma mudança de comportamento, deliberada:** `requireIssueCredentials` passa a existir para Pix
também, verificando que a credencial tem `pix_key`. Hoje uma credencial Pix sem `pix_key` só falha
no HTTP, virando `PROVIDER_DECLINED` ou `PROVIDER_UNAVAILABLE`; passa a falhar antes de gravar
linha, com `PROVIDER_CREDENTIALS_MISSING`, que é a resposta que o merchant pode agir sobre. Tem
teste próprio. Se não for desejada, a alternativa é um corpo vazio para Pix — mas aí a interface
tem um método que só metade dos implementadores honra.

## 4. Borda (`gateway-app/api/payment`)

### 4.1 Body polimórfico, selado

```java
@JsonTypeInfo(use = Id.NAME, include = As.EXISTING_PROPERTY, property = "method", visible = true)
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
@JsonIgnoreProperties(ignoreUnknown = false)
public record PixPaymentRequest(
    Long amount, String currency, String reference, String description, Integer expiresIn) implements CreatePaymentRequest { ... }

@JsonIgnoreProperties(ignoreUnknown = false)
public record BolecodePaymentRequest(
    Long amount, String currency, String reference, String description, Customer customer, LocalDate dueDate, Integer paymentLimitDays)
    implements CreatePaymentRequest { ... }
```

Os quatro componentes comuns são repetidos nos dois records: record não herda componente, e a
alternativa (`@JsonUnwrapped` de um record comum) troca repetição explícita por indireção que só
o Jackson entende.

`toCommand` é polimórfico em cada record — sem `switch`, sem registry, sem ternário. A dispatch por
método na borda é a própria hierarquia selada.

`@JsonIgnoreProperties(ignoreUnknown = false)` é **necessário**: o padrão do Spring Boot é ignorar
campo desconhecido, e sem isso um `expires_in` num corpo BOLECODE seria silenciosamente descartado
em vez de recusado — perdendo a garantia que o `validate()` de hoje dá. Com ele, campo do outro
método é 400 vindo do próprio Jackson.

### 4.2 `validate()` por método, só sobre o que é seu

`PixPaymentRequest.validate()`: `amount` positivo, `currency` BRL, `expires_in` positivo se
presente. Três checagens, nenhuma perguntando qual é o método.

`BolecodePaymentRequest.validate()`: `amount`, `currency`, `payment_limit_days` não negativo. O
pagador não é validado aqui — continua no domínio (§6), onde a mensagem nomeia o campo e nenhuma
linha foi gravada ainda.

`amount` e `currency` são a mesma regra nos dois. Em vez de repetir, os dois chamam
`RequestedAmount.of(amount, currency)` (em `api/payment/dto`), que devolve `Money` e é o único lugar
com as duas mensagens de hoje: `"amount must be a positive number of cents"` (porque `Money` aceita
zero e a API não) e `"currency must be BRL"` (porque `Money` aceita qualquer moeda de três letras e
o gateway só opera BRL).

### 4.3 Método desconhecido continua respondendo o que responde hoje

Com `@JsonSubTypes`, `method: "CARTAO"` vira `InvalidTypeIdException` do Jackson, não o nosso
`IllegalArgumentException("method must be PIX or BOLECODE")`. Como código e mensagem de erro são
contrato, `ErrorHandler` ganha um handler para `HttpMessageNotReadableException` que reconhece
tipo inválido no discriminador e responde 400 `INVALID_REQUEST` com a mensagem de hoje — e, para
qualquer outro erro de desserialização, 400 `INVALID_REQUEST` com detalhe fixo (o texto do Jackson
carrega nome de classe e trecho do corpo, que não são do merchant).

Isso vale de graça para o resto da API: hoje um JSON malformado em qualquer endpoint cai no handler
genérico do Spring, sem `urn:gateway:` no `type`.

### 4.4 O controller

```java
@PostMapping
public ResponseEntity<PaymentResponse> create(@RequestBody CreatePaymentRequest request) {
  request.validate();

  MerchantContext.Current caller = MerchantContext.current();
  CreatePaymentCommand command = request.toCommand(caller.merchantId(), providerEnvironment(caller.environment()));

  return withResource(HttpStatus.CREATED, payments.create(command));
}
```

O ambiente continua vindo da API key e nunca do corpo.

## 5. Domínio (`gateway-payments/payment/create`)

### 5.1 Comando selado, um por método

```java
public sealed interface CreatePaymentCommand permits CreatePixPayment, CreateBolecodePayment {
  MerchantId merchantId();
  ProviderEnvironment environment();
  Money amount();
  String reference();
  String description();
  PaymentMethod method();
}
```

`CreatePixPayment` acrescenta `expiresInSeconds`; `CreateBolecodePayment` acrescenta
`payer` (do tipo `PayerData`), `dueDate`, `paymentLimitDays`. Substituem
`PaymentService.CreateCharge` e `PaymentService.CreateBolecode`.

`PayerData` é o pagador **antes** de ser validado — strings cruas como chegaram do merchant:

```java
// payments/payment/create/PayerData.java
public record PayerData(String name, String document, AddressData address) {
  public record AddressData(String street, String district, String city, String state, String zip) {}
}
```

Ele existe porque `Payer` deixa de aceitar dado inválido: com componentes tipados (§6.1), um
`Payer` mal formado não pode ser construído, então o comando não pode carregar um. Quem traduz
`PayerData` em `Payer` é a factory do §6.2, dentro do domínio. A borda só copia
`BolecodePaymentRequest.Customer` para `PayerData`, sem validar nada.

### 5.2 Um flow por método, dono do seu create inteiro

```java
public interface PaymentFlow {
  PaymentMethod method();

  Payment create(CreatePaymentCommand command);
}
```

`PixPaymentFlow` e `BolecodePaymentFlow` leem de cima para baixo. O tronco comum sai em
colaboradores nomeados, compartilhados por composição:

- `PaymentDraftFactory` — monta o `Payment` CREATED e grava com o `createdEvent` na mesma
  transação. Para BOLECODE, reserva o nosso número dentro dessa transação (invariante da spec do
  Bolecode §7).
- `ProviderFailures` — classifica uma `ProviderException` em `MAY_HAVE_LANDED` / `DECLINED` /
  `UNAVAILABLE`, a partir do conjunto de códigos que **o flow** declara.
- `PendingAdoption` — CREATED → PENDING idempotente: payment que não está mais CREATED volta como
  está, porque o sweeper e um create lento competem para adotar a mesma cobrança e o perdedor não
  pode falhar.

A diferença que importa fica escrita em cada flow, não numa flag:

```java
// PixPaymentFlow
private static final Set<Code> MAY_HAVE_LANDED = Set.of(TIMEOUT, UNAVAILABLE);

// BolecodePaymentFlow — CONFLICT no registro é o banco dizendo "esse nosso número já existe",
// e o número é nosso e foi reservado antes da chamada: só pode ser nossa própria tentativa
// anterior que chegou (ruling R4).
private static final Set<Code> MAY_HAVE_LANDED = Set.of(TIMEOUT, UNAVAILABLE, CONFLICT);
```

E o que fazer quando a consulta volta vazia também: Pix **falha na hora** (`PROVIDER_TIMEOUT`,
o txid é nosso e uma consulta vazia decide); BOLECODE **deixa CREATED** para
`ExpirationService.sweepStuckCreated` decidir depois de `stuckCreatedAfter`, porque o 202 do banco
significa "operação em andamento" e consulta vazia um segundo depois não prova nada.

Um parâmetro booleano ou um `if (method == BOLECODE)` no tronco comum mentiria sobre essas duas
diferenças; como código de cada flow, elas estão legíveis.

### 5.3 `PaymentFlows`: registry checado no boot

```java
public final class PaymentFlows {
  private final Map<PaymentMethod, PaymentFlow> byMethod;

  public PaymentFlows(List<PaymentFlow> flows) { /* indexa; falha se faltar ou duplicar método */ }

  public PaymentFlow forMethod(PaymentMethod method) { ... }
}
```

O construtor exige exatamente um flow por valor de `PaymentMethod`: método novo sem flow é falha de
startup, não 500 em produção.

### 5.4 `PaymentService` depois disso

```java
public Payment create(CreatePaymentCommand command) {
  return flows.forMethod(command.method()).create(command);
}
```

`createCharge`, `createBolecode`, `adoptPending`, `adoptPendingBolecode`,
`adoptBolecodeFromStatus`, `fail` e `validatePayer` saem para `payment/create/`. O que fica —
`get`, `list`, `listByReference`, `events`, `cancel`, `settle`, `settleFromWebhook`,
`settleBoleto`, `openDivergence` — cabe em torno de 300 linhas, o limite do `CLAUDE.md`.

`adoptPending` e `adoptPendingBolecode` são chamados de fora do create (pelo sweeper e pelo
polling), então viram membros públicos de `PendingAdoption`, não métodos privados de um flow.

## 6. Validação do pagador: value objects + factory

### 6.1 Os tipos carregam a invariante

```java
// kernel/party/PersonName.java, Document.java  ·  kernel/address/Uf.java, ZipCode.java
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

`Uf.of` normaliza para maiúscula antes de checar — `"sp"` é uma UF válida digitada em minúscula,
não uma UF errada, e o enum do banco é maiúsculo. `ZipCode.of` guarda só dígitos. A normalização
que hoje está espalhada em `replaceAll("\\D","")` e `toUpperCase(Locale.ROOT)` dentro do validador
passa a acontecer num lugar por conceito, e depois disso o tipo garante o formato em todo lugar
onde ele passa.

`InvalidValue` é uma exceção do kernel que carrega só o motivo no nível do valor — o tipo não sabe
que ele se chama `customer.address.zip` na API.

`Payer` e `Address` passam a ser `Payer(PersonName, Document, Address)` e
`Address(street, district, city, Uf, ZipCode)`, e saem de `kernel/provider/boleto/` para
`kernel/party/`: com componentes tipados eles já estão sendo reescritos, e não são vocabulário de
boleto — são de quem paga.

### 6.2 A factory compõe o caminho do campo

```java
// payments/payment/create/PayerFactory.java
static Payer from(PayerData data) {
  if (data == null) throw new DomainException("CUSTOMER_REQUIRED", "customer is required for a BOLECODE payment");
  if (data.address() == null) throw new DomainException("CUSTOMER_REQUIRED", "customer.address is required");

  return new Payer(
      field("customer.name", () -> PersonName.of(data.name())),
      field("customer.document", () -> Document.of(data.document())),
      new Address(
          required("customer.address.street", data.address().street()),
          required("customer.address.district", data.address().district()),
          required("customer.address.city", data.address().city()),
          field("customer.address.state", () -> Uf.of(data.address().state())),
          field("customer.address.zip", () -> ZipCode.of(data.address().zip()))));
}
```

`field(path, supplier)` captura `InvalidValue` e relança
`new DomainException("CUSTOMER_REQUIRED", path + " " + reason)`; `required(path, value)` é a
checagem de branco com `path + " is required"`. As mensagens de hoje saem idênticas, campo por
campo — os 14 `if` viram 2 checagens de nulo e 7 construções tipadas.

Continua no domínio, não na borda: o 422 nomeia o campo na grafia da API e nenhuma linha foi
gravada ainda.

## 7. Mudança de contrato HTTP

Quebra deliberada, decidida nesta sessão. `POST /v1/payments`:

| antes | depois |
|---|---|
| um corpo com todos os campos; mistura recusada por `validate()` com mensagem própria | corpo por método; campo do outro método é 400 do Jackson |
| `expires_in` num corpo BOLECODE → 422 `"expires_in applies to PIX only..."` | 400 `INVALID_REQUEST` (campo desconhecido) |
| `due_date` num corpo PIX → 422 `"due_date and payment_limit_days apply to BOLECODE only"` | 400 `INVALID_REQUEST` (campo desconhecido) |
| `method` ausente ou inválido → 422 `"method must be PIX or BOLECODE"` | 400 `INVALID_REQUEST`, mesma mensagem (§4.3) |

O que **não** muda: os nomes e os tipos de todos os campos aceitos, a resposta (`PaymentResponse`),
os webhooks, e todo código de erro do domínio (`CUSTOMER_REQUIRED`, `INVALID_DUE_DATE`,
`INVALID_PAYMENT_LIMIT`, `PROVIDER_*`, `ALREADY_PAID`).

README e a seção de Bolecode do README são atualizados no mesmo commit da mudança.

## 8. Invariantes que o refactor não pode quebrar

Checklist do `CLAUDE.md`, verificada por teste existente:

1. Credencial resolvida antes de qualquer escrita — nenhuma linha para um merchant sem credencial.
2. Chamada ao banco fora de transação; `TransactionTemplate` só nas escritas antes e depois.
3. Timeout/UNAVAILABLE consultam o banco antes de decidir; Pix decide na hora, BOLECODE fica
   CREATED para o sweeper.
4. Txid do Pix é o id do payment; echo divergente do banco é logado, não adotado.
5. Nosso número reservado na mesma transação do CREATED.
6. Adoção idempotente: payment fora de CREATED volta como está.
7. Ambiente vem da API key.
8. Nada de credencial em log, erro ou `provider_requests`.
9. PENDING de BOLECODE enfileira os dois jobs (expiração e polling).
10. `PIX_TXID_UNCONFIRMED` continua sendo aberto por quem adotou, e só por ele (ruling R1).

## 9. Testes

TDD. A ordem que mantém o repo verde a cada passo:

1. **Kernel, contrato:** `MethodProvider` + as duas extensões + `PixIssueRequest`, com
   `BoletoContractTest` estendido. Providers Itaú adaptados. Verde antes de tocar em payments.
2. **`ProviderGateway`:** `resolvePix`/`resolveBoleto` com teste para `METHOD_NOT_SUPPORTED`; os 6
   call sites migram. `PROVIDER_CREDENTIALS_MISSING` de Pix ganha teste (§3.4).
3. **Value objects:** `DocumentTest`, `UfTest`, `ZipCodeTest`, `PersonNameTest` — cada formato
   aceito e recusado, e a normalização (`"sp"` → `SP`, CPF com pontuação, CEP com hífen).
4. **`PayerFactory`:** um teste por campo, afirmando código **e** mensagem exatos. Os dois testes
   que hoje asseguram `CUSTOMER_REQUIRED` (`BolecodeFlowIntegrationTest`,
   `BolecodeServiceIntegrationTest`) passam sem alteração — se precisarem mudar, o refactor errou.
5. **Flows:** `PaymentServiceIntegrationTest` e `BolecodeServiceIntegrationTest` passam com o
   `create(command)` novo. Os cenários de timeout dos dois métodos são o que prova §8.3.
6. **`PaymentFlows`:** teste de que falta de flow para um método é erro de construção.
7. **Borda:** testes novos para corpo polimórfico — PIX com `due_date` é 400, BOLECODE com
   `expires_in` é 400, `method` inválido é 400 com a mensagem de hoje, corpo válido de cada método
   chega ao flow certo. `PaymentsFlowIntegrationTest` e `BolecodeFlowIntegrationTest` atualizados
   só no corpo que enviam.
8. **`PaymentJsonContractTest`** não muda: ele assegura a resposta, que não é tocada.
9. `./mvnw verify` verde no fim de cada passo, e `ArchitectureTest` em particular — `PaymentMethod`
   subindo para o kernel e os value objects novos passam por `kernelImportsNothing` e
   `modelsHaveNoSpring`.

## 10. Decisões (para o `DECISOES.md`)

- **Uma interface de provider por espinha, extensão por método.** Rejeitado: interface gorda com
  capabilities e `UnsupportedOperationException`; records neutros meio-preenchidos. Custo se errado:
  se um terceiro método não couber em `issue/find/cancel`, a espinha é o lugar errado e a interface
  volta a se dividir — o preço é uma assinatura, não o comportamento.
- **`PaymentMethod` no kernel.** Rejeitado: enum separado para o lado do provider. Custo se errado:
  nenhum técnico; se um dia o produto do banco deixar de ser 1-para-1 com o nosso método, aí sim
  aparecem dois enums e uma tradução.
- **Request body polimórfico, quebrando o contrato.** Rejeitado: manter o corpo flat validado por
  dentro. Motivo: o corpo flat é o que obriga a validação cruzada por string. Custo se errado:
  cliente que mandava campo do outro método passa a receber 400 em vez de 422 com mensagem
  explicativa; não há cliente em produção hoje.
- **Fluxo por método dono do create inteiro, tronco por composição.** Rejeitado: template method com
  o serviço dirigindo os passos. Motivo: exigiria um objeto de contexto entre os passos e esconderia
  atrás de um flag a diferença Pix/BOLECODE no timeout. Custo se errado: mais linhas que a versão
  template, e uma mudança no tronco comum tem que ser lida nos dois flows.
- **Invariante de formato é responsabilidade do tipo.** Rejeitado: Bean Validation com anotações.
  Motivo: poria a grafia dos campos da API dentro do domínio e brigaria com o mapeamento para
  `DomainException`. Custo se errado: quatro tipos a mais no kernel.

## 11. Fora de escopo, com o porquê

- **Roteamento de provider por merchant/método.** `PROVIDER = "ITAU"` continua constante. Esta spec
  torna o roteamento possível (a resolução já é por método), mas escolher provider é decisão de
  produto, não de refactor.
- **Varredura de nomes e espaçamento no resto do repo.** Fase 2. O que esta spec cria já nasce no
  padrão; o que ela move é ajustado de passagem; o resto fica para commits de formatação separados,
  revisáveis por `git diff -w` vazio.
- **`gateway-merchants/repository/`** é a última pasta com nome de papel técnico do repo (entidades,
  repositórios e implementações de três conceitos misturados). Fase 2.
- **Idempotência, rate limit, auth** não são tocados.
