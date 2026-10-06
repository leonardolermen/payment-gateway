package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
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
 * A merchant disputes a payment and follows it to the operator's decision, through the real app:
 * the open goes through the idempotency filter, the decision through the admin queue (the dispute
 * id IS the divergence id), and every step reaches the merchant's endpoint as {@code
 * dispute.updated}, in order, on the payment's partition.
 *
 * <p>One test method, as in {@link PaymentsFlowIntegrationTest}: each step needs the state the
 * previous one left.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "webhook-delivery.retry-delay-ms=200",
      "gateway.payments.outbox-relay-ms=200",
      "gateway.payments.jobs-poll-ms=200",
      // The test profile's 5/min would trip halfway through this story; the limiter has its own
      // test.
      "gateway.rate-limit.requests-per-minute=1000"
    })
@ActiveProfiles("test")
@Testcontainers
class DisputesFlowIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer ITAU = new WireMockServer(options().dynamicPort());

  record Received(String type, String body, String signature) {}

  static final List<Received> received = new CopyOnWriteArrayList<>();
  static HttpServer sink;

  static {
    ITAU.start();
  }

  @DynamicPropertySource
  static void itau(DynamicPropertyRegistry r) {
    r.add("gateway.providers.itau.test-api-base", ITAU::baseUrl);
    r.add("gateway.providers.itau.test-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    r.add("gateway.providers.itau.test-mutual-tls", () -> "false");
  }

  @BeforeAll
  static void start() throws IOException {
    sink = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    sink.createContext(
        "/hook",
        ex -> {
          received.add(
              new Received(
                  ex.getRequestHeaders().getFirst("X-Gateway-Event-Type"),
                  new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                  ex.getRequestHeaders().getFirst("X-Gateway-Signature")));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    sink.start();

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
    sink.stop(0);
    ITAU.stop();
  }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private static String fixture(String name) {
    try (InputStream in =
        DisputesFlowIntegrationTest.class.getResourceAsStream("/itau/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> adminPost(String uri, Object body) {
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

  @SuppressWarnings("unchecked")
  private EntityExchangeResult<Map> postPayment(
      String apiKey, String idempotencyKey, Map<String, Object> body) {
    var spec =
        http()
            .post()
            .uri("/v1/payments")
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }
    return spec.body(body).exchange().expectBody(Map.class).returnResult();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getJson(String apiKey, String uri) {
    return http()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + apiKey)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("unchecked")
  private EntityExchangeResult<Map> postJson(
      String apiKey, String idempotencyKey, String uri, Object body) {
    var spec =
        http()
            .post()
            .uri(uri)
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON);
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }
    return spec.body(body).exchange().expectBody(Map.class).returnResult();
  }

  private int adminPostStatus(String uri, Object body) {
    return http()
        .post()
        .uri(uri)
        .header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .returnResult()
        .getStatus()
        .value();
  }

  @Test
  @SuppressWarnings("unchecked")
  void aMerchantDisputesAPaymentAndFollowsItToTheOperatorsDecision() {
    // 1. Merchant, TEST key, Itau credential, an endpoint listening to disputes.
    String merchantId =
        (String) adminPost("/v1/admin/merchants", Map.of("name", "Dispute Store")).get("id");
    String testKey =
        (String)
            adminPost(
                    "/v1/admin/merchants/" + merchantId + "/api-keys",
                    Map.of("environment", "TEST"))
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
    http()
        .post()
        .uri("/v1/webhooks/endpoints")
        .header("Authorization", "Bearer " + testKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            Map.of(
                "url",
                "http://localhost:" + sink.getAddress().getPort() + "/hook",
                "events",
                List.of("dispute.*")))
        .exchange()
        .expectStatus()
        .isCreated();

    // 2. A payment to dispute.
    EntityExchangeResult<Map> created =
        postJson(
            testKey,
            "pay-1",
            "/v1/payments",
            Map.of("amount", 15990, "currency", "BRL", "method", "PIX", "reference", "order-7"));
    assertThat(created.getStatus().value()).isEqualTo(201);
    String paymentId = (String) created.getResponseBody().get("id");
    String disputesUri = "/v1/payments/" + paymentId + "/disputes";

    // 3. The open needs an Idempotency-Key, and a reason the API knows.
    assertThat(
            postJson(testKey, null, disputesUri, Map.of("reason", "DUPLICATE")).getStatus().value())
        .isEqualTo(400);
    assertThat(
            postJson(testKey, "dsp-bad", disputesUri, Map.of("reason", "I_CHANGED_MY_MIND"))
                .getStatus()
                .value())
        .isEqualTo(400);

    // 4. Open.
    EntityExchangeResult<Map> opened =
        postJson(
            testKey, "dsp-1", disputesUri, Map.of("reason", "DUPLICATE", "note", "charged twice"));
    assertThat(opened.getStatus().value()).isEqualTo(201);
    Map<String, Object> dispute = opened.getResponseBody();
    String disputeId = (String) dispute.get("id");
    assertThat(dispute)
        .containsEntry("payment_id", paymentId)
        .containsEntry("reason", "DUPLICATE")
        .containsEntry("note", "charged twice")
        .containsEntry("status", "OPEN")
        .containsKeys("resolution", "resolution_note", "created_at", "resolved_at")
        .doesNotContainKeys("gateway_status", "detail", "resolved_by", "origin", "kind");

    // A second one while the first is open.
    EntityExchangeResult<Map> again =
        postJson(testKey, "dsp-2", disputesUri, Map.of("reason", "OTHER"));
    assertThat(again.getStatus().value()).isEqualTo(409);
    assertThat(again.getResponseBody()).containsEntry("type", "urn:gateway:DISPUTE_ALREADY_OPEN");

    // 5. The merchant lists and reads it.
    List<Map<String, Object>> listed =
        http()
            .get()
            .uri("/v1/disputes")
            .header("Authorization", "Bearer " + testKey)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(List.class)
            .returnResult()
            .getResponseBody();
    assertThat(listed).extracting(row -> row.get("id")).containsExactly(disputeId);
    assertThat(getJson(testKey, "/v1/disputes/" + disputeId)).isEqualTo(dispute);

    // 6. The operator reviews it, is refused a resolve without a resolution, and rejects it.
    String divergenceUri = "/v1/admin/divergences/" + disputeId;
    assertThat(adminPost(divergenceUri + "/review", Map.of()))
        .containsEntry("status", "UNDER_REVIEW");
    assertThat(adminPostStatus(divergenceUri + "/resolve", Map.of("note", "no resolution")))
        .isEqualTo(400);
    assertThat(
            adminPostStatus(divergenceUri + "/resolve", Map.of("resolution", "", "note", "blank")))
        .isEqualTo(400);
    assertThat(
            adminPost(
                divergenceUri + "/resolve",
                Map.of("resolution", "REJECTED", "note", "one charge only")))
        .containsEntry("status", "REJECTED");

    // 7. The merchant sees the decision and the operator's note.
    assertThat(getJson(testKey, "/v1/disputes/" + disputeId))
        .containsEntry("status", "REJECTED")
        .containsEntry("resolution", "REJECTED")
        .containsEntry("resolution_note", "one charge only")
        .containsKey("resolved_at");

    // 8. The endpoint heard every step, in order, all on the payment's partition.
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(
            () -> received.stream().filter(r -> "dispute.updated".equals(r.type())).count() >= 3);
    List<String> bodies =
        received.stream()
            .filter(r -> "dispute.updated".equals(r.type()))
            .map(Received::body)
            .toList();
    assertThat(bodies).hasSize(3).allSatisfy(body -> assertThat(body).contains(disputeId));
    assertThat(bodies.get(0)).contains("\"status\":\"OPEN\"");
    assertThat(bodies.get(1)).contains("\"status\":\"UNDER_REVIEW\"");
    assertThat(bodies.get(2)).contains("\"status\":\"REJECTED\"");
    assertThat(
            jdbc.queryForList(
                "SELECT DISTINCT partition_key FROM payments.outbox WHERE aggregate_id = ?",
                String.class,
                disputeId))
        .containsExactly(paymentId);
  }
}
