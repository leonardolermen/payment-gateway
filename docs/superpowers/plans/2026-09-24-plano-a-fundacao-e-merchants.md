# Payment Gateway — Plano A: fundação e merchants

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Subir o monólito modular do Payment Gateway com a fronteira de módulos cobrada por ArchUnit, o módulo `merchants` completo (merchant, API keys com hash, credenciais de provider cifradas em envelope AES-256-GCM), autenticação por API key, rate limit, webhooks de saída via `webhook-delivery`, correlação e mascaramento de log — sem nenhum pagamento ainda.

**Architecture:** Maven multi-módulo (`gateway-kernel`, `gateway-merchants`, `gateway-app`; os módulos `orders`, `payments` e `providers` entram nos Planos B/C). `kernel` não importa nada. Cada módulo de negócio é dono de um schema Postgres e das próprias migrations (`db/migration/<modulo>`, versões com prefixo por módulo), e o Flyway do `app` lista todas. O `app` é o único deploy: REST, filtro de API key, rate limit, workers, actuator. `webhook-delivery` entra como dependência com prefixo `X-Gateway`.

**Tech Stack:** Java 25, Spring Boot 4.0.7 (servlet blocking + virtual threads), Spring Data JPA/Hibernate, Flyway, PostgreSQL 17, `com.barrier:webhook-delivery:0.1.1` (GitHub Packages), `com.github.f4b6a3:ulid-creator`, Bucket4j, Micrometer/Prometheus, Logback JSON (`logstash-logback-encoder`), JUnit 5, AssertJ, Testcontainers, ArchUnit 1.5.0. Maven wrapper.

**Spec:** `docs/superpowers/specs/2026-09-23-payment-gateway-design.md` (§1.3, §2, §7, §8, §9 `merchants.*`, §11 arquitetura/testes). O plano argumenta a partir dela; executores leem os dois.

## Global Constraints

- Coordenadas `com.gateway:payment-gateway-parent:0.1.0-SNAPSHOT`; módulos `gateway-kernel`, `gateway-merchants`, `gateway-app`; pacote raiz `com.gateway`, um subpacote por módulo (`com.gateway.kernel`, `com.gateway.merchants`, `com.gateway.app`).
- Java 25, Spring Boot 4.0.7, virtual threads ligadas (`spring.threads.virtual.enabled=true`). Sem WebFlux.
- **`kernel` não importa nada** (nem Spring, nem JPA, nem outro módulo). Ninguém importa `app`. `merchants` não importa `orders`/`payments` (ainda não existem; a regra já fica escrita). Cobrado por ArchUnit no `app`.
- Um schema por módulo: `merchants`. Migrations em `gateway-merchants/src/main/resources/db/migration/merchants/V100__*.sql` (prefixo 1xx = merchants; 2xx = payments; 3xx = orders). O `app` configura `spring.flyway.locations` com todas e `schemas: merchants` (o Flyway cria o schema). Entidades JPA declaram `schema = "merchants"` em `@Table`.
- `webhook-delivery` 0.1.1: prefixo `X-Gateway`; `tenantId = merchant_id`. Nenhum `@EntityScan`/`@EnableJpaRepositories` explícito no `app` (a lib registra o próprio pacote; o `app` fica em `com.gateway.app`, que não é ancestral de `com.barrier.webhookdelivery`).
- API key: formato `gk_live_<26 chars ULID-like>` / `gk_test_…`; guardada como `SHA-256(pepper || key)` hex; `prefix` = primeiros 12 chars para busca; duas ativas por merchant no máximo (rotação com sobreposição de até 24 h); header `Authorization: Bearer gk_…`.
- Credenciais de provider e qualquer segredo: **AES-256-GCM envelope** — DEK aleatória de 32 bytes por merchant, cifrada pela chave mestra (`GATEWAY_MASTER_KEY`, base64 de 32 bytes, obrigatória fora do perfil `test`); nonce de 12 bytes novo por cifra; AAD = `merchant_id`. Nada cifrado é logado; `toString` de tipos com segredo mascara.
- Log JSON; `Masker` central: CPF (`\d{3}\.?\d{3}\.?\d{3}-?\d{2}`), `gk_(live|test)_\w+`, `Bearer \S+`, `"secret":"…"` → `***`. Testado.
- Nenhum teste fala com a rede externa. Destinos de webhook em testes são `HttpServer` em `localhost`.
- Comentários em português registrando POR QUÊ com evidência. Commits `tipo(escopo): frase em minuscula`, corpo explicando o porquê, terminando com `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Ambiente local: `export JAVA_HOME="$HOME/.jdks/corretto-25.0.4.1"` antes de qualquer Maven; Docker para Testcontainers; `~/.m2` já tem `com.barrier:webhook-delivery:0.1.1` instalada (o CI usa GitHub Packages com token).

---

## Estrutura de arquivos

```
payment-gateway/
  pom.xml                                   parent: módulos, BOMs, versões
  mvnw, mvnw.cmd, .mvn/wrapper/*            copiados de C:\Dev\webhook-delivery
  .gitignore  README.md  .github/workflows/ci.yml
  gateway-kernel/
    pom.xml
    src/main/java/com/gateway/kernel/
      money/Money.java                      long centavos + moeda; soma/subtração/comparação; nunca double
      ids/Ulid.java                         geração e validação de ULID (26 chars Crockford)
      ids/MerchantId.java                   record tipado sobre Ulid
      erros/DominioException.java           base das exceções de regra (código + mensagem)
      erros/NaoEncontradoException.java
      seguranca/Segredo.java                wrapper de String com toString mascarado
    src/test/java/com/gateway/kernel/...    MoneyTest, UlidTest, SegredoTest
  gateway-merchants/
    pom.xml
    src/main/java/com/gateway/merchants/
      dominio/Merchant.java, MerchantStatus.java
      dominio/ApiKey.java, ApiKeyAmbiente.java        (LIVE/TEST), geração, hash, prefixo
      dominio/ProviderCredential.java                 (merchantId, provider, ambiente, payload cifrado)
      dominio/Provider.java                            enum ITAU, FAKE
      cripto/EnvelopeCipher.java                       AES-256-GCM: DEK por merchant, chave mestra
      cripto/ChaveMestra.java                          carrega GATEWAY_MASTER_KEY
      repositorio/*Entity, *JpaRepository, *Repository, *RepositoryImpl   (package-private entities)
      servico/MerchantService.java                     criar, buscar, ativar/desativar
      servico/ApiKeyService.java                       emitir (devolve a chave UMA vez), autenticar, revogar
      servico/ProviderCredentialService.java           gravar (cifra), ler (decifra só na hora)
      MerchantsConfiguration.java                      @Configuration com @Import explícito (sem scan)
    src/main/resources/db/migration/merchants/V100__merchants.sql
    src/test/java/com/gateway/merchants/...            unit (dominio, cripto) + integração (repositorio, servico)
  gateway-app/
    pom.xml
    src/main/java/com/gateway/app/
      GatewayApplication.java
      seguranca/ApiKeyAuthFilter.java                  Authorization: Bearer → MerchantContext
      seguranca/MerchantContext.java                   ThreadLocal/request attribute com merchantId e ambiente
      seguranca/AdminKeyFilter.java                    X-Admin-Key para /v1/admin/**
      seguranca/RateLimitFilter.java                   Bucket4j por API key
      observabilidade/CorrelationFilter.java           X-Correlation-Id in/out + MDC
      observabilidade/Masker.java                      + logback-spring.xml com o encoder JSON usando o Masker
      api/admin/MerchantsAdminController.java          POST /v1/admin/merchants, POST …/{id}/api-keys, PUT …/{id}/providers/{provider}/credentials
      api/MeController.java                            GET /v1/me
      api/WebhookEndpointsController.java              PUT/GET/DELETE /v1/webhooks/endpoints/{id}, POST …/rotate-secret, GET /v1/webhooks/endpoints
      api/ErroHandler.java                             ProblemDetail para DominioException/NaoEncontrado/IllegalArgument
    src/main/resources/application.yml, logback-spring.xml
    src/test/java/com/gateway/app/
      arquitetura/ArquiteturaTest.java
      ...IntegrationTest (auth, rate limit, admin, webhooks, masker, cifra em repouso)
```

Responsabilidades: `kernel` = tipos sem dependência; `merchants` = quem é o cliente e com que credenciais ele fala com bancos; `app` = borda HTTP, segurança, observabilidade e montagem.

---

### Task 1: Esqueleto multi-módulo que compila, com CI

**Files:**
- Create: `pom.xml`, `gateway-kernel/pom.xml`, `gateway-merchants/pom.xml`, `gateway-app/pom.xml`, `.gitignore`, `.github/workflows/ci.yml`, `gateway-app/src/main/java/com/gateway/app/GatewayApplication.java`, `gateway-app/src/main/resources/application.yml`
- Copy: `C:\Dev\webhook-delivery\mvnw`, `mvnw.cmd`, `.mvn\` → raiz (o `mvnw` copiado precisa de `git update-index --chmod=+x mvnw` — foi o que quebrou o CI da lib).

**Interfaces:**
- Produces: reactor Maven com três módulos; `GatewayApplication` sobe (sem banco ainda: `spring.autoconfigure.exclude` temporário NÃO — em vez disso o app só é testado a partir da Task 5, quando há Testcontainers).

- [ ] **Step 1: Wrapper e `.gitignore`**

```bash
cd /c/Dev/payment-gateway && cp /c/Dev/webhook-delivery/mvnw /c/Dev/webhook-delivery/mvnw.cmd . && cp -r /c/Dev/webhook-delivery/.mvn . && printf 'target/\n.idea/\n*.iml\n.vscode/\n.superpowers/\n' > .gitignore
```

- [ ] **Step 2: Parent `pom.xml`**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>4.0.7</version>
        <relativePath/>
    </parent>
    <groupId>com.gateway</groupId>
    <artifactId>payment-gateway-parent</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>payment-gateway</name>

    <modules>
        <module>gateway-kernel</module>
        <module>gateway-merchants</module>
        <module>gateway-app</module>
    </modules>

    <properties>
        <java.version>25</java.version>
        <archunit.version>1.5.0</archunit.version>
        <testcontainers.version>1.21.4</testcontainers.version>
        <webhook-delivery.version>0.1.1</webhook-delivery.version>
        <ulid-creator.version>5.2.3</ulid-creator.version>
        <bucket4j.version>8.14.0</bucket4j.version>
        <logstash-encoder.version>8.1</logstash-encoder.version>
    </properties>

    <repositories>
        <!-- GitHub Packages exige token ate para ler; local resolve do ~/.m2, CI usa GITHUB_TOKEN. -->
        <repository>
            <id>github-webhook-delivery</id>
            <url>https://maven.pkg.github.com/leonardolermen/webhook-delivery</url>
        </repository>
    </repositories>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.testcontainers</groupId>
                <artifactId>testcontainers-bom</artifactId>
                <version>${testcontainers.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency><groupId>com.gateway</groupId><artifactId>gateway-kernel</artifactId><version>${project.version}</version></dependency>
            <dependency><groupId>com.gateway</groupId><artifactId>gateway-merchants</artifactId><version>${project.version}</version></dependency>
            <dependency><groupId>com.barrier</groupId><artifactId>webhook-delivery</artifactId><version>${webhook-delivery.version}</version></dependency>
            <dependency><groupId>com.github.f4b6a3</groupId><artifactId>ulid-creator</artifactId><version>${ulid-creator.version}</version></dependency>
            <dependency><groupId>com.bucket4j</groupId><artifactId>bucket4j_jdk17-core</artifactId><version>${bucket4j.version}</version></dependency>
            <dependency><groupId>net.logstash.logback</groupId><artifactId>logstash-logback-encoder</artifactId><version>${logstash-encoder.version}</version></dependency>
            <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><version>${archunit.version}</version><scope>test</scope></dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.assertj</groupId><artifactId>assertj-core</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```
Se algum `artifactId` do Bucket4j/ulid não resolver, consulte o Maven Central para o nome atual e registre no relatório; a versão é o que menos importa aqui.

- [ ] **Step 3: POMs dos módulos**

`gateway-kernel/pom.xml`:
```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent><groupId>com.gateway</groupId><artifactId>payment-gateway-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
    <artifactId>gateway-kernel</artifactId>
    <!-- Sem dependencias de proposito: o ArchUnit cobra e o pom e a primeira linha de defesa. -->
    <dependencies>
        <dependency><groupId>com.github.f4b6a3</groupId><artifactId>ulid-creator</artifactId></dependency>
    </dependencies>
</project>
```
(`ulid-creator` é a única exceção, e é uma lib sem dependências transitivas — registre no comentário. A regra ArchUnit "kernel não importa nada" é sobre pacotes do projeto e Spring/JPA.)

`gateway-merchants/pom.xml`:
```xml
<project ...>
    <modelVersion>4.0.0</modelVersion>
    <parent><groupId>com.gateway</groupId><artifactId>payment-gateway-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
    <artifactId>gateway-merchants</artifactId>
    <dependencies>
        <dependency><groupId>com.gateway</groupId><artifactId>gateway-kernel</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```

`gateway-app/pom.xml`:
```xml
<project ...>
    <modelVersion>4.0.0</modelVersion>
    <parent><groupId>com.gateway</groupId><artifactId>payment-gateway-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
    <artifactId>gateway-app</artifactId>
    <dependencies>
        <dependency><groupId>com.gateway</groupId><artifactId>gateway-kernel</artifactId></dependency>
        <dependency><groupId>com.gateway</groupId><artifactId>gateway-merchants</artifactId></dependency>
        <dependency><groupId>com.barrier</groupId><artifactId>webhook-delivery</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-flyway</artifactId></dependency>
        <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
        <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
        <dependency><groupId>com.bucket4j</groupId><artifactId>bucket4j_jdk17-core</artifactId></dependency>
        <dependency><groupId>net.logstash.logback</groupId><artifactId>logstash-logback-encoder</artifactId></dependency>
        <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-testcontainers</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>postgresql</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.testcontainers</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
        <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><scope>test</scope></dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
        </plugins>
    </build>
</project>
```
Os nomes exatos dos starters em Boot 4.0.7 (`spring-boot-starter-flyway`, `spring-boot-testcontainers`) são os que `C:\Dev\webhook-delivery\pom.xml` e `C:\Dev\barrier\services\webhook-api\pom.xml` usam hoje — copie de lá se houver dúvida.

- [ ] **Step 4: `GatewayApplication` e `application.yml`**

```java
package com.gateway.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Único deploy do gateway. Escaneia só {@code com.gateway.app}: os módulos de negócio entram por
 * {@code @Import} das suas configurações, um a um — a mesma regra do Barrier, para ficar legível o
 * que cada módulo expõe. A lib webhook-delivery entra pela própria autoconfiguração.
 */
@SpringBootApplication
@EnableScheduling
public class GatewayApplication {
  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
```

`gateway-app/src/main/resources/application.yml`:
```yaml
spring:
  application:
    name: payment-gateway
  threads:
    virtual:
      enabled: true
  datasource:
    url: ${DB_URL:jdbc:postgresql://localhost:5432/gateway}
    username: ${DB_USER:gateway}
    password: ${DB_PASSWORD:gateway}
    hikari:
      maximum-pool-size: ${DB_POOL_SIZE:10}
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    enabled: true
    # Um schema por modulo; o Flyway cria os schemas listados. As versoes tem prefixo por modulo
    # (1xx merchants, 2xx payments, 3xx orders) porque um historico so exige versoes unicas.
    schemas: merchants
    locations: classpath:db/migration/merchants

webhook-delivery:
  headers:
    prefix: X-Gateway
  correlation-mdc-key: correlationId
  workers: 3

gateway:
  admin-key: ${GATEWAY_ADMIN_KEY:}
  master-key: ${GATEWAY_MASTER_KEY:}
  api-key-pepper: ${GATEWAY_API_KEY_PEPPER:}
  rate-limit:
    requests-per-minute: 600

management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

- [ ] **Step 5: CI**

`.github/workflows/ci.yml`:
```yaml
name: CI
on:
  push:
    branches: [main]
  pull_request:
concurrency:
  group: ci-${{ github.event.pull_request.number || github.ref }}
  cancel-in-progress: true
permissions:
  contents: read
  packages: read
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          java-version: "25"
          distribution: corretto
          cache: maven
          server-id: github-webhook-delivery
          server-username: GITHUB_ACTOR
          server-password: GITHUB_TOKEN
      - run: ./mvnw -B verify
        env:
          GITHUB_ACTOR: ${{ github.actor }}
          GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
      - if: always()
        uses: actions/upload-artifact@v4
        with: { name: surefire-reports, path: "**/target/surefire-reports/**", retention-days: 7 }
```

- [ ] **Step 6: Compilar e commitar**

Run: `cd /c/Dev/payment-gateway && export JAVA_HOME="$HOME/.jdks/corretto-25.0.4.1" && ./mvnw -B -q -DskipTests package`
Expected: `BUILD SUCCESS` nos três módulos.

```bash
git update-index --chmod=+x mvnw
git add -A
git commit -m "build(reactor): tres modulos, toolchain do barrier e lib webhook-delivery

kernel/merchants/app agora; orders, payments e providers entram nos
planos B e C. Prefixo de versao de migration por modulo porque o
historico Flyway do app e um so."
```

---

### Task 2: `kernel` — `Money`, `Ulid`, `Segredo`, exceções

**Files:**
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/money/Money.java`, `ids/Ulid.java`, `ids/MerchantId.java`, `erros/DominioException.java`, `erros/NaoEncontradoException.java`, `seguranca/Segredo.java`
- Test: `gateway-kernel/src/test/java/com/gateway/kernel/money/MoneyTest.java`, `ids/UlidTest.java`, `seguranca/SegredoTest.java`

**Interfaces:**
- Produces:
  ```java
  record Money(long centavos, String moeda) { static Money brl(long centavos); Money mais(Money); Money menos(Money); boolean maiorQue(Money); boolean ehZero(); static Money ZERO_BRL }
  final class Ulid { static String novo(); static boolean valido(String); }   // 26 chars Crockford base32, monotônico
  record MerchantId(String valor) { static MerchantId novo(); }               // valida com Ulid.valido
  class DominioException extends RuntimeException { String codigo(); }        // (codigo, mensagem)
  class NaoEncontradoException extends DominioException                       // codigo "NAO_ENCONTRADO"
  final class Segredo { static Segredo de(String); String revelar(); toString() -> "***"; equals por valor constante-tempo }
  ```

- [ ] **Step 1: Testes**

`MoneyTest.java`:
```java
package com.gateway.kernel.money;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MoneyTest {
  @Test void somaESubtraiEmCentavos() {
    Money a = Money.brl(15990), b = Money.brl(10);
    assertThat(a.mais(b)).isEqualTo(Money.brl(16000));
    assertThat(a.menos(b)).isEqualTo(Money.brl(15980));
  }
  @Test void naoMisturaMoedas() {
    assertThatThrownBy(() -> Money.brl(1).mais(new Money(1, "USD"))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void naoAceitaNegativo() {
    assertThatThrownBy(() -> Money.brl(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Money.brl(1).menos(Money.brl(2))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void comparacao() {
    assertThat(Money.brl(2).maiorQue(Money.brl(1))).isTrue();
    assertThat(Money.ZERO_BRL.ehZero()).isTrue();
  }
}
```

`UlidTest.java`:
```java
package com.gateway.kernel.ids;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class UlidTest {
  @Test void tem26CharsCrockfordEValida() {
    String id = Ulid.novo();
    assertThat(id).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
    assertThat(Ulid.valido(id)).isTrue();
    assertThat(Ulid.valido("abc")).isFalse();
    assertThat(Ulid.valido(null)).isFalse();
  }
  /** txid do Pix e derivado do id: [a-zA-Z0-9]{26,35}. ULID cabe sem transformacao. */
  @Test void cabeNoFormatoDeTxidDoBacen() {
    assertThat(Ulid.novo()).matches("[a-zA-Z0-9]{26,35}");
  }
  @Test void monotonicoNoMesmoMilissegundo() {
    String a = Ulid.novo(), b = Ulid.novo();
    assertThat(a.compareTo(b)).isNegative();
  }
}
```

`SegredoTest.java`:
```java
package com.gateway.kernel.seguranca;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SegredoTest {
  @Test void toStringNuncaRevela() {
    Segredo s = Segredo.de("gk_live_ABC");
    assertThat(s.toString()).doesNotContain("ABC").isEqualTo("***");
    assertThat(s.revelar()).isEqualTo("gk_live_ABC");
  }
  @Test void igualdadePorValor() {
    assertThat(Segredo.de("x")).isEqualTo(Segredo.de("x")).isNotEqualTo(Segredo.de("y"));
  }
  @Test void naoAceitaVazio() {
    assertThatThrownBy(() -> Segredo.de(" ")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-kernel test` → erro de compilação.

- [ ] **Step 3: Implementar**

`Money.java`:
```java
package com.gateway.kernel.money;

/**
 * Dinheiro em centavos inteiros. Nunca {@code double}: R$ 0,10 + R$ 0,20 em double não dá R$ 0,30,
 * e um gateway que erra um centavo perde a conciliação inteira.
 */
public record Money(long centavos, String moeda) {
  public static final Money ZERO_BRL = new Money(0, "BRL");

  public Money {
    if (centavos < 0) throw new IllegalArgumentException("valor negativo: " + centavos);
    if (moeda == null || moeda.length() != 3) throw new IllegalArgumentException("moeda inválida: " + moeda);
  }

  public static Money brl(long centavos) { return new Money(centavos, "BRL"); }

  public Money mais(Money outro) { return new Money(centavos + mesmaMoeda(outro).centavos, moeda); }

  public Money menos(Money outro) {
    long r = centavos - mesmaMoeda(outro).centavos;
    if (r < 0) throw new IllegalArgumentException("resultado negativo");
    return new Money(r, moeda);
  }

  public boolean maiorQue(Money outro) { return centavos > mesmaMoeda(outro).centavos; }

  public boolean ehZero() { return centavos == 0; }

  private Money mesmaMoeda(Money outro) {
    if (!moeda.equals(outro.moeda)) throw new IllegalArgumentException("moedas diferentes: " + moeda + " e " + outro.moeda);
    return outro;
  }
}
```

`Ulid.java`:
```java
package com.gateway.kernel.ids;

import com.github.f4b6a3.ulid.UlidCreator;
import java.util.regex.Pattern;

/**
 * ULID como id de tudo: ordenável por tempo (índice B-tree feliz), 26 chars de Crockford base32 —
 * o que cabe direto no {@code txid} do Pix ({@code [a-zA-Z0-9]{26,35}}) sem transformação. UUID não
 * cabe (36 chars com hífen).
 */
public final class Ulid {
  private static final Pattern FORMATO = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

  private Ulid() {}

  /** Monotônico dentro do mesmo milissegundo: dois ids gerados em sequência ordenam na ordem de geração. */
  public static String novo() { return UlidCreator.getMonotonicUlid().toString(); }

  public static boolean valido(String s) { return s != null && FORMATO.matcher(s).matches(); }
}
```

`MerchantId.java`:
```java
package com.gateway.kernel.ids;

public record MerchantId(String valor) {
  public MerchantId {
    if (!Ulid.valido(valor)) throw new IllegalArgumentException("merchant id inválido: " + valor);
  }
  public static MerchantId novo() { return new MerchantId(Ulid.novo()); }
}
```

`DominioException.java` / `NaoEncontradoException.java`:
```java
package com.gateway.kernel.erros;

/** Erro de regra de negócio: vira 4xx na borda, com {@code codigo} estável para o merchant tratar. */
public class DominioException extends RuntimeException {
  private final String codigo;
  public DominioException(String codigo, String mensagem) { super(mensagem); this.codigo = codigo; }
  public String codigo() { return codigo; }
}
```
```java
package com.gateway.kernel.erros;

public class NaoEncontradoException extends DominioException {
  public NaoEncontradoException(String oQue, String id) { super("NAO_ENCONTRADO", oQue + " não encontrado: " + id); }
}
```

`Segredo.java`:
```java
package com.gateway.kernel.seguranca;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Valor sensível (API key, client secret, certificado) que nunca aparece em log, exceção ou
 * {@code toString}. Quem precisa do valor chama {@link #revelar()} de propósito, e o nome do método
 * é o aviso.
 */
public final class Segredo {
  private final String valor;

  private Segredo(String valor) { this.valor = valor; }

  public static Segredo de(String valor) {
    if (valor == null || valor.isBlank()) throw new IllegalArgumentException("segredo vazio");
    return new Segredo(valor);
  }

  public String revelar() { return valor; }

  @Override public String toString() { return "***"; }

  /** Comparação em tempo constante: comparar API key com equals de String vaza o tamanho do prefixo comum. */
  @Override public boolean equals(Object o) {
    return o instanceof Segredo s && MessageDigest.isEqual(valor.getBytes(StandardCharsets.UTF_8), s.valor.getBytes(StandardCharsets.UTF_8));
  }

  @Override public int hashCode() { return Objects.hash(valor); }
}
```

- [ ] **Step 4: Rodar e ver passar** — `./mvnw -B -q -pl gateway-kernel test` → verde.

- [ ] **Step 5: Commit**

```bash
git add gateway-kernel
git commit -m "feat(kernel): money em centavos, ulid, segredo mascarado e excecoes de dominio

ULID porque cabe no txid do Bacen sem transformacao; Money em long
porque double erra centavo; Segredo porque toString de record vazou
segredo no barrier."
```

---

### Task 3: `merchants` — domínio (`Merchant`, `ApiKey`, `ProviderCredential`) e `EnvelopeCipher`

**Files:**
- Create: `gateway-merchants/src/main/java/com/gateway/merchants/dominio/{Merchant,MerchantStatus,ApiKey,ApiKeyAmbiente,Provider,ProviderCredential}.java`, `cripto/{EnvelopeCipher,ChaveMestra,Cifrado}.java`
- Test: `gateway-merchants/src/test/java/com/gateway/merchants/dominio/ApiKeyTest.java`, `cripto/EnvelopeCipherTest.java`

**Interfaces:**
- Produces:
  ```java
  enum MerchantStatus { ATIVO, SUSPENSO }
  record Merchant(MerchantId id, String nome, MerchantStatus status, Instant criadoEm, Instant atualizadoEm) { static Merchant novo(String nome); Merchant suspender(); Merchant ativar(); boolean ativo(); }
  enum ApiKeyAmbiente { LIVE, TEST; String prefixoTexto() /* "gk_live_" | "gk_test_" */ }
  record ApiKey(String id, MerchantId merchantId, ApiKeyAmbiente ambiente, String prefixo, String hash, boolean ativa, Instant expiraEm, Instant criadoEm) {
      record Emitida(ApiKey apiKey, Segredo chaveEmClaro) {}
      static Emitida emitir(MerchantId, ApiKeyAmbiente, String pepper);          // chave = prefixoTexto + Ulid.novo()
      static String hashDe(String chaveEmClaro, String pepper);                  // hex SHA-256(pepper || chave)
      static String prefixoDe(String chaveEmClaro);                              // 12 primeiros chars
      static Optional<ApiKeyAmbiente> ambienteDe(String chaveEmClaro);
      ApiKey revogar(); ApiKey comExpiracao(Instant); boolean valida(Instant agora); }
  enum Provider { ITAU, FAKE }
  record ProviderCredential(String id, MerchantId merchantId, Provider provider, ApiKeyAmbiente ambiente, Cifrado payload, boolean ativa, Instant criadoEm, Instant atualizadoEm)
  record Cifrado(byte[] nonce, byte[] textoCifrado, byte[] dekCifrada, byte[] dekNonce)     // envelope completo
  final class ChaveMestra { static ChaveMestra deBase64(String); static ChaveMestra aleatoriaParaTestes(); }
  final class EnvelopeCipher { EnvelopeCipher(ChaveMestra); Cifrado cifrar(byte[] claro, String aad); byte[] decifrar(Cifrado, String aad); }
  ```

- [ ] **Step 1: Testes**

`ApiKeyTest.java`:
```java
package com.gateway.merchants.dominio;

import static org.assertj.core.api.Assertions.*;
import com.gateway.kernel.ids.MerchantId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApiKeyTest {
  static final String PEPPER = "pepper-de-teste";

  @Test void emiteComPrefixoDoAmbienteEGuardaSoHash() {
    ApiKey.Emitida e = ApiKey.emitir(MerchantId.novo(), ApiKeyAmbiente.LIVE, PEPPER);
    String clara = e.chaveEmClaro().revelar();
    assertThat(clara).startsWith("gk_live_").hasSize(8 + 26);
    assertThat(e.apiKey().hash()).isEqualTo(ApiKey.hashDe(clara, PEPPER)).doesNotContain(clara);
    assertThat(e.apiKey().prefixo()).isEqualTo(clara.substring(0, 12));
    assertThat(e.apiKey().ativa()).isTrue();
  }

  @Test void hashDependeDoPepper() {
    assertThat(ApiKey.hashDe("gk_test_X", "a")).isNotEqualTo(ApiKey.hashDe("gk_test_X", "b"));
  }

  @Test void ambienteVemDoPrefixo() {
    assertThat(ApiKey.ambienteDe("gk_test_ABC")).contains(ApiKeyAmbiente.TEST);
    assertThat(ApiKey.ambienteDe("gk_live_ABC")).contains(ApiKeyAmbiente.LIVE);
    assertThat(ApiKey.ambienteDe("sk_ABC")).isEmpty();
  }

  @Test void revogadaOuExpiradaNaoVale() {
    ApiKey k = ApiKey.emitir(MerchantId.novo(), ApiKeyAmbiente.TEST, PEPPER).apiKey();
    Instant agora = Instant.now();
    assertThat(k.valida(agora)).isTrue();
    assertThat(k.revogar().valida(agora)).isFalse();
    assertThat(k.comExpiracao(agora.minusSeconds(1)).valida(agora)).isFalse();
  }
}
```

`EnvelopeCipherTest.java`:
```java
package com.gateway.merchants.cripto;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EnvelopeCipherTest {
  final EnvelopeCipher cipher = new EnvelopeCipher(ChaveMestra.aleatoriaParaTestes());

  @Test void cifraEDecifraComAad() {
    byte[] claro = "client_secret=abc".getBytes(StandardCharsets.UTF_8);
    Cifrado c = cipher.cifrar(claro, "merchant-1");
    assertThat(cipher.decifrar(c, "merchant-1")).isEqualTo(claro);
    assertThat(new String(c.textoCifrado(), StandardCharsets.ISO_8859_1)).doesNotContain("client_secret");
  }

  /** AAD = merchant_id: um payload copiado para outro merchant não decifra. */
  @Test void aadErradoFalha() {
    Cifrado c = cipher.cifrar("x".getBytes(), "merchant-1");
    assertThatThrownBy(() -> cipher.decifrar(c, "merchant-2")).isInstanceOf(SecurityException.class);
  }

  @Test void nonceNovoACadaCifra() {
    Cifrado a = cipher.cifrar("x".getBytes(), "m"), b = cipher.cifrar("x".getBytes(), "m");
    assertThat(a.nonce()).isNotEqualTo(b.nonce());
    assertThat(a.dekCifrada()).isNotEqualTo(b.dekCifrada());
  }

  @Test void chaveMestraErradaFalha() {
    Cifrado c = cipher.cifrar("x".getBytes(), "m");
    assertThatThrownBy(() -> new EnvelopeCipher(ChaveMestra.aleatoriaParaTestes()).decifrar(c, "m")).isInstanceOf(SecurityException.class);
  }

  @Test void chaveMestraPrecisaDe32Bytes() {
    assertThatThrownBy(() -> ChaveMestra.deBase64("AAAA")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-merchants -am test -Dtest='ApiKeyTest,EnvelopeCipherTest' -Dsurefire.failIfNoSpecifiedTests=false` → erro de compilação.

- [ ] **Step 3: Implementar o domínio**

`MerchantStatus.java`: `public enum MerchantStatus { ATIVO, SUSPENSO }`

`Merchant.java`:
```java
package com.gateway.merchants.dominio;

import com.gateway.kernel.ids.MerchantId;
import java.time.Instant;

public record Merchant(MerchantId id, String nome, MerchantStatus status, Instant criadoEm, Instant atualizadoEm) {
  public Merchant {
    if (nome == null || nome.isBlank()) throw new IllegalArgumentException("nome obrigatório");
  }
  public static Merchant novo(String nome) {
    Instant agora = Instant.now();
    return new Merchant(MerchantId.novo(), nome.trim(), MerchantStatus.ATIVO, agora, agora);
  }
  public Merchant suspender() { return new Merchant(id, nome, MerchantStatus.SUSPENSO, criadoEm, Instant.now()); }
  public Merchant ativar() { return new Merchant(id, nome, MerchantStatus.ATIVO, criadoEm, Instant.now()); }
  public boolean ativo() { return status == MerchantStatus.ATIVO; }
}
```

`ApiKeyAmbiente.java`:
```java
package com.gateway.merchants.dominio;

/** Ambiente da chave: {@code test} usa o FakePixProvider — o sandbox do próprio gateway. */
public enum ApiKeyAmbiente {
  LIVE("gk_live_"), TEST("gk_test_");
  private final String prefixoTexto;
  ApiKeyAmbiente(String p) { this.prefixoTexto = p; }
  public String prefixoTexto() { return prefixoTexto; }
}
```

`ApiKey.java`:
```java
package com.gateway.merchants.dominio;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.seguranca.Segredo;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * API key do merchant. Guardamos o hash, não a chave: quem lê o banco não consegue chamar a API.
 * O prefixo (12 chars: {@code gk_live_} + 4) existe só para achar a linha antes de comparar o hash
 * — e para o merchant reconhecer a chave no dashboard sem vê-la inteira.
 *
 * <p>SHA-256 com pepper e não bcrypt: a chave tem 26 chars aleatórios (130 bits), então força bruta
 * é impossível mesmo com hash rápido, e bcrypt custaria ~100 ms por requisição autenticada.
 */
public record ApiKey(String id, MerchantId merchantId, ApiKeyAmbiente ambiente, String prefixo, String hash,
                     boolean ativa, Instant expiraEm, Instant criadoEm) {

  public record Emitida(ApiKey apiKey, Segredo chaveEmClaro) {}

  public static Emitida emitir(MerchantId merchantId, ApiKeyAmbiente ambiente, String pepper) {
    String clara = ambiente.prefixoTexto() + Ulid.novo();
    ApiKey k = new ApiKey(Ulid.novo(), merchantId, ambiente, prefixoDe(clara), hashDe(clara, pepper), true, null, Instant.now());
    return new Emitida(k, Segredo.de(clara));
  }

  public static String hashDe(String chaveEmClaro, String pepper) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(pepper.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(md.digest(chaveEmClaro.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String prefixoDe(String chaveEmClaro) { return chaveEmClaro.substring(0, Math.min(12, chaveEmClaro.length())); }

  public static Optional<ApiKeyAmbiente> ambienteDe(String chaveEmClaro) {
    for (ApiKeyAmbiente a : values()) if (chaveEmClaro != null && chaveEmClaro.startsWith(a.prefixoTexto())) return Optional.of(a);
    return Optional.empty();
  }

  public ApiKey revogar() { return new ApiKey(id, merchantId, ambiente, prefixo, hash, false, expiraEm, criadoEm); }

  /** Rotação: a chave antiga ganha um prazo (até 24 h) em vez de morrer na hora. */
  public ApiKey comExpiracao(Instant quando) { return new ApiKey(id, merchantId, ambiente, prefixo, hash, ativa, quando, criadoEm); }

  public boolean valida(Instant agora) { return ativa && (expiraEm == null || agora.isBefore(expiraEm)); }
}
```

`Provider.java`: `public enum Provider { ITAU, FAKE }`

`ProviderCredential.java`:
```java
package com.gateway.merchants.dominio;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.merchants.cripto.Cifrado;
import java.time.Instant;

/**
 * Credencial do merchant num provider (client_id/secret/certificado do Itaú, por exemplo), sempre
 * cifrada. O domínio nunca vê o texto claro: quem decifra é o {@code ProviderCredentialService},
 * no momento da chamada ao banco, e só ele.
 */
public record ProviderCredential(String id, MerchantId merchantId, Provider provider, ApiKeyAmbiente ambiente,
                                 Cifrado payload, boolean ativa, Instant criadoEm, Instant atualizadoEm) {
  public static ProviderCredential nova(MerchantId m, Provider p, ApiKeyAmbiente a, Cifrado payload) {
    Instant agora = Instant.now();
    return new ProviderCredential(Ulid.novo(), m, p, a, payload, true, agora, agora);
  }
  public ProviderCredential comPayload(Cifrado novo) { return new ProviderCredential(id, merchantId, provider, ambiente, novo, ativa, criadoEm, Instant.now()); }
  public ProviderCredential desativar() { return new ProviderCredential(id, merchantId, provider, ambiente, payload, false, criadoEm, Instant.now()); }
  @Override public String toString() { return "ProviderCredential[" + id + ", " + merchantId.valor() + ", " + provider + ", " + ambiente + ", payload=***]"; }
}
```

- [ ] **Step 4: Implementar a cifra**

`Cifrado.java`:
```java
package com.gateway.merchants.cripto;

import java.util.Arrays;

/** Envelope: texto cifrado com a DEK + a DEK cifrada com a chave mestra. Tudo o que vai para o banco. */
public record Cifrado(byte[] nonce, byte[] textoCifrado, byte[] dekCifrada, byte[] dekNonce) {
  @Override public String toString() { return "Cifrado[***]"; }
  @Override public boolean equals(Object o) {
    return o instanceof Cifrado c && Arrays.equals(nonce, c.nonce) && Arrays.equals(textoCifrado, c.textoCifrado)
        && Arrays.equals(dekCifrada, c.dekCifrada) && Arrays.equals(dekNonce, c.dekNonce);
  }
  @Override public int hashCode() { return Arrays.hashCode(textoCifrado); }
}
```

`ChaveMestra.java`:
```java
package com.gateway.merchants.cripto;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * Chave mestra do envelope. No MVP vem de variável de ambiente ({@code GATEWAY_MASTER_KEY}, base64
 * de 32 bytes); na Fase 2 vem de um KMS — e a troca é só aqui, porque a DEK por merchant continua
 * igual: rotacionar a mestra é recifrar 32 bytes por merchant, não todos os payloads.
 */
public final class ChaveMestra {
  private final SecretKey chave;

  private ChaveMestra(byte[] bytes) {
    if (bytes.length != 32) throw new IllegalArgumentException("chave mestra precisa de 32 bytes, veio " + bytes.length);
    this.chave = new SecretKeySpec(bytes, "AES");
  }

  public static ChaveMestra deBase64(String base64) {
    if (base64 == null || base64.isBlank()) throw new IllegalArgumentException("GATEWAY_MASTER_KEY ausente");
    return new ChaveMestra(Base64.getDecoder().decode(base64.trim()));
  }

  public static ChaveMestra aleatoriaParaTestes() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return new ChaveMestra(b);
  }

  SecretKey chave() { return chave; }

  @Override public String toString() { return "ChaveMestra[***]"; }
}
```

`EnvelopeCipher.java`:
```java
package com.gateway.merchants.cripto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM em envelope: cada cifra gera uma DEK nova, cifra o payload com ela e cifra a DEK
 * com a chave mestra. GCM porque autentica (payload adulterado não decifra) e o AAD amarra o
 * payload ao merchant: copiar a linha cifrada de um merchant para outro falha na decifra.
 *
 * <p>Nonce de 12 bytes aleatório por operação. Com DEK nova por cifra, a chance de repetir
 * (nonce, chave) é zero na prática — o limite de 2^32 cifras por chave do GCM nunca é tocado.
 */
public final class EnvelopeCipher {
  private static final int TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;
  private final ChaveMestra mestra;
  private final SecureRandom random = new SecureRandom();

  public EnvelopeCipher(ChaveMestra mestra) { this.mestra = mestra; }

  public Cifrado cifrar(byte[] claro, String aad) {
    try {
      byte[] dekBytes = new byte[32];
      random.nextBytes(dekBytes);
      SecretKey dek = new SecretKeySpec(dekBytes, "AES");
      byte[] nonce = nonce();
      byte[] texto = gcm(Cipher.ENCRYPT_MODE, dek, nonce, aad, claro);
      byte[] dekNonce = nonce();
      byte[] dekCifrada = gcm(Cipher.ENCRYPT_MODE, mestra.chave(), dekNonce, aad, dekBytes);
      return new Cifrado(nonce, texto, dekCifrada, dekNonce);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("falha ao cifrar", e);
    }
  }

  public byte[] decifrar(Cifrado c, String aad) {
    try {
      byte[] dekBytes = gcm(Cipher.DECRYPT_MODE, mestra.chave(), c.dekNonce(), aad, c.dekCifrada());
      return gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(dekBytes, "AES"), c.nonce(), aad, c.textoCifrado());
    } catch (GeneralSecurityException e) {
      // Mensagem sem detalhe de propósito: "tag inválida" vs "chave errada" é oráculo para ninguém.
      throw new SecurityException("falha ao decifrar");
    }
  }

  private byte[] nonce() { byte[] n = new byte[NONCE_BYTES]; random.nextBytes(n); return n; }

  private static byte[] gcm(int modo, SecretKey chave, byte[] nonce, String aad, byte[] entrada) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(modo, chave, new GCMParameterSpec(TAG_BITS, nonce));
    cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
    return cipher.doFinal(entrada);
  }
}
```

- [ ] **Step 5: Rodar e ver passar** — mesmo comando do Step 2 → verde.

- [ ] **Step 6: Commit**

```bash
git add gateway-merchants
git commit -m "feat(merchants): dominio de merchant, api key com hash e credencial cifrada em envelope

SHA-256 com pepper e nao bcrypt porque a chave tem 130 bits aleatorios
e bcrypt custaria 100 ms por requisicao. AES-GCM com AAD=merchant_id
para uma linha copiada entre merchants nao decifrar."
```

---

### Task 4: `merchants` — persistência, migration V100, services e configuração do módulo

**Files:**
- Create: `gateway-merchants/src/main/resources/db/migration/merchants/V100__merchants.sql`; `repositorio/{MerchantEntity,MerchantJpaRepository,MerchantRepository,MerchantRepositoryImpl,ApiKeyEntity,ApiKeyJpaRepository,ApiKeyRepository,ApiKeyRepositoryImpl,ProviderCredentialEntity,ProviderCredentialJpaRepository,ProviderCredentialRepository,ProviderCredentialRepositoryImpl}.java`; `servico/{MerchantService,ApiKeyService,ProviderCredentialService,MerchantsProperties}.java`; `MerchantsConfiguration.java`
- Test: `gateway-merchants/src/test/java/com/gateway/merchants/TestApp.java` (harness), `servico/MerchantsIntegrationTest.java`

**Interfaces:**
- Produces:
  ```java
  @ConfigurationProperties("gateway") record MerchantsProperties(String masterKey, String apiKeyPepper, Duration apiKeyRotationOverlap /* PT24H */)
  class MerchantService { Merchant criar(String nome); Merchant buscar(MerchantId); Merchant suspender(MerchantId); Merchant ativar(MerchantId); List<Merchant> listar(); }
  class ApiKeyService {
      ApiKey.Emitida emitir(MerchantId, ApiKeyAmbiente);                       // se já houver 2 ativas no ambiente → DominioException("LIMITE_DE_CHAVES")
      ApiKey.Emitida rotacionar(MerchantId, ApiKeyAmbiente);                   // emite nova; as antigas ativas ganham expiraEm = agora + overlap
      Optional<Autenticada> autenticar(String chaveEmClaro);                    // record Autenticada(MerchantId merchantId, ApiKeyAmbiente ambiente, String apiKeyId); só merchant ATIVO
      void revogar(MerchantId, String apiKeyId); List<ApiKey> listar(MerchantId); }
  class ProviderCredentialService {
      ProviderCredential gravar(MerchantId, Provider, ApiKeyAmbiente, byte[] payloadClaro);   // upsert por (merchant, provider, ambiente); cifra com AAD=merchant id
      Optional<byte[]> decifrar(MerchantId, Provider, ApiKeyAmbiente);                        // só ativa
      List<ProviderCredential> listar(MerchantId); }
  @Configuration MerchantsConfiguration — @Import dos RepositoryImpl e @Bean dos services, EnvelopeCipher, ChaveMestra; @EnableConfigurationProperties(MerchantsProperties)
  ```
  Repositórios: `MerchantRepository { save; findById(MerchantId); findAll }`, `ApiKeyRepository { save; findByPrefixo(String); findAtivasPorMerchantEAmbiente(MerchantId, ApiKeyAmbiente); findByMerchant(MerchantId); findById(String) }`, `ProviderCredentialRepository { save; find(MerchantId, Provider, ApiKeyAmbiente); findByMerchant(MerchantId) }`.

- [ ] **Step 1: Migration**

`V100__merchants.sql`:
```sql
-- Modulo merchants: quem e o cliente e com que credenciais ele fala com os bancos (modelo A da spec).
-- Schema proprio; nenhum outro modulo le estas tabelas — conversa por interface Java.

CREATE TABLE merchants (
    id            CHAR(26)     PRIMARY KEY,           -- ULID
    nome          VARCHAR(200) NOT NULL,
    status        VARCHAR(20)  NOT NULL,
    criado_em     TIMESTAMPTZ  NOT NULL,
    atualizado_em TIMESTAMPTZ  NOT NULL
);

-- So o hash: quem le o banco nao consegue chamar a API. prefixo = 12 primeiros chars, para
-- achar a linha antes de comparar o hash e para o merchant reconhecer a chave no dashboard.
CREATE TABLE api_keys (
    id          CHAR(26)    PRIMARY KEY,
    merchant_id CHAR(26)    NOT NULL REFERENCES merchants (id),
    ambiente    VARCHAR(10) NOT NULL,                  -- LIVE | TEST
    prefixo     VARCHAR(12) NOT NULL,
    hash        CHAR(64)    NOT NULL UNIQUE,           -- hex SHA-256(pepper || chave)
    ativa       BOOLEAN     NOT NULL DEFAULT true,
    expira_em   TIMESTAMPTZ,                            -- rotacao: a antiga vale ate aqui
    criado_em   TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_api_keys_prefixo ON api_keys (prefixo);
CREATE INDEX idx_api_keys_merchant ON api_keys (merchant_id, ambiente, ativa);

-- Envelope AES-256-GCM: payload cifrado com DEK por linha, DEK cifrada com a chave mestra.
-- AAD = merchant_id, entao copiar uma linha para outro merchant nao decifra.
CREATE TABLE provider_credentials (
    id            CHAR(26)    PRIMARY KEY,
    merchant_id   CHAR(26)    NOT NULL REFERENCES merchants (id),
    provider      VARCHAR(20) NOT NULL,                -- ITAU | FAKE
    ambiente      VARCHAR(10) NOT NULL,
    nonce         BYTEA       NOT NULL,
    texto_cifrado BYTEA       NOT NULL,
    dek_cifrada   BYTEA       NOT NULL,
    dek_nonce     BYTEA       NOT NULL,
    ativa         BOOLEAN     NOT NULL DEFAULT true,
    criado_em     TIMESTAMPTZ NOT NULL,
    atualizado_em TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_provider_credentials UNIQUE (merchant_id, provider, ambiente)
);
```

- [ ] **Step 2: Harness e teste de integração**

`gateway-merchants/src/test/java/com/gateway/merchants/TestApp.java`:
```java
package com.gateway.merchants;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Contexto mínimo do módulo: só o que MerchantsConfiguration importa. Sem scan — como o app faz. */
@SpringBootApplication(scanBasePackages = "com.gateway.merchants.nada")
@Import(MerchantsConfiguration.class)
public class TestApp {}
```

`src/test/resources/application.yml` (do módulo):
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    open-in-view: false
  flyway:
    schemas: merchants
    locations: classpath:db/migration/merchants
gateway:
  master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="   # 32 bytes zero: só teste
  api-key-pepper: pepper-de-teste
```

`servico/MerchantsIntegrationTest.java`:
```java
package com.gateway.merchants.servico;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.erros.DominioException;
import com.gateway.merchants.TestApp;
import com.gateway.merchants.dominio.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
class MerchantsIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired MerchantService merchants;
  @Autowired ApiKeyService apiKeys;
  @Autowired ProviderCredentialService credenciais;
  @Autowired JdbcTemplate jdbc;

  @Test
  void emiteAutenticaERotaciona() {
    Merchant m = merchants.criar("Loja Acme");
    ApiKey.Emitida e1 = apiKeys.emitir(m.id(), ApiKeyAmbiente.LIVE);

    ApiKeyService.Autenticada a = apiKeys.autenticar(e1.chaveEmClaro().revelar()).orElseThrow();
    assertThat(a.merchantId()).isEqualTo(m.id());
    assertThat(a.ambiente()).isEqualTo(ApiKeyAmbiente.LIVE);
    assertThat(apiKeys.autenticar("gk_live_NAOEXISTE00000000000000000")).isEmpty();
    assertThat(apiKeys.autenticar("lixo")).isEmpty();

    ApiKey.Emitida e2 = apiKeys.rotacionar(m.id(), ApiKeyAmbiente.LIVE);
    // as duas valem durante a sobreposição
    assertThat(apiKeys.autenticar(e1.chaveEmClaro().revelar())).isPresent();
    assertThat(apiKeys.autenticar(e2.chaveEmClaro().revelar())).isPresent();
    ApiKey antiga = apiKeys.listar(m.id()).stream().filter(k -> k.id().equals(e1.apiKey().id())).findFirst().orElseThrow();
    assertThat(antiga.expiraEm()).isAfter(Instant.now());
  }

  @Test
  void limiteDeDuasAtivasPorAmbiente() {
    Merchant m = merchants.criar("Loja B");
    apiKeys.emitir(m.id(), ApiKeyAmbiente.TEST);
    apiKeys.emitir(m.id(), ApiKeyAmbiente.TEST);
    assertThatThrownBy(() -> apiKeys.emitir(m.id(), ApiKeyAmbiente.TEST))
        .isInstanceOf(DominioException.class).extracting("codigo").isEqualTo("LIMITE_DE_CHAVES");
  }

  @Test
  void merchantSuspensoNaoAutentica() {
    Merchant m = merchants.criar("Loja C");
    ApiKey.Emitida e = apiKeys.emitir(m.id(), ApiKeyAmbiente.LIVE);
    merchants.suspender(m.id());
    assertThat(apiKeys.autenticar(e.chaveEmClaro().revelar())).isEmpty();
  }

  @Test
  void bancoNaoContemChaveNemCredencialEmClaro() {
    Merchant m = merchants.criar("Loja D");
    ApiKey.Emitida e = apiKeys.emitir(m.id(), ApiKeyAmbiente.LIVE);
    byte[] cred = "{\"client_id\":\"abc123\",\"client_secret\":\"segredo-do-itau\"}".getBytes(StandardCharsets.UTF_8);
    credenciais.gravar(m.id(), Provider.ITAU, ApiKeyAmbiente.LIVE, cred);

    String dumpApiKeys = String.join("|", jdbc.queryForList("SELECT hash || prefixo FROM merchants.api_keys", String.class));
    assertThat(dumpApiKeys).doesNotContain(e.chaveEmClaro().revelar());
    byte[] textoCifrado = jdbc.queryForObject("SELECT texto_cifrado FROM merchants.provider_credentials WHERE merchant_id = ?", byte[].class, m.id().valor());
    assertThat(new String(textoCifrado, StandardCharsets.ISO_8859_1)).doesNotContain("segredo-do-itau").doesNotContain("abc123");

    assertThat(credenciais.decifrar(m.id(), Provider.ITAU, ApiKeyAmbiente.LIVE)).contains(cred);
    assertThat(credenciais.decifrar(m.id(), Provider.ITAU, ApiKeyAmbiente.TEST)).isEmpty();
  }

  @Test
  void gravarDeNovoSubstituiOPayload() {
    Merchant m = merchants.criar("Loja E");
    credenciais.gravar(m.id(), Provider.ITAU, ApiKeyAmbiente.LIVE, "v1".getBytes());
    credenciais.gravar(m.id(), Provider.ITAU, ApiKeyAmbiente.LIVE, "v2".getBytes());
    assertThat(credenciais.listar(m.id())).hasSize(1);
    assertThat(credenciais.decifrar(m.id(), Provider.ITAU, ApiKeyAmbiente.LIVE)).contains("v2".getBytes());
  }
}
```

- [ ] **Step 3: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-merchants -am test -Dtest=MerchantsIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → erro de compilação.

- [ ] **Step 4: Entidades e repositórios**

Padrão para as três entidades (o mesmo da `webhook-delivery`: entidade `package-private` com Lombok ou getters/setters à mão — sem Lombok aqui, para o módulo não depender de annotation processor; escreva getters/setters):

`MerchantEntity.java`:
```java
package com.gateway.merchants.repositorio;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "merchants", schema = "merchants")
class MerchantEntity {
  @Id @Column(name = "id", length = 26, nullable = false) String id;
  @Column(name = "nome", nullable = false, length = 200) String nome;
  @Column(name = "status", nullable = false, length = 20) String status;
  @Column(name = "criado_em", nullable = false) Instant criadoEm;
  @Column(name = "atualizado_em", nullable = false) Instant atualizadoEm;
  protected MerchantEntity() {}
}
```
Campos package-private acessados direto pelo mapper no mesmo pacote (o Hibernate aceita acesso por campo com `@Id` em campo).

`ApiKeyEntity.java`: colunas `id, merchant_id, ambiente, prefixo, hash, ativa, expira_em, criado_em`, mesma forma.
`ProviderCredentialEntity.java`: colunas `id, merchant_id, provider, ambiente, nonce (byte[]), texto_cifrado (byte[]), dek_cifrada (byte[]), dek_nonce (byte[]), ativa, criado_em, atualizado_em`.

JPA repositories:
```java
interface MerchantJpaRepository extends JpaRepository<MerchantEntity, String> {}
interface ApiKeyJpaRepository extends JpaRepository<ApiKeyEntity, String> {
  List<ApiKeyEntity> findByPrefixo(String prefixo);
  List<ApiKeyEntity> findByMerchantIdAndAmbienteAndAtivaTrue(String merchantId, String ambiente);
  List<ApiKeyEntity> findByMerchantIdOrderByCriadoEmAsc(String merchantId);
}
interface ProviderCredentialJpaRepository extends JpaRepository<ProviderCredentialEntity, String> {
  Optional<ProviderCredentialEntity> findByMerchantIdAndProviderAndAmbiente(String merchantId, String provider, String ambiente);
  List<ProviderCredentialEntity> findByMerchantId(String merchantId);
}
```

Interfaces de domínio (públicas) e `*RepositoryImpl` (`public`, `@Repository`, construtor público, mapeando entidade ↔ record com `MerchantId`, enums e `Cifrado`). `save` faz `findById(...).orElseGet(new)` e copia campos, como em `WebhookEndpointRepositoryImpl` da lib.

- [ ] **Step 5: Services e configuração**

`MerchantsProperties.java`:
```java
package com.gateway.merchants.servico;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record MerchantsProperties(String masterKey, String apiKeyPepper, Duration apiKeyRotationOverlap) {
  public MerchantsProperties {
    if (apiKeyRotationOverlap == null) apiKeyRotationOverlap = Duration.ofHours(24);
    if (apiKeyPepper == null || apiKeyPepper.isBlank()) throw new IllegalArgumentException("gateway.api-key-pepper ausente");
  }
}
```

`MerchantService.java`:
```java
package com.gateway.merchants.servico;

import com.gateway.kernel.erros.NaoEncontradoException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.dominio.Merchant;
import com.gateway.merchants.repositorio.MerchantRepository;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class MerchantService {
  private final MerchantRepository repo;
  public MerchantService(MerchantRepository repo) { this.repo = repo; }

  @Transactional public Merchant criar(String nome) { return repo.save(Merchant.novo(nome)); }
  @Transactional(readOnly = true) public Merchant buscar(MerchantId id) { return repo.findById(id).orElseThrow(() -> new NaoEncontradoException("merchant", id.valor())); }
  @Transactional public Merchant suspender(MerchantId id) { return repo.save(buscar(id).suspender()); }
  @Transactional public Merchant ativar(MerchantId id) { return repo.save(buscar(id).ativar()); }
  @Transactional(readOnly = true) public List<Merchant> listar() { return repo.findAll(); }
}
```

`ApiKeyService.java`:
```java
package com.gateway.merchants.servico;

import com.gateway.kernel.erros.DominioException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.dominio.ApiKey;
import com.gateway.merchants.dominio.ApiKeyAmbiente;
import com.gateway.merchants.repositorio.ApiKeyRepository;
import com.gateway.merchants.repositorio.MerchantRepository;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

public class ApiKeyService {
  public record Autenticada(MerchantId merchantId, ApiKeyAmbiente ambiente, String apiKeyId) {}

  private static final int MAX_ATIVAS = 2;
  private final ApiKeyRepository repo;
  private final MerchantRepository merchants;
  private final MerchantsProperties props;

  public ApiKeyService(ApiKeyRepository repo, MerchantRepository merchants, MerchantsProperties props) {
    this.repo = repo; this.merchants = merchants; this.props = props;
  }

  /** Duas ativas no máximo: é o que a rotação com sobreposição precisa, e mais que isso é chave esquecida. */
  @Transactional
  public ApiKey.Emitida emitir(MerchantId merchantId, ApiKeyAmbiente ambiente) {
    if (repo.findAtivasPorMerchantEAmbiente(merchantId, ambiente).size() >= MAX_ATIVAS) {
      throw new DominioException("LIMITE_DE_CHAVES", "já existem " + MAX_ATIVAS + " chaves ativas em " + ambiente + "; revogue ou rotacione");
    }
    ApiKey.Emitida e = ApiKey.emitir(merchantId, ambiente, props.apiKeyPepper());
    repo.save(e.apiKey());
    return e;
  }

  /** Emite a nova e dá prazo às antigas: o merchant troca a chave quando puder, sem janela combinada a dedo. */
  @Transactional
  public ApiKey.Emitida rotacionar(MerchantId merchantId, ApiKeyAmbiente ambiente) {
    Instant limite = Instant.now().plus(props.apiKeyRotationOverlap());
    for (ApiKey k : repo.findAtivasPorMerchantEAmbiente(merchantId, ambiente)) {
      repo.save(k.comExpiracao(k.expiraEm() == null || k.expiraEm().isAfter(limite) ? limite : k.expiraEm()));
    }
    // As antigas continuam "ativas" com prazo, então o teto de 2 conta com elas: rotacionar com 2 ativas
    // deve funcionar. Por isso a emissão aqui não passa pelo teto.
    ApiKey.Emitida e = ApiKey.emitir(merchantId, ambiente, props.apiKeyPepper());
    repo.save(e.apiKey());
    return e;
  }

  /**
   * Busca pelo prefixo e compara o hash em tempo constante. O prefixo reduz a busca a uma linha
   * (ou a poucas, se dois merchants sortearem os mesmos 4 chars — daí a comparação completa).
   */
  @Transactional(readOnly = true)
  public Optional<Autenticada> autenticar(String chaveEmClaro) {
    if (ApiKey.ambienteDe(chaveEmClaro).isEmpty()) return Optional.empty();
    byte[] hash = ApiKey.hashDe(chaveEmClaro, props.apiKeyPepper()).getBytes(StandardCharsets.UTF_8);
    Instant agora = Instant.now();
    return repo.findByPrefixo(ApiKey.prefixoDe(chaveEmClaro)).stream()
        .filter(k -> MessageDigest.isEqual(hash, k.hash().getBytes(StandardCharsets.UTF_8)))
        .filter(k -> k.valida(agora))
        .filter(k -> merchants.findById(k.merchantId()).map(m -> m.ativo()).orElse(false))
        .findFirst()
        .map(k -> new Autenticada(k.merchantId(), k.ambiente(), k.id()));
  }

  @Transactional
  public void revogar(MerchantId merchantId, String apiKeyId) {
    repo.findById(apiKeyId).filter(k -> k.merchantId().equals(merchantId)).map(ApiKey::revogar).ifPresent(repo::save);
  }

  @Transactional(readOnly = true)
  public List<ApiKey> listar(MerchantId merchantId) { return repo.findByMerchant(merchantId); }
}
```

`ProviderCredentialService.java`:
```java
package com.gateway.merchants.servico;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.cripto.Cifrado;
import com.gateway.merchants.cripto.EnvelopeCipher;
import com.gateway.merchants.dominio.ApiKeyAmbiente;
import com.gateway.merchants.dominio.Provider;
import com.gateway.merchants.dominio.ProviderCredential;
import com.gateway.merchants.repositorio.ProviderCredentialRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** Único lugar que decifra credenciais — e só devolve bytes, nunca guarda em cache. */
public class ProviderCredentialService {
  private final ProviderCredentialRepository repo;
  private final EnvelopeCipher cipher;

  public ProviderCredentialService(ProviderCredentialRepository repo, EnvelopeCipher cipher) { this.repo = repo; this.cipher = cipher; }

  @Transactional
  public ProviderCredential gravar(MerchantId m, Provider p, ApiKeyAmbiente a, byte[] payloadClaro) {
    Cifrado c = cipher.cifrar(payloadClaro, m.valor());
    ProviderCredential cred = repo.find(m, p, a).map(x -> x.comPayload(c)).orElseGet(() -> ProviderCredential.nova(m, p, a, c));
    return repo.save(cred);
  }

  @Transactional(readOnly = true)
  public Optional<byte[]> decifrar(MerchantId m, Provider p, ApiKeyAmbiente a) {
    return repo.find(m, p, a).filter(ProviderCredential::ativa).map(c -> cipher.decifrar(c.payload(), m.valor()));
  }

  @Transactional(readOnly = true)
  public List<ProviderCredential> listar(MerchantId m) { return repo.findByMerchant(m); }
}
```

`MerchantsConfiguration.java`:
```java
package com.gateway.merchants;

import com.gateway.merchants.cripto.ChaveMestra;
import com.gateway.merchants.cripto.EnvelopeCipher;
import com.gateway.merchants.repositorio.*;
import com.gateway.merchants.servico.*;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * O que o módulo expõe, escolhido um a um. Sem component scan: o app importa esta classe e sabe
 * exatamente o que entrou. {@code @EntityScan}/{@code @EnableJpaRepositories} apontam só para o
 * pacote deste módulo — cada módulo declara os seus, e o Boot junta.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantsProperties.class)
@EntityScan("com.gateway.merchants.repositorio")
@EnableJpaRepositories("com.gateway.merchants.repositorio")
@Import({MerchantRepositoryImpl.class, ApiKeyRepositoryImpl.class, ProviderCredentialRepositoryImpl.class})
public class MerchantsConfiguration {
  @Bean public ChaveMestra chaveMestra(MerchantsProperties p) { return ChaveMestra.deBase64(p.masterKey()); }
  @Bean public EnvelopeCipher envelopeCipher(ChaveMestra m) { return new EnvelopeCipher(m); }
  @Bean public MerchantService merchantService(MerchantRepository r) { return new MerchantService(r); }
  @Bean public ApiKeyService apiKeyService(ApiKeyRepository r, MerchantRepository m, MerchantsProperties p) { return new ApiKeyService(r, m, p); }
  @Bean public ProviderCredentialService providerCredentialService(ProviderCredentialRepository r, EnvelopeCipher c) { return new ProviderCredentialService(r, c); }
}
```
**Atenção (ponto que a lib `webhook-delivery` documenta):** ao declarar `@EntityScan`/`@EnableJpaRepositories` aqui, o Boot deixa de usar o scan automático — e a lib registra o pacote dela via `AutoConfigurationPackages`, que `@EntityScan` explícito **ignora**. Na Task 6 o `app` vai verificar isso: se as entidades da lib não forem encontradas, a solução é o `app` declarar `@EntityScan({"com.gateway.merchants.repositorio", "com.barrier.webhookdelivery.repository"})` e `@EnableJpaRepositories` idem, e remover as anotações daqui (registre a decisão no relatório). O teste `MerchantsIntegrationTest` roda sem a lib, então aqui funciona de qualquer forma.

- [ ] **Step 6: Rodar e ver passar** — comando do Step 3 → 5 testes verdes.

- [ ] **Step 7: Commit**

```bash
git add gateway-merchants
git commit -m "feat(merchants): persistencia, migration V100 e servicos de merchant, api key e credencial

Autenticacao busca por prefixo e compara hash em tempo constante;
rotacao da prazo as chaves antigas em vez de mata-las. O teste prova
que o dump do banco nao contem chave nem credencial em claro."
```

---

### Task 5: `app` — montagem, ArchUnit, autenticação por API key, admin key, rate limit, erros

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/{AppConfiguration,seguranca/MerchantContext,seguranca/ApiKeyAuthFilter,seguranca/AdminKeyFilter,seguranca/RateLimitFilter,api/ErroHandler,api/MeController,api/admin/MerchantsAdminController,api/admin/dto/*}.java`
- Modify: `GatewayApplication.java` (`@Import(MerchantsConfiguration.class)`)
- Test: `gateway-app/src/test/java/com/gateway/app/arquitetura/ArquiteturaTest.java`, `gateway-app/src/test/java/com/gateway/app/AutenticacaoIntegrationTest.java`, `src/test/resources/application-test.yml`, `src/test/resources/archunit.properties`

**Interfaces:**
- Produces:
  - `MerchantContext` — `record Atual(MerchantId merchantId, ApiKeyAmbiente ambiente, String apiKeyId)`; `static Atual atual()` lê `RequestContextHolder`/atributo da request; lançar `IllegalStateException` fora de request autenticada.
  - Rotas: `POST /v1/admin/merchants {nome}` → 201 `{id, nome, status}`; `POST /v1/admin/merchants/{id}/api-keys {ambiente}` → 201 `{id, prefixo, ambiente, chave, aviso}` (a chave aparece UMA vez); `POST /v1/admin/merchants/{id}/api-keys/rotate {ambiente}`; `DELETE /v1/admin/merchants/{id}/api-keys/{keyId}` → 204; `PUT /v1/admin/merchants/{id}/providers/{provider}/credentials {ambiente, payload: {...json livre...}}` → 204 (payload serializado e cifrado; nunca devolvido); `GET /v1/me` → `{merchant_id, nome, ambiente}`.
  - Erros: `DominioException` → 422 `ProblemDetail{type: "urn:gateway:"+codigo, detail}`; `NaoEncontradoException` → 404; `IllegalArgumentException` → 400; sem API key/inválida → 401 `{"type":"urn:gateway:NAO_AUTENTICADO"}`; admin key errada → 403; rate limit → 429 com `Retry-After`.

- [ ] **Step 1: ArchUnit**

```java
package com.gateway.app.arquitetura;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** A fronteira da spec §2, cobrada por teste. Cada regra com nome, para a falha dizer qual caiu. */
@AnalyzeClasses(packages = "com.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArquiteturaTest {

  @ArchTest
  static void o_import_enxerga_os_modulos(JavaClasses classes) {
    assertThat(classes.size()).as("ArchUnit importou classes de menos; a regra passaria vacuamente").isGreaterThan(30);
  }

  @ArchTest
  static final ArchRule kernel_nao_importa_nada =
      noClasses().that().resideInAPackage("com.gateway.kernel..")
          .should().dependOnClassesThat().resideInAnyPackage("com.gateway.merchants..", "com.gateway.orders..", "com.gateway.payments..",
              "com.gateway.providers..", "com.gateway.app..", "org.springframework..", "jakarta.persistence..", "com.barrier..");

  @ArchTest
  static final ArchRule ninguem_importa_app =
      noClasses().that().resideOutsideOfPackage("com.gateway.app..").should().dependOnClassesThat().resideInAPackage("com.gateway.app..");

  @ArchTest
  static final ArchRule modulos_de_negocio_nao_se_importam =
      noClasses().that().resideInAPackage("com.gateway.merchants..")
          .should().dependOnClassesThat().resideInAnyPackage("com.gateway.orders..", "com.gateway.payments..", "com.gateway.providers..");

  @ArchTest
  static final ArchRule so_payments_conhece_providers =
      noClasses().that().resideOutsideOfPackages("com.gateway.payments..", "com.gateway.providers..", "com.gateway.app..")
          .should().dependOnClassesThat().resideInAPackage("com.gateway.providers..");

  @ArchTest
  static final ArchRule entidades_jpa_sao_package_private =
      classes().that().areAnnotatedWith(jakarta.persistence.Entity.class).should().bePackagePrivate();

  @ArchTest
  static final ArchRule dominio_sem_spring_nem_jpa =
      noClasses().that().resideInAPackage("..dominio..")
          .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..");
}
```
`archunit.properties`: `archRule.failOnEmptyShould=false`. As regras sobre `orders/payments/providers` ficam escritas agora e passam vacuamente até os Planos B/C — é o objetivo.

- [ ] **Step 2: Teste de integração de autenticação e admin**

`src/test/resources/application-test.yml`:
```yaml
gateway:
  admin-key: admin-de-teste
  master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
  api-key-pepper: pepper-de-teste
  rate-limit:
    requests-per-minute: 5
spring:
  flyway:
    schemas: merchants
    locations: classpath:db/migration/merchants
```

`AutenticacaoIntegrationTest.java`:
```java
package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AutenticacaoIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired TestRestTemplate http;

  private HttpHeaders admin() { HttpHeaders h = new HttpHeaders(); h.set("X-Admin-Key", "admin-de-teste"); h.setContentType(MediaType.APPLICATION_JSON); return h; }
  private HttpHeaders bearer(String chave) { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(chave); return h; }

  @SuppressWarnings("unchecked")
  private Map<String, Object> criaMerchantEChave(String nome, String ambiente) {
    ResponseEntity<Map> m = http.postForEntity("/v1/admin/merchants", new HttpEntity<>(Map.of("nome", nome), admin()), Map.class);
    assertThat(m.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) m.getBody().get("id");
    ResponseEntity<Map> k = http.postForEntity("/v1/admin/merchants/" + id + "/api-keys", new HttpEntity<>(Map.of("ambiente", ambiente), admin()), Map.class);
    assertThat(k.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> corpo = k.getBody();
    corpo.put("merchant_id", id);
    return corpo;
  }

  @Test
  void adminSemChaveEh403ESemApiKeyEh401() {
    assertThat(http.postForEntity("/v1/admin/merchants", new HttpEntity<>(Map.of("nome", "x")), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(http.getForEntity("/v1/me", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(http.exchange("/v1/me", HttpMethod.GET, new HttpEntity<>(bearer("gk_live_INVALIDA0000000000000000000")), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void chaveEmitidaAutenticaEMeDevolveOMerchant() {
    Map<String, Object> k = criaMerchantEChave("Loja Acme", "TEST");
    String chave = (String) k.get("chave");
    assertThat(chave).startsWith("gk_test_");
    ResponseEntity<Map> me = http.exchange("/v1/me", HttpMethod.GET, new HttpEntity<>(bearer(chave)), Map.class);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(me.getBody()).containsEntry("merchant_id", k.get("merchant_id")).containsEntry("ambiente", "TEST").containsEntry("nome", "Loja Acme");
  }

  @Test
  void chaveRevogadaDeixaDeValer() {
    Map<String, Object> k = criaMerchantEChave("Loja B", "LIVE");
    http.exchange("/v1/admin/merchants/" + k.get("merchant_id") + "/api-keys/" + k.get("id"), HttpMethod.DELETE, new HttpEntity<>(admin()), Void.class);
    assertThat(http.exchange("/v1/me", HttpMethod.GET, new HttpEntity<>(bearer((String) k.get("chave"))), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rateLimitPorChaveDevolve429ComRetryAfter() {
    Map<String, Object> k = criaMerchantEChave("Loja C", "TEST");
    HttpEntity<Void> req = new HttpEntity<>(bearer((String) k.get("chave")));
    for (int i = 0; i < 5; i++) assertThat(http.exchange("/v1/me", HttpMethod.GET, req, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    ResponseEntity<String> r = http.exchange("/v1/me", HttpMethod.GET, req, String.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(r.getHeaders().getFirst("Retry-After")).isNotBlank();
  }

  @Test
  void credencialDeProviderEhAceitaENuncaDevolvida() {
    Map<String, Object> k = criaMerchantEChave("Loja D", "LIVE");
    ResponseEntity<String> r = http.exchange("/v1/admin/merchants/" + k.get("merchant_id") + "/providers/ITAU/credentials", HttpMethod.PUT,
        new HttpEntity<>(Map.of("ambiente", "LIVE", "payload", Map.of("client_id", "abc", "client_secret", "segredo")), admin()), String.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(r.getBody()).isNull();
  }

  @Test
  void errosDeDominioViramProblemDetail() {
    Map<String, Object> k = criaMerchantEChave("Loja E", "TEST");
    http.postForEntity("/v1/admin/merchants/" + k.get("merchant_id") + "/api-keys", new HttpEntity<>(Map.of("ambiente", "TEST"), admin()), Map.class);
    ResponseEntity<Map> terceira = http.postForEntity("/v1/admin/merchants/" + k.get("merchant_id") + "/api-keys", new HttpEntity<>(Map.of("ambiente", "TEST"), admin()), Map.class);
    assertThat(terceira.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(terceira.getBody()).containsEntry("type", "urn:gateway:LIMITE_DE_CHAVES");
  }
}
```

- [ ] **Step 3: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-app -am test -Dtest='ArquiteturaTest,AutenticacaoIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` → erro de compilação (controllers inexistentes).

- [ ] **Step 4: Implementar a borda**

`GatewayApplication`: adicione `@Import(MerchantsConfiguration.class)`.

`AppConfiguration.java`:
```java
package com.gateway.app;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppConfiguration.AppProperties.class)
public class AppConfiguration {
  @ConfigurationProperties(prefix = "gateway")
  public record AppProperties(String adminKey, RateLimit rateLimit) {
    public AppProperties { if (rateLimit == null) rateLimit = new RateLimit(600); }
    public record RateLimit(int requestsPerMinute) { public RateLimit { if (requestsPerMinute <= 0) requestsPerMinute = 600; } }
  }
}
```
(`gateway.master-key`/`api-key-pepper` são lidas pela `MerchantsProperties` com o mesmo prefixo; dois records `@ConfigurationProperties` no mesmo prefixo funcionam — cada um pega os campos que declara.)

`seguranca/MerchantContext.java`:
```java
package com.gateway.app.seguranca;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.dominio.ApiKeyAmbiente;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Quem está chamando. Atributo da request e não ThreadLocal próprio: com virtual threads, o que importa é a request. */
public final class MerchantContext {
  public record Atual(MerchantId merchantId, ApiKeyAmbiente ambiente, String apiKeyId) {}
  static final String ATRIBUTO = MerchantContext.class.getName();

  private MerchantContext() {}

  static void definir(HttpServletRequest req, Atual atual) { req.setAttribute(ATRIBUTO, atual); }

  public static Atual atual() {
    var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    Object a = attrs == null ? null : attrs.getRequest().getAttribute(ATRIBUTO);
    if (a == null) throw new IllegalStateException("sem merchant autenticado nesta request");
    return (Atual) a;
  }
}
```

`seguranca/ApiKeyAuthFilter.java`:
```java
package com.gateway.app.seguranca;

import com.gateway.merchants.servico.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Autentica {@code Authorization: Bearer gk_…} em tudo sob /v1/ que não seja /v1/admin/** nem
 * /v1/providers/** (webhooks de entrada, autenticados pelo provider) nem actuator.
 */
@Component
@Order(20)
public class ApiKeyAuthFilter extends OncePerRequestFilter {
  private final ApiKeyService apiKeys;
  public ApiKeyAuthFilter(ApiKeyService apiKeys) { this.apiKeys = apiKeys; }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest req) {
    String p = req.getRequestURI();
    return !p.startsWith("/v1/") || p.startsWith("/v1/admin/") || p.startsWith("/v1/providers/");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
    String auth = req.getHeader("Authorization");
    if (auth == null || !auth.startsWith("Bearer ")) { Problemas.escrever(res, 401, "NAO_AUTENTICADO", "informe Authorization: Bearer gk_…"); return; }
    var atual = apiKeys.autenticar(auth.substring(7).trim());
    if (atual.isEmpty()) { Problemas.escrever(res, 401, "NAO_AUTENTICADO", "api key inválida, revogada ou merchant suspenso"); return; }
    MerchantContext.definir(req, new MerchantContext.Atual(atual.get().merchantId(), atual.get().ambiente(), atual.get().apiKeyId()));
    chain.doFilter(req, res);
  }
}
```

`seguranca/Problemas.java` (helper package-private que escreve `application/problem+json` `{"type":"urn:gateway:<codigo>","title":<codigo>,"status":n,"detail":…}` — 10 linhas com `ObjectMapper` ou `String.format`).

`seguranca/AdminKeyFilter.java`: `@Order(10)`, filtra só `/v1/admin/**`; compara `X-Admin-Key` com `gateway.admin-key` em tempo constante (`MessageDigest.isEqual`); admin key vazia na config → **403 sempre** (nunca "aberto por default"); erro → 403 `NAO_AUTORIZADO`.

`seguranca/RateLimitFilter.java`: `@Order(30)`; após a autenticação (usa `MerchantContext`), `ConcurrentHashMap<String /*apiKeyId*/, Bucket>` com `Bucket.builder().addLimit(Bandwidth.builder().capacity(n).refillGreedy(n, Duration.ofMinutes(1)).build()).build()`; `tryConsumeAndReturnRemaining(1)`; se negado → 429 com `Retry-After` = segundos até o próximo token (`probe.getNanosToWaitForRefill()/1e9`, arredondado para cima, mínimo 1). Comentário: em memória porque há uma instância; Redis quando houver mais de uma (spec §8). Se a assinatura da API do Bucket4j 8.14 diferir (`Bandwidth.classic`/`Refill.greedy` nas versões antigas), use a que existir e registre.

`api/ErroHandler.java` (`@RestControllerAdvice`): `DominioException` → 422 `ProblemDetail` com `type = URI.create("urn:gateway:" + codigo)`; `NaoEncontradoException` → 404 (antes do handler genérico, é subclasse); `IllegalArgumentException` → 400 `urn:gateway:REQUISICAO_INVALIDA`; `IllegalStateException` de `MerchantContext` → 401.

`api/MeController.java`: `GET /v1/me` → `{merchant_id, nome, ambiente}` via `MerchantService.buscar(MerchantContext.atual().merchantId())`.

`api/admin/MerchantsAdminController.java` (`@RequestMapping("/v1/admin/merchants")`):
- `POST` `{nome}` → 201 `{id, nome, status}`.
- `GET /{id}` → `{id, nome, status}`; `GET` lista.
- `POST /{id}/api-keys` `{ambiente}` → 201 `{id, prefixo, ambiente, chave, aviso: "Guarde agora: este valor não é recuperável."}`.
- `POST /{id}/api-keys/rotate` `{ambiente}` → 201, mesmo corpo.
- `DELETE /{id}/api-keys/{keyId}` → 204.
- `PUT /{id}/providers/{provider}/credentials` `{ambiente, payload: <objeto JSON>}` → serializa `payload` com o `ObjectMapper` do contexto (`tools.jackson.databind.ObjectMapper` no Boot 4) para bytes e chama `ProviderCredentialService.gravar` → 204 sem corpo.
- `POST /{id}/suspend`, `POST /{id}/activate` → 200 com o merchant.
DTOs como records em `api/admin/dto/`.

- [ ] **Step 5: Rodar e ver passar** — comando do Step 3 → verdes (ArchUnit + 6 testes de integração). Se o contexto falhar por entidades da lib `webhook-delivery` não encontradas (a lib ainda não é usada aqui — só na Task 6), ignore por enquanto; se falhar por conflito de `@EnableJpaRepositories`, veja a nota da Task 4 e resolva no `app`.

- [ ] **Step 6: Commit**

```bash
git add gateway-app
git commit -m "feat(app): montagem, archunit, autenticacao por api key, admin key, rate limit e erros

Fronteira dos modulos cobrada por teste desde o primeiro commit, com
as regras de orders/payments/providers ja escritas. Admin key vazia
fecha o admin em vez de abrir. Rate limit em memoria porque ha uma
instancia; Redis quando houver mais."
```

---

### Task 6: Webhooks de saída via `webhook-delivery` e API self-service do merchant

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/api/WebhookEndpointsController.java`, `api/dto/{RegistrarEndpointRequest,EndpointResponse,EndpointComSegredoResponse}.java`, `gateway-app/src/main/java/com/gateway/app/webhooks/EventosDoMerchant.java`
- Modify: `application.yml` (já tem `webhook-delivery.*`), `GatewayApplication` se precisar de `@EntityScan` (ver nota da Task 4)
- Test: `gateway-app/src/test/java/com/gateway/app/WebhooksIntegrationTest.java`

**Interfaces:**
- Produces:
  - Rotas (autenticadas por API key; `tenantId = merchantId.valor()`): `POST /v1/webhooks/endpoints {url, events?: [...]}` → 201 `{id, url, events, active, secret, aviso}` (segredo só aqui e na rotação); `GET /v1/webhooks/endpoints` → lista sem segredo; `GET /v1/webhooks/endpoints/{id}`; `PUT /v1/webhooks/endpoints/{id} {url, events}` → 200; `POST /v1/webhooks/endpoints/{id}/rotate-secret` → 200 com `secret` e `previous_secret_until`; `DELETE /v1/webhooks/endpoints/{id}` → 200 desativado. Um merchant **só vê os próprios** (`listByTenant`/`find` + checagem de `tenantId`; id de outro → 404).
  - `EventosDoMerchant.emitir(MerchantId, String eventType, String aggregateId, String partitionKey, Object payload)` → serializa com o `ObjectMapper`, gera `eventId` ULID→UUID (use `UUID.randomUUID()`; o ULID fica em `aggregateId`), chama `DeliveryIntake.accept(new DeliveryRequest(merchantId, eventType, eventId, aggregateId, partitionKey, json, correlationId do MDC))`. É o único ponto que fala com a lib; Planos B/C chamam isto.

- [ ] **Step 1: Teste**

```java
package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.webhookdelivery.client.HmacSigner;
import com.gateway.app.webhooks.EventosDoMerchant;
import com.gateway.kernel.ids.MerchantId;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.*;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "webhook-delivery.retry-delay-ms=200")
@ActiveProfiles("test")
@Testcontainers
class WebhooksIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  record Recebido(String body, Map<String, String> headers) {}
  static final List<Recebido> recebidos = new CopyOnWriteArrayList<>();
  static HttpServer sink;

  @BeforeAll static void sobe() throws Exception {
    sink = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    sink.createContext("/hook", ex -> {
      Map<String, String> h = new ConcurrentHashMap<>();
      ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.getFirst()));
      recebidos.add(new Recebido(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), h));
      ex.sendResponseHeaders(200, -1); ex.close();
    });
    sink.start();
  }
  @AfterAll static void desce() { sink.stop(0); }

  @Autowired TestRestTemplate http;
  @Autowired EventosDoMerchant eventos;
  @Autowired HmacSigner signer;

  private HttpHeaders admin() { HttpHeaders h = new HttpHeaders(); h.set("X-Admin-Key", "admin-de-teste"); h.setContentType(MediaType.APPLICATION_JSON); return h; }
  private HttpHeaders bearer(String k) { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(k); h.setContentType(MediaType.APPLICATION_JSON); return h; }

  @SuppressWarnings("unchecked")
  private String[] merchantEChave(String nome) {
    Map<String, Object> m = http.postForEntity("/v1/admin/merchants", new HttpEntity<>(Map.of("nome", nome), admin()), Map.class).getBody();
    Map<String, Object> k = http.postForEntity("/v1/admin/merchants/" + m.get("id") + "/api-keys", new HttpEntity<>(Map.of("ambiente", "TEST"), admin()), Map.class).getBody();
    return new String[] {(String) m.get("id"), (String) k.get("chave")};
  }

  private String url() { return "http://localhost:" + sink.getAddress().getPort() + "/hook"; }

  @Test
  @SuppressWarnings("unchecked")
  void registraEndpointRecebeEventoAssinadoComXGateway() {
    String[] mk = merchantEChave("Loja A");
    ResponseEntity<Map> criado = http.postForEntity("/v1/webhooks/endpoints", new HttpEntity<>(Map.of("url", url(), "events", List.of("payment.*")), bearer(mk[1])), Map.class);
    assertThat(criado.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String secret = (String) criado.getBody().get("secret");
    assertThat(secret).isNotBlank();

    eventos.emitir(new MerchantId(mk[0]), "payment.completed", "pay_1", "pay_1", Map.of("id", "pay_1", "status", "COMPLETED"));
    eventos.emitir(new MerchantId(mk[0]), "refund.completed", "ref_1", "pay_1", Map.of("id", "ref_1"));

    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> recebidos.size() == 1);
    Recebido r = recebidos.getFirst();
    assertThat(r.headers()).containsKey("x-gateway-signature").containsKey("x-gateway-event-id").containsEntry("x-gateway-event-type", "payment.completed");
    String assinatura = r.headers().get("x-gateway-signature");
    long t = Long.parseLong(assinatura.substring(2, assinatura.indexOf(',')));
    assertThat(assinatura).isEqualTo(signer.sign(r.body(), secret, Instant.ofEpochSecond(t)));
    assertThat(r.body()).contains("\"status\":\"COMPLETED\"");
  }

  @Test
  @SuppressWarnings("unchecked")
  void merchantNaoVeEndpointDeOutro() {
    String[] a = merchantEChave("Loja B"), b = merchantEChave("Loja C");
    Map<String, Object> e = http.postForEntity("/v1/webhooks/endpoints", new HttpEntity<>(Map.of("url", url()), bearer(a[1])), Map.class).getBody();
    assertThat(http.exchange("/v1/webhooks/endpoints/" + e.get("id"), HttpMethod.GET, new HttpEntity<>(bearer(b[1])), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<List> lista = http.exchange("/v1/webhooks/endpoints", HttpMethod.GET, new HttpEntity<>(bearer(b[1])), List.class);
    assertThat(lista.getBody()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void getNaoDevolveSegredoERotacaoDevolveNovo() {
    String[] mk = merchantEChave("Loja D");
    Map<String, Object> e = http.postForEntity("/v1/webhooks/endpoints", new HttpEntity<>(Map.of("url", url()), bearer(mk[1])), Map.class).getBody();
    Map<String, Object> get = http.exchange("/v1/webhooks/endpoints/" + e.get("id"), HttpMethod.GET, new HttpEntity<>(bearer(mk[1])), Map.class).getBody();
    assertThat(get).doesNotContainKey("secret");
    Map<String, Object> rot = http.postForEntity("/v1/webhooks/endpoints/" + e.get("id") + "/rotate-secret", new HttpEntity<>(bearer(mk[1])), Map.class).getBody();
    assertThat((String) rot.get("secret")).isNotEqualTo(e.get("secret"));
    assertThat(rot).containsKey("previous_secret_until");
  }
}
```
Adicione `org.awaitility:awaitility` (test) ao `gateway-app/pom.xml`.

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-app -am test -Dtest=WebhooksIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → compilação falha.

- [ ] **Step 3: Implementar**

`webhooks/EventosDoMerchant.java`:
```java
package com.gateway.app.webhooks;

import com.barrier.webhookdelivery.intake.DeliveryIntake;
import com.barrier.webhookdelivery.intake.DeliveryRequest;
import com.barrier.webhookdelivery.intake.IntakeResult;
import com.gateway.kernel.ids.MerchantId;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Único ponto do gateway que fala com a webhook-delivery. Os módulos de negócio (Planos B e C)
 * emitem por aqui a partir do relay do outbox — nunca da thread da request, porque a entrega é
 * assíncrona por construção e a lib também não entrega no accept.
 */
@Component
public class EventosDoMerchant {
  private final DeliveryIntake intake;
  private final ObjectMapper mapper;

  public EventosDoMerchant(DeliveryIntake intake, ObjectMapper mapper) { this.intake = intake; this.mapper = mapper; }

  public IntakeResult emitir(MerchantId merchantId, String eventType, String aggregateId, String partitionKey, Object payload) {
    String json = mapper.writeValueAsString(payload);
    return intake.accept(new DeliveryRequest(merchantId.valor(), eventType, UUID.randomUUID(), aggregateId, partitionKey, json, MDC.get("correlationId")));
  }
}
```
Se `writeValueAsString` do Jackson 3 lançar checked exception, envolva em `IllegalStateException`.

`api/WebhookEndpointsController.java` (`@RequestMapping("/v1/webhooks/endpoints")`) sobre `com.barrier.webhookdelivery.service.WebhookEndpointService`:
- helper `private WebhookEndpoint meu(UUID id)` = `service.find(id).filter(e -> e.tenantId().equals(MerchantContext.atual().merchantId().valor())).orElseThrow(() -> new NaoEncontradoException("endpoint", id.toString()))`.
- `POST` → `service.register(tenant, url, events)` → 201 `EndpointComSegredoResponse`.
- `GET` → `service.listByTenant(tenant)` → `EndpointResponse` (sem segredo).
- `GET /{id}`, `PUT /{id}` (`service.update(id, url, events)` após `meu(id)`), `POST /{id}/rotate-secret` (`service.rotateSecret`), `DELETE /{id}` (`service.deactivate`).
- `IllegalArgumentException` da validação de URL da lib → 400 pelo `ErroHandler`.

DTOs: `EndpointResponse(id, url, events, active, previous_secret_until, created_at, updated_at)`; `EndpointComSegredoResponse` = o mesmo + `secret` + `aviso`. Nomes de campo JSON em `snake_case` (configure `spring.jackson.property-naming-strategy: SNAKE_CASE` no `application.yml` — vale para toda a API, e a spec §10 usa snake_case).

**Scan de entidades**: rode o teste; se o contexto reclamar que `DeliveryEntity` não é uma entidade gerenciada, aplique a decisão da nota da Task 4: mova `@EntityScan`/`@EnableJpaRepositories` para `GatewayApplication` listando `com.gateway.merchants.repositorio` e `com.barrier.webhookdelivery.repository`, e remova das configurações de módulo (o `TestApp` do módulo `merchants` passa a declarar os seus). Registre no relatório.

- [ ] **Step 4: Rodar e ver passar** — comando do Step 2 → 3 verdes. Depois a suíte inteira: `./mvnw -B test` → tudo verde.

- [ ] **Step 5: Commit**

```bash
git add gateway-app
git commit -m "feat(app): webhooks de saida pela webhook-delivery com self-service do merchant

Prefixo X-Gateway, tenant = merchant. O merchant so ve os proprios
endpoints; o segredo aparece so no registro e na rotacao. Os planos B
e C emitem por EventosDoMerchant, nunca direto na lib."
```

---

### Task 7: Observabilidade base — correlação, log JSON com `Masker`, actuator

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/observabilidade/{CorrelationFilter,Masker,MaskingJsonProvider}.java`, `gateway-app/src/main/resources/logback-spring.xml`
- Test: `gateway-app/src/test/java/com/gateway/app/observabilidade/MaskerTest.java`, `gateway-app/src/test/java/com/gateway/app/ObservabilidadeIntegrationTest.java`

**Interfaces:**
- Produces: `Masker.mascarar(String) -> String` (estático, sem estado); header `X-Correlation-Id` aceito na entrada e sempre devolvido; MDC `correlationId`; `/actuator/prometheus` e `/actuator/health` públicos (sem API key).

- [ ] **Step 1: Testes**

`MaskerTest.java`:
```java
package com.gateway.app.observabilidade;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MaskerTest {
  @Test void mascaraCpfComESemPontuacao() {
    assertThat(Masker.mascarar("cpf 123.456.789-09 e 12345678909")).isEqualTo("cpf *** e ***");
  }
  @Test void mascaraApiKeyEBearer() {
    assertThat(Masker.mascarar("Authorization: Bearer gk_live_01ARZ3NDEKTSV4RRFFQ69G5FAV")).isEqualTo("Authorization: Bearer ***");
    assertThat(Masker.mascarar("chave gk_test_01ARZ3NDEKTSV4RRFFQ69G5FAV usada")).isEqualTo("chave *** usada");
  }
  @Test void mascaraCamposSecretEmJson() {
    assertThat(Masker.mascarar("{\"client_secret\":\"abc\",\"secret\":\"x\",\"pix_copia_e_cola\":\"000201…\",\"nome\":\"ok\"}"))
        .isEqualTo("{\"client_secret\":\"***\",\"secret\":\"***\",\"pix_copia_e_cola\":\"***\",\"nome\":\"ok\"}");
  }
  @Test void naoTocaNoResto() {
    assertThat(Masker.mascarar("pagamento pay_01ARZ3 COMPLETED em 15990 centavos")).isEqualTo("pagamento pay_01ARZ3 COMPLETED em 15990 centavos");
  }
}
```

`ObservabilidadeIntegrationTest.java` (mesma base Testcontainers + `@ActiveProfiles("test")` das anteriores):
```java
  @Test void correlationIdEntraESaiOuEhGerado() {
    ResponseEntity<String> r = http.exchange("/actuator/health", HttpMethod.GET, new HttpEntity<>(comHeader("X-Correlation-Id", "abc-123")), String.class);
    assertThat(r.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("abc-123");
    ResponseEntity<String> s = http.getForEntity("/actuator/health", String.class);
    assertThat(s.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
  }
  @Test void prometheusEHealthSaoPublicos() {
    assertThat(http.getForEntity("/actuator/prometheus", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
  }
  /** O encoder JSON passa pelo Masker: um log com api key sai com ***. Capturado pelo appender de teste. */
  @Test void logComSegredoSaiMascarado() {
    // Use um ListAppender do Logback no logger "com.gateway" para capturar; loga "chave gk_live_01ARZ3NDEKTSV4RRFFQ69G5FAV";
    // asserta que a mensagem formatada pelo MaskingJsonProvider contém "***" e não "gk_live_01ARZ".
  }
```
Implemente `logComSegredoSaiMascarado` de verdade: anexe um `ch.qos.logback.core.read.ListAppender` a `LoggerFactory.getLogger("com.gateway")` e verifique `Masker.mascarar(evento.getFormattedMessage())` — e, separadamente, um teste unitário de `MaskingJsonProvider` que alimenta um `ILoggingEvent` e confere o JSON gerado.

- [ ] **Step 2: Rodar e ver falhar** — compilação.

- [ ] **Step 3: Implementar**

`Masker.java`:
```java
package com.gateway.app.observabilidade;

import java.util.regex.Pattern;

/**
 * Mascaramento central de log. Regex e não lista de campos porque o vazamento vem de onde ninguém
 * previu — a mensagem de uma exceção, o toString de um DTO, o corpo de um erro HTTP do banco.
 */
public final class Masker {
  private static final Pattern CPF = Pattern.compile("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b");
  private static final Pattern API_KEY = Pattern.compile("gk_(live|test)_[0-9A-Za-z]+");
  private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)\\S+");
  private static final Pattern CAMPOS = Pattern.compile("(\"(?:client_secret|secret|previous_secret|pix_copia_e_cola|password|senha|token|access_token|certificate|certificado)\"\\s*:\\s*\")[^\"]*(\")");

  private Masker() {}

  public static String mascarar(String s) {
    if (s == null || s.isEmpty()) return s;
    String r = BEARER.matcher(s).replaceAll("$1***");
    r = API_KEY.matcher(r).replaceAll("***");
    r = CPF.matcher(r).replaceAll("***");
    r = CAMPOS.matcher(r).replaceAll("$1***$2");
    return r;
  }
}
```

`MaskingJsonProvider.java`: estende `net.logstash.logback.composite.loggingevent.MessageJsonProvider` e sobrescreve `writeTo(JsonGenerator, ILoggingEvent)` para escrever `Masker.mascarar(event.getFormattedMessage())` no campo `message`. Se a API do encoder 8.x preferir `setMessageSplitRegex`/`getMessage`, sobrescreva o método que devolve a mensagem — o teste unitário é o árbitro.

`logback-spring.xml`:
```xml
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
      <providers>
        <timestamp/><logLevel/><loggerName/><threadName/>
        <mdc/>
        <provider class="com.gateway.app.observabilidade.MaskingJsonProvider"/>
        <stackTrace/>
      </providers>
    </encoder>
  </appender>
  <root level="INFO"><appender-ref ref="JSON"/></root>
</configuration>
```

`CorrelationFilter.java`: `@Order(1)`, `OncePerRequestFilter` em tudo; lê `X-Correlation-Id` (ou gera `Ulid.novo()`), põe em `MDC("correlationId")`, devolve no header de resposta, limpa o MDC no `finally`.

Actuator público: `management.endpoints.web.exposure.include: health,info,prometheus` já está; o `ApiKeyAuthFilter` só cobre `/v1/`.

- [ ] **Step 4: Rodar e ver passar** — `./mvnw -B test` → suíte inteira verde.

- [ ] **Step 5: Commit**

```bash
git add gateway-app
git commit -m "feat(app): correlation id, log json com masker e actuator publico

Masker por regex e nao por campo: o vazamento vem de onde ninguem
previu. CPF, api key, bearer e campos secret/pix_copia_e_cola nunca
saem em log — com teste no encoder."
```

---

### Task 8: README, docker-compose de dev e verificação ponta a ponta

**Files:**
- Create: `README.md`, `docker-compose.yml`, `docs/superpowers/DECISOES.md`
- Test: nenhum novo — a suíte inteira e uma subida manual.

- [ ] **Step 1: `docker-compose.yml`**

```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: gateway
      POSTGRES_USER: gateway
      POSTGRES_PASSWORD: gateway
    ports: ["5432:5432"]
```

- [ ] **Step 2: README**

```markdown
# Payment Gateway

Orquestrador de pagamentos (modelo A: credenciais do merchant, o dinheiro nunca passa por aqui).
Spec: `docs/superpowers/specs/2026-09-23-payment-gateway-design.md`. Decisões: `docs/superpowers/DECISOES.md`.

## Rodar

```bash
docker compose up -d
export GATEWAY_ADMIN_KEY=dev-admin GATEWAY_API_KEY_PEPPER=dev-pepper
export GATEWAY_MASTER_KEY=$(openssl rand -base64 32)
./mvnw -pl gateway-app spring-boot:run
```

Sem `GATEWAY_MASTER_KEY` o app não sobe (a chave mestra cifra as credenciais dos merchants).
Sem `GATEWAY_ADMIN_KEY` o admin responde 403 — fechado por default.

## Primeiro merchant

```bash
curl -s -XPOST localhost:8080/v1/admin/merchants -H 'X-Admin-Key: dev-admin' -H 'Content-Type: application/json' -d '{"nome":"Loja"}'
curl -s -XPOST localhost:8080/v1/admin/merchants/<id>/api-keys -H 'X-Admin-Key: dev-admin' -H 'Content-Type: application/json' -d '{"ambiente":"TEST"}'
curl -s localhost:8080/v1/me -H 'Authorization: Bearer gk_test_…'
```

## Módulos

`gateway-kernel` (tipos sem dependência) · `gateway-merchants` (merchant, API keys, credenciais cifradas) ·
`gateway-app` (REST, auth, rate limit, webhooks de saída via `webhook-delivery`, observabilidade).
`orders`, `payments` e `providers` chegam nos planos B e C. A fronteira é cobrada por `ArquiteturaTest`.

## Build

`./mvnw verify` (Testcontainers; precisa de Docker). A lib `com.barrier:webhook-delivery` vem do GitHub
Packages: `~/.m2/settings.xml` com server `github-webhook-delivery` e um PAT `read:packages`.
```

- [ ] **Step 3: `docs/superpowers/DECISOES.md`** — append-only, começando com as decisões deste plano, cada uma com alternativa rejeitada e custo de estar errada:

```markdown
# Decisões

Append-only. Cada entrada: decisão, alternativa rejeitada, custo de estar errada.

## 2026-09-24 — API key com SHA-256+pepper, não bcrypt
Chave de 130 bits aleatórios: força bruta é impossível com hash rápido; bcrypt custaria ~100 ms por
requisição. Rejeitado: bcrypt/argon2. Custo se errado: nenhum enquanto a chave for aleatória; se um dia
aceitarmos chave escolhida pelo merchant, a decisão cai.

## 2026-09-24 — Envelope AES-256-GCM com DEK por merchant, mestra em env
Rejeitado: cifrar tudo direto com a mestra (rotacionar = recifrar tudo); KMS já no MVP (uma instância,
um operador). Custo se errado: a mestra em env é um segredo a mais para vazar; migrar para KMS é trocar
`ChaveMestra` e recifrar 32 bytes por merchant.

## 2026-09-24 — Rate limit em memória
Uma instância. Rejeitado: Redis no MVP. Custo se errado: com duas instâncias o limite vira 2x até
trocar por bucket distribuído.

## 2026-09-24 — Admin key vazia fecha o admin
Rejeitado: "sem chave = dev aberto". Custo se errado: nenhum; dev exporta uma variável.
```

- [ ] **Step 4: Verificação ponta a ponta**

Run: `./mvnw -B verify` → `BUILD SUCCESS`, todos os módulos. Depois `docker compose up -d`, exporte as três variáveis, `./mvnw -pl gateway-app spring-boot:run` em background e rode os três `curl` do README; `GET /v1/me` devolve o merchant. Pare o app. Cole a saída no relatório.

- [ ] **Step 5: Commit**

```bash
git add README.md docker-compose.yml docs/superpowers/DECISOES.md
git commit -m "docs: readme, compose de dev e decisoes do plano a

O app nao sobe sem chave mestra e o admin nao abre sem chave: os dois
defaults fechados estao documentados onde o proximo dev vai olhar."
```

---

## Self-review

**Cobertura da spec.** §1.3 multi-merchant → Tasks 3–5. §2 stack/topologia/ArchUnit → Tasks 1, 5. §7 webhooks de saída via lib, `X-Gateway`, self-service → Task 6. §8 correlação, log JSON, `Masker`, API key hash+prefixo+rotação, envelope AES-GCM, rate limit → Tasks 3, 4, 5, 7. §9 `merchants.*` → Task 4 (`merchants`, `api_keys`, `provider_credentials`; `merchant_webhooks` é a tabela da lib). §11 domínio puro / módulo com Postgres / arquitetura → Tasks 2–7. Não cobre (de propósito, é o Plano B): `PaymentGateway`/`CredentialLookup` no kernel, `providers`, `payments`; e Plano C: `orders`. `GET /v1/webhooks/deliveries` + `retry` (spec §10) fica para quando a lib expuser listagem por tenant — registrar como follow-up da lib (`0.2.0`), não bloqueia o Plano A.

**Placeholders.** Nenhum "TBD". Os pontos "se a API X diferir" dizem o que verificar e mandam registrar no relatório.

**Consistência de tipos.** `MerchantId(String valor)` em Tasks 2–6; `ApiKey.Emitida(apiKey, chaveEmClaro: Segredo)` em Tasks 3–5; `ApiKeyService.Autenticada(merchantId, ambiente, apiKeyId)` em Tasks 4–5; `MerchantContext.Atual` idem; `ProviderCredentialService.gravar(MerchantId, Provider, ApiKeyAmbiente, byte[])` em Tasks 4–5; `EventosDoMerchant.emitir(MerchantId, String, String, String, Object)` em Task 6; `Masker.mascarar(String)` em Task 7.
