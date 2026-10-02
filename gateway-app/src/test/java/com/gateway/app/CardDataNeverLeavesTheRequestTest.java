package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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

  /**
   * Where a card number would leak in practice: body and parameter logging at DEBUG/TRACE, which
   * the INFO root level of the test profile never emits. Raised to TRACE for this test only.
   * WireMock's own notifier is left out on purpose: it logs what the fake Cielo received, which is
   * the number by design, and is not the gateway writing it.
   */
  static final List<String> WATCHED_LOGGERS =
      List.of(
          "com.gateway",
          "org.springframework.web",
          "org.springframework.web.client",
          "java.net.http",
          "jdk.internal.httpclient",
          "org.apache.hc",
          "tools.jackson",
          "com.fasterxml.jackson",
          "org.hibernate.SQL",
          "org.hibernate.orm.jdbc.bind");

  static final Map<String, Level> PREVIOUS_LEVELS = new HashMap<>();

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
        WireMock.post(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo(NUMBER)))
            .willReturn(
                created(CardFlowIntegrationTest.fixture("post_sales_201_authorized_saved.json"))));
    CIELO.stubFor(
        WireMock.post(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardToken"))
            .willReturn(
                created(CardFlowIntegrationTest.fixture("post_sales_201_token_authorized.json"))));
    CIELO.stubFor(
        WireMock.post(urlEqualTo("/api/1/sales"))
            .withRequestBody(
                matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo(DECLINED_NUMBER)))
            .willReturn(created(CardFlowIntegrationTest.fixture("post_sales_201_denied.json"))));
    // A 400 that echoes what it was sent: the worst case for an exception message.
    CIELO.stubFor(
        WireMock.post(urlEqualTo("/api/1/sales"))
            .withRequestBody(
                matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo(CIELO_REFUSES)))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withBody(
                        "{\"CardNumber\":\""
                            + CIELO_REFUSES
                            + "\",\"SecurityCode\":\""
                            + CVV
                            + "\"}")));
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
    CIELO.stop();
  }

  /**
   * Attached once the Spring context is up, not in @BeforeAll: Boot's logging system resets logback
   * when the context starts, which silently detached an appender added earlier and left the scan
   * with no log events at all whenever this test ran first (measured: 0 events alone).
   */
  @BeforeEach
  void watchLogs() {
    LOGS.start();
    ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(LOGS);
    for (String name : WATCHED_LOGGERS) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      PREVIOUS_LEVELS.put(name, logger.getLevel());
      logger.setLevel(Level.TRACE);
      // A logger configured non-additive never reaches the root appender.
      if (!logger.isAdditive()) {
        logger.addAppender(LOGS);
      }
    }
  }

  @AfterEach
  void unwatchLogs() {
    ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(LOGS);
    for (String name : WATCHED_LOGGERS) {
      Logger logger = (Logger) LoggerFactory.getLogger(name);
      logger.detachAppender(LOGS);
      logger.setLevel(PREVIOUS_LEVELS.get(name));
    }
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder created(String body) {
    return aResponse()
        .withStatus(201)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @SuppressWarnings("unchecked")
  String admin(String uri, Object body) {
    return (String)
        http()
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
            .getResponseBody()
            .values()
            .stream()
            .filter(String.class::isInstance)
            .findFirst()
            .orElseThrow();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> post(
      String apiKey, String idempotencyKey, String uri, Object body, int expectedStatus) {
    return http()
        .post()
        .uri(uri)
        .header("Authorization", "Bearer " + apiKey)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectStatus()
        .isEqualTo(expectedStatus)
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  static Map<String, Object> newCard(String number, boolean capture, boolean save) {
    Map<String, Object> body = new HashMap<>();
    body.put("method", "CARD");
    body.put("amount", 12990);
    body.put("currency", "BRL");
    body.put(
        "card",
        Map.of("number", number, "holder", "JOAO DA SILVA", "expiry", "12/2030", "cvv", CVV));
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
            http()
                .post()
                .uri("/v1/admin/merchants/" + merchantId + "/api-keys")
                .header("X-Admin-Key", "test-admin")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("environment", "TEST"))
                .exchange()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody()
                .get("key");
    http()
        .put()
        .uri("/v1/admin/merchants/" + merchantId + "/providers/CIELO/credentials")
        .header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            Map.of(
                "environment",
                "TEST",
                "payload",
                Map.of(
                    "merchant_id",
                    "11111111-2222-3333-4444-555555555555",
                    "merchant_key",
                    "A".repeat(40))))
        .exchange()
        .expectStatus()
        .is2xxSuccessful();

    // Save the card (sent grouped, as a payer types it), capture, refund.
    Map<String, Object> saved =
        post(apiKey, "p1", "/v1/payments", newCard(GROUPED, false, true), 201);
    String paymentId = (String) saved.get("id");
    String cardId = (String) ((Map<String, Object>) saved.get("card")).get("card_id");
    post(apiKey, "p2", "/v1/payments/" + paymentId + "/capture", Map.of(), 200);
    post(apiKey, "p3", "/v1/payments/" + paymentId + "/refunds", Map.of("amount", 1000), 201);

    // Charge the saved card, a decline, a Cielo 400 echoing the card, and a card the gateway
    // refuses.
    Map<String, Object> byCardId =
        new HashMap<>(
            Map.of(
                "method",
                "CARD",
                "amount",
                5000,
                "currency",
                "BRL",
                "card_id",
                cardId,
                "cvv",
                CVV,
                "customer",
                Map.of("name", "Joao da Silva")));
    post(apiKey, "p4", "/v1/payments", byCardId, 201);
    post(apiKey, "p5", "/v1/payments", newCard(DECLINED_NUMBER, true, false), 402);
    post(apiKey, "p6", "/v1/payments", newCard(CIELO_REFUSES, true, false), 422);
    post(apiKey, "p7", "/v1/payments", newCard("4024007153763172", true, false), 422);

    // An order attempt by a registered customer, saving the card: the body is read by a different
    // DTO (CardAttemptBody) and the card is then adopted by the customer, a billing write.
    String customerId =
        (String)
            post(
                    apiKey,
                    "p8",
                    "/v1/customers",
                    Map.of("name", "Joao da Silva", "document", "52998224725"),
                    201)
                .get("id");
    String orderId =
        (String)
            post(
                    apiKey,
                    "p9",
                    "/v1/orders",
                    Map.of("amount", 12990, "currency", "BRL", "customer_id", customerId),
                    201)
                .get("id");
    Map<String, Object> orderAttempt = new HashMap<>(newCard(GROUPED, false, true));
    orderAttempt.remove("amount");
    orderAttempt.remove("currency");
    orderAttempt.remove("customer");
    post(apiKey, "p10", "/v1/orders/" + orderId + "/payments", orderAttempt, 201);

    // The scan is not vacuous: every step reached the Cielo, and the numbers only the Cielo. The
    // refused card never leaves the gateway.
    for (String number : List.of(NUMBER, DECLINED_NUMBER, CIELO_REFUSES)) {
      CIELO.verify(
          postRequestedFor(urlEqualTo("/api/1/sales")).withRequestBody(containing(number)));
    }
    CIELO.verify(
        postRequestedFor(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardToken")));
    CIELO.verify(
        0,
        postRequestedFor(urlEqualTo("/api/1/sales"))
            .withRequestBody(containing("4024007153763172")));
    CIELO.verify(
        putRequestedFor(
            urlPathEqualTo("/api/1/sales/" + CardFlowIntegrationTest.SALE + "/capture")));
    CIELO.verify(
        putRequestedFor(urlPathEqualTo("/api/1/sales/" + CardFlowIntegrationTest.SALE + "/void")));

    List<String> stored = new ArrayList<>();
    stored.addAll(
        text(
            "SELECT coalesce(request,'') || ' ' || coalesce(response,'') FROM payments.provider_requests"));
    stored.addAll(text("SELECT payload::text FROM payments.payment_events"));
    stored.addAll(text("SELECT payload FROM payments.outbox"));
    stored.addAll(text("SELECT details::text FROM payments.payments"));
    stored.addAll(
        text("SELECT raw_headers || convert_from(raw_body, 'UTF8') FROM payments.webhook_inbox"));
    stored.addAll(text("SELECT coalesce(response_body,'') FROM payments.idempotency_keys"));
    stored.addAll(text("SELECT coalesce(reason,'') FROM payments.refunds"));
    stored.addAll(text("SELECT coalesce(detail,'') FROM payments.reconciliation_divergences"));
    stored.addAll(text("SELECT holder || last4 || brand FROM payments.cards"));
    // Whole rows: every column the billing side writes, the inline payer included.
    stored.addAll(text("SELECT o::text FROM billing.orders o"));
    stored.addAll(text("SELECT c::text FROM billing.customers c"));
    stored.addAll(
        jdbc.queryForList("SELECT token_ciphertext FROM payments.cards", byte[].class).stream()
            .map(bytes -> new String(bytes, StandardCharsets.ISO_8859_1))
            .toList());

    List<String> logged = new ArrayList<>();
    // The test's own RestTestClient runs on this thread and logs the request it sends, card and
    // all; that is the payer's side of the wire. The gateway handles requests on the server's
    // threads and runs jobs on its own, so everything else is the gateway's.
    String payerThread = Thread.currentThread().getName();
    for (ILoggingEvent event : LOGS.list) {
      if (event.getThreadName().equals(payerThread)) {
        continue;
      }
      logged.add(event.getFormattedMessage());
      for (IThrowableProxy thrown = event.getThrowableProxy();
          thrown != null;
          thrown = thrown.getCause()) {
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
    // The net is live: request handling and SQL binding were logged at the levels a leak uses.
    assertThat(LOGS.list)
        .anyMatch(
            event ->
                !event.getThreadName().equals(payerThread)
                    && event.getLoggerName().startsWith("org.springframework.web")
                    && event.getLevel() == Level.DEBUG);
    assertThat(LOGS.list)
        .anyMatch(
            event ->
                !event.getThreadName().equals(payerThread)
                    && event.getLoggerName().startsWith("org.hibernate.orm.jdbc.bind")
                    && event.getLevel() == Level.TRACE);
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
