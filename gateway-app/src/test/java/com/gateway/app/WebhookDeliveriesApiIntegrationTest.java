package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.webhookdelivery.client.HmacSigner;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code /v1/webhooks/deliveries} through the real app. Deliveries are seeded the way production
 * makes them — Pix payments whose {@code payment.pending} goes to a real HTTP sink — and the sink
 * answers 500 while {@code failuresLeft} is positive, so with two attempts a delivery dies in well
 * under a second instead of the production schedule's hours.
 *
 * <p>One test method, like {@link PaymentsFlowIntegrationTest}: each step reads the state the
 * previous one left.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "webhook-delivery.retry-delay-ms=200",
      "webhook-delivery.max-attempts=2",
      "webhook-delivery.base-backoff=PT0.1S",
      "gateway.payments.outbox-relay-ms=200",
      "gateway.payments.jobs-poll-ms=200",
      "gateway.rate-limit.requests-per-minute=1000"
    })
@ActiveProfiles("test")
@Testcontainers
class WebhookDeliveriesApiIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer ITAU = new WireMockServer(options().dynamicPort());

  record Received(String body, String signature, int status) {}

  static final List<Received> received = new CopyOnWriteArrayList<>();
  static final AtomicInteger failuresLeft = new AtomicInteger();
  static HttpServer sink;

  static {
    ITAU.start();
  }

  private final JsonMapper json = JsonMapper.builder().build();

  @DynamicPropertySource
  static void itau(DynamicPropertyRegistry registry) {
    registry.add("gateway.providers.itau.test-api-base", ITAU::baseUrl);
    registry.add("gateway.providers.itau.test-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    registry.add("gateway.providers.itau.test-mutual-tls", () -> "false");
  }

  @BeforeAll
  static void start() throws IOException {
    sink = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    sink.createContext(
        "/hook",
        exchange -> {
          int status = failuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0 ? 500 : 200;
          received.add(
              new Received(
                  new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
                  exchange.getRequestHeaders().getFirst("X-Gateway-Signature"),
                  status));
          exchange.sendResponseHeaders(status, -1);
          exchange.close();
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

  @Test
  @SuppressWarnings("unchecked")
  void listInspectAndRedeliver() {
    String merchantId = createMerchant("Hook Store");
    String apiKey = createTestKey(merchantId);
    saveItauCredential(merchantId);
    String secret = registerEndpoint(apiKey);

    // 1. Seed: one delivery that dies (two 500s, two attempts), then two that land.
    failuresLeft.set(2);
    createPayment(apiKey, "seed-dead-1");
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> list(apiKey, "?status=DEAD").getResponseBody().size() == 1);

    createPayment(apiKey, "seed-ok-1");
    createPayment(apiKey, "seed-ok-2");
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> list(apiKey, "?status=DELIVERED").getResponseBody().size() == 2);

    // 2. The list: newest first, no payload; the status filter; bad params are a 400.
    List<Map<String, Object>> all = list(apiKey, "").getResponseBody();
    assertThat(all).hasSize(3);
    assertThat(all)
        .extracting(delivery -> Instant.parse((String) delivery.get("created_at")))
        .isSortedAccordingTo(Comparator.reverseOrder());
    assertThat(all).allSatisfy(delivery -> assertThat(delivery).doesNotContainKey("payload"));
    assertThat(all.getFirst()).containsKey("last_error").containsKey("redelivered_at");

    List<Map<String, Object>> dead = list(apiKey, "?status=DEAD").getResponseBody();
    assertThat(dead).hasSize(1);
    assertThat(dead.getFirst()).containsEntry("attempts", 2);
    String deadId = (String) dead.getFirst().get("id");

    assertThat(listProblem(apiKey, "?status=NOPE"))
        .containsEntry("status", 400)
        .containsEntry("type", "urn:gateway:INVALID_REQUEST");
    assertThat(listProblem(apiKey, "?after=garbage")).containsEntry("status", 400);
    assertThat(listProblem(apiKey, "?limit=101")).containsEntry("status", 400);

    // 3. Keyset paging at the API: limit=1 walks the same three, none repeated.
    List<Object> paged = new ArrayList<>();
    String cursor = null;
    do {
      String after = cursor == null ? "" : "&after=" + cursor;
      EntityExchangeResult<List> page = list(apiKey, "?limit=1" + after);
      for (Object item : page.getResponseBody()) {
        paged.add(((Map<?, ?>) item).get("id"));
      }
      cursor = page.getResponseHeaders().getFirst("X-Next-Cursor");
    } while (cursor != null && paged.size() <= all.size());
    assertThat(paged).containsExactlyElementsOf(all.stream().map(item -> item.get("id")).toList());

    // 4. One delivery in full: the payload is the body the endpoint received.
    List<Map<String, Object>> deliveredOnes = list(apiKey, "?status=DELIVERED").getResponseBody();
    String deliveredId = (String) deliveredOnes.getFirst().get("id");
    Map<String, Object> delivered = get(apiKey, "/v1/webhooks/deliveries/" + deliveredId, 200);
    assertThat(delivered).containsEntry("status", "DELIVERED");
    assertThat(received.stream().map(request -> parse(request.body())).toList())
        .contains((Map<String, Object>) delivered.get("payload"));

    // Another merchant's key sees nothing: 404, exactly like an id that does not exist.
    String otherKey = createTestKey(createMerchant("Other Store"));
    get(otherKey, "/v1/webhooks/deliveries/" + deliveredId, 404);

    // 5. Redeliver the dead one: idempotency key required, then the sink gets it again, signed.
    assertThat(redeliver(apiKey, deadId, null).getStatus().value()).isEqualTo(400);
    EntityExchangeResult<Map> scheduled = redeliver(apiKey, deadId, "redeliver-1");
    assertThat(scheduled.getStatus().value()).isEqualTo(202);
    assertThat(scheduled.getResponseBody()).containsEntry("status", "PENDING");

    Map<String, Object> deadPayload =
        (Map<String, Object>) get(apiKey, "/v1/webhooks/deliveries/" + deadId, 200).get("payload");
    Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> landed(deadPayload) != null);
    Received again = landed(deadPayload);
    String signature = again.signature();
    long timestamp = Long.parseLong(signature.substring(2, signature.indexOf(',')));
    assertThat(signature)
        .isEqualTo(new HmacSigner().sign(again.body(), secret, Instant.ofEpochSecond(timestamp)));

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .until(
            () ->
                "DELIVERED"
                    .equals(get(apiKey, "/v1/webhooks/deliveries/" + deadId, 200).get("status")));
    Map<String, Object> redelivered = get(apiKey, "/v1/webhooks/deliveries/" + deadId, 200);
    assertThat(redelivered.get("redelivered_at")).isNotNull();
    assertThat(redelivered.get("last_error_before_redelivery")).isNotNull();

    EntityExchangeResult<Map> conflict = redeliver(apiKey, deliveredId, "redeliver-2");
    assertThat(conflict.getStatus().value()).isEqualTo(409);
    assertThat(conflict.getResponseBody())
        .containsEntry("type", "urn:gateway:DELIVERY_NOT_REDELIVERABLE");

    // 6. Bulk: a window over 30 days is refused; a valid one schedules the dead ones.
    EntityExchangeResult<Map> tooWide =
        redeliverDead(apiKey, Instant.now().minus(Duration.ofDays(31)), "bulk-1");
    assertThat(tooWide.getStatus().value()).isEqualTo(422);
    assertThat(tooWide.getResponseBody()).containsEntry("type", "urn:gateway:WINDOW_TOO_WIDE");

    failuresLeft.set(2);
    createPayment(apiKey, "seed-dead-2");
    Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .until(() -> list(apiKey, "?status=DEAD").getResponseBody().size() == 1);

    EntityExchangeResult<Map> bulk =
        redeliverDead(apiKey, Instant.now().minus(Duration.ofDays(1)), "bulk-2");
    assertThat(bulk.getStatus().value()).isEqualTo(202);
    assertThat(bulk.getResponseBody()).containsEntry("scheduled", 1);
  }

  /** The first request for this payload the sink answered 200 — the failed attempts precede it. */
  private Received landed(Map<String, Object> payload) {
    return received.stream()
        .filter(request -> request.status() == 200 && parse(request.body()).equals(payload))
        .findFirst()
        .orElse(null);
  }

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private static String fixture(String name) {
    try (InputStream in =
        WebhookDeliveriesApiIntegrationTest.class.getResourceAsStream("/itau/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> parse(String body) {
    return json.readValue(body, Map.class);
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

  private String createMerchant(String name) {
    return (String) adminPost("/v1/admin/merchants", Map.of("name", name)).get("id");
  }

  private String createTestKey(String merchantId) {
    return (String)
        adminPost("/v1/admin/merchants/" + merchantId + "/api-keys", Map.of("environment", "TEST"))
            .get("key");
  }

  private void saveItauCredential(String merchantId) {
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
  }

  @SuppressWarnings("unchecked")
  private String registerEndpoint(String apiKey) {
    Map<String, Object> endpoint =
        http()
            .post()
            .uri("/v1/webhooks/endpoints")
            .header("Authorization", "Bearer " + apiKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "url",
                    "http://localhost:" + sink.getAddress().getPort() + "/hook",
                    "events",
                    List.of("payment.*")))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();

    return (String) endpoint.get("secret");
  }

  private void createPayment(String apiKey, String idempotencyKey) {
    http()
        .post()
        .uri("/v1/payments")
        .header("Authorization", "Bearer " + apiKey)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(
            Map.of("amount", 1000, "currency", "BRL", "method", "PIX", "reference", idempotencyKey))
        .exchange()
        .expectStatus()
        .isCreated();
  }

  private EntityExchangeResult<List> list(String apiKey, String query) {
    return http()
        .get()
        .uri("/v1/webhooks/deliveries" + query)
        .header("Authorization", "Bearer " + apiKey)
        .exchange()
        .expectBody(List.class)
        .returnResult();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> listProblem(String apiKey, String query) {
    return http()
        .get()
        .uri("/v1/webhooks/deliveries" + query)
        .header("Authorization", "Bearer " + apiKey)
        .exchange()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> get(String apiKey, String uri, int expectedStatus) {
    return http()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + apiKey)
        .exchange()
        .expectStatus()
        .isEqualTo(expectedStatus)
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  private EntityExchangeResult<Map> redeliver(String apiKey, String id, String idempotencyKey) {
    var spec =
        http()
            .post()
            .uri("/v1/webhooks/deliveries/" + id + "/redeliver")
            .header("Authorization", "Bearer " + apiKey);
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }

    return spec.exchange().expectBody(Map.class).returnResult();
  }

  private EntityExchangeResult<Map> redeliverDead(
      String apiKey, Instant since, String idempotencyKey) {
    return http()
        .post()
        .uri("/v1/webhooks/deliveries/redeliver-dead")
        .header("Authorization", "Bearer " + apiKey)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("since", since.toString()))
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }
}
