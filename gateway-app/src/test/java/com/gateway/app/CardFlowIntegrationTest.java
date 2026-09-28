package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
 * The card path through the real app (spec §10): authorize without capture and save the card,
 * capture part of it, refund part of that, read and delete the saved card, the Cielo notification
 * with and without its header, and a decline as a 402. WireMock plays both Cielo hosts.
 *
 * <p>One test method on purpose, like the Pix and Bolecode flow tests: every step builds on the
 * previous.
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
class CardFlowIntegrationTest {
  static final String SALE = "6f8d1753-86bb-4dc0-9ebb-09a29093e1fb";
  static final String NOTIFICATION_KEY = "notification-key-for-tests";

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer CIELO = new WireMockServer(options().dynamicPort());

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
            .withRequestBody(
                matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo("4024007153763171")))
            .willReturn(created(fixture("post_sales_201_authorized_saved.json"))));
    CIELO.stubFor(
        WireMock.post(urlEqualTo("/api/1/sales"))
            .withRequestBody(
                matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo("4024007153760052")))
            .willReturn(created(fixture("post_sales_201_denied.json"))));
    CIELO.stubFor(
        put(urlPathEqualTo("/api/1/sales/" + SALE + "/capture"))
            .willReturn(okJson(fixture("put_capture_200.json"))));
    CIELO.stubFor(get(urlEqualTo("/query/1/sales/" + SALE)).willReturn(okJson(capturedSale(6000))));
    CIELO.stubFor(
        put(urlPathEqualTo("/api/1/sales/" + SALE + "/void"))
            .willReturn(okJson(fixture("put_void_200.json"))));
  }

  @AfterAll
  static void stop() {
    CIELO.stop();
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder created(String body) {
    return aResponse()
        .withStatus(201)
        .withHeader("Content-Type", "application/json")
        .withBody(body);
  }

  static String fixture(String name) {
    try (InputStream in =
        CardFlowIntegrationTest.class.getResourceAsStream("/cielo/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** get_sale_200_credit with this test's PaymentId and a partial capture (fixtures README). */
  static String capturedSale(long captured) {
    return fixture("get_sale_200_credit.json")
        .replace("2352fc91-f9a4-4ca2-aedb-31488b9658c9", SALE)
        .replace("\"CapturedAmount\": 15700", "\"CapturedAmount\": " + captured)
        .replace("\"Amount\": 15700", "\"Amount\": 12990");
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

  @SuppressWarnings("unchecked")
  EntityExchangeResult<Map> post(String apiKey, String idempotencyKey, String uri, Object body) {
    return http()
        .post()
        .uri(uri)
        .header("Authorization", "Bearer " + apiKey)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  static Map<String, Object> cardBody(String number, boolean capture, boolean saveCard) {
    Map<String, Object> body = new HashMap<>();
    body.put("method", "CARD");
    body.put("amount", 12990);
    body.put("currency", "BRL");
    body.put("reference", "order-42");
    body.put("description", "Pedido 42");
    body.put("soft_descriptor", "LOJA42");
    body.put(
        "card",
        Map.of(
            "number",
            number,
            "holder",
            "JOAO DA SILVA",
            "expiry",
            "12/2030",
            "cvv",
            "987",
            "brand",
            "VISA"));
    body.put("installments", 3);
    body.put("capture", capture);
    body.put("save_card", saveCard);
    body.put(
        "customer",
        Map.of("name", "Joao da Silva", "document", "12345678901", "email", "joao@example.com"));
    return body;
  }

  @Test
  @SuppressWarnings("unchecked")
  void authorizeCaptureRefundCardsNotificationAndDecline() {
    // 1. Merchant, TEST key, Cielo credential, notification key.
    String merchantId =
        (String) adminPost("/v1/admin/merchants", Map.of("name", "Card Store")).get("id");
    String testKey =
        (String)
            adminPost(
                    "/v1/admin/merchants/" + merchantId + "/api-keys",
                    Map.of("environment", "TEST"))
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
    http()
        .put()
        .uri("/v1/admin/merchants/" + merchantId + "/providers/CIELO/notification-key")
        .header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("key", NOTIFICATION_KEY))
        .exchange()
        .expectStatus()
        .isNoContent();

    // 2. Authorize without capture, saving the card: 201 AUTHORIZED with the card block.
    EntityExchangeResult<Map> created =
        post(testKey, "c1", "/v1/payments", cardBody("4024007153763171", false, true));
    assertThat(created.getStatus().value()).isEqualTo(201);
    Map<String, Object> payment = created.getResponseBody();
    String paymentId = (String) payment.get("id");
    assertThat(payment)
        .containsEntry("status", "AUTHORIZED")
        .containsEntry("method", "CARD")
        .containsEntry("provider", "CIELO");
    assertThat(payment.get("pix")).isNull();
    assertThat(payment.get("boleto")).isNull();
    Map<String, Object> card = (Map<String, Object>) payment.get("card");
    assertThat(card)
        .containsEntry("brand", "VISA")
        .containsEntry("installments", 3)
        .containsEntry("tid", "1124060407175");
    String cardId = (String) card.get("card_id");
    assertThat(cardId).isNotNull();

    // 3. Capture 60,00 of 129,90: 200 COMPLETED with the captured amount.
    EntityExchangeResult<Map> captured =
        post(testKey, "c2", "/v1/payments/" + paymentId + "/capture", Map.of("amount", 6000));
    assertThat(captured.getStatus().value()).isEqualTo(200);
    assertThat(captured.getResponseBody())
        .containsEntry("status", "COMPLETED")
        .containsEntry("paid_amount", 6000);
    CIELO.verify(
        putRequestedFor(urlPathEqualTo("/api/1/sales/" + SALE + "/capture"))
            .withQueryParam("amount", equalTo("6000")));
    EntityExchangeResult<Map> again =
        post(testKey, "c3", "/v1/payments/" + paymentId + "/capture", Map.of());
    assertThat(again.getStatus().value()).isEqualTo(409);
    assertThat(again.getResponseBody().get("type")).isEqualTo("urn:gateway:ALREADY_CAPTURED");

    // 4. Refund 20,00: synchronous, COMPLETED in the response.
    EntityExchangeResult<Map> refund =
        post(testKey, "c4", "/v1/payments/" + paymentId + "/refunds", Map.of("amount", 2000));
    assertThat(refund.getStatus().value()).isEqualTo(201);
    assertThat(refund.getResponseBody()).containsEntry("state", "COMPLETED");
    CIELO.verify(
        putRequestedFor(urlPathEqualTo("/api/1/sales/" + SALE + "/void"))
            .withQueryParam("amount", equalTo("2000")));

    // 5. The saved card, then gone.
    Map<String, Object> saved =
        http()
            .get()
            .uri("/v1/cards/" + cardId)
            .header("Authorization", "Bearer " + testKey)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    assertThat(saved)
        .containsEntry("brand", "VISA")
        .containsEntry("expiry", "12/2030")
        .containsEntry("holder", "JOAO DA SILVA");
    assertThat(saved).doesNotContainKey("token");

    // Another merchant's key cannot read or delete it: the same 404 as a card that does not exist.
    String otherMerchantId =
        (String) adminPost("/v1/admin/merchants", Map.of("name", "Other Store")).get("id");
    String otherKey =
        (String)
            adminPost(
                    "/v1/admin/merchants/" + otherMerchantId + "/api-keys",
                    Map.of("environment", "TEST"))
                .get("key");
    http()
        .get()
        .uri("/v1/cards/" + cardId)
        .header("Authorization", "Bearer " + otherKey)
        .exchange()
        .expectStatus()
        .isNotFound();
    http()
        .delete()
        .uri("/v1/cards/" + cardId)
        .header("Authorization", "Bearer " + otherKey)
        .exchange()
        .expectStatus()
        .isNotFound();

    http()
        .delete()
        .uri("/v1/cards/" + cardId)
        .header("Authorization", "Bearer " + testKey)
        .exchange()
        .expectStatus()
        .isNoContent();
    http()
        .get()
        .uri("/v1/cards/" + cardId)
        .header("Authorization", "Bearer " + testKey)
        .exchange()
        .expectStatus()
        .isNotFound();

    // 6. The Cielo notification: 404 without the header or with a wrong one, 200 with it.
    String token =
        jdbc.queryForObject(
            "SELECT inbound_webhook_token FROM merchants.merchants WHERE id = ?",
            String.class,
            merchantId);
    String notification = "{\"PaymentId\":\"" + SALE + "\",\"ChangeType\":1}";
    http()
        .post()
        .uri("/v1/providers/cielo/webhooks/" + token)
        .contentType(MediaType.APPLICATION_JSON)
        .body(notification)
        .exchange()
        .expectStatus()
        .isNotFound();
    http()
        .post()
        .uri("/v1/providers/cielo/webhooks/" + token)
        .header("X-Gateway-Notification-Key", "wrong")
        .contentType(MediaType.APPLICATION_JSON)
        .body(notification)
        .exchange()
        .expectStatus()
        .isNotFound();
    http()
        .post()
        .uri("/v1/providers/cielo/webhooks/" + token)
        .header("X-Gateway-Notification-Key", NOTIFICATION_KEY)
        .contentType(MediaType.APPLICATION_JSON)
        .body(notification)
        .exchange()
        .expectStatus()
        .isOk();
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(
            () ->
                jdbc.queryForObject(
                    "SELECT status FROM payments.webhook_inbox WHERE merchant_id = ? AND provider = 'CIELO'",
                    String.class,
                    merchantId),
            status -> !status.equals("RECEIVED"));
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM payments.webhook_inbox WHERE merchant_id = ? AND provider = 'CIELO'",
                String.class,
                merchantId))
        .isEqualTo("PROCESSED");

    // 7. A decline: 402 with our decline code and the failed payment's id.
    EntityExchangeResult<Map> declined =
        post(testKey, "c5", "/v1/payments", cardBody("4024007153760052", true, false));
    assertThat(declined.getStatus().value()).isEqualTo(402);
    assertThat(declined.getResponseBody())
        .containsEntry("type", "urn:gateway:CARD_DECLINED")
        .containsEntry("decline_code", "INSUFFICIENT_FUNDS")
        .containsKey("payment_id");
    assertThat(declined.getResponseBody().toString()).doesNotContain("Nao Autorizada");

    List<String> statuses =
        jdbc.queryForList(
            "SELECT status FROM payments.payments WHERE merchant_id = ? ORDER BY created_at",
            String.class,
            merchantId);
    assertThat(statuses).containsExactly("COMPLETED", "FAILED");
  }
}
