package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.payments.dispute.DisputeReason;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.Divergences;
import com.gateway.payments.reconciliation.ReconciliationDivergence;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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
 * The operator's routes under {@code /v1/admin}: the divergence queue. Payments are created through
 * the real API against a WireMock Itau, so every divergence points at a payment the app owns.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "gateway.rate-limit.requests-per-minute=1000")
@ActiveProfiles("test")
@Testcontainers
class AdminOperationsIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer ITAU = new WireMockServer(options().dynamicPort());

  static {
    ITAU.start();
  }

  @DynamicPropertySource
  static void itau(DynamicPropertyRegistry registry) {
    registry.add("gateway.providers.itau.test-api-base", ITAU::baseUrl);
    registry.add("gateway.providers.itau.test-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    registry.add("gateway.providers.itau.test-mutual-tls", () -> "false");
  }

  @BeforeAll
  static void stubTheBank() {
    ITAU.stubFor(
        post(urlEqualTo("/api/oauth/jwt"))
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
                    .withBody(fixture("put_cob_201.json"))));
  }

  @AfterAll
  static void stop() {
    ITAU.stop();
  }

  @LocalServerPort int port;
  @Autowired Divergences divergences;
  @Autowired PaymentRepository payments;
  @Autowired JdbcTemplate jdbc;

  @Test
  @SuppressWarnings("unchecked")
  void theOperatorListsReviewsAndResolvesASystemDivergence() {
    Payment payment = newPixPayment();
    divergences.open(payment, "PAID", "bank says paid");

    EntityExchangeResult<List> listed =
        http()
            .get()
            .uri("/v1/admin/divergences?status=OPEN&merchant_id=" + payment.merchantId().value())
            .header("X-Admin-Key", "test-admin")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(List.class)
            .returnResult();
    List<Map<String, Object>> rows = listed.getResponseBody();
    assertThat(rows).hasSize(1);
    Map<String, Object> row = rows.getFirst();
    assertThat(row)
        .containsEntry("payment_id", payment.id())
        .containsEntry("origin", "SYSTEM")
        .containsEntry("kind", "PAID")
        .containsEntry("status", "OPEN");
    assertThat((Map<String, Object>) row.get("payment"))
        .containsEntry("merchant_id", payment.merchantId().value())
        .containsEntry("method", "PIX");
    assertThat(listed.getResponseHeaders().getFirst("X-Next-Cursor")).isNull();
    String id = (String) row.get("id");

    Map<String, Object> reviewed =
        adminPost("/v1/admin/divergences/" + id + "/review", Map.of(), 200);
    assertThat(reviewed).containsEntry("status", "UNDER_REVIEW");

    Map<String, Object> notAllowed =
        adminPost(
            "/v1/admin/divergences/" + id + "/resolve",
            Map.of("resolution", "RESOLVED", "note", "wrong origin"),
            422);
    assertThat(notAllowed.get("type")).isEqualTo("urn:gateway:RESOLUTION_NOT_ALLOWED");

    Map<String, Object> resolved =
        adminPost(
            "/v1/admin/divergences/" + id + "/resolve",
            Map.of("resolution", "FALSE_POSITIVE", "note", "bank echo"),
            200);
    assertThat(resolved)
        .containsEntry("status", "RESOLVED")
        .containsEntry("resolution", "FALSE_POSITIVE")
        .containsEntry("resolution_note", "bank echo")
        .containsEntry("resolved_by", "admin");

    Map<String, Object> closed =
        adminPost(
            "/v1/admin/divergences/" + id + "/resolve",
            Map.of("resolution", "CONFIRMED", "note", "again"),
            409);
    assertThat(closed.get("type")).isEqualTo("urn:gateway:DIVERGENCE_CLOSED");
    assertThat(outboxTypes(id)).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void resolvingADisputeTellsTheMerchantOnce() {
    Payment payment = newPixPayment();
    ReconciliationDivergence dispute =
        divergences.openDispute(payment, DisputeReason.DUPLICATE, "charged twice");

    Map<String, Object> detail =
        http()
            .get()
            .uri("/v1/admin/divergences/" + dispute.id())
            .header("X-Admin-Key", "test-admin")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    assertThat(detail)
        .containsEntry("origin", "MERCHANT")
        .containsEntry("reason", "DUPLICATE")
        .containsEntry("merchant_note", "charged twice");
    assertThat((Map<String, Object>) detail.get("payment")).containsEntry("id", payment.id());

    adminPost(
        "/v1/admin/divergences/" + dispute.id() + "/resolve",
        Map.of("resolution", "REJECTED", "note", "one charge only"),
        200);

    assertThat(outboxTypes(dispute.id())).containsExactly("dispute.updated");
    assertThat(
            jdbc.queryForObject(
                "SELECT payload FROM payments.outbox WHERE aggregate_id = ?",
                String.class,
                dispute.id()))
        .contains("\"status\":\"REJECTED\"");
  }

  @Test
  void aFullPageCarriesTheCursorToTheNextOne() {
    Payment payment = newPixPayment();
    divergences.open(payment, "PAID", "one");
    divergences.open(payment, "UNCONFIRMED_WEBHOOK", "two");
    String uri = "/v1/admin/divergences?merchant_id=" + payment.merchantId().value();

    EntityExchangeResult<List> first =
        http()
            .get()
            .uri(uri + "&limit=1")
            .header("X-Admin-Key", "test-admin")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(List.class)
            .returnResult();
    String cursor = first.getResponseHeaders().getFirst("X-Next-Cursor");
    assertThat(cursor).isNotNull();

    List<?> second =
        http()
            .get()
            .uri(uri + "&limit=1&after=" + cursor)
            .header("X-Admin-Key", "test-admin")
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(List.class)
            .returnResult()
            .getResponseBody();
    assertThat(second).hasSize(1).isNotEqualTo(first.getResponseBody());
  }

  @Test
  void anUnknownIdIsA404AndNoKeyIsA403() {
    http()
        .get()
        .uri("/v1/admin/divergences/01UNKNOWN")
        .header("X-Admin-Key", "test-admin")
        .exchange()
        .expectStatus()
        .isNotFound();

    http().get().uri("/v1/admin/divergences").exchange().expectStatus().isForbidden();
  }

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> adminPost(String uri, Object body, int status) {
    return http()
        .post()
        .uri(uri)
        .header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectStatus()
        .isEqualTo(status)
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  private List<String> outboxTypes(String aggregateId) {
    return jdbc.queryForList(
        "SELECT event_type FROM payments.outbox WHERE aggregate_id = ?", String.class, aggregateId);
  }

  @SuppressWarnings("unchecked")
  private Payment newPixPayment() {
    String merchantId =
        (String) adminPost("/v1/admin/merchants", Map.of("name", "Divergent Store"), 201).get("id");
    String testKey =
        (String)
            adminPost(
                    "/v1/admin/merchants/" + merchantId + "/api-keys",
                    Map.of("environment", "TEST"),
                    201)
                .get("key");
    http()
        .put()
        .uri("/v1/admin/merchants/" + merchantId + "/providers/ITAU/credentials")
        .header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON)
        .body(
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
                    "a1f4102e-a446-4a57-bcce-6fa48899c1d1")))
        .exchange()
        .expectStatus()
        .is2xxSuccessful();

    Map<String, Object> created =
        http()
            .post()
            .uri("/v1/payments")
            .header("Authorization", "Bearer " + testKey)
            .header("Idempotency-Key", "divergence-" + merchantId)
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("amount", 1000, "currency", "BRL", "method", "PIX"))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();

    return payments.findById((String) created.get("id")).orElseThrow();
  }

  private static String fixture(String name) {
    try (InputStream in =
        AdminOperationsIntegrationTest.class.getResourceAsStream("/itau/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
