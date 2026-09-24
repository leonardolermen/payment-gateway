# Payment Gateway — Plano A: fundação e merchants

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Subir o monólito modular do Payment Gateway com a fronteira de módulos cobrada por ArchUnit, o módulo `merchants` completo (merchant, API keys com hash, credenciais de provider cifradas em envelope AES-256-GCM), autenticação por API key, rate limit, webhooks de saída via `webhook-delivery`, correlação e mascaramento de log — sem nenhum pagamento ainda.

**Architecture:** Maven multi-módulo (`gateway-kernel`, `gateway-merchants`, `gateway-app`; `orders`, `payments` e `providers` entram nos Planos B/C). `kernel` não importa nada. Cada módulo de negócio é dono de um schema Postgres e das próprias migrations (`db/migration/<module>`, versões com prefixo por módulo), e o Flyway do `app` lista todas. O `app` é o único deploy: REST, filtro de API key, rate limit, workers, actuator. `webhook-delivery` entra como dependência com prefixo `X-Gateway`.

**Tech Stack:** Java 25, Spring Boot 4.0.7 (servlet blocking + virtual threads), Spring Data JPA/Hibernate, Flyway, PostgreSQL 17, `com.barrier:webhook-delivery:0.1.1` (GitHub Packages), `com.github.f4b6a3:ulid-creator`, Bucket4j, Micrometer/Prometheus, Logback JSON (`logstash-logback-encoder`), JUnit 5, AssertJ, Testcontainers, ArchUnit 1.5.0. Maven wrapper.

**Spec:** `docs/superpowers/specs/2026-09-23-payment-gateway-design.md` (§1.3, §2, §7, §8, §9 `merchants.*`, §11). O plano argumenta a partir dela; executores leem os dois.

## Global Constraints

- **Todo o código do repositório em inglês**: identificadores, colunas SQL, comentários, mensagens de erro e de log, commits, README. (A prosa deste plano e da spec fica em português; o que vai para o repo, não.) Decisão do dono em 2026-09-24.
- Coordenadas `com.gateway:payment-gateway-parent:0.1.0-SNAPSHOT`; módulos `gateway-kernel`, `gateway-merchants`, `gateway-app`; pacote raiz `com.gateway`, um subpacote por módulo (`com.gateway.kernel`, `com.gateway.merchants`, `com.gateway.app`).
- Java 25, Spring Boot 4.0.7, virtual threads ligadas (`spring.threads.virtual.enabled=true`). Sem WebFlux.
- **`kernel` não importa nada** (nem Spring, nem JPA, nem outro módulo). Ninguém importa `app`. `merchants` não importa `orders`/`payments`/`providers` (ainda não existem; a regra já fica escrita). Cobrado por ArchUnit no `app`.
- Um schema por módulo: `merchants`. Migrations em `gateway-merchants/src/main/resources/db/migration/merchants/V100__*.sql` (prefixo 1xx = merchants; 2xx = payments; 3xx = orders). O `app` configura `spring.flyway.locations` com todas e `schemas: merchants`. Entidades JPA declaram `schema = "merchants"` em `@Table`.
- `webhook-delivery` 0.1.1: prefixo `X-Gateway`; `tenantId = merchant id`. O `app` fica em `com.gateway.app`, que não é ancestral de `com.barrier.webhookdelivery` (a lib documenta esse conflito).
- API key: formato `gk_live_<26 chars ULID>` / `gk_test_…`; guardada como `SHA-256(pepper || key)` hex; `prefix` = primeiros 12 chars para busca; duas ativas por merchant e ambiente no máximo (rotação com sobreposição de até 24 h); header `Authorization: Bearer gk_…`.
- Credenciais de provider e qualquer segredo: **AES-256-GCM envelope** — DEK aleatória de 32 bytes por cifra, cifrada pela chave mestra (`GATEWAY_MASTER_KEY`, base64 de 32 bytes, obrigatória); nonce de 12 bytes novo por cifra; AAD = merchant id. Nada cifrado é logado; `toString` de tipos com segredo mascara.
- Log JSON; `Masker` central: CPF (`\d{3}\.?\d{3}\.?\d{3}-?\d{2}`), `gk_(live|test)_\w+`, `Bearer \S+`, campos JSON `secret`/`client_secret`/`pix_copia_e_cola`/… → `***`. Testado.
- Nenhum teste fala com a rede externa. Destinos de webhook em testes são `HttpServer` em `localhost`.
- Comentários registram POR QUÊ com evidência. Commits `type(scope): lowercase subject`, corpo explicando o porquê, terminando com `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.
- Ambiente local: `export JAVA_HOME="$HOME/.jdks/corretto-25.0.4.1"` antes de qualquer Maven; Docker para Testcontainers; `~/.m2` já tem `com.barrier:webhook-delivery:0.1.1` (o CI usa GitHub Packages com o secret `PACKAGES_READ_TOKEN`, um PAT `read:packages` — o `GITHUB_TOKEN` de outro repo não lê pacote Maven de conta pessoal).

---

## Estrutura de arquivos

```
payment-gateway/
  pom.xml                                   parent: modules, BOMs, versions
  mvnw, mvnw.cmd, .mvn/wrapper/*            copied from C:\Dev\webhook-delivery
  .gitignore  README.md  .github/workflows/ci.yml
  gateway-kernel/
    pom.xml
    src/main/java/com/gateway/kernel/
      money/Money.java                      long cents + currency; add/subtract/compare; never double
      ids/Ulid.java                         ULID generation/validation (26 chars Crockford)
      ids/MerchantId.java                   typed record over Ulid
      errors/DomainException.java           base for rule violations (code + message)
      errors/NotFoundException.java
      security/Secret.java                  String wrapper with masked toString
    src/test/java/com/gateway/kernel/...    MoneyTest, UlidTest, SecretTest
  gateway-merchants/
    pom.xml
    src/main/java/com/gateway/merchants/
      domain/Merchant.java, MerchantStatus.java
      domain/ApiKey.java, ApiKeyEnvironment.java     (LIVE/TEST), issue, hash, prefix
      domain/ProviderCredential.java                  (merchantId, provider, environment, encrypted payload)
      domain/Provider.java                            enum ITAU, FAKE
      crypto/EnvelopeCipher.java                      AES-256-GCM: DEK per encryption, master key
      crypto/MasterKey.java                           loads GATEWAY_MASTER_KEY
      crypto/Encrypted.java                           the envelope record
      repository/*Entity, *JpaRepository, *Repository, *RepositoryImpl   (package-private entities)
      service/MerchantService.java                    create, get, suspend/activate
      service/ApiKeyService.java                      issue (returns the key ONCE), authenticate, revoke, rotate
      service/ProviderCredentialService.java          store (encrypt), read (decrypt only when needed)
      service/MerchantsProperties.java
      MerchantsConfiguration.java                     @Configuration with explicit @Import (no scan)
    src/main/resources/db/migration/merchants/V100__merchants.sql
    src/test/java/com/gateway/merchants/...           unit (domain, crypto) + integration (service)
  gateway-app/
    pom.xml
    src/main/java/com/gateway/app/
      GatewayApplication.java
      AppConfiguration.java
      security/ApiKeyAuthFilter.java                  Authorization: Bearer → MerchantContext
      security/MerchantContext.java                   request attribute with merchant id and environment
      security/AdminKeyFilter.java                    X-Admin-Key for /v1/admin/**
      security/RateLimitFilter.java                   Bucket4j per API key
      security/Problems.java                          writes application/problem+json
      observability/CorrelationFilter.java            X-Correlation-Id in/out + MDC
      observability/Masker.java, MaskingJsonProvider.java
      webhooks/MerchantEvents.java                    the only caller of webhook-delivery
      api/ErrorHandler.java                           ProblemDetail for DomainException/NotFound/IllegalArgument
      api/MeController.java                           GET /v1/me
      api/WebhookEndpointsController.java             /v1/webhooks/endpoints/**
      api/admin/MerchantsAdminController.java         /v1/admin/merchants/**
      api/dto/*, api/admin/dto/*
    src/main/resources/application.yml, logback-spring.xml
    src/test/java/com/gateway/app/
      architecture/ArchitectureTest.java
      AuthenticationIntegrationTest, WebhooksIntegrationTest, ObservabilityIntegrationTest, observability/MaskerTest
```

Responsabilidades: `kernel` = tipos sem dependência; `merchants` = quem é o cliente e com que credenciais ele fala com bancos; `app` = borda HTTP, segurança, observabilidade e montagem.

---

### Task 1: Esqueleto multi-módulo que compila, com CI

**Files:**
- Create: `pom.xml`, `gateway-kernel/pom.xml`, `gateway-merchants/pom.xml`, `gateway-app/pom.xml`, `.gitignore`, `.github/workflows/ci.yml`, `gateway-app/src/main/java/com/gateway/app/GatewayApplication.java`, `gateway-app/src/main/resources/application.yml`
- Copy: `C:\Dev\webhook-delivery\mvnw`, `mvnw.cmd`, `.mvn\` → raiz (depois `git update-index --chmod=+x mvnw` — o wrapper sem bit de execução foi o que derrubou o primeiro CI da lib).

**Interfaces:**
- Produces: reactor Maven com três módulos; `GatewayApplication`.

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
        <!-- GitHub Packages requires a token even to read; local builds resolve from ~/.m2, CI uses a PAT secret. -->
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
Se algum `artifactId` do Bucket4j/ulid não resolver, consulte o Maven Central para o nome atual e registre no relatório.

- [ ] **Step 3: POMs dos módulos**

`gateway-kernel/pom.xml`:
```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent><groupId>com.gateway</groupId><artifactId>payment-gateway-parent</artifactId><version>0.1.0-SNAPSHOT</version></parent>
    <artifactId>gateway-kernel</artifactId>
    <!-- No dependencies on purpose: ArchUnit enforces it and the pom is the first line of defense.
         ulid-creator is the one exception, and it has no transitive dependencies. -->
    <dependencies>
        <dependency><groupId>com.github.f4b6a3</groupId><artifactId>ulid-creator</artifactId></dependency>
    </dependencies>
</project>
```

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
        <dependency><groupId>org.awaitility</groupId><artifactId>awaitility</artifactId><scope>test</scope></dependency>
        <dependency><groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId><scope>test</scope></dependency>
    </dependencies>
    <build>
        <plugins>
            <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
        </plugins>
    </build>
</project>
```
Os nomes exatos dos starters em Boot 4.0.7 são os que `C:\Dev\barrier\services\webhook-api\pom.xml` usa hoje — copie de lá se houver dúvida.

- [ ] **Step 4: `GatewayApplication` e `application.yml`**

```java
package com.gateway.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The gateway's single deployable. Scans only {@code com.gateway.app}: business modules come in
 * through explicit {@code @Import} of their configuration classes, one by one, so what each module
 * exposes stays readable. The webhook-delivery library registers itself via its autoconfiguration.
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
  jackson:
    property-naming-strategy: SNAKE_CASE
  flyway:
    enabled: true
    # One schema per module; Flyway creates the listed schemas. Versions are prefixed per module
    # (1xx merchants, 2xx payments, 3xx orders) because a single history table needs unique versions.
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
          server-username: PACKAGES_USER
          server-password: PACKAGES_READ_TOKEN
      # A Maven package on a personal account cannot be read with another repo's GITHUB_TOKEN;
      # PACKAGES_READ_TOKEN is a PAT with read:packages, stored as a repository secret.
      - run: ./mvnw -B verify
        env:
          PACKAGES_USER: ${{ github.actor }}
          PACKAGES_READ_TOKEN: ${{ secrets.PACKAGES_READ_TOKEN }}
      - if: always()
        uses: actions/upload-artifact@v4
        with: { name: surefire-reports, path: "**/target/surefire-reports/**", retention-days: 7 }
```

- [ ] **Step 6: Compilar e commitar**

Run: `cd /c/Dev/payment-gateway && export JAVA_HOME="$HOME/.jdks/corretto-25.0.4.1" && ./mvnw -B -q -DskipTests package`
Expected: `BUILD SUCCESS`.

```bash
git update-index --chmod=+x mvnw
git add -A
git commit -m "build(reactor): three modules, barrier toolchain and webhook-delivery lib

kernel/merchants/app now; orders, payments and providers come with
plans B and C. Migration versions are prefixed per module because the
app has a single Flyway history."
```

---

### Task 2: `kernel` — `Money`, `Ulid`, `Secret`, exceptions

**Files:**
- Create: `gateway-kernel/src/main/java/com/gateway/kernel/money/Money.java`, `ids/Ulid.java`, `ids/MerchantId.java`, `errors/DomainException.java`, `errors/NotFoundException.java`, `security/Secret.java`
- Test: `gateway-kernel/src/test/java/com/gateway/kernel/money/MoneyTest.java`, `ids/UlidTest.java`, `security/SecretTest.java`

**Interfaces:**
- Produces:
  ```java
  record Money(long cents, String currency) { static Money brl(long cents); Money plus(Money); Money minus(Money); boolean greaterThan(Money); boolean isZero(); static Money ZERO_BRL }
  final class Ulid { static String next(); static boolean isValid(String); }
  record MerchantId(String value) { static MerchantId next(); }
  class DomainException extends RuntimeException { DomainException(String code, String message); String code(); }
  class NotFoundException extends DomainException { NotFoundException(String what, String id); }   // code "NOT_FOUND"
  final class Secret { static Secret of(String); String reveal(); toString() -> "***"; constant-time equals }
  ```

- [ ] **Step 1: Testes**

`MoneyTest.java`:
```java
package com.gateway.kernel.money;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MoneyTest {
  @Test void addsAndSubtractsInCents() {
    Money a = Money.brl(15990), b = Money.brl(10);
    assertThat(a.plus(b)).isEqualTo(Money.brl(16000));
    assertThat(a.minus(b)).isEqualTo(Money.brl(15980));
  }
  @Test void rejectsMixedCurrencies() {
    assertThatThrownBy(() -> Money.brl(1).plus(new Money(1, "USD"))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void rejectsNegative() {
    assertThatThrownBy(() -> Money.brl(-1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Money.brl(1).minus(Money.brl(2))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test void compares() {
    assertThat(Money.brl(2).greaterThan(Money.brl(1))).isTrue();
    assertThat(Money.ZERO_BRL.isZero()).isTrue();
  }
}
```

`UlidTest.java`:
```java
package com.gateway.kernel.ids;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class UlidTest {
  @Test void is26CrockfordCharsAndValidates() {
    String id = Ulid.next();
    assertThat(id).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
    assertThat(Ulid.isValid(id)).isTrue();
    assertThat(Ulid.isValid("abc")).isFalse();
    assertThat(Ulid.isValid(null)).isFalse();
  }
  /** The Pix txid is derived from this id: [a-zA-Z0-9]{26,35}. A ULID fits with no transformation. */
  @Test void fitsTheBacenTxidFormat() {
    assertThat(Ulid.next()).matches("[a-zA-Z0-9]{26,35}");
  }
  @Test void isMonotonicWithinTheSameMillisecond() {
    String a = Ulid.next(), b = Ulid.next();
    assertThat(a.compareTo(b)).isNegative();
  }
}
```

`SecretTest.java`:
```java
package com.gateway.kernel.security;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SecretTest {
  @Test void toStringNeverReveals() {
    Secret s = Secret.of("gk_live_ABC");
    assertThat(s.toString()).doesNotContain("ABC").isEqualTo("***");
    assertThat(s.reveal()).isEqualTo("gk_live_ABC");
  }
  @Test void equalsByValue() {
    assertThat(Secret.of("x")).isEqualTo(Secret.of("x")).isNotEqualTo(Secret.of("y"));
  }
  @Test void rejectsBlank() {
    assertThatThrownBy(() -> Secret.of(" ")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-kernel test` → erro de compilação.

- [ ] **Step 3: Implementar**

`Money.java`:
```java
package com.gateway.kernel.money;

/**
 * Money as integer cents. Never {@code double}: 0.10 + 0.20 is not 0.30 in floating point, and a
 * gateway that is one cent off loses the whole reconciliation.
 */
public record Money(long cents, String currency) {
  public static final Money ZERO_BRL = new Money(0, "BRL");

  public Money {
    if (cents < 0) throw new IllegalArgumentException("negative amount: " + cents);
    if (currency == null || currency.length() != 3) throw new IllegalArgumentException("invalid currency: " + currency);
  }

  public static Money brl(long cents) { return new Money(cents, "BRL"); }

  public Money plus(Money other) { return new Money(cents + sameCurrency(other).cents, currency); }

  public Money minus(Money other) {
    long r = cents - sameCurrency(other).cents;
    if (r < 0) throw new IllegalArgumentException("negative result");
    return new Money(r, currency);
  }

  public boolean greaterThan(Money other) { return cents > sameCurrency(other).cents; }

  public boolean isZero() { return cents == 0; }

  private Money sameCurrency(Money other) {
    if (!currency.equals(other.currency)) throw new IllegalArgumentException("currency mismatch: " + currency + " vs " + other.currency);
    return other;
  }
}
```

`Ulid.java`:
```java
package com.gateway.kernel.ids;

import com.github.f4b6a3.ulid.UlidCreator;
import java.util.regex.Pattern;

/**
 * ULID as the id of everything: time-ordered (B-tree friendly), 26 Crockford base32 chars — which
 * fits the Pix {@code txid} ({@code [a-zA-Z0-9]{26,35}}) with no transformation. A UUID does not
 * (36 chars with hyphens).
 */
public final class Ulid {
  private static final Pattern FORMAT = Pattern.compile("[0-9A-HJKMNP-TV-Z]{26}");

  private Ulid() {}

  /** Monotonic within a millisecond: two ids generated back to back sort in generation order. */
  public static String next() { return UlidCreator.getMonotonicUlid().toString(); }

  public static boolean isValid(String s) { return s != null && FORMAT.matcher(s).matches(); }
}
```

`MerchantId.java`:
```java
package com.gateway.kernel.ids;

public record MerchantId(String value) {
  public MerchantId {
    if (!Ulid.isValid(value)) throw new IllegalArgumentException("invalid merchant id: " + value);
  }
  public static MerchantId next() { return new MerchantId(Ulid.next()); }
}
```

`DomainException.java` / `NotFoundException.java`:
```java
package com.gateway.kernel.errors;

/** A business rule violation: becomes a 4xx at the edge, with a stable {@code code} merchants can handle. */
public class DomainException extends RuntimeException {
  private final String code;
  public DomainException(String code, String message) { super(message); this.code = code; }
  public String code() { return code; }
}
```
```java
package com.gateway.kernel.errors;

public class NotFoundException extends DomainException {
  public NotFoundException(String what, String id) { super("NOT_FOUND", what + " not found: " + id); }
}
```

`Secret.java`:
```java
package com.gateway.kernel.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * A sensitive value (API key, client secret, certificate) that never shows up in logs, exceptions
 * or {@code toString}. Whoever needs the value calls {@link #reveal()} on purpose — the method name
 * is the warning.
 */
public final class Secret {
  private final String value;

  private Secret(String value) { this.value = value; }

  public static Secret of(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("empty secret");
    return new Secret(value);
  }

  public String reveal() { return value; }

  @Override public String toString() { return "***"; }

  /** Constant-time comparison: comparing API keys with String.equals leaks the length of the common prefix. */
  @Override public boolean equals(Object o) {
    return o instanceof Secret s && MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8), s.value.getBytes(StandardCharsets.UTF_8));
  }

  @Override public int hashCode() { return Objects.hash(value); }
}
```

- [ ] **Step 4: Rodar e ver passar** — `./mvnw -B -q -pl gateway-kernel test` → verde.

- [ ] **Step 5: Commit**

```bash
git add gateway-kernel
git commit -m "feat(kernel): money in cents, ulid, masked secret and domain exceptions

ULID because it fits the Bacen txid untouched; Money as long because
double loses cents; Secret because a record toString leaked a secret
in barrier once."
```

---

### Task 3: `merchants` — domain (`Merchant`, `ApiKey`, `ProviderCredential`) and `EnvelopeCipher`

**Files:**
- Create: `gateway-merchants/src/main/java/com/gateway/merchants/domain/{Merchant,MerchantStatus,ApiKey,ApiKeyEnvironment,Provider,ProviderCredential}.java`, `crypto/{EnvelopeCipher,MasterKey,Encrypted}.java`
- Test: `gateway-merchants/src/test/java/com/gateway/merchants/domain/ApiKeyTest.java`, `crypto/EnvelopeCipherTest.java`

**Interfaces:**
- Produces:
  ```java
  enum MerchantStatus { ACTIVE, SUSPENDED }
  record Merchant(MerchantId id, String name, MerchantStatus status, Instant createdAt, Instant updatedAt) { static Merchant create(String name); Merchant suspend(); Merchant activate(); boolean isActive(); }
  enum ApiKeyEnvironment { LIVE, TEST; String keyPrefix() /* "gk_live_" | "gk_test_" */ }
  record ApiKey(String id, MerchantId merchantId, ApiKeyEnvironment environment, String prefix, String hash, boolean active, Instant expiresAt, Instant createdAt) {
      record Issued(ApiKey apiKey, Secret plainKey) {}
      static Issued issue(MerchantId, ApiKeyEnvironment, String pepper);        // key = keyPrefix + Ulid.next()
      static String hashOf(String plainKey, String pepper);                     // hex SHA-256(pepper || key)
      static String prefixOf(String plainKey);                                  // first 12 chars
      static Optional<ApiKeyEnvironment> environmentOf(String plainKey);
      ApiKey revoke(); ApiKey expiringAt(Instant); boolean isValid(Instant now); }
  enum Provider { ITAU, FAKE }
  record ProviderCredential(String id, MerchantId merchantId, Provider provider, ApiKeyEnvironment environment, Encrypted payload, boolean active, Instant createdAt, Instant updatedAt)
  record Encrypted(byte[] nonce, byte[] ciphertext, byte[] encryptedDek, byte[] dekNonce)
  final class MasterKey { static MasterKey fromBase64(String); static MasterKey randomForTests(); }
  final class EnvelopeCipher { EnvelopeCipher(MasterKey); Encrypted encrypt(byte[] plaintext, String aad); byte[] decrypt(Encrypted, String aad); }
  ```

- [ ] **Step 1: Testes**

`ApiKeyTest.java`:
```java
package com.gateway.merchants.domain;

import static org.assertj.core.api.Assertions.*;
import com.gateway.kernel.ids.MerchantId;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class ApiKeyTest {
  static final String PEPPER = "test-pepper";

  @Test void issuesWithEnvironmentPrefixAndStoresOnlyTheHash() {
    ApiKey.Issued issued = ApiKey.issue(MerchantId.next(), ApiKeyEnvironment.LIVE, PEPPER);
    String plain = issued.plainKey().reveal();
    assertThat(plain).startsWith("gk_live_").hasSize(8 + 26);
    assertThat(issued.apiKey().hash()).isEqualTo(ApiKey.hashOf(plain, PEPPER)).doesNotContain(plain);
    assertThat(issued.apiKey().prefix()).isEqualTo(plain.substring(0, 12));
    assertThat(issued.apiKey().active()).isTrue();
  }

  @Test void hashDependsOnPepper() {
    assertThat(ApiKey.hashOf("gk_test_X", "a")).isNotEqualTo(ApiKey.hashOf("gk_test_X", "b"));
  }

  @Test void environmentComesFromThePrefix() {
    assertThat(ApiKey.environmentOf("gk_test_ABC")).contains(ApiKeyEnvironment.TEST);
    assertThat(ApiKey.environmentOf("gk_live_ABC")).contains(ApiKeyEnvironment.LIVE);
    assertThat(ApiKey.environmentOf("sk_ABC")).isEmpty();
  }

  @Test void revokedOrExpiredIsInvalid() {
    ApiKey k = ApiKey.issue(MerchantId.next(), ApiKeyEnvironment.TEST, PEPPER).apiKey();
    Instant now = Instant.now();
    assertThat(k.isValid(now)).isTrue();
    assertThat(k.revoke().isValid(now)).isFalse();
    assertThat(k.expiringAt(now.minusSeconds(1)).isValid(now)).isFalse();
  }
}
```

`EnvelopeCipherTest.java`:
```java
package com.gateway.merchants.crypto;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EnvelopeCipherTest {
  final EnvelopeCipher cipher = new EnvelopeCipher(MasterKey.randomForTests());

  @Test void encryptsAndDecryptsWithAad() {
    byte[] plain = "client_secret=abc".getBytes(StandardCharsets.UTF_8);
    Encrypted e = cipher.encrypt(plain, "merchant-1");
    assertThat(cipher.decrypt(e, "merchant-1")).isEqualTo(plain);
    assertThat(new String(e.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("client_secret");
  }

  /** AAD = merchant id: a payload copied to another merchant does not decrypt. */
  @Test void wrongAadFails() {
    Encrypted e = cipher.encrypt("x".getBytes(), "merchant-1");
    assertThatThrownBy(() -> cipher.decrypt(e, "merchant-2")).isInstanceOf(SecurityException.class);
  }

  @Test void freshNonceAndDekPerEncryption() {
    Encrypted a = cipher.encrypt("x".getBytes(), "m"), b = cipher.encrypt("x".getBytes(), "m");
    assertThat(a.nonce()).isNotEqualTo(b.nonce());
    assertThat(a.encryptedDek()).isNotEqualTo(b.encryptedDek());
  }

  @Test void wrongMasterKeyFails() {
    Encrypted e = cipher.encrypt("x".getBytes(), "m");
    assertThatThrownBy(() -> new EnvelopeCipher(MasterKey.randomForTests()).decrypt(e, "m")).isInstanceOf(SecurityException.class);
  }

  @Test void masterKeyMustBe32Bytes() {
    assertThatThrownBy(() -> MasterKey.fromBase64("AAAA")).isInstanceOf(IllegalArgumentException.class);
  }
}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-merchants -am test -Dtest='ApiKeyTest,EnvelopeCipherTest' -Dsurefire.failIfNoSpecifiedTests=false` → erro de compilação.

- [ ] **Step 3: Implementar o domínio**

`MerchantStatus.java`: `public enum MerchantStatus { ACTIVE, SUSPENDED }`

`Merchant.java`:
```java
package com.gateway.merchants.domain;

import com.gateway.kernel.ids.MerchantId;
import java.time.Instant;

public record Merchant(MerchantId id, String name, MerchantStatus status, Instant createdAt, Instant updatedAt) {
  public Merchant {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
  }
  public static Merchant create(String name) {
    Instant now = Instant.now();
    return new Merchant(MerchantId.next(), name.trim(), MerchantStatus.ACTIVE, now, now);
  }
  public Merchant suspend() { return new Merchant(id, name, MerchantStatus.SUSPENDED, createdAt, Instant.now()); }
  public Merchant activate() { return new Merchant(id, name, MerchantStatus.ACTIVE, createdAt, Instant.now()); }
  public boolean isActive() { return status == MerchantStatus.ACTIVE; }
}
```

`ApiKeyEnvironment.java`:
```java
package com.gateway.merchants.domain;

/** Key environment: {@code test} routes to the FakePixProvider — the gateway's own sandbox. */
public enum ApiKeyEnvironment {
  LIVE("gk_live_"), TEST("gk_test_");
  private final String keyPrefix;
  ApiKeyEnvironment(String p) { this.keyPrefix = p; }
  public String keyPrefix() { return keyPrefix; }
}
```

`ApiKey.java`:
```java
package com.gateway.merchants.domain;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.security.Secret;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * A merchant's API key. We store the hash, not the key: whoever reads the database cannot call the
 * API. The prefix (12 chars: {@code gk_live_} + 4) exists only to find the row before comparing the
 * hash — and so the merchant can recognise the key in a dashboard without seeing it whole.
 *
 * <p>SHA-256 with a pepper rather than bcrypt: the key carries 26 random chars (130 bits), so brute
 * force is impossible even with a fast hash, and bcrypt would cost ~100 ms per authenticated request.
 */
public record ApiKey(String id, MerchantId merchantId, ApiKeyEnvironment environment, String prefix, String hash,
                     boolean active, Instant expiresAt, Instant createdAt) {

  public record Issued(ApiKey apiKey, Secret plainKey) {}

  public static Issued issue(MerchantId merchantId, ApiKeyEnvironment environment, String pepper) {
    String plain = environment.keyPrefix() + Ulid.next();
    ApiKey k = new ApiKey(Ulid.next(), merchantId, environment, prefixOf(plain), hashOf(plain, pepper), true, null, Instant.now());
    return new Issued(k, Secret.of(plain));
  }

  public static String hashOf(String plainKey, String pepper) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      md.update(pepper.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(md.digest(plainKey.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String prefixOf(String plainKey) { return plainKey.substring(0, Math.min(12, plainKey.length())); }

  public static Optional<ApiKeyEnvironment> environmentOf(String plainKey) {
    for (ApiKeyEnvironment e : values()) if (plainKey != null && plainKey.startsWith(e.keyPrefix())) return Optional.of(e);
    return Optional.empty();
  }

  public ApiKey revoke() { return new ApiKey(id, merchantId, environment, prefix, hash, false, expiresAt, createdAt); }

  /** Rotation: the old key gets a deadline (up to 24 h) instead of dying on the spot. */
  public ApiKey expiringAt(Instant when) { return new ApiKey(id, merchantId, environment, prefix, hash, active, when, createdAt); }

  public boolean isValid(Instant now) { return active && (expiresAt == null || now.isBefore(expiresAt)); }
}
```

`Provider.java`: `public enum Provider { ITAU, FAKE }`

`ProviderCredential.java`:
```java
package com.gateway.merchants.domain;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.merchants.crypto.Encrypted;
import java.time.Instant;

/**
 * The merchant's credential at a provider (Itaú client_id/secret/certificate, for instance), always
 * encrypted. The domain never sees the plaintext: {@code ProviderCredentialService} decrypts at the
 * moment of the bank call, and only it does.
 */
public record ProviderCredential(String id, MerchantId merchantId, Provider provider, ApiKeyEnvironment environment,
                                 Encrypted payload, boolean active, Instant createdAt, Instant updatedAt) {
  public static ProviderCredential create(MerchantId m, Provider p, ApiKeyEnvironment e, Encrypted payload) {
    Instant now = Instant.now();
    return new ProviderCredential(Ulid.next(), m, p, e, payload, true, now, now);
  }
  public ProviderCredential withPayload(Encrypted next) { return new ProviderCredential(id, merchantId, provider, environment, next, active, createdAt, Instant.now()); }
  public ProviderCredential deactivate() { return new ProviderCredential(id, merchantId, provider, environment, payload, false, createdAt, Instant.now()); }
  @Override public String toString() { return "ProviderCredential[" + id + ", " + merchantId.value() + ", " + provider + ", " + environment + ", payload=***]"; }
}
```

- [ ] **Step 4: Implementar a cifra**

`Encrypted.java`:
```java
package com.gateway.merchants.crypto;

import java.util.Arrays;

/** The envelope: ciphertext under the DEK, plus the DEK encrypted under the master key. Everything that goes to the database. */
public record Encrypted(byte[] nonce, byte[] ciphertext, byte[] encryptedDek, byte[] dekNonce) {
  @Override public String toString() { return "Encrypted[***]"; }
  @Override public boolean equals(Object o) {
    return o instanceof Encrypted e && Arrays.equals(nonce, e.nonce) && Arrays.equals(ciphertext, e.ciphertext)
        && Arrays.equals(encryptedDek, e.encryptedDek) && Arrays.equals(dekNonce, e.dekNonce);
  }
  @Override public int hashCode() { return Arrays.hashCode(ciphertext); }
}
```

`MasterKey.java`:
```java
package com.gateway.merchants.crypto;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The envelope's master key. In the MVP it comes from an environment variable
 * ({@code GATEWAY_MASTER_KEY}, base64 of 32 bytes); in phase 2 it comes from a KMS — and the swap
 * happens only here, because the per-row DEK stays the same: rotating the master key re-encrypts
 * 32 bytes per row, not every payload.
 */
public final class MasterKey {
  private final SecretKey key;

  private MasterKey(byte[] bytes) {
    if (bytes.length != 32) throw new IllegalArgumentException("master key must be 32 bytes, got " + bytes.length);
    this.key = new SecretKeySpec(bytes, "AES");
  }

  public static MasterKey fromBase64(String base64) {
    if (base64 == null || base64.isBlank()) throw new IllegalArgumentException("GATEWAY_MASTER_KEY is missing");
    return new MasterKey(Base64.getDecoder().decode(base64.trim()));
  }

  public static MasterKey randomForTests() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return new MasterKey(b);
  }

  SecretKey key() { return key; }

  @Override public String toString() { return "MasterKey[***]"; }
}
```

`EnvelopeCipher.java`:
```java
package com.gateway.merchants.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM envelope: every encryption generates a fresh DEK, encrypts the payload with it and
 * encrypts the DEK with the master key. GCM because it authenticates (a tampered payload does not
 * decrypt) and the AAD binds the payload to the merchant: copying one merchant's encrypted row to
 * another fails on decrypt.
 *
 * <p>Random 12-byte nonce per operation. With a fresh DEK per encryption the chance of repeating a
 * (nonce, key) pair is zero in practice — GCM's 2^32-encryptions-per-key limit is never approached.
 */
public final class EnvelopeCipher {
  private static final int TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;
  private final MasterKey master;
  private final SecureRandom random = new SecureRandom();

  public EnvelopeCipher(MasterKey master) { this.master = master; }

  public Encrypted encrypt(byte[] plaintext, String aad) {
    try {
      byte[] dekBytes = new byte[32];
      random.nextBytes(dekBytes);
      SecretKey dek = new SecretKeySpec(dekBytes, "AES");
      byte[] nonce = nonce();
      byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, dek, nonce, aad, plaintext);
      byte[] dekNonce = nonce();
      byte[] encryptedDek = gcm(Cipher.ENCRYPT_MODE, master.key(), dekNonce, aad, dekBytes);
      return new Encrypted(nonce, ciphertext, encryptedDek, dekNonce);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("encryption failed", e);
    }
  }

  public byte[] decrypt(Encrypted e, String aad) {
    try {
      byte[] dekBytes = gcm(Cipher.DECRYPT_MODE, master.key(), e.dekNonce(), aad, e.encryptedDek());
      return gcm(Cipher.DECRYPT_MODE, new SecretKeySpec(dekBytes, "AES"), e.nonce(), aad, e.ciphertext());
    } catch (GeneralSecurityException ex) {
      // Deliberately vague: "bad tag" vs "wrong key" is an oracle for nobody.
      throw new SecurityException("decryption failed");
    }
  }

  private byte[] nonce() { byte[] n = new byte[NONCE_BYTES]; random.nextBytes(n); return n; }

  private static byte[] gcm(int mode, SecretKey key, byte[] nonce, String aad, byte[] input) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
    cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
    return cipher.doFinal(input);
  }
}
```

- [ ] **Step 5: Rodar e ver passar** — comando do Step 2 → verde.

- [ ] **Step 6: Commit**

```bash
git add gateway-merchants
git commit -m "feat(merchants): merchant domain, hashed api key and envelope-encrypted credential

SHA-256 with pepper instead of bcrypt because the key has 130 random
bits and bcrypt would cost 100 ms per request. AES-GCM with
AAD=merchant id so a row copied between merchants does not decrypt."
```

---

### Task 4: `merchants` — persistence, migration V100, services and module configuration

**Files:**
- Create: `gateway-merchants/src/main/resources/db/migration/merchants/V100__merchants.sql`; `repository/{MerchantEntity,MerchantJpaRepository,MerchantRepository,MerchantRepositoryImpl,ApiKeyEntity,ApiKeyJpaRepository,ApiKeyRepository,ApiKeyRepositoryImpl,ProviderCredentialEntity,ProviderCredentialJpaRepository,ProviderCredentialRepository,ProviderCredentialRepositoryImpl}.java`; `service/{MerchantService,ApiKeyService,ProviderCredentialService,MerchantsProperties}.java`; `MerchantsConfiguration.java`
- Test: `gateway-merchants/src/test/java/com/gateway/merchants/TestApp.java`, `src/test/resources/application.yml`, `service/MerchantsIntegrationTest.java`

**Interfaces:**
- Produces:
  ```java
  @ConfigurationProperties("gateway") record MerchantsProperties(String masterKey, String apiKeyPepper, Duration apiKeyRotationOverlap /* PT24H */)
  class MerchantService { Merchant create(String name); Merchant get(MerchantId); Merchant suspend(MerchantId); Merchant activate(MerchantId); List<Merchant> list(); }
  class ApiKeyService {
      record Authenticated(MerchantId merchantId, ApiKeyEnvironment environment, String apiKeyId) {}
      ApiKey.Issued issue(MerchantId, ApiKeyEnvironment);          // 2 active per environment already → DomainException("API_KEY_LIMIT")
      ApiKey.Issued rotate(MerchantId, ApiKeyEnvironment);         // issues a new one; old active ones get expiresAt = now + overlap
      Optional<Authenticated> authenticate(String plainKey);       // only ACTIVE merchants
      void revoke(MerchantId, String apiKeyId); List<ApiKey> list(MerchantId); }
  class ProviderCredentialService {
      ProviderCredential store(MerchantId, Provider, ApiKeyEnvironment, byte[] plaintext);   // upsert per (merchant, provider, environment); AAD = merchant id
      Optional<byte[]> decrypt(MerchantId, Provider, ApiKeyEnvironment);                    // only active
      List<ProviderCredential> list(MerchantId); }
  @Configuration MerchantsConfiguration — @Import of the RepositoryImpls, @Bean for services, EnvelopeCipher, MasterKey; @EnableConfigurationProperties(MerchantsProperties)
  ```
  Repositories: `MerchantRepository { save; findById(MerchantId); findAll }`, `ApiKeyRepository { save; findByPrefix(String); findActiveByMerchantAndEnvironment(MerchantId, ApiKeyEnvironment); findByMerchant(MerchantId); findById(String) }`, `ProviderCredentialRepository { save; find(MerchantId, Provider, ApiKeyEnvironment); findByMerchant(MerchantId) }`.

- [ ] **Step 1: Migration**

`V100__merchants.sql`:
```sql
-- merchants module: who the customer is and with which credentials it talks to the banks (spec model A).
-- Own schema; no other module reads these tables — modules talk through Java interfaces.

CREATE TABLE merchants (
    id         CHAR(26)     PRIMARY KEY,           -- ULID
    name       VARCHAR(200) NOT NULL,
    status     VARCHAR(20)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL,
    updated_at TIMESTAMPTZ  NOT NULL
);

-- Hash only: whoever reads the database cannot call the API. prefix = first 12 chars, to find
-- the row before comparing the hash and so the merchant recognises the key in a dashboard.
CREATE TABLE api_keys (
    id          CHAR(26)    PRIMARY KEY,
    merchant_id CHAR(26)    NOT NULL REFERENCES merchants (id),
    environment VARCHAR(10) NOT NULL,                -- LIVE | TEST
    prefix      VARCHAR(12) NOT NULL,
    hash        CHAR(64)    NOT NULL UNIQUE,         -- hex SHA-256(pepper || key)
    active      BOOLEAN     NOT NULL DEFAULT true,
    expires_at  TIMESTAMPTZ,                          -- rotation: the old key is valid until here
    created_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_api_keys_prefix ON api_keys (prefix);
CREATE INDEX idx_api_keys_merchant ON api_keys (merchant_id, environment, active);

-- AES-256-GCM envelope: payload encrypted with a per-row DEK, DEK encrypted with the master key.
-- AAD = merchant_id, so copying a row to another merchant does not decrypt.
CREATE TABLE provider_credentials (
    id            CHAR(26)    PRIMARY KEY,
    merchant_id   CHAR(26)    NOT NULL REFERENCES merchants (id),
    provider      VARCHAR(20) NOT NULL,              -- ITAU | FAKE
    environment   VARCHAR(10) NOT NULL,
    nonce         BYTEA       NOT NULL,
    ciphertext    BYTEA       NOT NULL,
    encrypted_dek BYTEA       NOT NULL,
    dek_nonce     BYTEA       NOT NULL,
    active        BOOLEAN     NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_provider_credentials UNIQUE (merchant_id, provider, environment)
);
```

- [ ] **Step 2: Harness e teste de integração**

`gateway-merchants/src/test/java/com/gateway/merchants/TestApp.java`:
```java
package com.gateway.merchants;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

/** Minimal module context: only what MerchantsConfiguration imports. No scan — the app works the same way. */
@SpringBootApplication(scanBasePackages = "com.gateway.merchants.none")
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
  master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="   # 32 zero bytes: tests only
  api-key-pepper: test-pepper
```

`service/MerchantsIntegrationTest.java`:
```java
package com.gateway.merchants.service;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.errors.DomainException;
import com.gateway.merchants.TestApp;
import com.gateway.merchants.domain.*;
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
  @Autowired ProviderCredentialService credentials;
  @Autowired JdbcTemplate jdbc;

  @Test
  void issuesAuthenticatesAndRotates() {
    Merchant m = merchants.create("Acme Store");
    ApiKey.Issued k1 = apiKeys.issue(m.id(), ApiKeyEnvironment.LIVE);

    ApiKeyService.Authenticated a = apiKeys.authenticate(k1.plainKey().reveal()).orElseThrow();
    assertThat(a.merchantId()).isEqualTo(m.id());
    assertThat(a.environment()).isEqualTo(ApiKeyEnvironment.LIVE);
    assertThat(apiKeys.authenticate("gk_live_DOESNOTEXIST00000000000000")).isEmpty();
    assertThat(apiKeys.authenticate("garbage")).isEmpty();

    ApiKey.Issued k2 = apiKeys.rotate(m.id(), ApiKeyEnvironment.LIVE);
    // both valid during the overlap window
    assertThat(apiKeys.authenticate(k1.plainKey().reveal())).isPresent();
    assertThat(apiKeys.authenticate(k2.plainKey().reveal())).isPresent();
    ApiKey old = apiKeys.list(m.id()).stream().filter(k -> k.id().equals(k1.apiKey().id())).findFirst().orElseThrow();
    assertThat(old.expiresAt()).isAfter(Instant.now());
  }

  @Test
  void atMostTwoActiveKeysPerEnvironment() {
    Merchant m = merchants.create("Store B");
    apiKeys.issue(m.id(), ApiKeyEnvironment.TEST);
    apiKeys.issue(m.id(), ApiKeyEnvironment.TEST);
    assertThatThrownBy(() -> apiKeys.issue(m.id(), ApiKeyEnvironment.TEST))
        .isInstanceOf(DomainException.class).extracting("code").isEqualTo("API_KEY_LIMIT");
  }

  @Test
  void suspendedMerchantDoesNotAuthenticate() {
    Merchant m = merchants.create("Store C");
    ApiKey.Issued k = apiKeys.issue(m.id(), ApiKeyEnvironment.LIVE);
    merchants.suspend(m.id());
    assertThat(apiKeys.authenticate(k.plainKey().reveal())).isEmpty();
  }

  @Test
  void databaseHoldsNeitherPlainKeyNorPlainCredential() {
    Merchant m = merchants.create("Store D");
    ApiKey.Issued k = apiKeys.issue(m.id(), ApiKeyEnvironment.LIVE);
    byte[] cred = "{\"client_id\":\"abc123\",\"client_secret\":\"itau-secret\"}".getBytes(StandardCharsets.UTF_8);
    credentials.store(m.id(), Provider.ITAU, ApiKeyEnvironment.LIVE, cred);

    String apiKeyDump = String.join("|", jdbc.queryForList("SELECT hash || prefix FROM merchants.api_keys", String.class));
    assertThat(apiKeyDump).doesNotContain(k.plainKey().reveal());
    byte[] ciphertext = jdbc.queryForObject("SELECT ciphertext FROM merchants.provider_credentials WHERE merchant_id = ?", byte[].class, m.id().value());
    assertThat(new String(ciphertext, StandardCharsets.ISO_8859_1)).doesNotContain("itau-secret").doesNotContain("abc123");

    assertThat(credentials.decrypt(m.id(), Provider.ITAU, ApiKeyEnvironment.LIVE)).contains(cred);
    assertThat(credentials.decrypt(m.id(), Provider.ITAU, ApiKeyEnvironment.TEST)).isEmpty();
  }

  @Test
  void storingAgainReplacesThePayload() {
    Merchant m = merchants.create("Store E");
    credentials.store(m.id(), Provider.ITAU, ApiKeyEnvironment.LIVE, "v1".getBytes());
    credentials.store(m.id(), Provider.ITAU, ApiKeyEnvironment.LIVE, "v2".getBytes());
    assertThat(credentials.list(m.id())).hasSize(1);
    assertThat(credentials.decrypt(m.id(), Provider.ITAU, ApiKeyEnvironment.LIVE)).contains("v2".getBytes());
  }
}
```

- [ ] **Step 3: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-merchants -am test -Dtest=MerchantsIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → erro de compilação.

- [ ] **Step 4: Entities e repositories**

Padrão (sem Lombok; campos package-private acessados pelo mapper no mesmo pacote; Hibernate usa acesso por campo porque `@Id` está no campo):

`MerchantEntity.java`:
```java
package com.gateway.merchants.repository;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "merchants", schema = "merchants")
class MerchantEntity {
  @Id @Column(name = "id", length = 26, nullable = false) String id;
  @Column(name = "name", nullable = false, length = 200) String name;
  @Column(name = "status", nullable = false, length = 20) String status;
  @Column(name = "created_at", nullable = false) Instant createdAt;
  @Column(name = "updated_at", nullable = false) Instant updatedAt;
  protected MerchantEntity() {}
}
```
`ApiKeyEntity.java`: colunas `id, merchant_id, environment, prefix, hash, active, expires_at, created_at`.
`ProviderCredentialEntity.java`: colunas `id, merchant_id, provider, environment, nonce (byte[]), ciphertext (byte[]), encrypted_dek (byte[]), dek_nonce (byte[]), active, created_at, updated_at`.

JPA repositories:
```java
interface MerchantJpaRepository extends JpaRepository<MerchantEntity, String> {}
interface ApiKeyJpaRepository extends JpaRepository<ApiKeyEntity, String> {
  List<ApiKeyEntity> findByPrefix(String prefix);
  List<ApiKeyEntity> findByMerchantIdAndEnvironmentAndActiveTrue(String merchantId, String environment);
  List<ApiKeyEntity> findByMerchantIdOrderByCreatedAtAsc(String merchantId);
}
interface ProviderCredentialJpaRepository extends JpaRepository<ProviderCredentialEntity, String> {
  Optional<ProviderCredentialEntity> findByMerchantIdAndProviderAndEnvironment(String merchantId, String provider, String environment);
  List<ProviderCredentialEntity> findByMerchantId(String merchantId);
}
```

Interfaces de domínio (públicas) e `*RepositoryImpl` (`public`, `@Repository`, construtor público, mapeando entity ↔ record com `MerchantId`, enums e `Encrypted`). `save` faz `findById(...).orElseGet(new)` e copia campos — o mesmo padrão de `WebhookEndpointRepositoryImpl` em `C:\Dev\webhook-delivery`.

- [ ] **Step 5: Services e configuração**

`MerchantsProperties.java`:
```java
package com.gateway.merchants.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record MerchantsProperties(String masterKey, String apiKeyPepper, Duration apiKeyRotationOverlap) {
  public MerchantsProperties {
    if (apiKeyRotationOverlap == null) apiKeyRotationOverlap = Duration.ofHours(24);
    if (apiKeyPepper == null || apiKeyPepper.isBlank()) throw new IllegalArgumentException("gateway.api-key-pepper is missing");
  }
}
```

`MerchantService.java`:
```java
package com.gateway.merchants.service;

import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.domain.Merchant;
import com.gateway.merchants.repository.MerchantRepository;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class MerchantService {
  private final MerchantRepository repo;
  public MerchantService(MerchantRepository repo) { this.repo = repo; }

  @Transactional public Merchant create(String name) { return repo.save(Merchant.create(name)); }
  @Transactional(readOnly = true) public Merchant get(MerchantId id) { return repo.findById(id).orElseThrow(() -> new NotFoundException("merchant", id.value())); }
  @Transactional public Merchant suspend(MerchantId id) { return repo.save(get(id).suspend()); }
  @Transactional public Merchant activate(MerchantId id) { return repo.save(get(id).activate()); }
  @Transactional(readOnly = true) public List<Merchant> list() { return repo.findAll(); }
}
```

`ApiKeyService.java`:
```java
package com.gateway.merchants.service;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.domain.ApiKey;
import com.gateway.merchants.domain.ApiKeyEnvironment;
import com.gateway.merchants.repository.ApiKeyRepository;
import com.gateway.merchants.repository.MerchantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

public class ApiKeyService {
  public record Authenticated(MerchantId merchantId, ApiKeyEnvironment environment, String apiKeyId) {}

  private static final int MAX_ACTIVE = 2;
  private final ApiKeyRepository repo;
  private final MerchantRepository merchants;
  private final MerchantsProperties props;

  public ApiKeyService(ApiKeyRepository repo, MerchantRepository merchants, MerchantsProperties props) {
    this.repo = repo; this.merchants = merchants; this.props = props;
  }

  /** At most two active: that is what overlap rotation needs, and anything beyond is a forgotten key. */
  @Transactional
  public ApiKey.Issued issue(MerchantId merchantId, ApiKeyEnvironment environment) {
    if (repo.findActiveByMerchantAndEnvironment(merchantId, environment).size() >= MAX_ACTIVE) {
      throw new DomainException("API_KEY_LIMIT", "there are already " + MAX_ACTIVE + " active keys in " + environment + "; revoke or rotate");
    }
    ApiKey.Issued issued = ApiKey.issue(merchantId, environment, props.apiKeyPepper());
    repo.save(issued.apiKey());
    return issued;
  }

  /** Issues the new key and gives the old ones a deadline: the merchant switches when it can, no agreed instant needed. */
  @Transactional
  public ApiKey.Issued rotate(MerchantId merchantId, ApiKeyEnvironment environment) {
    Instant deadline = Instant.now().plus(props.apiKeyRotationOverlap());
    for (ApiKey k : repo.findActiveByMerchantAndEnvironment(merchantId, environment)) {
      repo.save(k.expiringAt(k.expiresAt() == null || k.expiresAt().isAfter(deadline) ? deadline : k.expiresAt()));
    }
    // The old ones stay "active" with a deadline, so the cap of 2 counts them: rotating with 2 active
    // must work. That is why issuing here bypasses the cap.
    ApiKey.Issued issued = ApiKey.issue(merchantId, environment, props.apiKeyPepper());
    repo.save(issued.apiKey());
    return issued;
  }

  /**
   * Looks up by prefix and compares the hash in constant time. The prefix narrows the search to one
   * row (or a few, if two merchants drew the same 4 chars — hence the full comparison).
   */
  @Transactional(readOnly = true)
  public Optional<Authenticated> authenticate(String plainKey) {
    if (ApiKey.environmentOf(plainKey).isEmpty()) return Optional.empty();
    byte[] hash = ApiKey.hashOf(plainKey, props.apiKeyPepper()).getBytes(StandardCharsets.UTF_8);
    Instant now = Instant.now();
    return repo.findByPrefix(ApiKey.prefixOf(plainKey)).stream()
        .filter(k -> MessageDigest.isEqual(hash, k.hash().getBytes(StandardCharsets.UTF_8)))
        .filter(k -> k.isValid(now))
        .filter(k -> merchants.findById(k.merchantId()).map(m -> m.isActive()).orElse(false))
        .findFirst()
        .map(k -> new Authenticated(k.merchantId(), k.environment(), k.id()));
  }

  @Transactional
  public void revoke(MerchantId merchantId, String apiKeyId) {
    repo.findById(apiKeyId).filter(k -> k.merchantId().equals(merchantId)).map(ApiKey::revoke).ifPresent(repo::save);
  }

  @Transactional(readOnly = true)
  public List<ApiKey> list(MerchantId merchantId) { return repo.findByMerchant(merchantId); }
}
```

`ProviderCredentialService.java`:
```java
package com.gateway.merchants.service;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.crypto.Encrypted;
import com.gateway.merchants.crypto.EnvelopeCipher;
import com.gateway.merchants.domain.ApiKeyEnvironment;
import com.gateway.merchants.domain.Provider;
import com.gateway.merchants.domain.ProviderCredential;
import com.gateway.merchants.repository.ProviderCredentialRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** The only place that decrypts credentials — and it returns bytes, never caches them. */
public class ProviderCredentialService {
  private final ProviderCredentialRepository repo;
  private final EnvelopeCipher cipher;

  public ProviderCredentialService(ProviderCredentialRepository repo, EnvelopeCipher cipher) { this.repo = repo; this.cipher = cipher; }

  @Transactional
  public ProviderCredential store(MerchantId m, Provider p, ApiKeyEnvironment e, byte[] plaintext) {
    Encrypted enc = cipher.encrypt(plaintext, m.value());
    ProviderCredential cred = repo.find(m, p, e).map(x -> x.withPayload(enc)).orElseGet(() -> ProviderCredential.create(m, p, e, enc));
    return repo.save(cred);
  }

  @Transactional(readOnly = true)
  public Optional<byte[]> decrypt(MerchantId m, Provider p, ApiKeyEnvironment e) {
    return repo.find(m, p, e).filter(ProviderCredential::active).map(c -> cipher.decrypt(c.payload(), m.value()));
  }

  @Transactional(readOnly = true)
  public List<ProviderCredential> list(MerchantId m) { return repo.findByMerchant(m); }
}
```

`MerchantsConfiguration.java`:
```java
package com.gateway.merchants;

import com.gateway.merchants.crypto.EnvelopeCipher;
import com.gateway.merchants.crypto.MasterKey;
import com.gateway.merchants.repository.*;
import com.gateway.merchants.service.*;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * What the module exposes, chosen one by one. No component scan: the app imports this class and
 * knows exactly what came in. {@code @EntityScan}/{@code @EnableJpaRepositories} point only at this
 * module's package — each module declares its own and Boot merges them.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantsProperties.class)
@EntityScan("com.gateway.merchants.repository")
@EnableJpaRepositories("com.gateway.merchants.repository")
@Import({MerchantRepositoryImpl.class, ApiKeyRepositoryImpl.class, ProviderCredentialRepositoryImpl.class})
public class MerchantsConfiguration {
  @Bean public MasterKey masterKey(MerchantsProperties p) { return MasterKey.fromBase64(p.masterKey()); }
  @Bean public EnvelopeCipher envelopeCipher(MasterKey m) { return new EnvelopeCipher(m); }
  @Bean public MerchantService merchantService(MerchantRepository r) { return new MerchantService(r); }
  @Bean public ApiKeyService apiKeyService(ApiKeyRepository r, MerchantRepository m, MerchantsProperties p) { return new ApiKeyService(r, m, p); }
  @Bean public ProviderCredentialService providerCredentialService(ProviderCredentialRepository r, EnvelopeCipher c) { return new ProviderCredentialService(r, c); }
}
```
**Atenção (a lib `webhook-delivery` documenta isto):** um `@EntityScan`/`@EnableJpaRepositories` explícito faz o Boot ignorar o pacote que a lib registra via `AutoConfigurationPackages`. Na Task 6 o `app` verifica: se as entidades da lib não forem encontradas, o `app` passa a declarar `@EntityScan({"com.gateway.merchants.repository", "com.barrier.webhookdelivery.repository"})` e `@EnableJpaRepositories` idem, e estas anotações saem daqui (o `TestApp` do módulo passa a declarar as suas). Registre a decisão no relatório.

- [ ] **Step 6: Rodar e ver passar** — comando do Step 3 → 5 testes verdes.

- [ ] **Step 7: Commit**

```bash
git add gateway-merchants
git commit -m "feat(merchants): persistence, migration V100 and merchant, api key and credential services

Authentication looks up by prefix and compares the hash in constant
time; rotation gives old keys a deadline instead of killing them. The
test proves the database dump holds neither a plain key nor a plain
credential."
```

---

### Task 5: `app` — assembly, ArchUnit, API key auth, admin key, rate limit, errors

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/{AppConfiguration,security/MerchantContext,security/ApiKeyAuthFilter,security/AdminKeyFilter,security/RateLimitFilter,security/Problems,api/ErrorHandler,api/MeController,api/admin/MerchantsAdminController,api/admin/dto/*}.java`
- Modify: `GatewayApplication.java` (`@Import(MerchantsConfiguration.class)`)
- Test: `gateway-app/src/test/java/com/gateway/app/architecture/ArchitectureTest.java`, `gateway-app/src/test/java/com/gateway/app/AuthenticationIntegrationTest.java`, `src/test/resources/application-test.yml`, `src/test/resources/archunit.properties`

**Interfaces:**
- Produces:
  - `MerchantContext` — `record Current(MerchantId merchantId, ApiKeyEnvironment environment, String apiKeyId)`; `static Current current()` lê o atributo da request; lança `IllegalStateException` fora de request autenticada.
  - Rotas: `POST /v1/admin/merchants {name}` → 201 `{id, name, status}`; `GET /v1/admin/merchants`, `GET /{id}`; `POST /{id}/suspend`, `POST /{id}/activate`; `POST /v1/admin/merchants/{id}/api-keys {environment}` → 201 `{id, prefix, environment, key, warning}` (a chave aparece UMA vez); `POST /{id}/api-keys/rotate {environment}` → 201 idem; `DELETE /{id}/api-keys/{keyId}` → 204; `PUT /{id}/providers/{provider}/credentials {environment, payload: {...}}` → 204 sem corpo; `GET /v1/me` → `{merchant_id, name, environment}`.
  - Erros: `DomainException` → 422 `ProblemDetail{type: "urn:gateway:"+code, detail}`; `NotFoundException` → 404; `IllegalArgumentException` → 400 `urn:gateway:INVALID_REQUEST`; sem API key/inválida → 401 `urn:gateway:UNAUTHENTICATED`; admin key errada → 403 `urn:gateway:FORBIDDEN`; rate limit → 429 com `Retry-After`.

- [ ] **Step 1: ArchUnit**

```java
package com.gateway.app.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;
import static org.assertj.core.api.Assertions.assertThat;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** The module boundary from spec §2, enforced by test. Each rule is named so a failure says which boundary fell. */
@AnalyzeClasses(packages = "com.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

  @ArchTest
  static void importSeesTheModules(JavaClasses classes) {
    assertThat(classes.size()).as("ArchUnit imported too few classes; the rules would pass vacuously").isGreaterThan(30);
  }

  @ArchTest
  static final ArchRule kernelImportsNothing =
      noClasses().that().resideInAPackage("com.gateway.kernel..")
          .should().dependOnClassesThat().resideInAnyPackage("com.gateway.merchants..", "com.gateway.orders..", "com.gateway.payments..",
              "com.gateway.providers..", "com.gateway.app..", "org.springframework..", "jakarta.persistence..", "com.barrier..");

  @ArchTest
  static final ArchRule nobodyImportsApp =
      noClasses().that().resideOutsideOfPackage("com.gateway.app..").should().dependOnClassesThat().resideInAPackage("com.gateway.app..");

  @ArchTest
  static final ArchRule businessModulesDoNotImportEachOther =
      noClasses().that().resideInAPackage("com.gateway.merchants..")
          .should().dependOnClassesThat().resideInAnyPackage("com.gateway.orders..", "com.gateway.payments..", "com.gateway.providers..");

  @ArchTest
  static final ArchRule onlyPaymentsKnowsProviders =
      noClasses().that().resideOutsideOfPackages("com.gateway.payments..", "com.gateway.providers..", "com.gateway.app..")
          .should().dependOnClassesThat().resideInAPackage("com.gateway.providers..");

  @ArchTest
  static final ArchRule jpaEntitiesArePackagePrivate =
      classes().that().areAnnotatedWith(jakarta.persistence.Entity.class).should().bePackagePrivate();

  @ArchTest
  static final ArchRule domainHasNoSpringOrJpa =
      noClasses().that().resideInAPackage("..domain..")
          .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta.persistence..");
}
```
`archunit.properties`: `archRule.failOnEmptyShould=false`. As regras sobre `orders/payments/providers` passam vacuamente até os Planos B/C — é o objetivo.

- [ ] **Step 2: Teste de integração de autenticação e admin**

`src/test/resources/application-test.yml`:
```yaml
gateway:
  admin-key: test-admin
  master-key: "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
  api-key-pepper: test-pepper
  rate-limit:
    requests-per-minute: 5
spring:
  flyway:
    schemas: merchants
    locations: classpath:db/migration/merchants
```

`AuthenticationIntegrationTest.java`:
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
class AuthenticationIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @Autowired TestRestTemplate http;

  private HttpHeaders admin() { HttpHeaders h = new HttpHeaders(); h.set("X-Admin-Key", "test-admin"); h.setContentType(MediaType.APPLICATION_JSON); return h; }
  private HttpHeaders bearer(String key) { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(key); return h; }

  @SuppressWarnings("unchecked")
  private Map<String, Object> merchantAndKey(String name, String environment) {
    ResponseEntity<Map> m = http.postForEntity("/v1/admin/merchants", new HttpEntity<>(Map.of("name", name), admin()), Map.class);
    assertThat(m.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String id = (String) m.getBody().get("id");
    ResponseEntity<Map> k = http.postForEntity("/v1/admin/merchants/" + id + "/api-keys", new HttpEntity<>(Map.of("environment", environment), admin()), Map.class);
    assertThat(k.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    Map<String, Object> body = k.getBody();
    body.put("merchant_id", id);
    return body;
  }

  @Test
  void adminWithoutKeyIs403AndNoApiKeyIs401() {
    assertThat(http.postForEntity("/v1/admin/merchants", new HttpEntity<>(Map.of("name", "x")), String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    assertThat(http.getForEntity("/v1/me", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(http.exchange("/v1/me", HttpMethod.GET, new HttpEntity<>(bearer("gk_live_INVALID0000000000000000000")), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void issuedKeyAuthenticatesAndMeReturnsTheMerchant() {
    Map<String, Object> k = merchantAndKey("Acme Store", "TEST");
    String key = (String) k.get("key");
    assertThat(key).startsWith("gk_test_");
    ResponseEntity<Map> me = http.exchange("/v1/me", HttpMethod.GET, new HttpEntity<>(bearer(key)), Map.class);
    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(me.getBody()).containsEntry("merchant_id", k.get("merchant_id")).containsEntry("environment", "TEST").containsEntry("name", "Acme Store");
  }

  @Test
  void revokedKeyStopsWorking() {
    Map<String, Object> k = merchantAndKey("Store B", "LIVE");
    http.exchange("/v1/admin/merchants/" + k.get("merchant_id") + "/api-keys/" + k.get("id"), HttpMethod.DELETE, new HttpEntity<>(admin()), Void.class);
    assertThat(http.exchange("/v1/me", HttpMethod.GET, new HttpEntity<>(bearer((String) k.get("key"))), String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
  }

  @Test
  void rateLimitPerKeyReturns429WithRetryAfter() {
    Map<String, Object> k = merchantAndKey("Store C", "TEST");
    HttpEntity<Void> req = new HttpEntity<>(bearer((String) k.get("key")));
    for (int i = 0; i < 5; i++) assertThat(http.exchange("/v1/me", HttpMethod.GET, req, String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    ResponseEntity<String> r = http.exchange("/v1/me", HttpMethod.GET, req, String.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    assertThat(r.getHeaders().getFirst("Retry-After")).isNotBlank();
  }

  @Test
  void providerCredentialIsAcceptedAndNeverReturned() {
    Map<String, Object> k = merchantAndKey("Store D", "LIVE");
    ResponseEntity<String> r = http.exchange("/v1/admin/merchants/" + k.get("merchant_id") + "/providers/ITAU/credentials", HttpMethod.PUT,
        new HttpEntity<>(Map.of("environment", "LIVE", "payload", Map.of("client_id", "abc", "client_secret", "secret")), admin()), String.class);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    assertThat(r.getBody()).isNull();
  }

  @Test
  void domainErrorsBecomeProblemDetails() {
    Map<String, Object> k = merchantAndKey("Store E", "TEST");
    http.postForEntity("/v1/admin/merchants/" + k.get("merchant_id") + "/api-keys", new HttpEntity<>(Map.of("environment", "TEST"), admin()), Map.class);
    ResponseEntity<Map> third = http.postForEntity("/v1/admin/merchants/" + k.get("merchant_id") + "/api-keys", new HttpEntity<>(Map.of("environment", "TEST"), admin()), Map.class);
    assertThat(third.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    assertThat(third.getBody()).containsEntry("type", "urn:gateway:API_KEY_LIMIT");
  }
}
```

- [ ] **Step 3: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-app -am test -Dtest='ArchitectureTest,AuthenticationIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false` → erro de compilação.

- [ ] **Step 4: Implementar a borda**

`GatewayApplication`: adicione `@Import(MerchantsConfiguration.class)`.

`AppConfiguration.java`:
```java
package com.gateway.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppConfiguration.AppProperties.class)
public class AppConfiguration {
  /** Same "gateway" prefix as MerchantsProperties; each record binds only the fields it declares. */
  @ConfigurationProperties(prefix = "gateway")
  public record AppProperties(String adminKey, RateLimit rateLimit) {
    public AppProperties { if (rateLimit == null) rateLimit = new RateLimit(600); }
    public record RateLimit(int requestsPerMinute) { public RateLimit { if (requestsPerMinute <= 0) requestsPerMinute = 600; } }
  }
}
```

`security/MerchantContext.java`:
```java
package com.gateway.app.security;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.domain.ApiKeyEnvironment;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Who is calling. A request attribute, not a ThreadLocal of our own: with virtual threads the request is what matters. */
public final class MerchantContext {
  public record Current(MerchantId merchantId, ApiKeyEnvironment environment, String apiKeyId) {}
  static final String ATTRIBUTE = MerchantContext.class.getName();

  private MerchantContext() {}

  static void set(HttpServletRequest req, Current current) { req.setAttribute(ATTRIBUTE, current); }

  public static Current current() {
    var attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    Object a = attrs == null ? null : attrs.getRequest().getAttribute(ATTRIBUTE);
    if (a == null) throw new IllegalStateException("no authenticated merchant on this request");
    return (Current) a;
  }
}
```

`security/ApiKeyAuthFilter.java`:
```java
package com.gateway.app.security;

import com.gateway.merchants.service.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer gk_…} on everything under /v1/ except /v1/admin/**
 * and /v1/providers/** (inbound webhooks, authenticated by the provider) and the actuator.
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
    if (auth == null || !auth.startsWith("Bearer ")) { Problems.write(res, 401, "UNAUTHENTICATED", "send Authorization: Bearer gk_…"); return; }
    var current = apiKeys.authenticate(auth.substring(7).trim());
    if (current.isEmpty()) { Problems.write(res, 401, "UNAUTHENTICATED", "invalid or revoked api key, or suspended merchant"); return; }
    MerchantContext.set(req, new MerchantContext.Current(current.get().merchantId(), current.get().environment(), current.get().apiKeyId()));
    chain.doFilter(req, res);
  }
}
```

`security/Problems.java` (package-private helper que escreve `application/problem+json` `{"type":"urn:gateway:<code>","title":<code>,"status":n,"detail":…}` — ~10 linhas com `String.format` e escape mínimo).

`security/AdminKeyFilter.java`: `@Order(10)`, filtra só `/v1/admin/**`; compara `X-Admin-Key` com `gateway.admin-key` em tempo constante (`MessageDigest.isEqual`); admin key vazia na config → **403 sempre** (never "open by default"); erro → 403 `FORBIDDEN`.

`security/RateLimitFilter.java`: `@Order(30)`; após a autenticação (usa `MerchantContext`, e só filtra as rotas que `ApiKeyAuthFilter` filtra), `ConcurrentHashMap<String /*apiKeyId*/, Bucket>` com `Bucket.builder().addLimit(Bandwidth.builder().capacity(n).refillGreedy(n, Duration.ofMinutes(1)).build()).build()`; `tryConsumeAndReturnRemaining(1)`; se negado → 429 com `Retry-After` = segundos até o próximo token (`ceil(probe.getNanosToWaitForRefill()/1e9)`, mínimo 1). Comentário: in-memory because there is one instance; Redis when there are more (spec §8). Se a API do Bucket4j 8.14 diferir (`Bandwidth.classic`/`Refill.greedy` nas versões antigas), use a que existir e registre.

`api/ErrorHandler.java` (`@RestControllerAdvice`): `NotFoundException` → 404 (antes do genérico, é subclasse); `DomainException` → 422 `ProblemDetail` com `type = URI.create("urn:gateway:" + code)`; `IllegalArgumentException` → 400 `urn:gateway:INVALID_REQUEST`; `IllegalStateException` de `MerchantContext` → 401 `UNAUTHENTICATED`.

`api/MeController.java`: `GET /v1/me` → `{merchant_id, name, environment}` via `MerchantService.get(MerchantContext.current().merchantId())`.

`api/admin/MerchantsAdminController.java` (`@RequestMapping("/v1/admin/merchants")`):
- `POST` `{name}` → 201 `{id, name, status}`; `GET`, `GET /{id}`; `POST /{id}/suspend`, `POST /{id}/activate` → 200 com o merchant.
- `POST /{id}/api-keys` `{environment}` → 201 `{id, prefix, environment, key, warning: "Store it now: this value cannot be retrieved again."}`.
- `POST /{id}/api-keys/rotate` `{environment}` → 201, mesmo corpo.
- `DELETE /{id}/api-keys/{keyId}` → 204.
- `PUT /{id}/providers/{provider}/credentials` `{environment, payload: <JSON object>}` → serializa `payload` com o `ObjectMapper` do contexto (`tools.jackson.databind.ObjectMapper` no Boot 4) e chama `ProviderCredentialService.store` → 204 sem corpo.
DTOs como records em `api/admin/dto/`. JSON em snake_case (já configurado no `application.yml`).

- [ ] **Step 5: Rodar e ver passar** — comando do Step 3 → verdes (ArchUnit + 6 testes de integração).

- [ ] **Step 6: Commit**

```bash
git add gateway-app
git commit -m "feat(app): assembly, archunit, api key auth, admin key, rate limit and errors

Module boundary enforced by test from the first commit, with the
orders/payments/providers rules already written. An empty admin key
closes the admin instead of opening it. Rate limit in memory because
there is one instance; Redis when there are more."
```

---

### Task 6: Outbound webhooks via `webhook-delivery` and the merchant self-service API

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/api/WebhookEndpointsController.java`, `api/dto/{RegisterEndpointRequest,EndpointResponse,EndpointWithSecretResponse}.java`, `gateway-app/src/main/java/com/gateway/app/webhooks/MerchantEvents.java`
- Modify: `GatewayApplication` se precisar de `@EntityScan` (nota da Task 4)
- Test: `gateway-app/src/test/java/com/gateway/app/WebhooksIntegrationTest.java`

**Interfaces:**
- Produces:
  - Rotas (autenticadas por API key; `tenantId = merchantId.value()`): `POST /v1/webhooks/endpoints {url, events?: [...]}` → 201 `{id, url, events, active, secret, warning}`; `GET /v1/webhooks/endpoints` → lista sem segredo; `GET /v1/webhooks/endpoints/{id}`; `PUT /v1/webhooks/endpoints/{id} {url, events}` → 200; `POST /v1/webhooks/endpoints/{id}/rotate-secret` → 200 com `secret` e `previous_secret_until`; `DELETE /v1/webhooks/endpoints/{id}` → 200 desativado. Um merchant **só vê os próprios** (id de outro → 404).
  - `MerchantEvents.emit(MerchantId, String eventType, String aggregateId, String partitionKey, Object payload) -> IntakeResult` → serializa com o `ObjectMapper`, `eventId = UUID.randomUUID()`, chama `DeliveryIntake.accept(new DeliveryRequest(merchantId.value(), eventType, eventId, aggregateId, partitionKey, json, MDC "correlationId"))`. Único ponto que fala com a lib; Planos B/C chamam isto.

- [ ] **Step 1: Teste**

```java
package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.webhookdelivery.client.HmacSigner;
import com.gateway.app.webhooks.MerchantEvents;
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

  record Received(String body, Map<String, String> headers) {}
  static final List<Received> received = new CopyOnWriteArrayList<>();
  static HttpServer sink;

  @BeforeAll static void start() throws Exception {
    sink = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    sink.createContext("/hook", ex -> {
      Map<String, String> h = new ConcurrentHashMap<>();
      ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.getFirst()));
      received.add(new Received(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), h));
      ex.sendResponseHeaders(200, -1); ex.close();
    });
    sink.start();
  }
  @AfterAll static void stop() { sink.stop(0); }

  @Autowired TestRestTemplate http;
  @Autowired MerchantEvents events;
  @Autowired HmacSigner signer;

  private HttpHeaders admin() { HttpHeaders h = new HttpHeaders(); h.set("X-Admin-Key", "test-admin"); h.setContentType(MediaType.APPLICATION_JSON); return h; }
  private HttpHeaders bearer(String k) { HttpHeaders h = new HttpHeaders(); h.setBearerAuth(k); h.setContentType(MediaType.APPLICATION_JSON); return h; }

  @SuppressWarnings("unchecked")
  private String[] merchantAndKey(String name) {
    Map<String, Object> m = http.postForEntity("/v1/admin/merchants", new HttpEntity<>(Map.of("name", name), admin()), Map.class).getBody();
    Map<String, Object> k = http.postForEntity("/v1/admin/merchants/" + m.get("id") + "/api-keys", new HttpEntity<>(Map.of("environment", "TEST"), admin()), Map.class).getBody();
    return new String[] {(String) m.get("id"), (String) k.get("key")};
  }

  private String url() { return "http://localhost:" + sink.getAddress().getPort() + "/hook"; }

  @Test
  @SuppressWarnings("unchecked")
  void registersEndpointAndReceivesSignedEventWithXGatewayHeaders() {
    String[] mk = merchantAndKey("Store A");
    ResponseEntity<Map> created = http.postForEntity("/v1/webhooks/endpoints", new HttpEntity<>(Map.of("url", url(), "events", List.of("payment.*")), bearer(mk[1])), Map.class);
    assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    String secret = (String) created.getBody().get("secret");
    assertThat(secret).isNotBlank();

    events.emit(new MerchantId(mk[0]), "payment.completed", "pay_1", "pay_1", Map.of("id", "pay_1", "status", "COMPLETED"));
    events.emit(new MerchantId(mk[0]), "refund.completed", "ref_1", "pay_1", Map.of("id", "ref_1"));

    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
    Received r = received.getFirst();
    assertThat(r.headers()).containsKey("x-gateway-signature").containsKey("x-gateway-event-id").containsEntry("x-gateway-event-type", "payment.completed");
    String signature = r.headers().get("x-gateway-signature");
    long t = Long.parseLong(signature.substring(2, signature.indexOf(',')));
    assertThat(signature).isEqualTo(signer.sign(r.body(), secret, Instant.ofEpochSecond(t)));
    assertThat(r.body()).contains("\"status\":\"COMPLETED\"");
  }

  @Test
  @SuppressWarnings("unchecked")
  void merchantCannotSeeAnotherMerchantsEndpoint() {
    String[] a = merchantAndKey("Store B"), b = merchantAndKey("Store C");
    Map<String, Object> e = http.postForEntity("/v1/webhooks/endpoints", new HttpEntity<>(Map.of("url", url()), bearer(a[1])), Map.class).getBody();
    assertThat(http.exchange("/v1/webhooks/endpoints/" + e.get("id"), HttpMethod.GET, new HttpEntity<>(bearer(b[1])), String.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    ResponseEntity<List> list = http.exchange("/v1/webhooks/endpoints", HttpMethod.GET, new HttpEntity<>(bearer(b[1])), List.class);
    assertThat(list.getBody()).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void getHidesTheSecretAndRotationReturnsANewOne() {
    String[] mk = merchantAndKey("Store D");
    Map<String, Object> e = http.postForEntity("/v1/webhooks/endpoints", new HttpEntity<>(Map.of("url", url()), bearer(mk[1])), Map.class).getBody();
    Map<String, Object> get = http.exchange("/v1/webhooks/endpoints/" + e.get("id"), HttpMethod.GET, new HttpEntity<>(bearer(mk[1])), Map.class).getBody();
    assertThat(get).doesNotContainKey("secret");
    Map<String, Object> rot = http.postForEntity("/v1/webhooks/endpoints/" + e.get("id") + "/rotate-secret", new HttpEntity<>(bearer(mk[1])), Map.class).getBody();
    assertThat((String) rot.get("secret")).isNotEqualTo(e.get("secret"));
    assertThat(rot).containsKey("previous_secret_until");
  }
}
```

- [ ] **Step 2: Rodar e ver falhar** — `./mvnw -B -q -pl gateway-app -am test -Dtest=WebhooksIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false` → compilação falha.

- [ ] **Step 3: Implementar**

`webhooks/MerchantEvents.java`:
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
 * The only point in the gateway that talks to webhook-delivery. Business modules (plans B and C)
 * emit through here from the outbox relay — never from the request thread, because delivery is
 * asynchronous by construction and the library does not deliver on accept either.
 */
@Component
public class MerchantEvents {
  private final DeliveryIntake intake;
  private final ObjectMapper mapper;

  public MerchantEvents(DeliveryIntake intake, ObjectMapper mapper) { this.intake = intake; this.mapper = mapper; }

  public IntakeResult emit(MerchantId merchantId, String eventType, String aggregateId, String partitionKey, Object payload) {
    String json = mapper.writeValueAsString(payload);
    return intake.accept(new DeliveryRequest(merchantId.value(), eventType, UUID.randomUUID(), aggregateId, partitionKey, json, MDC.get("correlationId")));
  }
}
```
Se `writeValueAsString` do Jackson 3 lançar checked exception, envolva em `IllegalStateException`.

`api/WebhookEndpointsController.java` (`@RequestMapping("/v1/webhooks/endpoints")`) sobre `com.barrier.webhookdelivery.service.WebhookEndpointService`:
- helper `private WebhookEndpoint mine(UUID id)` = `service.find(id).filter(e -> e.tenantId().equals(MerchantContext.current().merchantId().value())).orElseThrow(() -> new NotFoundException("endpoint", id.toString()))`.
- `POST` → `service.register(tenant, url, events)` → 201 `EndpointWithSecretResponse`.
- `GET` → `service.listByTenant(tenant)` → `EndpointResponse` (sem segredo).
- `GET /{id}`, `PUT /{id}` (`service.update(id, url, events)` após `mine(id)`), `POST /{id}/rotate-secret` (`service.rotateSecret`), `DELETE /{id}` (`service.deactivate`).
- `IllegalArgumentException` da validação de URL da lib → 400 pelo `ErrorHandler`.

DTOs: `EndpointResponse(id, url, events, active, previousSecretUntil, createdAt, updatedAt)` (snake_case na serialização); `EndpointWithSecretResponse` = o mesmo + `secret` + `warning`.

**Entity scan**: rode o teste; se o contexto reclamar que `DeliveryEntity` não é uma entidade gerenciada, aplique a decisão da nota da Task 4: mova `@EntityScan`/`@EnableJpaRepositories` para `GatewayApplication` listando `com.gateway.merchants.repository` e `com.barrier.webhookdelivery.repository`, remova das configurações de módulo (o `TestApp` do módulo passa a declarar as suas). Registre no relatório.

- [ ] **Step 4: Rodar e ver passar** — comando do Step 2 → 3 verdes. Depois a suíte inteira: `./mvnw -B test` → tudo verde.

- [ ] **Step 5: Commit**

```bash
git add gateway-app
git commit -m "feat(app): outbound webhooks through webhook-delivery with merchant self-service

X-Gateway prefix, tenant = merchant. A merchant only sees its own
endpoints; the secret shows up only on registration and rotation.
Plans B and C emit through MerchantEvents, never straight to the lib."
```

---

### Task 7: Base observability — correlation, JSON logs with `Masker`, actuator

**Files:**
- Create: `gateway-app/src/main/java/com/gateway/app/observability/{CorrelationFilter,Masker,MaskingJsonProvider}.java`, `gateway-app/src/main/resources/logback-spring.xml`
- Test: `gateway-app/src/test/java/com/gateway/app/observability/MaskerTest.java`, `observability/MaskingJsonProviderTest.java`, `gateway-app/src/test/java/com/gateway/app/ObservabilityIntegrationTest.java`

**Interfaces:**
- Produces: `Masker.mask(String) -> String` (estático); header `X-Correlation-Id` aceito na entrada e sempre devolvido; MDC `correlationId`; `/actuator/prometheus` e `/actuator/health` públicos.

- [ ] **Step 1: Testes**

`MaskerTest.java`:
```java
package com.gateway.app.observability;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class MaskerTest {
  @Test void masksCpfWithAndWithoutPunctuation() {
    assertThat(Masker.mask("cpf 123.456.789-09 and 12345678909")).isEqualTo("cpf *** and ***");
  }
  @Test void masksApiKeyAndBearer() {
    assertThat(Masker.mask("Authorization: Bearer gk_live_01ARZ3NDEKTSV4RRFFQ69G5FAV")).isEqualTo("Authorization: Bearer ***");
    assertThat(Masker.mask("key gk_test_01ARZ3NDEKTSV4RRFFQ69G5FAV used")).isEqualTo("key *** used");
  }
  @Test void masksSecretJsonFields() {
    assertThat(Masker.mask("{\"client_secret\":\"abc\",\"secret\":\"x\",\"pix_copia_e_cola\":\"000201…\",\"name\":\"ok\"}"))
        .isEqualTo("{\"client_secret\":\"***\",\"secret\":\"***\",\"pix_copia_e_cola\":\"***\",\"name\":\"ok\"}");
  }
  @Test void leavesTheRestAlone() {
    assertThat(Masker.mask("payment pay_01ARZ3 COMPLETED at 15990 cents")).isEqualTo("payment pay_01ARZ3 COMPLETED at 15990 cents");
  }
}
```

`MaskingJsonProviderTest.java`: constrói um `ch.qos.logback.classic.spi.LoggingEvent` com mensagem `"key gk_live_01ARZ3NDEKTSV4RRFFQ69G5FAV"`, passa por `MaskingJsonProvider` escrevendo num `JsonGenerator` sobre `StringWriter`, e asserta que o JSON contém `"message":"key ***"` e não `gk_live_01ARZ`.

`ObservabilityIntegrationTest.java` (mesma base Testcontainers + `@ActiveProfiles("test")`):
```java
  @Test void correlationIdIsEchoedOrGenerated() {
    HttpHeaders h = new HttpHeaders(); h.set("X-Correlation-Id", "abc-123");
    ResponseEntity<String> r = http.exchange("/actuator/health", HttpMethod.GET, new HttpEntity<>(h), String.class);
    assertThat(r.getHeaders().getFirst("X-Correlation-Id")).isEqualTo("abc-123");
    ResponseEntity<String> s = http.getForEntity("/actuator/health", String.class);
    assertThat(s.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
  }
  @Test void prometheusAndHealthArePublic() {
    assertThat(http.getForEntity("/actuator/prometheus", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
  }
```

- [ ] **Step 2: Rodar e ver falhar** — compilação.

- [ ] **Step 3: Implementar**

`Masker.java`:
```java
package com.gateway.app.observability;

import java.util.regex.Pattern;

/**
 * Central log masking. Regex rather than a list of fields because leaks come from where nobody
 * expected — an exception message, a DTO's toString, the body of an HTTP error from the bank.
 */
public final class Masker {
  private static final Pattern CPF = Pattern.compile("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b");
  private static final Pattern API_KEY = Pattern.compile("gk_(live|test)_[0-9A-Za-z]+");
  private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)\\S+");
  private static final Pattern FIELDS = Pattern.compile("(\"(?:client_secret|secret|previous_secret|pix_copia_e_cola|password|token|access_token|certificate)\"\\s*:\\s*\")[^\"]*(\")");

  private Masker() {}

  public static String mask(String s) {
    if (s == null || s.isEmpty()) return s;
    String r = BEARER.matcher(s).replaceAll("$1***");
    r = API_KEY.matcher(r).replaceAll("***");
    r = CPF.matcher(r).replaceAll("***");
    r = FIELDS.matcher(r).replaceAll("$1***$2");
    return r;
  }
}
```

`MaskingJsonProvider.java`: estende `net.logstash.logback.composite.loggingevent.MessageJsonProvider` e sobrescreve o método que escreve a mensagem para usar `Masker.mask(event.getFormattedMessage())`. O teste unitário é o árbitro da API exata do encoder 8.x.

`logback-spring.xml`:
```xml
<configuration>
  <appender name="JSON" class="ch.qos.logback.core.ConsoleAppender">
    <encoder class="net.logstash.logback.encoder.LoggingEventCompositeJsonEncoder">
      <providers>
        <timestamp/><logLevel/><loggerName/><threadName/>
        <mdc/>
        <provider class="com.gateway.app.observability.MaskingJsonProvider"/>
        <stackTrace/>
      </providers>
    </encoder>
  </appender>
  <root level="INFO"><appender-ref ref="JSON"/></root>
</configuration>
```

`CorrelationFilter.java`: `@Order(1)`, `OncePerRequestFilter` em tudo; lê `X-Correlation-Id` (ou gera `Ulid.next()`), põe em `MDC("correlationId")`, devolve no header de resposta, limpa o MDC no `finally`.

- [ ] **Step 4: Rodar e ver passar** — `./mvnw -B test` → suíte inteira verde.

- [ ] **Step 5: Commit**

```bash
git add gateway-app
git commit -m "feat(app): correlation id, json logs with masker and public actuator

Masker by regex, not by field: leaks come from where nobody expected.
CPF, api keys, bearer tokens and secret/pix_copia_e_cola fields never
reach a log — with a test on the encoder."
```

---

### Task 8: README, dev docker-compose and end-to-end check

**Files:**
- Create: `README.md`, `docker-compose.yml`, `docs/superpowers/DECISOES.md`

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

Payment orchestrator (model A: the merchant's own credentials; money never passes through here).
Spec: `docs/superpowers/specs/2026-09-23-payment-gateway-design.md`. Decisions: `docs/superpowers/DECISOES.md`.

## Run

```bash
docker compose up -d
export GATEWAY_ADMIN_KEY=dev-admin GATEWAY_API_KEY_PEPPER=dev-pepper
export GATEWAY_MASTER_KEY=$(openssl rand -base64 32)
./mvnw -pl gateway-app spring-boot:run
```

Without `GATEWAY_MASTER_KEY` the app does not start (the master key encrypts merchant credentials).
Without `GATEWAY_ADMIN_KEY` the admin API answers 403 — closed by default.

## First merchant

```bash
curl -s -XPOST localhost:8080/v1/admin/merchants -H 'X-Admin-Key: dev-admin' -H 'Content-Type: application/json' -d '{"name":"Store"}'
curl -s -XPOST localhost:8080/v1/admin/merchants/<id>/api-keys -H 'X-Admin-Key: dev-admin' -H 'Content-Type: application/json' -d '{"environment":"TEST"}'
curl -s localhost:8080/v1/me -H 'Authorization: Bearer gk_test_…'
```

## Modules

`gateway-kernel` (dependency-free types) · `gateway-merchants` (merchant, API keys, encrypted credentials) ·
`gateway-app` (REST, auth, rate limit, outbound webhooks via `webhook-delivery`, observability).
`orders`, `payments` and `providers` arrive with plans B and C. The boundary is enforced by `ArchitectureTest`.

## Build

`./mvnw verify` (Testcontainers; needs Docker). The `com.barrier:webhook-delivery` library comes from GitHub
Packages: `~/.m2/settings.xml` with server `github-webhook-delivery` and a PAT with `read:packages`. CI uses the
`PACKAGES_READ_TOKEN` repository secret for the same reason.
```

- [ ] **Step 3: `docs/superpowers/DECISOES.md`** — append-only, em português (é doc, não código), com as decisões deste plano, cada uma com alternativa rejeitada e custo de estar errada:

```markdown
# Decisões

Append-only. Cada entrada: decisão, alternativa rejeitada, custo de estar errada.

## 2026-09-24 — Todo o código em inglês
Identificadores, comentários, mensagens, commits e README. Rejeitado: português como no Barrier e no
agent-orchestrator. Motivo: produto para terceiros e lib compartilhada com API pública em inglês. Custo se
errado: nenhum técnico; os docs de spec/plano continuam em português.

## 2026-09-24 — API key com SHA-256+pepper, não bcrypt
Chave de 130 bits aleatórios: força bruta é impossível com hash rápido; bcrypt custaria ~100 ms por
requisição. Rejeitado: bcrypt/argon2. Custo se errado: nenhum enquanto a chave for aleatória; se um dia
aceitarmos chave escolhida pelo merchant, a decisão cai.

## 2026-09-24 — Envelope AES-256-GCM com DEK por linha, mestra em env
Rejeitado: cifrar tudo direto com a mestra (rotacionar = recifrar tudo); KMS já no MVP (uma instância,
um operador). Custo se errado: a mestra em env é um segredo a mais para vazar; migrar para KMS é trocar
`MasterKey` e recifrar 32 bytes por linha.

## 2026-09-24 — Rate limit em memória
Uma instância. Rejeitado: Redis no MVP. Custo se errado: com duas instâncias o limite vira 2x até
trocar por bucket distribuído.

## 2026-09-24 — Admin key vazia fecha o admin
Rejeitado: "sem chave = dev aberto". Custo se errado: nenhum; dev exporta uma variável.
```

- [ ] **Step 4: Verificação ponta a ponta**

Run: `./mvnw -B verify` → `BUILD SUCCESS`. Depois `docker compose up -d`, exporte as três variáveis, `./mvnw -pl gateway-app spring-boot:run` em background, rode os três `curl` do README; `GET /v1/me` devolve o merchant. Pare o app. Cole a saída no relatório.

- [ ] **Step 5: Commit**

```bash
git add README.md docker-compose.yml docs/superpowers/DECISOES.md
git commit -m "docs: readme, dev compose and plan a decisions

The app does not start without a master key and the admin does not
open without a key: both closed defaults are documented where the next
developer will look."
```

---

## Self-review

**Cobertura da spec.** §1.3 multi-merchant → Tasks 3–5. §2 stack/topologia/ArchUnit → Tasks 1, 5. §7 webhooks de saída via lib, `X-Gateway`, self-service → Task 6. §8 correlação, log JSON, `Masker`, API key hash+prefixo+rotação, envelope AES-GCM, rate limit → Tasks 3, 4, 5, 7. §9 `merchants.*` → Task 4. §11 → Tasks 2–7. Fora deste plano (de propósito): `PaymentGateway`/`CredentialLookup` no kernel, `providers`, `payments` (Plano B); `orders` (Plano C). `GET /v1/webhooks/deliveries` + `retry` (spec §10) fica para quando a lib expuser listagem por tenant — follow-up da `webhook-delivery 0.2.0`.

**Placeholders.** Nenhum "TBD". Os pontos "se a API X diferir" dizem o que verificar e mandam registrar no relatório.

**Consistência de tipos.** `MerchantId(String value)` em Tasks 2–6; `ApiKey.Issued(apiKey, plainKey: Secret)` em Tasks 3–5; `ApiKeyService.Authenticated(merchantId, environment, apiKeyId)` em Tasks 4–5; `MerchantContext.Current` idem; `ProviderCredentialService.store(MerchantId, Provider, ApiKeyEnvironment, byte[])` em Tasks 4–5; `MerchantEvents.emit(MerchantId, String, String, String, Object)` em Task 6; `Masker.mask(String)` em Task 7; código de erro `API_KEY_LIMIT` em Tasks 4–5; JSON snake_case (`merchant_id`, `previous_secret_until`) configurado na Task 1 e usado nas 5–6.
