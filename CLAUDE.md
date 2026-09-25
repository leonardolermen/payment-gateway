# payment-gateway

Orquestrador de pagamentos, modelo A: as credenciais são do merchant e o dinheiro nunca passa por
aqui. Spring Boot, Java, Maven multi-módulo.

O padrão de código geral está em `~/.claude/CLAUDE.md` e vale aqui também. Este arquivo tem só o
que é específico deste repositório.

## Comandos

```bash
./mvnw verify                      # tudo, com Testcontainers (precisa de Docker)
./mvnw -pl gateway-payments test   # um módulo
./mvnw -pl gateway-app spring-boot:run
```

`verify` é o que o CI roda (`.github/workflows/ci.yml`). Nada é "pronto" sem ele verde.

## Módulos e a fronteira

```
gateway-kernel      tipos sem dependência nenhuma (nem Spring, nem JPA)
gateway-merchants   merchant, API keys, credenciais cifradas
gateway-providers   os bancos (Itaú); só conhece o kernel
gateway-payments    pagamentos, jobs, outbox, reconciliação; NÃO importa providers
gateway-app         REST, auth, rate limit, webhooks, observabilidade; o único que conhece todos
```

`ArchitectureTest` (ArchUnit) reprova o build se a fronteira cair. As regras que mais aparecem no
caminho de um refactor:

- `kernel` não importa Spring nem `jakarta.persistence`.
- `payments` e `merchants` não se importam; a cola fica em `gateway-app` (`ProviderWiring`).
- `payments` não importa `providers` — os dois se falam por interfaces do kernel.
- Nenhuma classe fora de `providers` tem "Itau" no nome. O vocabulário do banco não sai de lá.
- `@Entity` é package-private e só existe em `..persistence..`.
- Modelo (record, enum, agregado) não vê Spring; só os tipos cujo nome termina em `Service`,
  `Runner`, `Gateway`, `Relay`, `Properties`, `Configuration`, `Events` podem.

Se um refactor precisa furar uma dessas, a conversa é sobre a fronteira, não sobre a regra —
afrouxar o ArchUnit para o código passar é a resposta errada.

## Pastas

Feature-first em todo módulo, como manda `~/.claude/CLAUDE.md`: pasta com nome de conceito, papel
técnico só como sub-pasta folha. Vale nos quatro módulos de negócio:

```
gateway-merchants/.../merchants/
  merchant/     Merchant, MerchantStatus, MerchantService  + persistence/
  apikey/       ApiKey, ApiKeyEnvironment, ApiKeyService    + persistence/
  credential/   Provider, ProviderCredential, ProviderCredentialService + persistence/
  crypto/       Encrypted, EnvelopeCipher, MasterKey        (cifrar é um conceito, não um papel)
  MerchantsConfiguration, MerchantsProperties               (nível do módulo)
```

`gateway-payments` segue o mesmo (`payment/`, `refund/`, `jobs/`, `outbox/`, `idempotency/`,
`inbox/`, `reconciliation/`, `provider/`, cada um com seu `persistence/`), e `gateway-app` também:

```
gateway-app/.../app/
  api/
    payment/    PaymentsController  + dto/
    refund/     RefundsController   + dto/
    webhook/    WebhookEndpointsController + dto/
    merchant/   MerchantController
    admin/      MerchantsAdminController + dto/
    support/    ErrorHandler, IdempotencyFilter
  inbound/      pix/, mtls/         (webhook que chega do banco)
  outbound/     MerchantEvents, OutboxRelay
  security/     os filtros de autenticação, rate limit, path
  observability/
  providers/    ProviderWiring — onde os três módulos se encontram
  jobs/
```

`api/` é a borda que o merchant chama; `inbound/` é a borda que o banco chama. Não se misturam.

## Idioma

Todo o código em inglês: identificadores, comentários, mensagens de erro, log, commit, README
(`DECISOES.md`, 2026-09-24). Spec, plano e decisão em `docs/superpowers/` ficam em português.

## Decisões

`docs/superpowers/DECISOES.md` é append-only. Cada entrada: a decisão, a alternativa rejeitada e o
custo de estar errada. Decisão nova entra no fim; decisão antiga não é editada nem apagada — se
mudou, é uma entrada nova dizendo que substitui a anterior.

Antes de propor alternativa para algo que parece estranho no código, procure no DECISOES: quase
sempre o estranho foi escolhido de propósito e o motivo está escrito.

## Dinheiro não perdoa

O que este repositório protege, e que um refactor não pode quebrar sem intenção declarada:

- **Timeout não é falha.** `TIMEOUT` e `UNAVAILABLE` do banco não dizem se a cobrança chegou. O
  fluxo pergunta ao banco antes de decidir. Falhar direto é dizer "não cobrei" para um pagador que
  já pagou.
- **O txid do Pix é nosso** (é o id do payment); o `nosso número` do boleto também é nosso e é
  reservado antes da chamada. O echo do banco que divergir é logado, não adotado.
- **Nada é gravado antes da credencial ser resolvida.** Merchant sem credencial não deixa linha
  para trás.
- **A chamada ao banco roda fora de transação.** `PaymentService` não é `@Transactional` de
  propósito: um timeout de 30 s no banco não pode segurar conexão e lock por 30 s. `TransactionTemplate`
  envolve só as escritas antes e depois.
- **O ambiente vem da API key, nunca do request body.** Uma chave TEST não alcança credencial LIVE
  por mais que o corpo peça.
- **Código e mensagem de erro são contrato.** `CUSTOMER_REQUIRED`, `PROVIDER_TIMEOUT`,
  `INVALID_DUE_DATE` e a grafia dos campos na mensagem estão em teste e na documentação do cliente.
- **Adoção idempotente.** Um payment que não está mais `CREATED` é devolvido como está: o sweeper e
  um create lento competem para adotar a mesma cobrança e o perdedor não pode falhar.

## Segredos

Credencial, chave privada, API key e conta beneficiária nunca vão para log, para `provider_requests`
nem para mensagem de erro. O mascaramento vive em `gateway-app/observability` (`Masker`) e tem
teste. Placeholder em README e em comando de exemplo continua placeholder.

## Testes

Sem provider fake: o ambiente TEST é o sandbox real do Itaú, e os testes usam WireMock com fixtures
tiradas do OpenAPI em `docs/providers/` (`DECISOES.md`, 2026-09-24). Um fake nunca divergiria da API
real do jeito que a API real diverge da própria especificação.

Integração usa Testcontainers com Postgres — o mesmo banco da produção, não H2.

## Fluxo de trabalho

Mudança de tamanho arquitetural passa por spec em `docs/superpowers/specs/` e plano em
`docs/superpowers/plans/`, nessa ordem, antes do código. Os arquivos existentes são o modelo de
formato.
