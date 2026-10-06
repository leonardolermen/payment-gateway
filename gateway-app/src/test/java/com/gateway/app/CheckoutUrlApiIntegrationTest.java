package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The merchant side of the public checkout: the url comes back once, on create and on rotate,
 * because only the token's hash is stored and GET therefore has nothing to rebuild it from.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "gateway.checkout.base-url=https://pay.test/pay/",
      "gateway.rate-limit.requests-per-minute=1000"
    })
@ActiveProfiles("test")
@Testcontainers
class CheckoutUrlApiIntegrationTest {
  private static final String URL_PREFIX = "https://pay.test/pay/chk_";
  private static final int TOKEN_BODY_LENGTH = 43;

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  @Autowired JdbcTemplate jdbc;

  RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @SuppressWarnings("unchecked")
  private String newKey() {
    Map<String, Object> merchant =
        http()
            .post()
            .uri("/v1/admin/merchants")
            .header("X-Admin-Key", "test-admin")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("name", "Checkout Store"))
            .exchange()
            .expectStatus()
            .is2xxSuccessful()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    Map<String, Object> apiKey =
        http()
            .post()
            .uri("/v1/admin/merchants/" + merchant.get("id") + "/api-keys")
            .header("X-Admin-Key", "test-admin")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("environment", "TEST"))
            .exchange()
            .expectStatus()
            .is2xxSuccessful()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();

    return (String) apiKey.get("key");
  }

  @SuppressWarnings("rawtypes")
  private EntityExchangeResult<Map> post(
      String key, String idempotencyKey, String uri, Object body) {
    var spec =
        http()
            .post()
            .uri(uri)
            .header("Authorization", "Bearer " + key)
            .contentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }

    return spec.body(body == null ? Map.of() : body)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  @SuppressWarnings("rawtypes")
  private Map get(String key, String uri) {
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

  private static Map<String, Object> orderBody() {
    return Map.of(
        "amount",
        10000,
        "currency",
        "BRL",
        "reference",
        "pedido-1",
        "customer",
        Map.of(
            "name",
            "Ana Souza",
            "document",
            "52998224725",
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
                "01001-000")));
  }

  private String hashOf(String orderId) {
    return jdbc.queryForObject(
        "SELECT checkout_token_hash FROM billing.orders WHERE id = ?", String.class, orderId);
  }

  @SuppressWarnings("rawtypes")
  private EntityExchangeResult<Map> createOrder(String key, String idempotencyKey) {
    EntityExchangeResult<Map> created = post(key, idempotencyKey, "/v1/orders", orderBody());
    assertThat(created.getStatus().value()).isEqualTo(201);

    return created;
  }

  @Test
  void createReturnsACheckoutUrlOnceAndGetReturnsNull() {
    String key = newKey();

    var created = createOrder(key, "ord-1");
    String url = (String) created.getResponseBody().get("checkout_url");
    String orderId = (String) created.getResponseBody().get("id");

    assertThat(url).startsWith(URL_PREFIX);
    String token = url.substring("https://pay.test/pay/".length());
    assertThat(token).hasSize("chk_".length() + TOKEN_BODY_LENGTH);

    assertThat(get(key, "/v1/orders/" + orderId)).containsEntry("checkout_url", null);

    String hash = hashOf(orderId);
    assertThat(hash).matches("[0-9a-f]{64}").isNotEqualTo(token);
  }

  @Test
  void rotateIssuesANewUrlAndNeedsAnIdempotencyKey() {
    String key = newKey();
    var created = createOrder(key, "ord-2");
    String firstUrl = (String) created.getResponseBody().get("checkout_url");
    String orderId = (String) created.getResponseBody().get("id");
    String firstHash = hashOf(orderId);
    String uri = "/v1/orders/" + orderId + "/checkout-token/rotate";

    var missingKey = post(key, null, uri, null);
    assertThat(missingKey.getStatus().value()).isEqualTo(400);
    assertThat(missingKey.getResponseBody())
        .containsEntry("type", "urn:gateway:IDEMPOTENCY_KEY_REQUIRED");

    var rotated = post(key, "rot-1", uri, null);
    assertThat(rotated.getStatus().value()).isEqualTo(200);
    String newUrl = (String) rotated.getResponseBody().get("checkout_url");
    assertThat(newUrl).startsWith(URL_PREFIX).isNotEqualTo(firstUrl);
    assertThat(hashOf(orderId)).isNotEqualTo(firstHash);
  }

  @Test
  void rotateOnACanceledOrderIs409() {
    String key = newKey();
    String orderId = (String) createOrder(key, "ord-3").getResponseBody().get("id");

    assertThat(post(key, "cancel-1", "/v1/orders/" + orderId + "/cancel", null).getStatus().value())
        .isEqualTo(200);

    var rotated = post(key, "rot-3", "/v1/orders/" + orderId + "/checkout-token/rotate", null);
    assertThat(rotated.getStatus().value()).isEqualTo(409);
    assertThat(rotated.getResponseBody()).containsEntry("type", "urn:gateway:ORDER_CLOSED");
  }
}
