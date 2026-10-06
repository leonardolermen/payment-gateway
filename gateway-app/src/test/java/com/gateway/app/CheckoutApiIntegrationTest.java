package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
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
import org.junit.jupiter.api.BeforeEach;
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
 * The payer's routes through the real app, with WireMock playing the Itaú and the Cielo. Every
 * public call goes out with no Authorization header at all: the token in the path is the whole
 * authorization, and a test that sent the merchant key along would prove nothing.
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
class CheckoutApiIntegrationTest {
  static final String CARD_NUMBER = BillingApiIntegrationTest.CARD_NUMBER;
  static final String DOCUMENT = "52998224725";
  static final String EMAIL = "ana@example.com";
  static final int TOKEN_LENGTH = 47;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer CIELO = new WireMockServer(options().dynamicPort());
  static final WireMockServer ITAU = new WireMockServer(options().dynamicPort());

  static {
    CIELO.start();
    ITAU.start();
  }

  /** One merchant for every test: created by the first one to run. */
  static String merchantId;

  static String apiKey;

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
    CIELO.stubFor(
        WireMock.post(urlEqualTo("/api/1/sales"))
            .withRequestBody(
                matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo(CARD_NUMBER)))
            .willReturn(
                BillingApiIntegrationTest.created(
                    BillingApiIntegrationTest.paid(
                        BillingApiIntegrationTest.cieloFixture(
                            "post_sales_201_authorized_saved.json")))));

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
    ITAU.stubFor(
        WireMock.patch(urlMatching("/cob/[A-Za-z0-9]+"))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        BillingApiIntegrationTest.itauFixture("put_cob_201.json")
                            .replace("\"ATIVA\"", "\"REMOVIDA_PELO_USUARIO_RECEBEDOR\""))));
  }

  @AfterAll
  static void stop() {
    CIELO.stop();
    ITAU.stop();
  }

  @LocalServerPort int port;

  @BeforeEach
  void merchant() {
    if (merchantId != null) {
      return;
    }

    merchantId =
        (String) adminPost("/v1/admin/merchants", Map.of("name", "Billing Store")).get("id");
    apiKey = keyFor(merchantId);
    cieloCredential(merchantId);
    itauCredential(merchantId);
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

  String keyFor(String merchant) {
    return (String)
        adminPost("/v1/admin/merchants/" + merchant + "/api-keys", Map.of("environment", "TEST"))
            .get("key");
  }

  void cieloCredential(String merchant) {
    adminPut(
        "/v1/admin/merchants/" + merchant + "/providers/CIELO/credentials",
        Map.of(
            "environment",
            "TEST",
            "payload",
            Map.of(
                "merchant_id",
                "11111111-2222-3333-4444-555555555555",
                "merchant_key",
                "A".repeat(40))));
  }

  void itauCredential(String merchant) {
    adminPut(
        "/v1/admin/merchants/" + merchant + "/providers/ITAU/credentials",
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
  }

  /** The merchant's side: key and Idempotency-Key, like every merchant POST. */
  @SuppressWarnings("rawtypes")
  EntityExchangeResult<Map> merchantPost(String key, String uri, Object body) {
    idempotencyCounter++;

    return http()
        .post()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .header("Idempotency-Key", "checkout-" + idempotencyCounter)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> merchantGet(String key, String uri) {
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

  /** The payer's side: no Authorization header, no Idempotency-Key. */
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

  @SuppressWarnings("rawtypes")
  EntityExchangeResult<Map> publicGet(String uri) {
    return http().get().uri(uri).exchange().expectBody(Map.class).returnResult();
  }

  EntityExchangeResult<String> publicGetText(String uri) {
    return http().get().uri(uri).exchange().expectBody(String.class).returnResult();
  }

  /** An order with the payer inline; returns {id, token}. */
  String[] order(String key) {
    Map<String, Object> customer =
        Map.of(
            "name",
            "Ana Souza",
            "document",
            DOCUMENT,
            "email",
            EMAIL,
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
    @SuppressWarnings("rawtypes")
    EntityExchangeResult<Map> created =
        merchantPost(
            key,
            "/v1/orders",
            Map.of(
                "amount",
                10000,
                "currency",
                "BRL",
                "reference",
                "pedido-checkout",
                "description",
                "Camiseta",
                "customer",
                customer));
    assertThat(created.getStatus().value()).isEqualTo(201);

    String url = (String) created.getResponseBody().get("checkout_url");
    String token = url.substring(url.length() - TOKEN_LENGTH);

    return new String[] {(String) created.getResponseBody().get("id"), token};
  }

  static Map<String, Object> cardAttempt() {
    Map<String, Object> body = new HashMap<>();
    body.put("method", "CARD");
    body.put(
        "card",
        Map.of("number", CARD_NUMBER, "holder", "ANA SOUZA", "expiry", "12/2030", "cvv", "987"));
    body.put("installments", 1);
    body.put("capture", true);
    return body;
  }

  static Map<String, Object> pixAttempt() {
    return Map.of("method", "PIX", "expires_in", 600);
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void theCheckoutIsReadWithoutAKey() {
    String[] order = order(apiKey);

    EntityExchangeResult<Map> checkout = publicGet("/v1/checkout/" + order[1]);

    assertThat(checkout.getStatus().value()).isEqualTo(200);
    assertThat(checkout.getResponseBody())
        .containsEntry("order_id", order[0])
        .containsEntry("merchant_name", "Billing Store")
        .containsEntry("amount", 10000)
        .containsEntry("currency", "BRL")
        .containsEntry("description", "Camiseta")
        .containsEntry("status", "OPEN")
        .containsEntry("active_payment", null);
    assertThat((List<String>) checkout.getResponseBody().get("methods"))
        .contains("PIX", "BOLECODE", "CARD");
  }

  @Test
  void unknownTokenIs404() {
    String unknown = "chk_" + "z".repeat(43);

    EntityExchangeResult<String> missing = publicGetText("/v1/checkout/" + unknown);
    EntityExchangeResult<String> garbage = publicGetText("/v1/checkout/garbage");

    assertThat(missing.getStatus().value()).isEqualTo(404);
    assertThat(missing.getResponseBody()).contains("NOT_FOUND").doesNotContain(unknown);
    assertThat(garbage.getStatus().value()).isEqualTo(404);
    assertThat(garbage.getResponseBody()).doesNotContain("garbage");
  }

  @Test
  void theCheckoutResponseNeverCarriesThePayer() {
    String[] order = order(apiKey);
    publicPost("/v1/checkout/" + order[1] + "/payments", pixAttempt());

    String body = publicGetText("/v1/checkout/" + order[1]).getResponseBody();

    assertThat(body)
        .doesNotContain(DOCUMENT)
        .doesNotContain(EMAIL)
        .doesNotContain(merchantId)
        .doesNotContain("payer")
        .doesNotContain("customer")
        .doesNotContain("reference")
        .doesNotContain("provider")
        .doesNotContain("txid");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void aPixAttemptThenPollingThenCancel() {
    String[] order = order(apiKey);
    String base = "/v1/checkout/" + order[1];

    EntityExchangeResult<Map> attempt = publicPost(base + "/payments", pixAttempt());

    assertThat(attempt.getStatus().value()).isEqualTo(201);
    assertThat(attempt.getResponseBody())
        .containsEntry("method", "PIX")
        .containsEntry("status", "PENDING");
    assertThat(((Map<String, Object>) attempt.getResponseBody().get("pix")).get("copia_e_cola"))
        .isNotNull();
    String paymentId = (String) attempt.getResponseBody().get("id");

    EntityExchangeResult<Map> polled = publicGet(base + "/payments/" + paymentId);

    assertThat(polled.getStatus().value()).isEqualTo(200);
    // Not the whole body: expires_at comes back from Postgres at microseconds, the 201 had nanos.
    assertThat(polled.getResponseBody())
        .containsEntry("id", paymentId)
        .containsEntry("status", "PENDING");
    assertThat(((Map<String, Object>) polled.getResponseBody().get("pix")).get("copia_e_cola"))
        .isEqualTo(
            ((Map<String, Object>) attempt.getResponseBody().get("pix")).get("copia_e_cola"));
    assertThat((Map<String, Object>) publicGet(base).getResponseBody().get("active_payment"))
        .containsEntry("id", paymentId);

    EntityExchangeResult<Map> canceled =
        publicPost(base + "/payments/" + paymentId + "/cancel", Map.of());

    assertThat(canceled.getStatus().value()).isEqualTo(200);
    assertThat(canceled.getResponseBody()).containsEntry("status", "CANCELED");
    assertThat(publicGet(base).getResponseBody())
        .containsEntry("status", "OPEN")
        .containsEntry("active_payment", null);
  }

  @Test
  @SuppressWarnings("rawtypes")
  void aSecondActiveAttemptIs409() {
    String[] order = order(apiKey);
    String base = "/v1/checkout/" + order[1];

    String first =
        (String) publicPost(base + "/payments", pixAttempt()).getResponseBody().get("id");
    EntityExchangeResult<Map> second = publicPost(base + "/payments", pixAttempt());

    assertThat(second.getStatus().value()).isEqualTo(409);
    assertThat(second.getResponseBody())
        .containsEntry("type", "urn:gateway:ORDER_HAS_ACTIVE_PAYMENT")
        .containsEntry("payment_id", first);
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void aCardAttemptIsPaid() {
    String[] order = order(apiKey);
    String base = "/v1/checkout/" + order[1];

    EntityExchangeResult<Map> charged = publicPost(base + "/payments", cardAttempt());

    assertThat(charged.getStatus().value()).isEqualTo(201);
    assertThat(charged.getResponseBody())
        .containsEntry("method", "CARD")
        .containsEntry("status", "COMPLETED");
    Map<String, Object> card = (Map<String, Object>) charged.getResponseBody().get("card");
    assertThat(card).containsEntry("installments", 1);
    // The fixture's masked number, not the one sent: the receipt shows what the Cielo answered.
    assertThat(card.get("last4")).isNotNull();
    assertThat(card).doesNotContainKeys("number", "tid", "authorization_code", "card_id");
    assertThat(charged.getResponseBody().toString()).doesNotContain(CARD_NUMBER);

    String paymentId = (String) charged.getResponseBody().get("id");
    EntityExchangeResult<Map> cancel =
        publicPost(base + "/payments/" + paymentId + "/cancel", Map.of());

    assertThat(cancel.getStatus().value()).isEqualTo(422);
    assertThat(cancel.getResponseBody())
        .containsEntry("type", "urn:gateway:CHECKOUT_CANNOT_CANCEL_CARD");
  }

  @Test
  @SuppressWarnings("rawtypes")
  void aPaidOrderStillAnswersTheGet() {
    String[] order = order(apiKey);
    String base = "/v1/checkout/" + order[1];
    assertThat(publicPost(base + "/payments", cardAttempt()).getResponseBody())
        .containsEntry("status", "COMPLETED");

    // The order turns PAID only when the outbox relay hands the payment event to the settlement.
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .until(() -> "PAID".equals(merchantGet(apiKey, "/v1/orders/" + order[0]).get("status")));

    EntityExchangeResult<Map> checkout = publicGet(base);

    assertThat(checkout.getStatus().value()).isEqualTo(200);
    assertThat(checkout.getResponseBody())
        .containsEntry("status", "PAID")
        .containsEntry("active_payment", null);

    EntityExchangeResult<Map> again = publicPost(base + "/payments", pixAttempt());

    assertThat(again.getStatus().value()).isEqualTo(410);
    assertThat(again.getResponseBody()).containsEntry("type", "urn:gateway:CHECKOUT_ORDER_CLOSED");
  }

  @Test
  @SuppressWarnings("rawtypes")
  void aCanceledOrderIs410ForPostAnd200ForGet() {
    String[] order = order(apiKey);
    String base = "/v1/checkout/" + order[1];
    assertThat(
            merchantPost(apiKey, "/v1/orders/" + order[0] + "/cancel", Map.of())
                .getStatus()
                .value())
        .isEqualTo(200);

    EntityExchangeResult<Map> checkout = publicGet(base);
    EntityExchangeResult<Map> attempt = publicPost(base + "/payments", pixAttempt());

    assertThat(checkout.getStatus().value()).isEqualTo(200);
    assertThat(checkout.getResponseBody()).containsEntry("status", "CANCELED");
    assertThat(attempt.getStatus().value()).isEqualTo(410);
    assertThat(attempt.getResponseBody())
        .containsEntry("type", "urn:gateway:CHECKOUT_ORDER_CLOSED");
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void aMethodWithoutACredentialIs422() {
    String pixOnly =
        (String) adminPost("/v1/admin/merchants", Map.of("name", "Pix Only")).get("id");
    String pixOnlyKey = keyFor(pixOnly);
    itauCredential(pixOnly);
    String[] order = order(pixOnlyKey);
    String base = "/v1/checkout/" + order[1];

    List<String> methods = (List<String>) publicGet(base).getResponseBody().get("methods");
    EntityExchangeResult<Map> card = publicPost(base + "/payments", cardAttempt());

    assertThat(methods).containsExactly("PIX", "BOLECODE");
    assertThat(card.getStatus().value()).isEqualTo(422);
    assertThat(card.getResponseBody())
        .containsEntry("type", "urn:gateway:PROVIDER_CREDENTIALS_MISSING");
  }

  @Test
  @SuppressWarnings("rawtypes")
  void aPaymentOfAnotherOrderIs404() {
    String[] first = order(apiKey);
    String[] second = order(apiKey);
    String paymentId =
        (String)
            publicPost("/v1/checkout/" + first[1] + "/payments", pixAttempt())
                .getResponseBody()
                .get("id");

    EntityExchangeResult<Map> read =
        publicGet("/v1/checkout/" + second[1] + "/payments/" + paymentId);
    EntityExchangeResult<Map> cancel =
        publicPost("/v1/checkout/" + second[1] + "/payments/" + paymentId + "/cancel", Map.of());

    assertThat(read.getStatus().value()).isEqualTo(404);
    assertThat(cancel.getStatus().value()).isEqualTo(404);
  }
}
