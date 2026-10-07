# Logs: console legível em dev e conteúdo que diz o que aconteceu — design

Data: 2026-10-07. Pedido do usuário: "precisamos melhorar muito [os logs], seria legal se desse pra
fazer coloridos", com escopo escolhido "dev legível + conteúdo". Duas fases independentes; a A não
depende da B. Fora: agregação de log (Loki, ELK), log de acesso HTTP, reescrever o texto das 51
chamadas de log existentes — o texto delas já nomeia ids e motivo; o problema está em volta delas.

## 1. O que existe e o que falta
`logback-spring.xml` tem um único appender, JSON (logstash encoder 8.1), com `MaskingJsonProvider` e
`MaskingStackTraceJsonProvider` passando mensagem e stack pelo `Masker`. Bom para máquina, ilegível no
console do IntelliJ. O MDC só tem `correlationId`, e só em request HTTP: o que um job loga não diz de
qual job veio. Uma falha de rede no banco aparece como `token request failed: null` (a
`ConnectException` não tem mensagem) seguida de ~40 linhas de stack, para algo que é operação normal.
A subida imprime "Bootstrapping Spring Data JPA repositories" quatro vezes, uma por módulo.

## 2. Fase A — formato do console
**Ativação.** `application.yml` ganha `gateway.logging.format: ${LOG_FORMAT:json}`; o
`logback-spring.xml` lê com `<springProperty>` e escolhe o appender. `json` = o appender de hoje,
idêntico. `pretty` = texto colorido. Outro valor derruba a subida com mensagem que nomeia
`LOG_FORMAT` e os valores aceitos — como o guard do mTLS, um typo não vira silenciosamente um ou outro
formato. JSON é o padrão porque é o que a produção consome: esquecer a variável num deploy mantém JSON.
`.env.example` documenta `LOG_FORMAT=pretty` para dev.

**Linha pretty.** `HH:mm:ss.SSS NÍVEL Classe [contexto] mensagem`:
- nível colorido (ERROR vermelho, WARN amarelo, INFO verde, DEBUG cinza), hora cinza, nome simples da
  classe em ciano, alinhado em largura fixa;
- `[contexto]` só aparece se houver chave conhecida no MDC, em ordem fixa: `cid=` (o `correlationId`),
  `job`, `merchant`, `env`, `provider`, `op`; ids **inteiros** (copiáveis para uma query). `jobId` fica
  só no JSON — na linha pretty o tipo do job basta para ler, e o id está no banco;
- stack trace indentado abaixo da linha.

Cores por `%clr` do Spring Boot, que respeita `spring.output.ansi.enabled`: saída redirecionada para
arquivo sai sem códigos ANSI.

**Mascaramento.** `MaskingMessageConverter` e `MaskingThrowableConverter` em `observability/`, ambos
chamando o mesmo `Masker`. O formato pretty não pode ser o lugar por onde um segredo vaza: os casos do
`MaskingJsonProviderTest` passam a rodar contra os dois formatos.

**Ruído.** WARN para `org.springframework.data.repository.config.RepositoryConfigurationDelegate`, os
loggers de pool/dialeto do Hibernate e `com.zaxxer.hikari`, nos **dois** formatos — o ruído também
ocupa o JSON de produção. Banner e `Started GatewayApplication` ficam.

## 3. Fase B — conteúdo
**Causa vazia.** `ItauTokenClient`, `PixApiClient` e `BoletoHttp` montam `"... failed: " +
e.getMessage()`. Um helper em `providers` descreve a falha de transporte: mensagem da exceção, ou o
nome da classe quando ela é nula, mais o host do destino (sem path nem query, que podem carregar
dados) — `token request to localhost:8099 failed: ConnectException`. Isso também melhora o que fica em
`provider_requests`. O merchant continua lendo a frase fixa de `ProviderErrors`: nenhum contrato muda.

**Uma linha para falha esperada.** No conversor de throwable, compartilhado pelos dois formatos: se a
exceção logada (ou a primeira da cadeia) é `ProviderException` com código `UNAVAILABLE` ou `TIMEOUT`, o
stack vira uma linha — `ProviderException UNAVAILABLE: <mensagem> ← ConnectException`. Todo outro
código e toda outra exceção mantêm o stack inteiro: `UNAUTHENTICATED`, `INVALID`, um NPE, um erro de
parse são o que alguém precisa ler. As chamadas de log existentes não mudam.

**Contexto nos jobs.** Chaves novas, só de log:
- `JobRunner.runDue`: `job` (tipo) e `jobId` durante `handler.run`;
- `ReconciliationService.reconcileAll`: `merchant` e `env` por merchant;
- `ProviderGateway.call`: `provider` e `op` durante a chamada ao banco.

Cada uma é removida em `finally` (as threads são reaproveitadas) e restaura o valor anterior quando
aninhada. `correlationId` **não** é criado para jobs: `MerchantEvents` o lê para o webhook do merchant e
`CieloHttp`/`BoletoHttp` o mandam ao banco como header — inventá-lo num job mudaria o que sai para fora.
MDC é SLF4J puro: `payments` continua sem Spring nos modelos, e o ArchUnit não muda.

## 4. Testes
- Converters: mascaram mensagem e stack com os casos do teste JSON (chave `gk_live_…`, PAN, segredo).
- `logback-spring.xml` carregado com `json`, `pretty` e um valor inválido: appender certo ativo nos dois
  primeiros, falha nomeando `LOG_FORMAT` no terceiro.
- Helper de transporte: `ConnectException` sem mensagem → texto com a classe e o host; URL com query não
  vaza a query.
- Conversor: `UNAVAILABLE`/`TIMEOUT` (direta e como causa) viram uma linha; `UNAUTHENTICATED` e
  `IllegalStateException` mantêm o stack.
- MDC: chaves presentes durante o handler e ausentes depois, inclusive quando o handler lança.

## 5. Decisões (para o `DECISOES.md`)
1. **Formato escolhido por `LOG_FORMAT`, JSON por padrão, valor desconhecido falha a subida.**
   Rejeitado: profile `dev` (acumula outras mudanças de comportamento) e detectar TTY (o console do
   IntelliJ não é TTY; um container com `-t` logaria texto em prod). Custo se errado: quem esquece a
   variável em dev lê JSON — incômodo, nunca perigo.
2. **Falha esperada do banco é logada em uma linha, pela camada de log.** Rejeitado: decidir em cada
   chamada — a próxima chamada escrita esqueceria. Custo se errado: um `log.warn(..., e)` sem stack
   surpreende quem não leu esta entrada; um `UNAVAILABLE` que na verdade era bug perde o stack (a causa
   raiz continua na linha).
3. **Contexto de job em chaves próprias do MDC, nunca em `correlationId`.** Rejeitado: gerar
   `correlationId` por job. Custo se errado: nenhum externo; o log de job só não casa com o de request.
