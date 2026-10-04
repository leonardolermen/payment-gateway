package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
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
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Customers, plans, orders and subscriptions through the real app (spec §5), with WireMock playing
 * the Itaú (Pix) and the Cielo (card). The last part is the first time the app context runs the
 * real chain behind a subscription: OutboxRelay to OrderSettlement marks a card order PAID, and the
 * job runner bills the first cycle with the card the customer saved on that order.
 *
 * <p>One test method on purpose, like the other flow tests: every step builds on the previous.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "gateway.payments.jobs-poll-ms=200",
      "gateway.payments.outbox-relay-ms=200",
      "gateway.rate-limit.requests-per-minute=1000"
    })
@ActiveProfiles("test")
@Testcontainers
class BillingApiIntegrationTest {
  static final String CARD_NUMBER = "4024007153763171";
  static final String DOCUMENT = "52998224725";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer CIELO = new WireMockServer(options().dynamicPort());
  static final WireMockServer ITAU = new WireMockServer(options().dynamicPort());

  static {
    CIELO.start();
    ITAU.start();
  }

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
    // A captured sale that saves the card: the saved fixture with Capture true and Status 2 (PAID).
    CIELO.stubFor(
        WireMock.post(urlEqualTo("/api/1/sales"))
            .withRequestBody(
                matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo(CARD_NUMBER)))
            .willReturn(created(paid(cieloFixture("post_sales_201_authorized_saved.json")))));
    // The subscription cycle: the stored token, no number and no CVV.
    CIELO.stubFor(
        WireMock.post(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardToken"))
            .willReturn(created(paid(cieloFixture("post_sales_201_token_authorized.json")))));

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
                    .withBody(itauFixture("put_cob_201.json"))));
    ITAU.stubFor(
        WireMock.patch(urlMatching("/cob/[A-Za-z0-9]+"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        itauFixture("put_cob_201.json")
                            .replace("\"ATIVA\"", "\"REMOVIDA_PELO_USUARIO_RECEBEDOR\""))));
  }

  @AfterAll
  static void stop() {
    CIELO.stop();
    ITAU.stop();
  }

  @LocalServerPort int port;

  static String paid(String sale) {
    return sale.replace("\"Capture\": false", "\"Capture\": true")
        .replace("\"Status\": 1", "\"Status\": 2");
  }

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder created(String body) {
    return aResponse()
        .withStatus(201)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  static String cieloFixture(String name) {
    return CardFlowIntegrationTest.fixture(name);
  }

  static String itauFixture(String name) {
    try (InputStream in =
        BillingApiIntegrationTest.class.getResourceAsStream("/itau/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

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

  @SuppressWarnings("rawtypes")
  EntityExchangeResult<Map> post(String apiKey, String idempotencyKey, String uri, Object body) {
    var spec =
        http()
            .post()
            .uri(uri)
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }

    return spec.body(body).exchange().expectBody(Map.class).returnResult();
  }

  @SuppressWarnings("rawtypes")
  EntityExchangeResult<Map> patch(String apiKey, String uri, Object body) {
    return http()
        .patch()
        .uri(uri)
        .header("Authorization", "Bearer " + apiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> getJson(String apiKey, String uri) {
    return http()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + apiKey)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  List<?> getList(String apiKey, String uri) {
    return http()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + apiKey)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(List.class)
        .returnResult()
        .getResponseBody();
  }

  static Map<String, Object> newCardAttempt() {
    Map<String, Object> body = new HashMap<>();
    body.put("method", "CARD");
    body.put(
        "card",
        Map.of(
            "number",
            CARD_NUMBER,
            "holder",
            "ANA SOUZA",
            "expiry",
            "12/2030",
            "cvv",
            "987",
            "brand",
            "VISA"));
    body.put("installments", 1);
    body.put("capture", true);
    body.put("save_card", true);
    return body;
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void customersPlansOrdersAndACardSubscription() {
    // 1. Merchant, TEST key, a Cielo and an Itaú credential.
    String merchantId =
        (String) adminPost("/v1/admin/merchants", Map.of("name", "Billing Store")).get("id");
    String key =
        (String)
            adminPost(
                    "/v1/admin/merchants/" + merchantId + "/api-keys",
                    Map.of("environment", "TEST"))
                .get("key");
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

    // 2. A customer, then the same document again: 409 naming the one that exists.
    Map<String, Object> customerBody =
        Map.of(
            "name",
            "Ana Souza",
            "document",
            DOCUMENT,
            "email",
            "ana@example.com",
            "address",
            Map.of(
                "street",
                "Rua A, 1",
                "district",
                "Centro",
                "city",
                "Sao Paulo",
                "state",
                "SP",
                "zip",
                "01001-000"));
    EntityExchangeResult<Map> createdCustomer = post(key, "cus-1", "/v1/customers", customerBody);
    assertThat(createdCustomer.getStatus().value()).isEqualTo(201);
    assertThat(createdCustomer.getResponseHeaders().containsHeader("X-Resource-Id")).isFalse();
    String customerId = (String) createdCustomer.getResponseBody().get("id");
    assertThat(createdCustomer.getResponseBody())
        .containsEntry("document", "***.982.***-25")
        .containsEntry("email", "ana@example.com");

    EntityExchangeResult<Map> duplicate = post(key, "cus-2", "/v1/customers", customerBody);
    assertThat(duplicate.getStatus().value()).isEqualTo(409);
    assertThat(duplicate.getResponseBody())
        .containsEntry("type", "urn:gateway:CUSTOMER_EXISTS")
        .containsEntry("customer_id", customerId);

    assertThat(getList(key, "/v1/customers?document=" + DOCUMENT)).hasSize(1);
    EntityExchangeResult<Map> renamed =
        patch(key, "/v1/customers/" + customerId, Map.of("name", "Ana Souza Lima"));
    assertThat(renamed.getStatus().value()).isEqualTo(200);
    assertThat(renamed.getResponseBody()).containsEntry("name", "Ana Souza Lima");
    assertThat(
            patch(key, "/v1/customers/" + customerId, Map.of("document", "11144477735"))
                .getStatus()
                .value())
        .isEqualTo(400);

    // 3. A monthly plan; its price never changes.
    EntityExchangeResult<Map> createdPlan =
        post(
            key,
            "plan-1",
            "/v1/plans",
            Map.of("name", "Mensal", "amount", 10000, "currency", "BRL", "interval", "MONTH"));
    assertThat(createdPlan.getStatus().value()).isEqualTo(201);
    String planId = (String) createdPlan.getResponseBody().get("id");
    assertThat(createdPlan.getResponseBody())
        .containsEntry("interval_count", 1)
        .containsEntry("active", true);
    EntityExchangeResult<Map> repriced = patch(key, "/v1/plans/" + planId, Map.of("amount", 20000));
    assertThat(repriced.getStatus().value()).isEqualTo(422);
    assertThat(repriced.getResponseBody())
        .containsEntry("type", "urn:gateway:PLAN_IMMUTABLE")
        .containsEntry("detail", "amount cannot change; create a new plan");

    // 4. An order paid by Pix: one attempt at a time, then canceled with its attempt.
    EntityExchangeResult<Map> pixOrder =
        post(
            key,
            "ord-1",
            "/v1/orders",
            Map.of(
                "amount",
                10000,
                "currency",
                "BRL",
                "reference",
                "pedido-1",
                "customer_id",
                customerId));
    assertThat(pixOrder.getStatus().value()).isEqualTo(201);
    assertThat(pixOrder.getResponseBody()).containsEntry("status", "OPEN");
    String pixOrderId = (String) pixOrder.getResponseBody().get("id");

    EntityExchangeResult<Map> pix =
        post(key, "att-1", "/v1/orders/" + pixOrderId + "/payments", Map.of("method", "PIX"));
    assertThat(pix.getStatus().value()).isEqualTo(201);
    String pixPaymentId = (String) pix.getResponseBody().get("id");
    assertThat(pix.getResponseBody())
        .containsEntry("order_id", pixOrderId)
        .containsEntry("amount", 10000)
        .containsEntry("status", "PENDING");

    EntityExchangeResult<Map> second =
        post(key, "att-2", "/v1/orders/" + pixOrderId + "/payments", Map.of("method", "PIX"));
    assertThat(second.getStatus().value()).isEqualTo(409);
    assertThat(second.getResponseBody())
        .containsEntry("type", "urn:gateway:ORDER_HAS_ACTIVE_PAYMENT")
        .containsEntry("payment_id", pixPaymentId);

    EntityExchangeResult<Map> canceled =
        post(key, "can-1", "/v1/orders/" + pixOrderId + "/cancel", Map.of());
    assertThat(canceled.getStatus().value()).isEqualTo(200);
    assertThat(canceled.getResponseBody()).containsEntry("status", "CANCELED");
    assertThat(getJson(key, "/v1/payments/" + pixPaymentId)).containsEntry("status", "CANCELED");
    EntityExchangeResult<Map> closed =
        post(key, "att-3", "/v1/orders/" + pixOrderId + "/payments", Map.of("method", "PIX"));
    assertThat(closed.getStatus().value()).isEqualTo(409);
    assertThat(closed.getResponseBody()).containsEntry("type", "urn:gateway:ORDER_CLOSED");

    // 5. An order paid by a new card, saved: the settlement marks it PAID, the card is the
    // customer's.
    String cardOrderId =
        (String)
            post(
                    key,
                    "ord-2",
                    "/v1/orders",
                    Map.of(
                        "amount",
                        10000,
                        "currency",
                        "BRL",
                        "reference",
                        "pedido-2",
                        "customer_id",
                        customerId))
                .getResponseBody()
                .get("id");
    EntityExchangeResult<Map> charged =
        post(key, "att-4", "/v1/orders/" + cardOrderId + "/payments", newCardAttempt());
    assertThat(charged.getStatus().value()).isEqualTo(201);
    assertThat(charged.getResponseBody()).containsEntry("status", "COMPLETED");
    assertThat(charged.getResponseBody().toString())
        .doesNotContain(CARD_NUMBER)
        .doesNotContain("987");
    String cardPaymentId = (String) charged.getResponseBody().get("id");
    String cardId =
        (String) ((Map<String, Object>) charged.getResponseBody().get("card")).get("card_id");
    assertThat(cardId).isNotNull();

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> "PAID".equals(getJson(key, "/v1/orders/" + cardOrderId).get("status")));
    Map<String, Object> paidOrder = getJson(key, "/v1/orders/" + cardOrderId);
    assertThat(paidOrder).containsEntry("paid_payment_id", cardPaymentId);
    assertThat((List<Map<String, Object>>) paidOrder.get("payments"))
        .extracting(attempt -> attempt.get("id"))
        .containsExactly(cardPaymentId);
    assertThat(getList(key, "/v1/orders?reference=pedido-2")).hasSize(1);
    assertThat(getList(key, "/v1/orders/" + cardOrderId + "/payments")).hasSize(1);

    List<Map<String, Object>> cards =
        (List<Map<String, Object>>) getList(key, "/v1/customers/" + customerId + "/cards");
    assertThat(cards).extracting(card -> card.get("id")).containsExactly(cardId);

    // 6. A subscription on that card: the job runner bills the first cycle at once, by token.
    EntityExchangeResult<Map> subscribed =
        post(
            key,
            "sub-1",
            "/v1/subscriptions",
            Map.of(
                "customer_id", customerId, "plan_id", planId, "method", "CARD", "card_id", cardId));
    assertThat(subscribed.getStatus().value()).isEqualTo(201);
    assertThat(subscribed.getResponseBody())
        .containsEntry("status", "ACTIVE")
        .containsEntry("card_id", cardId);
    assertThat(subscribed.getResponseBody().get("next_billing_at")).isNotNull();
    String subscriptionId = (String) subscribed.getResponseBody().get("id");

    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(
            () -> {
              Object latest =
                  getJson(key, "/v1/subscriptions/" + subscriptionId).get("latest_order");
              return latest != null && "PAID".equals(((Map<String, Object>) latest).get("status"));
            });
    List<Map<String, Object>> invoices =
        (List<Map<String, Object>>) getList(key, "/v1/subscriptions/" + subscriptionId + "/orders");
    assertThat(invoices).hasSize(1);
    assertThat(invoices.getFirst())
        .containsEntry("subscription_id", subscriptionId)
        .containsEntry("invoice_number", 1)
        .containsEntry("amount", 10000);
    CIELO.verify(
        postRequestedFor(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.Payment.CreditCard.CardToken")));
    assertThat(getList(key, "/v1/subscriptions?customer_id=" + customerId)).hasSize(1);

    // The customer cannot go while the subscription is active.
    http()
        .delete()
        .uri("/v1/customers/" + customerId)
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectStatus()
        .isEqualTo(409);

    // 7. Canceled now; then the customer may be deleted.
    EntityExchangeResult<Map> ended =
        post(
            key,
            "sub-cancel-1",
            "/v1/subscriptions/" + subscriptionId + "/cancel",
            Map.of("at_period_end", false));
    assertThat(ended.getStatus().value()).isEqualTo(200);
    assertThat(ended.getResponseBody()).containsEntry("status", "CANCELED");

    http()
        .delete()
        .uri("/v1/customers/" + customerId)
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectStatus()
        .isNoContent();
    http()
        .get()
        .uri("/v1/customers/" + customerId)
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectStatus()
        .isNotFound();

    // 8. Every create and every action that may move money requires an Idempotency-Key.
    for (String uri :
        List.of(
            "/v1/customers",
            "/v1/orders",
            "/v1/plans",
            "/v1/subscriptions",
            "/v1/orders/" + cardOrderId + "/payments",
            "/v1/orders/" + cardOrderId + "/cancel",
            "/v1/subscriptions/" + subscriptionId + "/cancel")) {
      EntityExchangeResult<Map> withoutKey = post(key, null, uri, Map.of());
      assertThat(withoutKey.getStatus().value()).as(uri).isEqualTo(400);
      assertThat(withoutKey.getResponseBody())
          .as(uri)
          .containsEntry("type", "urn:gateway:IDEMPOTENCY_KEY_REQUIRED");
    }
  }
}
