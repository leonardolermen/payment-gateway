package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
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
 * The signed-in user's own account (/v1/me) and the store's team: invites by e-mail, roles, removal
 * and the owner the store cannot lose.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "gateway.mail.host=127.0.0.1",
      "gateway.mail.port=3025",
      "gateway.mail.from=no-reply@test",
      "gateway.checkout.auth-rate-limit-per-minute=1000",
      "gateway.rate-limit.requests-per-minute=1000"
    })
@ActiveProfiles("test")
@Testcontainers
@SuppressWarnings({"rawtypes", "unchecked"})
class TeamApiIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @RegisterExtension
  static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP);

  private static final Pattern LINK = Pattern.compile("https?://\\S+");

  record Logged(String access, String userId) {}

  @LocalServerPort int port;

  @Test
  void theLastOwnerStays() {
    Logged ana = signupVerified("ana@dono.com");
    assertThat(
            patch(ana.access(), "/v1/merchant/users/" + ana.userId(), Map.of("role", "FINANCE"))
                .getStatus()
                .value())
        .isEqualTo(400); // own account

    Logged bia = invite(ana, "bia@dono.com", "FINANCE");
    assertThat(
            patch(bia.access(), "/v1/merchant/users/" + ana.userId(), Map.of("role", "FINANCE"))
                .getStatus()
                .value())
        .isEqualTo(403); // FINANCE cannot

    assertThat(delete(ana.access(), "/v1/merchant/users/" + bia.userId()).getStatus().value())
        .isEqualTo(204);
    assertThat(status(bia.access(), "GET", "/v1/me")).isEqualTo(401);

    Logged cris = invite(ana, "cris@dono.com", "OWNER");
    assertThat(
            patchBody(
                cris.access(), "/v1/merchant/users/" + ana.userId(), Map.of("role", "READONLY")))
        .contains("200");
    // cris is the last owner now, and the only person who could demote or remove her is herself,
    // which the team routes refuse: through the API, LAST_OWNER stays behind these two doors.
    assertThat(deleteBody(ana.access(), "/v1/merchant/users/" + cris.userId())).startsWith("403");
    assertThat(deleteBody(cris.access(), "/v1/merchant/users/" + cris.userId())).startsWith("400");
  }

  @Test
  void inviteGoesByEmailAndEntersWithTheRole() {
    Logged ana = signupVerified("ana@convite.com");

    assertThat(
            post(ana.access(), "/v1/invites", Map.of("email", "bia@convite.com", "role", "FINANCE"))
                .getStatus()
                .value())
        .isEqualTo(202);
    Map<String, Object> team = get(ana.access(), "/v1/merchant/users");
    assertThat((List<Map<String, Object>>) team.get("invites"))
        .singleElement()
        .satisfies(
            invite ->
                assertThat(invite)
                    .containsEntry("email", "bia@convite.com")
                    .containsEntry("role", "FINANCE")
                    .containsKey("expires_at"));

    String link = awaitLink("bia@convite.com", "/invite/");
    EntityExchangeResult<Map> accepted =
        post(
            "/v1/auth/invite/accept",
            Map.of("token", tokenOf(link), "name", "Bia", "password", "senha-forte-2"));
    assertThat(accepted.getStatus().value()).isEqualTo(201);

    Map<String, Object> me = get((String) accepted.getResponseBody().get("access_token"), "/v1/me");
    assertThat(((Map<String, Object>) me.get("user")))
        .containsEntry("role", "FINANCE")
        .containsEntry("email_verified", true);
    assertThat(
            post(
                    "/v1/auth/invite/accept",
                    Map.of("token", tokenOf(link), "name", "Bia", "password", "senha-forte-2"))
                .getStatus()
                .value())
        .isEqualTo(410);
    assertThat(
            post(
                    ana.access(),
                    "/v1/invites",
                    Map.of("email", "bia@convite.com", "role", "READONLY"))
                .getStatus()
                .value())
        .isEqualTo(409);

    Map<String, Object> after = get(ana.access(), "/v1/merchant/users");
    assertThat((List<?>) after.get("users")).hasSize(2);
    assertThat((List<?>) after.get("invites")).isEmpty();
  }

  @Test
  void aResentInviteReplacesTheFirstLink() {
    Logged ana = signupVerified("ana@reenvio.com");

    post(ana.access(), "/v1/invites", Map.of("email", "edu@reenvio.com", "role", "FINANCE"));
    String first = awaitLink("edu@reenvio.com", "/invite/");
    post(ana.access(), "/v1/invites", Map.of("email", "EDU@reenvio.com", "role", "READONLY"));

    assertThat((List<?>) get(ana.access(), "/v1/merchant/users").get("invites")).hasSize(1);
    assertThat(
            post(
                    "/v1/auth/invite/accept",
                    Map.of("token", tokenOf(first), "name", "Edu", "password", "senha-forte-2"))
                .getStatus()
                .value())
        .isEqualTo(410);
  }

  @Test
  void meSessionsAndPasswordChange() {
    Logged ana = signupVerified("ana@sessao.com");
    EntityExchangeResult<Map> phone =
        post("/v1/auth/login", Map.of("email", "ana@sessao.com", "password", "senha-forte-1"));

    List<Map<String, Object>> sessions = getList(ana.access(), "/v1/me/sessions");
    assertThat(sessions).hasSize(2);
    assertThat(sessions)
        .filteredOn(session -> Boolean.TRUE.equals(session.get("current")))
        .hasSize(1);

    assertThat(delete(ana.access(), "/v1/me/sessions/others").getStatus().value()).isEqualTo(204);
    assertThat(status((String) phone.getResponseBody().get("access_token"), "GET", "/v1/orders"))
        .isEqualTo(401);

    assertThat(
            post(
                    ana.access(),
                    "/v1/me/password",
                    Map.of("current", "errada-errada", "new", "nova-senha-11"))
                .getStatus()
                .value())
        .isEqualTo(401);
    assertThat(
            post(
                    ana.access(),
                    "/v1/me/password",
                    Map.of("current", "senha-forte-1", "new", "nova-senha-11"))
                .getStatus()
                .value())
        .isEqualTo(204);
    assertThat(
            post("/v1/auth/login", Map.of("email", "ana@sessao.com", "password", "nova-senha-11"))
                .getStatus()
                .value())
        .isEqualTo(200);

    assertThat(post(ana.access(), "/v1/me/email/resend", Map.of()).getStatus().value())
        .isEqualTo(409); // ALREADY_VERIFIED
  }

  @Test
  void meShowsTheAccountAndRenames() {
    Logged ana = signupVerified("ana@perfil.com");

    assertThat(patch(ana.access(), "/v1/me", Map.of("name", "Ana Maria")).getStatus().value())
        .isEqualTo(200);

    Map<String, Object> me = get(ana.access(), "/v1/me");
    assertThat((Map<String, Object>) me.get("user"))
        .containsEntry("id", ana.userId())
        .containsEntry("name", "Ana Maria")
        .containsEntry("email", "ana@perfil.com")
        .containsEntry("role", "OWNER");
    assertThat((Map<String, Object>) me.get("merchant")).containsEntry("name", "Loja");
    assertThat((Map<String, Object>) me.get("onboarding"))
        .containsEntry("email_verified", true)
        .containsEntry("live_enabled", true);
  }

  @Test
  void aResendRightAfterSignupIsTooSoon() {
    String access = signup("gil@cedo.com");

    EntityExchangeResult<Map> resend = post(access, "/v1/me/email/resend", Map.of());

    assertThat(resend.getStatus().value()).isEqualTo(429);
    assertThat(resend.getResponseBody()).containsEntry("type", "urn:gateway:RESEND_TOO_SOON");
  }

  private Logged signupVerified(String email) {
    String access = signup(email);

    String link = awaitLink(email, "/verify/");
    assertThat(post("/v1/auth/email/verify", Map.of("token", tokenOf(link))).getStatus().value())
        .isEqualTo(204);

    return new Logged(access, userIdOf(access));
  }

  private String signup(String email) {
    EntityExchangeResult<Map> signed =
        post(
            "/v1/auth/signup",
            Map.of(
                "store_name", "Loja", "name", "Ana", "email", email, "password", "senha-forte-1"));
    assertThat(signed.getStatus().value()).isEqualTo(201);

    return (String) signed.getResponseBody().get("access_token");
  }

  private Logged invite(Logged owner, String email, String role) {
    assertThat(
            post(owner.access(), "/v1/invites", Map.of("email", email, "role", role))
                .getStatus()
                .value())
        .isEqualTo(202);

    String link = awaitLink(email, "/invite/");
    EntityExchangeResult<Map> accepted =
        post(
            "/v1/auth/invite/accept",
            Map.of("token", tokenOf(link), "name", "Convidada", "password", "senha-forte-2"));
    assertThat(accepted.getStatus().value()).isEqualTo(201);

    String access = (String) accepted.getResponseBody().get("access_token");

    return new Logged(access, userIdOf(access));
  }

  private String userIdOf(String access) {
    return (String) ((Map<String, Object>) get(access, "/v1/me").get("user")).get("id");
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

  private EntityExchangeResult<Map> post(String bearer, String uri, Object body) {
    return send(HttpMethod.POST, bearer, uri, body).expectBody(Map.class).returnResult();
  }

  private EntityExchangeResult<Map> patch(String bearer, String uri, Object body) {
    return send(HttpMethod.PATCH, bearer, uri, body).expectBody(Map.class).returnResult();
  }

  private EntityExchangeResult<Map> delete(String bearer, String uri) {
    return send(HttpMethod.DELETE, bearer, uri, null).expectBody(Map.class).returnResult();
  }

  /** Status and body in one string, for asserting on either. */
  private String patchBody(String bearer, String uri, Object body) {
    return text(send(HttpMethod.PATCH, bearer, uri, body));
  }

  private String deleteBody(String bearer, String uri) {
    return text(send(HttpMethod.DELETE, bearer, uri, null));
  }

  private static String text(RestTestClient.ResponseSpec response) {
    EntityExchangeResult<String> result = response.expectBody(String.class).returnResult();

    return result.getStatus().value() + " " + result.getResponseBody();
  }

  private Map<String, Object> get(String bearer, String uri) {
    return send(HttpMethod.GET, bearer, uri, null)
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  private List<Map<String, Object>> getList(String bearer, String uri) {
    return send(HttpMethod.GET, bearer, uri, null)
        .expectBody(List.class)
        .returnResult()
        .getResponseBody();
  }

  private int status(String bearer, String method, String uri) {
    return send(HttpMethod.valueOf(method), bearer, uri, null)
        .expectBody(String.class)
        .returnResult()
        .getStatus()
        .value();
  }

  private RestTestClient.ResponseSpec send(
      HttpMethod method, String bearer, String uri, Object body) {
    RestTestClient.RequestBodySpec request =
        http().method(method).uri(uri).header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);

    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).body(body);
    }

    return request.exchange();
  }

  private static String awaitLink(String recipient, String marker) {
    return Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(() -> firstLink(MAIL, recipient, marker), Objects::nonNull);
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

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }
}
