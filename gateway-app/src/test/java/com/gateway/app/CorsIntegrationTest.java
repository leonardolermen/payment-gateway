package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Two Spring contexts in one file: a nested class per property variant, since a context is keyed by
 * its properties. The first has an allowlist, the second none (CORS must then be fully off).
 */
class CorsIntegrationTest {
  private static final String ALLOWED = "https://pay.test";

  @Nested
  @SpringBootTest(
      webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
      properties = "gateway.checkout.cors-origins=" + ALLOWED)
  @ActiveProfiles("test")
  @Testcontainers
  class WithAnAllowlist {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @LocalServerPort int port;

    @Test
    void anAllowedOriginGetsItsPreflightAnswered() {
      http()
          .options()
          .uri("/v1/checkout/chk_x")
          .header("Origin", ALLOWED)
          .header("Access-Control-Request-Method", "POST")
          .header("Access-Control-Request-Headers", "authorization,idempotency-key")
          .exchange()
          .expectStatus()
          .is2xxSuccessful()
          .expectHeader()
          .valueEquals("Access-Control-Allow-Origin", ALLOWED)
          .expectHeader()
          .value(
              "Access-Control-Allow-Headers",
              // CorsFilter echoes the requested header names as sent (lower-case); names are
              // case-insensitive, so the assertion is too.
              value ->
                  assertThat(value.toLowerCase()).contains("authorization", "idempotency-key"));
    }

    @Test
    void anotherOriginGetsNoCorsHeaders() {
      http()
          .options()
          .uri("/v1/checkout/chk_x")
          .header("Origin", "https://evil.test")
          .header("Access-Control-Request-Method", "POST")
          .exchange()
          .expectHeader()
          .doesNotExist("Access-Control-Allow-Origin");
    }

    @Test
    void aPreflightOnAMerchantRouteNeverReachesTheKeyFilter() {
      // 2xx without a key proves the preflight returned from the CORS filter, before auth.
      http()
          .options()
          .uri("/v1/merchant")
          .header("Origin", ALLOWED)
          .header("Access-Control-Request-Method", "GET")
          .exchange()
          .expectStatus()
          .is2xxSuccessful();
      http()
          .get()
          .uri("/v1/merchant")
          .header("Origin", ALLOWED)
          .exchange()
          .expectStatus()
          .isUnauthorized();
    }

    @Test
    void aRealMerchantRequestCarriesTheCorsHeaders() {
      http()
          .get()
          .uri("/v1/merchant")
          .header("Origin", ALLOWED)
          .header("Authorization", "Bearer " + newKey())
          .exchange()
          .expectStatus()
          .isOk()
          .expectHeader()
          .valueEquals("Access-Control-Allow-Origin", ALLOWED)
          .expectHeader()
          .value(
              "Access-Control-Expose-Headers",
              value -> assertThat(value).contains("X-Next-Cursor"));
    }

    private RestTestClient http() {
      return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
    }

    @SuppressWarnings("unchecked")
    private String newKey() {
      Map<String, Object> merchant = admin("/v1/admin/merchants", Map.of("name", "Cors Store"));
      Map<String, Object> key =
          admin(
              "/v1/admin/merchants/" + merchant.get("id") + "/api-keys",
              Map.of("environment", "TEST"));
      return (String) key.get("key");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> admin(String uri, Map<String, Object> body) {
      return http()
          .post()
          .uri(uri)
          .header("X-Admin-Key", "test-admin")
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .exchange()
          .expectStatus()
          .isCreated()
          .expectBody(Map.class)
          .returnResult()
          .getResponseBody();
    }
  }

  @Nested
  @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
  @ActiveProfiles("test")
  @Testcontainers
  class WithoutAnAllowlist {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @LocalServerPort int port;

    @Test
    void noCorsHeadersAtAll() {
      RestTestClient.bindToServer()
          .baseUrl("http://localhost:" + port)
          .build()
          .options()
          .uri("/v1/checkout/chk_x")
          .header("Origin", ALLOWED)
          .header("Access-Control-Request-Method", "POST")
          .exchange()
          .expectHeader()
          .doesNotExist("Access-Control-Allow-Origin");
    }
  }
}
