package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import tools.jackson.databind.json.JsonMapper;

/**
 * Spec 2026-10-07 through the real app, WireMock playing the Cielo. Each test has its own merchant:
 * the settings are per merchant, and one test's rate must not price another's checkout.
 *
 * <p>The expected values are the hand-computed table of {@code InstallmentPricingTest}: R$ 100,00
 * at 2,99% a month is 4x 2690 (10760), 6x 1846 (11076) and 10x 1172 (11720).
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
class InstallmentsIntegrationTest {
  static final String CARD_NUMBER = BillingApiIntegrationTest.CARD_NUMBER;
  static final int TOKEN_LENGTH = 47;
  static final JsonMapper JSON = JsonMapper.builder().build();

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer CIELO = new WireMockServer(options().dynamicPort());

  static {
    CIELO.start();
  }

  static int idempotencyCounter;

  @DynamicPropertySource
  static void providers(DynamicPropertyRegistry registry) {
    registry.add("gateway.providers.cielo.test-api-base", () -> CIELO.baseUrl() + "/api");
    registry.add("gateway.providers.cielo.test-query-api-base", () -> CIELO.baseUrl() + "/query");
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
  }

  @AfterAll
  static void stop() {
    CIELO.stop();
  }

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  String merchantId;

  String testKey;

  @BeforeEach
  void merchant() {
    merchantId =
        (String) adminPost("/v1/admin/merchants", Map.of("name", "Installments Store")).get("id");
    testKey = keyFor(merchantId, "TEST");
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

  String keyFor(String merchant, String environment) {
    return (String)
        adminPost(
                "/v1/admin/merchants/" + merchant + "/api-keys", Map.of("environment", environment))
            .get("key");
  }

  @SuppressWarnings("rawtypes")
  EntityExchangeResult<Map> merchantPost(String key, String uri, Object body) {
    idempotencyCounter++;

    return http()
        .post()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .header("Idempotency-Key", "installments-" + idempotencyCounter)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  @SuppressWarnings("rawtypes")
  EntityExchangeResult<Map> merchantGet(String key, String uri) {
    return http()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  /** No Idempotency-Key on purpose: the PUT replaces state and is not under IdempotencyFilter. */
  @SuppressWarnings("rawtypes")
  EntityExchangeResult<Map> putSettings(String key, Object body) {
    return http()
        .put()
        .uri("/v1/installment-settings")
        .header("Authorization", "Bearer " + key)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  static Map<String, Object> settings(int max, int freeUpTo, int bps) {
    return Map.of(
        "max_installments", max, "interest_free_up_to", freeUpTo, "monthly_rate_bps", bps);
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
  Map<String, Object> checkout(String token) {
    return http()
        .get()
        .uri("/v1/checkout/" + token)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("unchecked")
  List<Map<String, Object>> optionsOf(String token) {
    return (List<Map<String, Object>>) checkout(token).get("installment_options");
  }

  /** An order with the payer inline; returns {id, token}. */
  String[] order(String key, long amount) {
    Map<String, Object> customer =
        Map.of("name", "Ana Souza", "document", "52998224725", "email", "ana@example.com");
    @SuppressWarnings("rawtypes")
    EntityExchangeResult<Map> created =
        merchantPost(
            key,
            "/v1/orders",
            Map.of("amount", amount, "currency", "BRL", "reference", "p-1", "customer", customer));
    assertThat(created.getStatus().value()).isEqualTo(201);

    String url = (String) created.getResponseBody().get("checkout_url");
    String token = url.substring(url.length() - TOKEN_LENGTH);

    return new String[] {(String) created.getResponseBody().get("id"), token};
  }

  static Map<String, Object> cardAttempt(int installments) {
    Map<String, Object> body = new HashMap<>();
    body.put("method", "CARD");
    body.put(
        "card",
        Map.of("number", CARD_NUMBER, "holder", "ANA SOUZA", "expiry", "12/2030", "cvv", "987"));
    body.put("installments", installments);
    body.put("capture", true);
    return body;
  }

  static Map<String, Object> option(int count, int installment, int total, boolean free) {
    return Map.of(
        "count", count, "installment_amount", installment, "total", total, "interest_free", free);
  }

  @Test
  void withoutSettingsTheCheckoutOffersTwelveInterestFree() {
    List<Map<String, Object>> hundred = optionsOf(order(testKey, 10000)[1]);
    List<Map<String, Object>> twenty = optionsOf(order(testKey, 2000)[1]);

    assertThat(hundred).hasSize(12);
    assertThat(hundred)
        .allSatisfy(
            option ->
                assertThat(option)
                    .containsEntry("total", 10000)
                    .containsEntry("interest_free", true));
    assertThat(hundred.get(2)).isEqualTo(option(3, 3333, 10000, true));
    assertThat(hundred.getLast()).isEqualTo(option(12, 833, 10000, true));
    // R$ 5,00 per installment: 2000 / 4 = 500 is the last one offered.
    assertThat(twenty).extracting(option -> option.get("count")).containsExactly(1, 2, 3, 4);
  }

  @Test
  @SuppressWarnings("unchecked")
  void theSettingsArePutReadAndPriceTheCheckout() {
    EntityExchangeResult<Map> put = putSettings(testKey, settings(10, 3, 299));

    assertThat(put.getStatus().value()).isEqualTo(200);
    assertThat((Map<String, Object>) put.getResponseBody())
        .containsEntry("environment", "TEST")
        .containsEntry("max_installments", 10)
        .containsEntry("interest_free_up_to", 3)
        .containsEntry("monthly_rate_bps", 299);
    assertThat(put.getResponseBody().get("updated_at")).isNotNull();
    assertThat(merchantGet(testKey, "/v1/installment-settings").getResponseBody())
        .isEqualTo(put.getResponseBody());

    List<Map<String, Object>> options = optionsOf(order(testKey, 10000)[1]);

    assertThat(options).hasSize(10);
    assertThat(options.subList(0, 3))
        .containsExactly(
            option(1, 10000, 10000, true),
            option(2, 5000, 10000, true),
            option(3, 3333, 10000, true));
    assertThat(options.subList(3, 10))
        .allSatisfy(option -> assertThat(option).containsEntry("interest_free", false));
    assertThat(options.get(3)).isEqualTo(option(4, 2690, 10760, false));
    assertThat(options.get(5)).isEqualTo(option(6, 1846, 11076, false));
    assertThat(options.get(9)).isEqualTo(option(10, 1172, 11720, false));
  }

  @Test
  @SuppressWarnings("unchecked")
  void sixInstallmentsThroughTheCheckoutChargeTheTotal() throws Exception {
    putSettings(testKey, settings(10, 3, 299));
    String[] order = order(testKey, 10000);

    EntityExchangeResult<Map> charged =
        publicPost("/v1/checkout/" + order[1] + "/payments", cardAttempt(6));

    assertThat(charged.getStatus().value()).isEqualTo(201);
    assertThat(charged.getResponseBody()).containsEntry("status", "COMPLETED");
    assertThat((Map<String, Object>) charged.getResponseBody().get("card"))
        .containsEntry("installments", 6)
        .containsEntry("interest_amount", 1076);
    String paymentId = (String) charged.getResponseBody().get("id");

    // The acquirer is asked for the total in six: its division gives the 1846 the payer saw.
    CIELO.verify(
        1,
        postRequestedFor(urlEqualTo("/api/1/sales"))
            .withRequestBody(matchingJsonPath("$.MerchantOrderId", equalTo(paymentId)))
            .withRequestBody(matchingJsonPath("$.Payment.Amount", equalTo("11076")))
            .withRequestBody(matchingJsonPath("$.Payment.Installments", equalTo("6"))));

    Map<String, Object> payment =
        merchantGet(testKey, "/v1/payments/" + paymentId).getResponseBody();
    assertThat(payment).containsEntry("amount", 11076).containsEntry("order_id", order[0]);
    assertThat((Map<String, Object>) payment.get("card"))
        .containsEntry("installments", 6)
        .containsEntry("interest_amount", 1076);
    // The order keeps what was owed; the interest lives on the payment.
    assertThat(merchantGet(testKey, "/v1/orders/" + order[0]).getResponseBody())
        .containsEntry("amount", 10000);

    String completed =
        jdbc.queryForObject(
            "SELECT payload FROM payments.outbox WHERE aggregate_id = ?"
                + " AND event_type = 'payment.completed'",
            String.class,
            paymentId);
    Map<String, Object> event = JSON.readValue(completed, Map.class);
    assertThat(event).containsEntry("amount", 11076);
    assertThat((Map<String, Object>) event.get("card")).containsEntry("interest_amount", 1076);
  }

  @Test
  @SuppressWarnings("rawtypes")
  void aCountTheSettingsDoNotOfferIs422() {
    putSettings(testKey, settings(10, 3, 299));
    String[] order = order(testKey, 10000);

    EntityExchangeResult<Map> merchant =
        merchantPost(testKey, "/v1/orders/" + order[0] + "/payments", cardAttempt(11));
    EntityExchangeResult<Map> payer =
        publicPost("/v1/checkout/" + order[1] + "/payments", cardAttempt(11));

    assertThat(merchant.getStatus().value()).isEqualTo(422);
    assertThat(merchant.getResponseBody())
        .containsEntry("type", "urn:gateway:INVALID_INSTALLMENTS")
        .containsEntry("detail", "installments 11 is not offered for this order (1 to 10)");
    assertThat(payer.getStatus().value()).isEqualTo(422);
    assertThat(payer.getResponseBody()).containsEntry("type", "urn:gateway:INVALID_INSTALLMENTS");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments.payments WHERE order_id = ?",
                Integer.class,
                order[0]))
        .isZero();
  }

  @Test
  @SuppressWarnings({"rawtypes", "unchecked"})
  void aLiveKeyNeitherSeesNorChangesTheTestSettings() {
    String liveKey = keyFor(merchantId, "LIVE");
    putSettings(testKey, settings(10, 3, 299));

    EntityExchangeResult<Map> live = merchantGet(liveKey, "/v1/installment-settings");

    assertThat((Map<String, Object>) live.getResponseBody())
        .containsEntry("environment", "LIVE")
        .containsEntry("max_installments", 12)
        .containsEntry("interest_free_up_to", 12)
        .containsEntry("monthly_rate_bps", 0)
        .containsEntry("updated_at", null);

    assertThat(putSettings(liveKey, settings(6, 6, 0)).getStatus().value()).isEqualTo(200);

    assertThat(
            (Map<String, Object>)
                merchantGet(testKey, "/v1/installment-settings").getResponseBody())
        .containsEntry("environment", "TEST")
        .containsEntry("max_installments", 10)
        .containsEntry("interest_free_up_to", 3)
        .containsEntry("monthly_rate_bps", 299);
  }

  @Test
  @SuppressWarnings("rawtypes")
  void anInvalidPutIs400AndChangesNothing() {
    EntityExchangeResult<Map> freeAboveMax = putSettings(testKey, settings(6, 7, 0));
    EntityExchangeResult<Map> rateTooHigh = putSettings(testKey, settings(6, 1, 1001));
    EntityExchangeResult<Map> missing =
        putSettings(testKey, Map.of("max_installments", 6, "interest_free_up_to", 1));
    EntityExchangeResult<Map> environmentInTheBody =
        putSettings(
            testKey,
            Map.of(
                "environment",
                "LIVE",
                "max_installments",
                6,
                "interest_free_up_to",
                1,
                "monthly_rate_bps",
                0));

    assertThat(freeAboveMax.getStatus().value()).isEqualTo(400);
    assertThat(freeAboveMax.getResponseBody())
        .containsEntry("type", "urn:gateway:INVALID_REQUEST")
        .containsEntry("detail", "interest_free_up_to must be between 1 and max_installments (6)");
    assertThat(rateTooHigh.getStatus().value()).isEqualTo(400);
    assertThat(rateTooHigh.getResponseBody())
        .containsEntry("detail", "monthly_rate_bps must be between 0 and 1000");
    assertThat(missing.getStatus().value()).isEqualTo(400);
    assertThat(missing.getResponseBody()).containsEntry("detail", "monthly_rate_bps is required");
    // The environment is the key's: a body that names one is an unknown field, not a switch.
    assertThat(environmentInTheBody.getStatus().value()).isEqualTo(400);
    assertThat(merchantGet(testKey, "/v1/installment-settings").getResponseBody())
        .containsEntry("max_installments", 12)
        .containsEntry("updated_at", null);
  }

  @Test
  void withoutTheCardTheCheckoutOffersNoInstallments() {
    String pixOnly = (String) adminPost("/v1/admin/merchants", Map.of("name", "No Card")).get("id");
    String[] order = order(keyFor(pixOnly, "TEST"), 10000);

    assertThat(optionsOf(order[1])).isEmpty();
  }
}
