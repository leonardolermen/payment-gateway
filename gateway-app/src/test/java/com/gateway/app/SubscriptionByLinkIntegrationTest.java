package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
 * Spec 2026-10-07 through the real app: a card subscription created without a card waits
 * INCOMPLETE, the payer pays its first invoice by link with a card that is saved, and the job
 * runner charges that card on the next cycle by token; invoices carry their link in their event;
 * the panel lists subscriptions by cursor. WireMock plays the Cielo and the Itaú.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "gateway.payments.jobs-poll-ms=200",
      "gateway.payments.outbox-relay-ms=200",
      "gateway.rate-limit.requests-per-minute=1000",
      "gateway.checkout.base-url=https://pay.test/pay/"
    })
@ActiveProfiles("test")
@Testcontainers
class SubscriptionByLinkIntegrationTest {
  static final String CARD_NUMBER = BillingApiIntegrationTest.CARD_NUMBER;
  static final String LINK_BASE = "https://pay.test/pay/";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer CIELO = new WireMockServer(options().dynamicPort());
  static final WireMockServer ITAU = new WireMockServer(options().dynamicPort());

  static {
    CIELO.start();
    ITAU.start();
  }

  static int idempotencyCounter;

  @DynamicPropertySource
  static void providers(DynamicPropertyRegistry registry) {
    registry.add("gateway.providers.cielo.test-api-base", () -> CIELO.baseUrl() + "/api");
    registry.add("gateway.providers.cielo.test-query-api-base", () -> CIELO.baseUrl() + "/query");
    registry.add("gateway.providers.itau.test-api-base", ITAU::baseUrl);
    registry.add("gateway.providers.itau.test-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    registry.add("gateway.providers.itau.test-mutual-tls", () -> "false");
  }

  @BeforeAll
  static void stubs() {
    // The payer's card on the link: a captured sale that saves it.
    CIELO.stubFor(
        WireMock.post(urlEqualTo("/api/1/sales"))
            .withRequestBody(
                matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo(CARD_NUMBER)))
            .willReturn(
                BillingApiIntegrationTest.created(
                    BillingApiIntegrationTest.paid(
                        BillingApiIntegrationTest.cieloFixture(
                            "post_sales_201_authorized_saved.json")))));
    // The next cycle: the stored token, no number and no CVV.
    CIELO.stubFor(
        WireMock.post(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardToken"))
            .willReturn(
                BillingApiIntegrationTest.created(
                    BillingApiIntegrationTest.paid(
                        BillingApiIntegrationTest.cieloFixture(
                            "post_sales_201_token_authorized.json")))));

    ITAU.stubFor(
        WireMock.post(urlEqualTo("/api/oauth/jwt"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        "{\"access_token\":\"tok-123\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    ITAU.stubFor(
        put(urlMatching("/cob/[A-Za-z0-9]+"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(BillingApiIntegrationTest.itauFixture("put_cob_201.json"))));
  }

  @AfterAll
  static void stop() {
    CIELO.stop();
    ITAU.stop();
  }

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> adminPost(String uri, Object body) {
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

  void adminPut(String uri, Object body) {
    http()
        .put()
        .uri(uri)
        .header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectStatus()
        .is2xxSuccessful();
  }

  /** A merchant with both banks in TEST; returns {merchantId, testKey}. */
  String[] merchant(String name) {
    String merchantId = (String) adminPost("/v1/admin/merchants", Map.of("name", name)).get("id");
    String key = keyFor(merchantId, "TEST");
    adminPut(
        "/v1/admin/merchants/" + merchantId + "/providers/CIELO/credentials",
        Map.of(
            "environment",
            "TEST",
            "payload",
            Map.of(
                "merchant_id",
                "11111111-2222-3333-4444-555555555555",
                "merchant_key",
                "A".repeat(40))));
    adminPut(
        "/v1/admin/merchants/" + merchantId + "/providers/ITAU/credentials",
        Map.of(
            "environment",
            "TEST",
            "payload",
            Map.of(
                "client_id",
                "sandbox-client",
                "client_secret",
                "sandbox-secret",
                "pix_key",
                "a1f4102e-a446-4a57-bcce-6fa48899c1d1")));

    return new String[] {merchantId, key};
  }

  String keyFor(String merchantId, String environment) {
    return (String)
        adminPost(
                "/v1/admin/merchants/" + merchantId + "/api-keys",
                Map.of("environment", environment))
            .get("key");
  }

  @SuppressWarnings("rawtypes")
  EntityExchangeResult<Map> post(String key, String uri, Object body) {
    idempotencyCounter++;

    return http()
        .post()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .header("Idempotency-Key", "by-link-" + idempotencyCounter)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> getJson(String key, String uri) {
    return http()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("rawtypes")
  EntityExchangeResult<List> getList(String key, String uri) {
    return http()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectBody(List.class)
        .returnResult();
  }

  int statusOf(String key, String uri) {
    return http()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectBody(String.class)
        .returnResult()
        .getStatus()
        .value();
  }

  @SuppressWarnings("rawtypes")
  EntityExchangeResult<Map> publicPost(String uri, Object body) {
    return http()
        .post()
        .uri(uri)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> publicGet(String uri) {
    return http()
        .get()
        .uri(uri)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  String customer(String key) {
    return (String)
        post(
                key,
                "/v1/customers",
                Map.of("name", "Ana Souza", "document", "52998224725", "email", "ana@example.com"))
            .getResponseBody()
            .get("id");
  }

  String plan(String key) {
    return (String)
        post(
                key,
                "/v1/plans",
                Map.of("name", "Mensal", "amount", 10000, "currency", "BRL", "interval", "MONTH"))
            .getResponseBody()
            .get("id");
  }

  @SuppressWarnings("rawtypes")
  EntityExchangeResult<Map> subscribe(String key, String customerId, String planId, String method) {
    return post(
        key,
        "/v1/subscriptions",
        Map.of("customer_id", customerId, "plan_id", planId, "method", method));
  }

  static Map<String, Object> cardAttempt(boolean saveCard) {
    Map<String, Object> body = new HashMap<>();
    body.put("method", "CARD");
    body.put(
        "card",
        Map.of("number", CARD_NUMBER, "holder", "ANA SOUZA", "expiry", "12/2030", "cvv", "987"));
    body.put("installments", 1);
    body.put("capture", true);
    body.put("save_card", saveCard);
    return body;
  }

  String outboxPayload(String aggregateId, String type) {
    return jdbc.queryForObject(
        "SELECT payload FROM payments.outbox WHERE aggregate_id = ? AND event_type = ?",
        String.class,
        aggregateId,
        type);
  }

  int count(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void aCardSubscriptionStartsByLinkSavesTheCardAndBillsItNextCycle() {
    String key = merchant("Link Store")[1];
    String customerId = customer(key);
    String planId = plan(key);

    // 1. No card_id: INCOMPLETE, with the first invoice and its link in the response.
    EntityExchangeResult<Map> created = subscribe(key, customerId, planId, "CARD");
    assertThat(created.getStatus().value()).isEqualTo(201);
    Map<String, Object> body = created.getResponseBody();
    assertThat(body)
        .containsEntry("status", "INCOMPLETE")
        .containsEntry("card_id", null)
        .containsEntry("customer_name", "Ana Souza")
        .containsEntry("plan_name", "Mensal")
        .containsEntry("amount", 10000)
        .containsEntry("interval", "MONTH")
        .containsEntry("interval_count", 1);
    String subscriptionId = (String) body.get("id");
    Map<String, Object> firstInvoice = (Map<String, Object>) body.get("first_invoice");
    String invoiceId = (String) firstInvoice.get("order_id");
    String url = (String) firstInvoice.get("checkout_url");
    assertThat(url).startsWith(LINK_BASE + "chk_");
    String token = url.substring(LINK_BASE.length());

    // The link is in the response and in invoice.created; the database keeps only its hash.
    assertThat(outboxPayload(invoiceId, "invoice.created"))
        .contains("\"checkout_url\":\"" + url + "\"");
    String hash =
        jdbc.queryForObject(
            "SELECT checkout_token_hash FROM billing.orders WHERE id = ?", String.class, invoiceId);
    assertThat(hash).matches("[0-9a-f]{64}").doesNotContain(token);
    assertThat(
            count(
                "SELECT count(*) FROM billing.orders WHERE checkout_token_hash LIKE ?",
                "%" + token + "%"))
        .isZero();
    assertThat(getJson(key, "/v1/orders/" + invoiceId)).containsEntry("checkout_url", null);
    assertThat(getJson(key, "/v1/subscriptions/" + subscriptionId))
        .containsEntry("first_invoice", null)
        .containsEntry("status", "INCOMPLETE");

    // 2. The payer's page: only the card, and it says the card stays saved for the plan.
    Map<String, Object> page = publicGet("/v1/checkout/" + token);
    assertThat((List<String>) page.get("methods")).containsExactly("CARD");
    assertThat(page)
        .containsEntry("saves_card_for_subscription", true)
        .containsEntry("plan_name", "Mensal");

    EntityExchangeResult<Map> pix =
        publicPost("/v1/checkout/" + token + "/payments", Map.of("method", "PIX"));
    assertThat(pix.getStatus().value()).isEqualTo(422);
    assertThat(pix.getResponseBody()).containsEntry("type", "urn:gateway:CHECKOUT_CARD_REQUIRED");

    // 3. save_card: false is overridden: the Cielo is asked to save it.
    EntityExchangeResult<Map> charged =
        publicPost("/v1/checkout/" + token + "/payments", cardAttempt(false));
    assertThat(charged.getStatus().value()).isEqualTo(201);
    assertThat(charged.getResponseBody()).containsEntry("status", "COMPLETED");
    CIELO.verify(
        postRequestedFor(urlEqualTo("/api/1/sales"))
            .withRequestBody(
                matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo(CARD_NUMBER)))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.SaveCard", equalTo("true"))));

    // 4. Paid: ACTIVE with the saved card, announced.
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                "ACTIVE".equals(getJson(key, "/v1/subscriptions/" + subscriptionId).get("status")));
    Map<String, Object> active = getJson(key, "/v1/subscriptions/" + subscriptionId);
    String cardId = (String) active.get("card_id");
    assertThat(cardId).isNotNull();
    assertThat(
            (List<Map<String, Object>>)
                getList(key, "/v1/customers/" + customerId + "/cards").getResponseBody())
        .extracting(card -> card.get("id"))
        .contains(cardId);
    assertThat(outboxPayload(subscriptionId, "subscription.activated"))
        .contains("\"status\":\"ACTIVE\"")
        .contains(cardId);
    assertThat(getJson(key, "/v1/orders/" + invoiceId)).containsEntry("status", "PAID");

    // 5. The next cycle, brought forward: the saved card by token, without a CVV.
    jdbc.update(
        "UPDATE billing.subscriptions SET current_period_start = current_period_start - 31,"
            + " current_period_end = (now() AT TIME ZONE 'America/Sao_Paulo')::date,"
            + " next_billing_at = now() WHERE id = ?",
        subscriptionId);
    jdbc.update(
        "UPDATE payments.jobs SET next_run_at = now()"
            + " WHERE type = 'BILL_SUBSCRIPTION' AND ref_id = ?",
        subscriptionId);

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () -> {
              List<Map<String, Object>> invoices =
                  getList(key, "/v1/subscriptions/" + subscriptionId + "/orders").getResponseBody();
              return invoices.size() == 2 && "PAID".equals(invoices.getFirst().get("status"));
            });
    CIELO.verify(
        postRequestedFor(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardToken"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.SecurityCode", absent())));
    String secondInvoice =
        (String)
            ((Map<String, Object>)
                    getList(key, "/v1/subscriptions/" + subscriptionId + "/orders")
                        .getResponseBody()
                        .getFirst())
                .get("id");
    assertThat(outboxPayload(secondInvoice, "invoice.created"))
        .contains("\"charged\":true")
        .contains("\"checkout_url\":\"" + LINK_BASE + "chk_");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void anExpiredFirstInvoiceEndsTheSubscriptionWithoutDunning() {
    String key = merchant("Expiry Store")[1];
    Map<String, Object> created =
        subscribe(key, customer(key), plan(key), "CARD").getResponseBody();
    String subscriptionId = (String) created.get("id");
    String invoiceId =
        (String) ((Map<String, Object>) created.get("first_invoice")).get("order_id");

    jdbc.update(
        "UPDATE billing.orders SET expires_at = now() - interval '1 minute' WHERE id = ?",
        invoiceId);
    jdbc.update(
        "UPDATE payments.jobs SET next_run_at = now() WHERE type = 'EXPIRE_ORDER' AND ref_id = ?",
        invoiceId);

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                "INCOMPLETE_EXPIRED"
                    .equals(getJson(key, "/v1/subscriptions/" + subscriptionId).get("status")));
    assertThat(getJson(key, "/v1/orders/" + invoiceId)).containsEntry("status", "EXPIRED");
    assertThat(
            count(
                "SELECT count(*) FROM billing.dunning_attempts WHERE subscription_id = ?",
                subscriptionId))
        .isZero();
    assertThat(
            count(
                "SELECT count(*) FROM payments.jobs WHERE type IN ('DUNNING_RETRY',"
                    + " 'BILL_SUBSCRIPTION') AND ref_id = ?",
                subscriptionId))
        .isZero();
    assertThat(getJson(key, "/v1/subscriptions/" + subscriptionId))
        .containsEntry("next_billing_at", null);

    EntityExchangeResult<Map> cancel =
        post(key, "/v1/subscriptions/" + subscriptionId + "/cancel", Map.of());
    assertThat(cancel.getStatus().value()).isEqualTo(409);
    assertThat(cancel.getResponseBody())
        .containsEntry("type", "urn:gateway:SUBSCRIPTION_NOT_ACTIVE");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void theMerchantCancelsAnIncompleteSubscription() {
    String key = merchant("Cancel Store")[1];
    Map<String, Object> created =
        subscribe(key, customer(key), plan(key), "CARD").getResponseBody();
    String subscriptionId = (String) created.get("id");
    Map<String, Object> firstInvoice = (Map<String, Object>) created.get("first_invoice");
    String token = ((String) firstInvoice.get("checkout_url")).substring(LINK_BASE.length());

    // No body: at_period_end defaults to true, which an INCOMPLETE subscription ignores.
    EntityExchangeResult<Map> canceled =
        post(key, "/v1/subscriptions/" + subscriptionId + "/cancel", Map.of());

    assertThat(canceled.getStatus().value()).isEqualTo(200);
    assertThat(canceled.getResponseBody()).containsEntry("status", "CANCELED");
    assertThat(getJson(key, "/v1/orders/" + firstInvoice.get("order_id")))
        .containsEntry("status", "CANCELED");
    assertThat(publicGet("/v1/checkout/" + token)).containsEntry("status", "CANCELED");
    assertThat(outboxPayload(subscriptionId, "subscription.canceled"))
        .contains("\"status\":\"CANCELED\"");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void aPixSubscriptionIsBornActiveAndItsInvoiceEventCarriesTheLink() {
    String key = merchant("Pix Store")[1];

    EntityExchangeResult<Map> created = subscribe(key, customer(key), plan(key), "PIX");

    assertThat(created.getStatus().value()).isEqualTo(201);
    assertThat(created.getResponseBody())
        .containsEntry("status", "ACTIVE")
        .containsEntry("first_invoice", null);
    String subscriptionId = (String) created.getResponseBody().get("id");

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                !getList(key, "/v1/subscriptions/" + subscriptionId + "/orders")
                        .getResponseBody()
                        .isEmpty()
                    && count(
                            "SELECT count(*) FROM payments.outbox WHERE event_type ="
                                + " 'invoice.created' AND partition_key = ?",
                            subscriptionId)
                        == 1);
    Map<String, Object> invoice =
        (Map<String, Object>)
            getList(key, "/v1/subscriptions/" + subscriptionId + "/orders")
                .getResponseBody()
                .getFirst();
    String payload = outboxPayload((String) invoice.get("id"), "invoice.created");
    assertThat(payload).contains("\"method\":\"PIX\"").contains("\"checkout_url\":\"" + LINK_BASE);
    String token =
        payload.replaceAll("(?s).*\"checkout_url\":\"" + LINK_BASE + "([^\"]+)\".*", "$1");
    assertThat(invoice).containsEntry("checkout_url", null);

    // The link works, and the page is the ordinary one: the subscription is not waiting for it.
    Map<String, Object> page = publicGet("/v1/checkout/" + token);
    assertThat(page)
        .containsEntry("order_id", invoice.get("id"))
        .containsEntry("saves_card_for_subscription", false)
        .containsEntry("plan_name", "Mensal");
    assertThat((List<String>) page.get("methods")).contains("PIX", "CARD");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void thePanelListsSubscriptionsByCursorAndStatusInTheKeysEnvironment() {
    String[] merchant = merchant("List Store");
    String key = merchant[1];
    String customerId = customer(key);
    String planId = plan(key);
    String first = (String) subscribe(key, customerId, planId, "PIX").getResponseBody().get("id");
    String second = (String) subscribe(key, customerId, planId, "PIX").getResponseBody().get("id");
    String third = (String) subscribe(key, customerId, planId, "CARD").getResponseBody().get("id");

    List<Map<String, Object>> page1 = getList(key, "/v1/subscriptions?limit=2").getResponseBody();
    assertThat(page1).extracting(row -> row.get("id")).containsExactly(third, second);
    assertThat(page1.getFirst())
        .containsEntry("customer_name", "Ana Souza")
        .containsEntry("plan_name", "Mensal")
        .containsEntry("amount", 10000)
        .containsEntry("interval", "MONTH")
        .containsEntry("interval_count", 1)
        .containsEntry("first_invoice", null);

    List<Map<String, Object>> page2 =
        getList(key, "/v1/subscriptions?limit=2&cursor=" + second).getResponseBody();
    assertThat(page2).extracting(row -> row.get("id")).containsExactly(first);

    assertThat(
            (List<Map<String, Object>>)
                getList(key, "/v1/subscriptions?status=INCOMPLETE").getResponseBody())
        .extracting(row -> row.get("id"))
        .containsExactly(third);
    assertThat(getList(key, "/v1/subscriptions?customer_id=" + customerId).getResponseBody())
        .hasSize(3);

    assertThat(statusOf(key, "/v1/subscriptions?status=BOGUS")).isEqualTo(400);
    assertThat(statusOf(key, "/v1/subscriptions?limit=0")).isEqualTo(400);
    assertThat(statusOf(key, "/v1/subscriptions?limit=101")).isEqualTo(400);
    assertThat(statusOf(key, "/v1/subscriptions?customer_id=" + customerId + "&cursor=" + first))
        .isEqualTo(400);
    assertThat(statusOf(key, "/v1/subscriptions?customer_id=" + customerId + "&status=ACTIVE"))
        .isEqualTo(400);

    // A LIVE key of the same merchant sees none of the TEST subscriptions.
    String liveKey = keyFor(merchant[0], "LIVE");
    assertThat(getList(liveKey, "/v1/subscriptions").getResponseBody()).isEmpty();
  }
}
