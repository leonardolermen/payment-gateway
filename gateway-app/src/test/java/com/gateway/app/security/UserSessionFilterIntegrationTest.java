package com.gateway.app.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.session.SessionService;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import com.gateway.merchants.user.UserService;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * A panel session authenticates beside an API key: environment from X-Environment, LIVE only with a
 * verified e-mail, writes gated by role, and the account routes closed to keys.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "gateway.rate-limit.requests-per-minute=1000")
@ActiveProfiles("test")
@Testcontainers
class UserSessionFilterIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  @Autowired MerchantService merchants;
  @Autowired UserService users;
  @Autowired SessionService sessions;

  record Logged(String access, User user, Merchant store) {}

  private record Keys(String test, String live) {}

  @Test
  void aSessionListsOrdersInTestByDefaultAndLiveOnlyWhenVerified() {
    Logged ana = user(Role.OWNER, false);
    assertThat(status(ana.access(), "GET", "/v1/orders", null)).isEqualTo(200);
    assertThat(statusBody(ana.access(), "GET", "/v1/orders", "LIVE"))
        .contains("urn:gateway:EMAIL_NOT_VERIFIED");
    assertThat(status(ana.access(), "GET", "/v1/orders", "live")).isEqualTo(200);

    Logged verified = user(Role.OWNER, true);
    assertThat(status(verified.access(), "GET", "/v1/orders", "LIVE")).isEqualTo(200);
  }

  @Test
  void rolesGateWrites() {
    Logged reader = user(Role.READONLY, true);
    assertThat(status(reader.access(), "GET", "/v1/orders", null)).isEqualTo(200);
    assertThat(statusBody(reader.access(), "POST", "/v1/plans", null))
        .contains("FORBIDDEN_FOR_ROLE")
        .contains("\"required_role\":\"FINANCE\"");

    Logged finance = user(Role.FINANCE, true);
    assertThat(statusBody(finance.access(), "POST", "/v1/webhooks/endpoints", null))
        .contains("FORBIDDEN_FOR_ROLE")
        .contains("OWNER");
  }

  @Test
  void anExpiredOrGarbageSessionIs401() {
    assertThat(status("gs_garbage", "GET", "/v1/orders", null)).isEqualTo(401);
    assertThat(statusBody("gs_garbage", "GET", "/v1/orders", null))
        .contains("urn:gateway:SESSION_EXPIRED");
  }

  @Test
  void anApiKeyIsUntouchedByRoles() {
    Keys keys = newMerchant();

    assertThat(status(keys.test(), "POST", "/v1/webhooks/endpoints", null)).isNotEqualTo(403);
    assertThat(status(keys.test(), "GET", "/v1/me", null)).isEqualTo(403);
    assertThat(statusBody(keys.test(), "GET", "/v1/me", null))
        .contains("urn:gateway:USER_SESSION_REQUIRED");
    assertThat(status(keys.test(), "GET", "/v1/merchant", null)).isEqualTo(200);
  }

  @Test
  void aRemovedUsersSessionIs401NotA500() {
    Logged bruno = user(Role.FINANCE, true);
    users.remove(bruno.store().id(), bruno.user().id());

    assertThat(status(bruno.access(), "GET", "/v1/orders", null)).isEqualTo(401);
    assertThat(statusBody(bruno.access(), "GET", "/v1/orders", null))
        .contains("urn:gateway:SESSION_EXPIRED");
  }

  @Test
  void aSuspendedMerchantsSessionIs401() {
    Logged carla = user(Role.OWNER, true);
    merchants.suspend(carla.store().id());

    assertThat(status(carla.access(), "GET", "/v1/orders", null)).isEqualTo(401);
    assertThat(statusBody(carla.access(), "GET", "/v1/orders", null))
        .contains("urn:gateway:SESSION_EXPIRED");
  }

  @Test
  void authIsLimitedPerIpInItsOwnBucket() {
    // The route 404s until the auth controller exists; the filter counts it all the same.
    for (int i = 0; i < 10; i++) {
      assertThat(status("none", "POST", "/v1/auth/login", null)).isNotEqualTo(429);
    }

    assertThat(status("none", "POST", "/v1/auth/login", null)).isEqualTo(429);
    assertThat(status("none", "GET", "/v1/checkout/chk_unknown", null)).isNotEqualTo(429);
  }

  private Logged user(Role role, boolean verified) {
    Merchant store = merchants.create("Loja");
    // Globally unique: the e-mail is unique across merchants and the context outlives one test.
    EmailAddress email = new EmailAddress(role + "-" + UUID.randomUUID() + "@loja.com");
    User user = users.register(store.id(), "Ana", email, role, "senha-forte-1");

    if (verified) {
      user = users.markEmailVerified(user.id());
    }

    String access = sessions.open(user.id(), null, null).accessToken().reveal();
    return new Logged(access, user, store);
  }

  private EntityExchangeResult<String> exchange(
      String bearer, String method, String path, String environment) {
    RestTestClient.RequestBodySpec request =
        http()
            .method(HttpMethod.valueOf(method))
            .uri(path)
            .header("Authorization", "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON);

    if (environment != null) {
      request.header("X-Environment", environment);
    }

    return request.exchange().expectBody(String.class).returnResult();
  }

  private int status(String bearer, String method, String path, String environment) {
    return exchange(bearer, method, path, environment).getStatus().value();
  }

  private String statusBody(String bearer, String method, String path, String environment) {
    return exchange(bearer, method, path, environment).getResponseBody();
  }

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> admin(String uri, Object body) {
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

  private Keys newMerchant() {
    String merchantId =
        (String) admin("/v1/admin/merchants", Map.of("name", "Loja de Dev")).get("id");
    String keys = "/v1/admin/merchants/" + merchantId + "/api-keys";

    return new Keys(
        (String) admin(keys, Map.of("environment", "TEST")).get("key"),
        (String) admin(keys, Map.of("environment", "LIVE")).get("key"));
  }
}
