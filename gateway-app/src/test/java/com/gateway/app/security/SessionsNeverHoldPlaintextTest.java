package com.gateway.app.security;

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
import java.util.stream.Collectors;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
 * Passwords, access tokens and one-time links are stored only as hashes; the e-mail that carries a
 * link waits in {@code outbound_emails} until SEND_EMAIL delivers it, and the row goes with it.
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
class SessionsNeverHoldPlaintextTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @RegisterExtension
  static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP);

  private static final Pattern LINK = Pattern.compile("https?://\\S+");
  private static final String EMAIL = "sweep@loja.com";
  private static final String PASSWORD = "senha-unica-xyz-sweep";

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  @Test
  void noTableHoldsAPasswordASessionTokenOrAOneTimeToken() {
    EntityExchangeResult<Map> signed =
        post(
            "/v1/auth/signup",
            Map.of("store_name", "Loja", "name", "Ana", "email", EMAIL, "password", PASSWORD));
    assertThat(signed.getStatus().value()).isEqualTo(201);
    String access = (String) signed.getResponseBody().get("access_token");
    String refresh = refreshOf(signed);

    post("/v1/auth/password/forgot", Map.of("email", EMAIL));
    String link =
        Awaitility.await()
            .atMost(Duration.ofSeconds(10))
            .until(() -> firstLink(EMAIL, "/reset/"), Objects::nonNull);

    for (String table :
        List.of(
            "merchants.users", "merchants.sessions", "merchants.user_tokens", "payments.jobs")) {
      String dump =
          jdbc.queryForList("SELECT t::text AS row_text FROM " + table + " t").stream()
              .map(row -> row.get("row_text").toString())
              .collect(Collectors.joining("\n"));

      if (!table.equals("payments.jobs")) {
        assertThat(dump).as(table + " has rows").isNotBlank();
      }

      assertThat(dump)
          .as(table)
          .doesNotContain(PASSWORD)
          .doesNotContain(access)
          .doesNotContain(refresh)
          .doesNotContain(tokenOf(link));
    }

    // outbound_emails holds the link until SEND_EMAIL delivers it; after delivery the row is gone.
    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM merchants.outbound_emails", Integer.class))
                    .isZero());
  }

  private static String refreshOf(EntityExchangeResult<?> result) {
    return result.getResponseHeaders().getOrEmpty("Set-Cookie").stream()
        .filter(header -> header.startsWith("gw_refresh="))
        .map(header -> header.substring("gw_refresh=".length(), header.indexOf(';')))
        .findFirst()
        .orElseThrow();
  }

  private EntityExchangeResult<Map> post(String uri, Object body) {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build()
        .post()
        .uri(uri)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }

  private static String firstLink(String recipient, String marker) {
    return Arrays.stream(MAIL.getReceivedMessagesForDomain(recipient))
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
}
