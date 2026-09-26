package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.payments.inbox.WebhookInboxService;
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
 * The whole Pix path through the real app: REST with idempotency keys, the Itau provider over HTTP
 * (WireMock plays the bank, sandbox-shaped credentials, no mTLS), the outbox relay, the job runner
 * and the signed merchant webhook. The fixtures under {@code itau/fixtures} are copies of the
 * provider module's (Task 4): gateway-providers publishes no test-jar, and a copy is simpler than
 * adding one for four files.
 *
 * <p>One test method on purpose: every step depends on the state the previous one left, and the
 * brief's scenario is a single story (charge, replay, settle, refund).
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
class PaymentsFlowIntegrationTest {

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
    ITAU.stubFor(
        put(urlMatching("/pix/[A-Za-z0-9]+/devolucao/[A-Za-z0-9]+"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(fixture("put_devolucao_201_processing.json"))));
  }

  @AfterAll
  static void stop() {
    sink.stop(0);
    ITAU.stop();
  }

  @LocalServerPort int port;
  @Autowired WebhookInboxService webhookInbox;
  @Autowired JdbcTemplate jdbc;

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private static String fixture(String name) {
    try (InputStream in =
        PaymentsFlowIntegrationTest.class.getResourceAsStream("/itau/fixtures/" + name)) {
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

  @Test
  @SuppressWarnings("unchecked")
  void chargeReplaySettleAndRefundAgainstItau() {
    // 1. Merchant, TEST key, sandbox-shaped Itau credential, webhook endpoint.
    String merchantId =
        (String) adminPost("/v1/admin/merchants", Map.of("name", "Pix Store")).get("id");
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
                List.of("payment.*")))
        .exchange()
        .expectStatus()
        .isCreated();

    // 2. Create the charge.
    Map<String, Object> request =
        Map.of(
            "amount",
            15990,
            "currency",
            "BRL",
            "method",
            "PIX",
            "reference",
            "order-42",
            "description",
            "Pedido 42");
    EntityExchangeResult<Map> created = postPayment(testKey, "k1", request);
    assertThat(created.getStatus().value()).isEqualTo(201);
    Map<String, Object> payment = created.getResponseBody();
    String paymentId = (String) payment.get("id");
    assertThat(payment)
        .containsEntry("status", "PENDING")
        .containsEntry("amount", 15990)
        .containsEntry("environment", "TEST")
        .containsEntry("reference", "order-42")
        .containsKey("expires_at")
        .containsKey("created_at");
    Map<String, Object> pix = (Map<String, Object>) payment.get("pix");
    assertThat(pix.get("copia_e_cola"))
        .isEqualTo(
            new String(fixture("put_cob_201.json"))
                .replaceAll("(?s).*\"pixCopiaECola\": \"([^\"]+)\".*", "$1"));
    assertThat(created.getResponseHeaders().getFirst("X-Resource-Id"))
        .as("internal header must not leak")
        .isNull();

    ITAU.verify(
        1,
        postRequestedFor(urlEqualTo("/api/oauth/jwt"))
            .withRequestBody(containing("grant_type=client_credentials"))
            .withRequestBody(containing("client_id=sandbox-client")));
    ITAU.verify(
        1,
        putRequestedFor(urlEqualTo("/cob/" + paymentId))
            .withHeader("Authorization", matching("Bearer tok-123"))
            .withRequestBody(containing("\"valor\":{\"original\":\"159.90\"}")));

    // 3. Replay: same response, no second PUT.
    EntityExchangeResult<Map> replay = postPayment(testKey, "k1", request);
    assertThat(replay.getStatus().value()).isEqualTo(201);
    assertThat(replay.getResponseHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
    assertThat(replay.getResponseBody()).isEqualTo(payment);
    ITAU.verify(1, putRequestedFor(urlMatching("/cob/.*")));

    // 4. Same key, different body.
    EntityExchangeResult<Map> reused =
        postPayment(testKey, "k1", Map.of("amount", 100, "currency", "BRL", "method", "PIX"));
    assertThat(reused.getStatus().value()).isEqualTo(422);
    assertThat(reused.getResponseBody())
        .containsEntry("type", "urn:gateway:IDEMPOTENCY_KEY_REUSED");

    // 5. The relay delivered payment.pending, signed.
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> received.stream().anyMatch(r -> "payment.pending".equals(r.type())));
    Received pending =
        received.stream().filter(r -> "payment.pending".equals(r.type())).findFirst().orElseThrow();
    assertThat(pending.signature()).isNotBlank();
    assertThat(pending.body())
        .contains(paymentId)
        .contains("\"status\":\"PENDING\"")
        .contains("\"method\":\"PIX\"");

    // 6. The bank says it was paid (HTTP + mTLS intake is Task 9; here the inbox is called
    // directly).
    String webhook =
        fixture("webhook_pix.json")
            .replace("7978c0c97ea847e78e8849634473c1f1", paymentId)
            .replace("\"110.00\"", "\"159.90\"")
            // The fixture's 2020 payment time would put the refund below outside the bank's 90-day
            // window.
            .replace(
                "\"2020-01-01T00:00:00Z\"",
                "\""
                    + java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                    + "\"")
            .replaceAll("(?s),\\s*\"devolucoes\": \\[.*?\\]\\s*(?=})", "");
    assertThat(webhook).doesNotContain("devolucoes");
    // The webhook is only a hint: processing asks GET /cob/{txid}, and the bank must say CONCLUIDA
    // with the same endToEndId and amount before anything reaches the merchant.
    ITAU.stubFor(
        get(urlEqualTo("/cob/" + paymentId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        fixture("get_cob_200_completed.json")
                            .replace("7978c0c97ea847e78e8849634473c1f1", paymentId)
                            .replace("\"567.89\"", "\"159.90\"")
                            // The bank's horario is what paid_at becomes; 2020 would close the
                            // 90-day refund window below.
                            .replace(
                                "\"2020-01-01T00:00:00Z\"",
                                "\""
                                    + java.time.Instant.now()
                                        .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
                                    + "\""))));
    webhookInbox.accept(
        "ITAU", new MerchantId(merchantId), "{}", webhook.getBytes(StandardCharsets.UTF_8));

    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(
            () -> "COMPLETED".equals(getJson(testKey, "/v1/payments/" + paymentId).get("status")));
    Map<String, Object> completed = getJson(testKey, "/v1/payments/" + paymentId);
    assertThat(((Map<String, Object>) completed.get("pix")).get("end_to_end_id"))
        .isEqualTo("E12345678202009091221kkkkkkkkkkk");
    assertThat(completed).containsEntry("paid_amount", 15990);
    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> received.stream().anyMatch(r -> "payment.completed".equals(r.type())));
    List<String> order =
        received.stream().map(Received::type).filter(t -> t.startsWith("payment.")).toList();
    assertThat(order.indexOf("payment.pending")).isLessThan(order.indexOf("payment.completed"));
    assertThat(
            received.stream()
                .filter(r -> "payment.completed".equals(r.type()))
                .findFirst()
                .orElseThrow()
                .body())
        .contains("\"status\":\"COMPLETED\"");

    List<Map<String, Object>> events =
        http()
            .get()
            .uri("/v1/payments/" + paymentId + "/events")
            .header("Authorization", "Bearer " + testKey)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(List.class)
            .returnResult()
            .getResponseBody();
    assertThat(events)
        .extracting(e -> e.get("type"))
        .containsExactly("created", "pending", "completed");

    // 7. Partial refund: PROCESSING at the bank, then settled by polling.
    Map<String, Object> refund =
        http()
            .post()
            .uri("/v1/payments/" + paymentId + "/refunds")
            .header("Authorization", "Bearer " + testKey)
            .header("Idempotency-Key", "r1")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("amount", 5000))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();
    String refundId = (String) refund.get("id");
    assertThat(refund)
        .containsEntry("state", "PROCESSING")
        .containsEntry("amount", 5000)
        .containsEntry("payment_id", paymentId);
    ITAU.verify(
        1,
        putRequestedFor(urlEqualTo("/pix/E12345678202009091221kkkkkkkkkkk/devolucao/" + refundId)));

    ITAU.stubFor(
        get(urlEqualTo("/pix/E12345678202009091221kkkkkkkkkkk/devolucao/" + refundId))
            .willReturn(
                aResponse()
                    .withStatus(200)
                    .withHeader("Content-Type", "application/json")
                    .withBody(
                        fixture("get_devolucao_200_done.json")
                            .replace("\"123456\"", "\"" + refundId + "\"")
                            .replace("\"7.89\"", "\"50.00\""))));
    // The poll is due in 5 minutes; bring it forward instead of waiting.
    jdbc.update(
        "UPDATE payments.jobs SET next_run_at = now() WHERE type = 'POLL_REFUND' AND ref_id = ?",
        refundId);

    Awaitility.await()
        .atMost(Duration.ofSeconds(15))
        .until(() -> "COMPLETED".equals(getJson(testKey, "/v1/refunds/" + refundId).get("state")));
    assertThat(getJson(testKey, "/v1/payments/" + paymentId))
        .containsEntry("refunded_amount", 5000);
    List<?> refunds =
        http()
            .get()
            .uri("/v1/payments/" + paymentId + "/refunds")
            .header("Authorization", "Bearer " + testKey)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(List.class)
            .returnResult()
            .getResponseBody();
    assertThat(refunds).hasSize(1);

    // 7b. Lookup by the merchant's own reference: what the 409 IN_PROGRESS text points to.
    List<Map<String, Object>> byReference =
        http()
            .get()
            .uri("/v1/payments?reference=order-42")
            .header("Authorization", "Bearer " + testKey)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(List.class)
            .returnResult()
            .getResponseBody();
    assertThat(byReference).extracting(m -> m.get("id")).containsExactly(paymentId);
    List<?> none =
        http()
            .get()
            .uri("/v1/payments?reference=order-nope")
            .header("Authorization", "Bearer " + testKey)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(List.class)
            .returnResult()
            .getResponseBody();
    assertThat(none).isEmpty();

    // 8. No Idempotency-Key.
    EntityExchangeResult<Map> noKey = postPayment(testKey, null, request);
    assertThat(noKey.getStatus().value()).isEqualTo(400);
    assertThat(noKey.getResponseBody())
        .containsEntry("type", "urn:gateway:IDEMPOTENCY_KEY_REQUIRED");

    // 9. LIVE key, no LIVE credential: refused before any bank call.
    String liveKey =
        (String)
            adminPost(
                    "/v1/admin/merchants/" + merchantId + "/api-keys",
                    Map.of("environment", "LIVE"))
                .get("key");
    int putsBefore = ITAU.findAll(putRequestedFor(urlMatching("/cob/.*"))).size();
    int tokensBefore = ITAU.findAll(postRequestedFor(urlEqualTo("/api/oauth/jwt"))).size();
    EntityExchangeResult<Map> live = postPayment(liveKey, "live-1", request);
    assertThat(live.getStatus().value()).isEqualTo(422);
    assertThat(live.getResponseBody())
        .containsEntry("type", "urn:gateway:PROVIDER_CREDENTIALS_MISSING");
    assertThat(ITAU.findAll(putRequestedFor(urlMatching("/cob/.*")))).hasSize(putsBefore);
    assertThat(ITAU.findAll(postRequestedFor(urlEqualTo("/api/oauth/jwt")))).hasSize(tokensBefore);

    // 10. Keys are scoped by environment: the LIVE key reusing the TEST request's key and body is a
    // fresh LIVE request (no LIVE credential -> 422), never a replay of the TEST payment.
    EntityExchangeResult<Map> liveSameKey = postPayment(liveKey, "k1", request);
    assertThat(liveSameKey.getStatus().value()).isEqualTo(422);
    assertThat(liveSameKey.getResponseHeaders().getFirst("Idempotent-Replayed")).isNull();
    assertThat(liveSameKey.getResponseBody())
        .containsEntry("type", "urn:gateway:PROVIDER_CREDENTIALS_MISSING");
    assertThat(ITAU.findAll(putRequestedFor(urlMatching("/cob/.*")))).hasSize(putsBefore);
  }
}
