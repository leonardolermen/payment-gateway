package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Deviation from the brief: the brief's snippet uses {@code TestRestTemplate}, which does not exist
 * in this Boot 4.0.7 / Spring Framework 7 dependency set — {@code TestRestTemplate} was removed in
 * favor of {@link RestTestClient} (see {@code org.springframework.test.web.servlet.client}). Ran
 * {@code mvn dependency:tree} and grepped every {@code spring-boot-*} jar in the local repo for
 * {@code TestRestTemplate.class}: zero matches; {@code spring-test-7.0.8.jar} carries {@code
 * RestTestClient} instead. This test keeps the brief's scenarios and assertions, expressed with the
 * client that is actually on the classpath.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuthenticationIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private RestTestClient.RequestBodySpec adminPost(String uri) {
    return http()
        .post()
        .uri(uri)
        .header("X-Admin-Key", "test-admin")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> merchantAndKey(String name, String environment) {
    Map<String, Object> m =
        adminPost("/v1/admin/merchants")
            .body(Map.of("name", name))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    String id = (String) m.get("id");
    Map<String, Object> body =
        adminPost("/v1/admin/merchants/" + id + "/api-keys")
            .body(Map.of("environment", environment))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    body.put("merchant_id", id);
    return body;
  }

  @Test
  void adminWithoutKeyIs403AndNoApiKeyIs401() {
    http()
        .post()
        .uri("/v1/admin/merchants")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(Map.of("name", "x"))
        .exchange()
        .expectStatus()
        .isForbidden();
    http().get().uri("/v1/merchant").exchange().expectStatus().isUnauthorized();
    http()
        .get()
        .uri("/v1/merchant")
        .header("Authorization", "Bearer gk_live_INVALID0000000000000000000")
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void issuedKeyAuthenticatesAndMeReturnsTheMerchant() {
    Map<String, Object> k = merchantAndKey("Acme Store", "TEST");
    String key = (String) k.get("key");
    assertThat(key).startsWith("gk_test_");
    Map<String, Object> me =
        http()
            .get()
            .uri("/v1/merchant")
            .header("Authorization", "Bearer " + key)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    assertThat(me)
        .containsEntry("merchant_id", k.get("merchant_id"))
        .containsEntry("environment", "TEST")
        .containsEntry("name", "Acme Store");
  }

  @Test
  void revokedKeyStopsWorking() {
    Map<String, Object> k = merchantAndKey("Store B", "LIVE");
    http()
        .delete()
        .uri("/v1/admin/merchants/" + k.get("merchant_id") + "/api-keys/" + k.get("id"))
        .header("X-Admin-Key", "test-admin")
        .exchange()
        .expectStatus()
        .isNoContent();
    http()
        .get()
        .uri("/v1/merchant")
        .header("Authorization", "Bearer " + k.get("key"))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  @Test
  void rateLimitPerKeyReturns429WithRetryAfter() {
    Map<String, Object> k = merchantAndKey("Store C", "TEST");
    String key = (String) k.get("key");
    for (int i = 0; i < 5; i++) {
      http()
          .get()
          .uri("/v1/merchant")
          .header("Authorization", "Bearer " + key)
          .exchange()
          .expectStatus()
          .isOk();
    }
    http()
        .get()
        .uri("/v1/merchant")
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectStatus()
        .isEqualTo(HttpStatusCode.valueOf(429))
        .expectHeader()
        .exists("Retry-After");
  }

  @Test
  void providerCredentialIsAcceptedAndNeverReturned() {
    Map<String, Object> k = merchantAndKey("Store D", "LIVE");
    http()
        .put()
        .uri("/v1/admin/merchants/" + k.get("merchant_id") + "/providers/ITAU/credentials")
        .header("X-Admin-Key", "test-admin")
        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
        .body(
            Map.of(
                "environment",
                "LIVE",
                "payload",
                Map.of("client_id", "abc", "client_secret", "secret")))
        .exchange()
        .expectStatus()
        .isNoContent()
        .expectBody()
        .isEmpty();
  }

  @Test
  void domainErrorsBecomeProblemDetails() {
    Map<String, Object> k = merchantAndKey("Store E", "TEST");
    adminPost("/v1/admin/merchants/" + k.get("merchant_id") + "/api-keys")
        .body(Map.of("environment", "TEST"))
        .exchange();
    Map<String, Object> third =
        adminPost("/v1/admin/merchants/" + k.get("merchant_id") + "/api-keys")
            .body(Map.of("environment", "TEST"))
            .exchange()
            .expectStatus()
            .isEqualTo(HttpStatusCode.valueOf(422))
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    assertThat(third).containsEntry("type", "urn:gateway:API_KEY_LIMIT");
  }

  /**
   * The final review's reproduction: MVC routes on the decoded, matrix-stripped path, so these used
   * to reach the admin controller with a merchant key. PathSanityFilter refuses them before any
   * auth.
   */
  @Test
  void encodedOrMatrixAdminPathsAreRejected() {
    String key = (String) merchantAndKey("Store P", "TEST").get("key");
    for (boolean withKey : new boolean[] {true, false}) {
      var get =
          http()
              .get()
              .uri(java.net.URI.create("http://localhost:" + port + "/v1/%61dmin/merchants"));
      if (withKey) {
        get = get.header("Authorization", "Bearer " + key);
      }
      Map<String, Object> body =
          get.exchange()
              .expectStatus()
              .isBadRequest()
              .expectBody(Map.class)
              .returnResult()
              .getResponseBody();
      assertThat(body).containsEntry("type", "urn:gateway:INVALID_PATH");

      var post =
          http()
              .post()
              .uri(java.net.URI.create("http://localhost:" + port + "/v1/admin;x/merchants"))
              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
              .body(Map.of("name", "evil"));
      if (withKey) {
        post = post.header("Authorization", "Bearer " + key);
      }
      post.exchange().expectStatus().isBadRequest();
    }
  }

  @Test
  void merchantKeyAloneIsForbiddenOnAdmin() {
    String key = (String) merchantAndKey("Store Q", "TEST").get("key");
    http()
        .get()
        .uri("/v1/admin/merchants")
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectStatus()
        .isForbidden();
    http()
        .get()
        .uri("/v1/merchant")
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectStatus()
        .isOk();
  }

  @Test
  void createMerchantWithoutNameIs400() {
    Map<String, Object> body =
        adminPost("/v1/admin/merchants")
            .body(Map.of())
            .exchange()
            .expectStatus()
            .isBadRequest()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    assertThat(body).containsEntry("type", "urn:gateway:INVALID_REQUEST");
  }
}
