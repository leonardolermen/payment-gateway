package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The panel's unauthenticated surface: signup, login, the rotating refresh cookie, logout, password
 * reset and e-mail verification, with the e-mails going out through GreenMail.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "gateway.mail.host=127.0.0.1",
      "gateway.mail.port=3025",
      "gateway.mail.from=no-reply@test",
      "gateway.checkout.auth-rate-limit-per-minute=1000"
    })
@ActiveProfiles("test")
@Testcontainers
@SuppressWarnings("rawtypes")
class AuthApiIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @RegisterExtension
  static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP);

  private static final Pattern LINK = Pattern.compile("https?://\\S+");

  @LocalServerPort int port;

  @Test
  void signupSendsVerificationAndLiveOpensAfterIt() {
    EntityExchangeResult<Map> signed =
        post(
            "/v1/auth/signup",
            Map.of(
                "store_name",
                "Loja",
                "name",
                "Ana",
                "email",
                "ana@loja.com",
                "password",
                "senha-forte-1"));
    assertThat(signed.getStatus().value()).isEqualTo(201);
    String access = accessOf(signed);
    String cookie = signed.getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE);
    assertThat(cookie)
        .startsWith("gw_refresh=gr_")
        .contains("HttpOnly")
        .contains("SameSite=None")
        .contains("Path=/v1/auth");

    assertThat(status(access, "GET", "/v1/orders", "LIVE")).isEqualTo(403);

    String link =
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> firstLink(MAIL, "ana@loja.com", "/verify/"), Objects::nonNull);
    assertThat(post("/v1/auth/email/verify", Map.of("token", tokenOf(link))).getStatus().value())
        .isEqualTo(204);
    assertThat(status(access, "GET", "/v1/orders", "LIVE")).isEqualTo(200);
    assertThat(post("/v1/auth/email/verify", Map.of("token", tokenOf(link))).getStatus().value())
        .isEqualTo(410);

    EntityExchangeResult<Map> again =
        post(
            "/v1/auth/signup",
            Map.of(
                "store_name",
                "Outra",
                "name",
                "Ana",
                "email",
                "ANA@loja.com",
                "password",
                "senha-forte-1"));
    assertThat(again.getStatus().value()).isEqualTo(409);
  }

  @Test
  void loginRefreshAndLogout() {
    signup("bia@loja.com");

    EntityExchangeResult<Map> wrong =
        post("/v1/auth/login", Map.of("email", "bia@loja.com", "password", "errada-errada"));
    EntityExchangeResult<Map> unknown =
        post("/v1/auth/login", Map.of("email", "x@loja.com", "password", "senha-forte-1"));
    assertThat(wrong.getStatus().value()).isEqualTo(401);
    assertThat(wrong.getResponseBody()).isEqualTo(unknown.getResponseBody());

    EntityExchangeResult<Map> loggedIn =
        post("/v1/auth/login", Map.of("email", "bia@loja.com", "password", "senha-forte-1"));
    String refreshCookie = cookieValue(loggedIn, "gw_refresh");

    EntityExchangeResult<Map> refreshed =
        postWithCookie("/v1/auth/refresh", "gw_refresh=" + refreshCookie);
    assertThat(refreshed.getStatus().value()).isEqualTo(200);
    assertThat(cookieValue(refreshed, "gw_refresh")).isNotEqualTo(refreshCookie);

    // replay of the first cookie kills the session
    assertThat(
            postWithCookie("/v1/auth/refresh", "gw_refresh=" + refreshCookie).getStatus().value())
        .isEqualTo(401);
    assertThat(status(accessOf(refreshed), "GET", "/v1/orders", null)).isEqualTo(401);

    EntityExchangeResult<Map> again =
        post("/v1/auth/login", Map.of("email", "bia@loja.com", "password", "senha-forte-1"));
    EntityExchangeResult<Map> loggedOut =
        postWithCookieAndBearer(
            "/v1/auth/logout", "gw_refresh=" + cookieValue(again, "gw_refresh"), accessOf(again));
    assertThat(loggedOut.getStatus().value()).isEqualTo(204);
    assertThat(loggedOut.getResponseHeaders().getFirst(HttpHeaders.SET_COOKIE))
        .startsWith("gw_refresh=;")
        .contains("Max-Age=0");
    assertThat(status(accessOf(again), "GET", "/v1/orders", null)).isEqualTo(401);
  }

  @Test
  void forgotAndResetAlwaysAnswer202AndResetRevokesSessions() {
    String access = signup("caio@loja.com");

    assertThat(
            post("/v1/auth/password/forgot", Map.of("email", "nobody@loja.com"))
                .getStatus()
                .value())
        .isEqualTo(202);
    assertThat(
            post("/v1/auth/password/forgot", Map.of("email", "caio@loja.com")).getStatus().value())
        .isEqualTo(202);
    String link =
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> firstLink(MAIL, "caio@loja.com", "/reset/"), Objects::nonNull);

    EntityExchangeResult<Map> reset =
        post(
            "/v1/auth/password/reset", Map.of("token", tokenOf(link), "password", "nova-senha-11"));
    assertThat(reset.getStatus().value()).isEqualTo(204);
    assertThat(status(access, "GET", "/v1/orders", null)).isEqualTo(401);

    EntityExchangeResult<Map> loggedIn =
        post("/v1/auth/login", Map.of("email", "caio@loja.com", "password", "nova-senha-11"));
    assertThat(loggedIn.getStatus().value()).isEqualTo(200);
  }

  private String signup(String email) {
    EntityExchangeResult<Map> signed =
        post(
            "/v1/auth/signup",
            Map.of(
                "store_name", "Loja", "name", "Ana", "email", email, "password", "senha-forte-1"));
    assertThat(signed.getStatus().value()).isEqualTo(201);

    return accessOf(signed);
  }

  private static String accessOf(EntityExchangeResult<Map> result) {
    return (String) result.getResponseBody().get("access_token");
  }

  private EntityExchangeResult<Map> post(String uri, Object body) {
    return http()
        .post()
        .uri(uri)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  private EntityExchangeResult<Map> postWithCookie(String uri, String cookieHeader) {
    return http()
        .post()
        .uri(uri)
        .header(HttpHeaders.COOKIE, cookieHeader)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  private EntityExchangeResult<Map> postWithCookieAndBearer(
      String uri, String cookieHeader, String bearer) {
    return http()
        .post()
        .uri(uri)
        .header(HttpHeaders.COOKIE, cookieHeader)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  private static String cookieValue(EntityExchangeResult<?> result, String name) {
    return result.getResponseHeaders().getOrEmpty(HttpHeaders.SET_COOKIE).stream()
        .filter(header -> header.startsWith(name + "="))
        .map(header -> header.substring(name.length() + 1, header.indexOf(';')))
        .findFirst()
        .orElseThrow();
  }

  /**
   * By recipient: SEND_EMAIL runs on the job scheduler, so another test's e-mail can land after
   * GreenMail was reset for this one.
   */
  private static String firstLink(GreenMailExtension mail, String recipient, String marker) {
    return Arrays.stream(mail.getReceivedMessagesForDomain(recipient))
        .map(GreenMailUtil::getBody)
        .map(LINK::matcher)
        .flatMap(Matcher::results)
        .map(MatchResult::group)
        .filter(link -> link.contains(marker))
        .findFirst()
        .orElse(null);
  }

  private static String tokenOf(String link) {
    return link.substring(link.lastIndexOf('/') + 1);
  }

  private int status(String bearer, String method, String path, String environment) {
    RestTestClient.RequestBodySpec request =
        http()
            .method(HttpMethod.valueOf(method))
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);

    if (environment != null) {
      request.header("X-Environment", environment);
    }

    return request.exchange().expectBody(String.class).returnResult().getStatus().value();
  }

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }
}
