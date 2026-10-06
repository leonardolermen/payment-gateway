package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.webhookdelivery.client.HmacSigner;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

/**
 * The outbound webhook path end to end, through the real app: a Pix payment's event reaches the
 * merchant signed, a failing endpoint exhausts its attempts into {@code DEAD}, a manual redelivery
 * lands with the same event id, a rotated secret signs with both secrets during the overlap, and a
 * deactivated endpoint refuses redelivery.
 *
 * <p>The sandboxes cannot call back into a gateway, so this test is the webhook proof the README
 * points to. The merchant receiver is a WireMock server so each step can flip it between 500 and
 * 200 and read the exact headers it got. One test method: each step reads the state the previous
 * one left.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "webhook-delivery.retry-delay-ms=200",
      "webhook-delivery.max-attempts=3",
      "webhook-delivery.base-backoff=PT0.2S",
      "webhook-delivery.secret-rotation-overlap=PT1H",
      "gateway.payments.outbox-relay-ms=200",
      "gateway.payments.jobs-poll-ms=200",
      "gateway.rate-limit.requests-per-minute=1000"
    })
@ActiveProfiles("test")
@Testcontainers
class WebhookDeliveryFlowIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer ITAU = new WireMockServer(options().dynamicPort());
  static final WireMockServer RECEIVER = new WireMockServer(options().dynamicPort());

  static {
    ITAU.start();
    RECEIVER.start();
  }

  private static final Duration CEILING = Duration.ofSeconds(10);

  private final HmacSigner signer = new HmacSigner();

  @DynamicPropertySource
  static void itau(DynamicPropertyRegistry registry) {
    registry.add("gateway.providers.itau.test-api-base", ITAU::baseUrl);
    registry.add("gateway.providers.itau.test-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    registry.add("gateway.providers.itau.test-mutual-tls", () -> "false");
  }

  @BeforeAll
  static void stubItau() {
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
    RECEIVER.stop();
    ITAU.stop();
  }

  @LocalServerPort int port;

  @Test
  @SuppressWarnings("unchecked")
  void deliverFailRedeliverRotateDeactivate() {
    // 1. Merchant, key, Itau credential, an endpoint on payment.* -> S1.
    String merchantId = createMerchant("Flow Hook Store");
    String apiKey = createTestKey(merchantId);
    saveItauCredential(merchantId);
    Map<String, Object> endpoint = registerEndpoint(apiKey);
    String endpointId = (String) endpoint.get("id");
    String firstSecret = (String) endpoint.get("secret");

    // 2. Receiver 200: payment.pending arrives signed with S1 and carries a UUID event id.
    receiverAnswers(200);
    String firstPayment = createPayment(apiKey, "flow-ok-1");

    Awaitility.await().atMost(CEILING).until(() -> !requestsAbout(firstPayment).isEmpty());
    LoggedRequest pending = requestsAbout(firstPayment).getFirst();
    assertThat(pending.getHeader("X-Gateway-Event-Type")).isEqualTo("payment.pending");
    assertThat(isSignedWith(pending, "X-Gateway-Signature", firstSecret)).isTrue();
    assertThat(UUID.fromString(pending.getHeader("X-Gateway-Event-Id"))).isNotNull();

    // 3. Receiver 500: attempts pile up with the 500 recorded, then the delivery dies.
    receiverAnswers(500);
    String failingPayment = createPayment(apiKey, "flow-dead-1");

    Awaitility.await()
        .atMost(Duration.ofSeconds(3))
        .until(
            () -> {
              Map<String, Object> delivery = deliveryAbout(apiKey, failingPayment);
              return delivery != null && ((Integer) delivery.get("attempts")) >= 2;
            });
    assertThat((String) deliveryAbout(apiKey, failingPayment).get("last_error")).contains("500");

    Awaitility.await()
        .atMost(CEILING)
        .until(() -> "DEAD".equals(deliveryAbout(apiKey, failingPayment).get("status")));
    String deadId = (String) deliveryAbout(apiKey, failingPayment).get("id");
    assertThat(get(apiKey, deadId)).containsEntry("attempts", 3);

    // 4. Redeliver with the receiver back to 200: delivered, same event id, history kept.
    receiverAnswers(200);
    EntityExchangeResult<Map> scheduled = redeliver(apiKey, deadId, "flow-redeliver-1");
    assertThat(scheduled.getStatus().value()).isEqualTo(202);

    Awaitility.await()
        .atMost(CEILING)
        .until(() -> "DELIVERED".equals(get(apiKey, deadId).get("status")));
    Map<String, Object> redelivered = get(apiKey, deadId);
    assertThat(redelivered.get("redelivered_at")).isNotNull();
    assertThat((String) redelivered.get("last_error_before_redelivery")).contains("500");

    List<LoggedRequest> failingRequests = requestsAbout(failingPayment);
    assertThat(failingRequests).hasSize(4);
    assertThat(failingRequests.stream().map(request -> request.getHeader("X-Gateway-Event-Id")))
        .containsOnly(failingRequests.getFirst().getHeader("X-Gateway-Event-Id"));
    assertThat(UUID.fromString(failingRequests.getLast().getHeader("X-Gateway-Event-Id")))
        .isNotNull();

    // 5. Rotate -> S2: the next delivery is signed with S2 and, during the overlap, also with S1.
    String secondSecret = rotateSecret(apiKey, endpointId);
    assertThat(secondSecret).isNotEqualTo(firstSecret);
    String rotatedPayment = createPayment(apiKey, "flow-rotated-1");

    Awaitility.await().atMost(CEILING).until(() -> !requestsAbout(rotatedPayment).isEmpty());
    LoggedRequest rotated = requestsAbout(rotatedPayment).getFirst();
    assertThat(isSignedWith(rotated, "X-Gateway-Signature", secondSecret)).isTrue();
    assertThat(isSignedWith(rotated, "X-Gateway-Signature-Previous", firstSecret)).isTrue();

    // 6. Deactivate the endpoint: redelivering to it is refused. The step-4 delivery is already
    // DELIVERED, which is refused on its own, so a second DEAD one - redeliverable until the
    // endpoint goes inactive - is what proves the deactivation is the reason.
    receiverAnswers(500);
    String orphanedPayment = createPayment(apiKey, "flow-dead-2");
    Awaitility.await()
        .atMost(CEILING)
        .until(
            () -> {
              Map<String, Object> delivery = deliveryAbout(apiKey, orphanedPayment);
              return delivery != null && "DEAD".equals(delivery.get("status"));
            });
    String orphanedId = (String) deliveryAbout(apiKey, orphanedPayment).get("id");

    deactivate(apiKey, endpointId);

    for (String deliveryId : List.of(deadId, orphanedId)) {
      EntityExchangeResult<Map> refused = redeliver(apiKey, deliveryId, "again-" + deliveryId);
      assertThat(refused.getStatus().value()).isEqualTo(409);
      assertThat(refused.getResponseBody())
          .containsEntry("type", "urn:gateway:DELIVERY_NOT_REDELIVERABLE");
    }
  }

  /** The merchant's check: recompute the HMAC over {@code t + "." + body} with this secret. */
  private boolean isSignedWith(LoggedRequest request, String header, String secret) {
    String signature = request.getHeader(header);
    if (signature == null) {
      return false;
    }

    long timestamp = Long.parseLong(signature.substring(2, signature.indexOf(',')));
    String expected =
        signer.sign(request.getBodyAsString(), secret, Instant.ofEpochSecond(timestamp));

    return signature.equals(expected);
  }

  private static void receiverAnswers(int status) {
    RECEIVER.resetMappings();
    RECEIVER.stubFor(post(urlEqualTo("/hook")).willReturn(aResponse().withStatus(status)));
  }

  /** Every request the receiver got whose body mentions this payment, oldest first. */
  private static List<LoggedRequest> requestsAbout(String paymentId) {
    return RECEIVER.findAll(postRequestedFor(urlEqualTo("/hook"))).stream()
        .filter(request -> request.getBodyAsString().contains(paymentId))
        .toList();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> deliveryAbout(String apiKey, String paymentId) {
    List<Map<String, Object>> deliveries =
        http()
            .get()
            .uri("/v1/webhooks/deliveries")
            .header("Authorization", "Bearer " + apiKey)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(List.class)
            .returnResult()
            .getResponseBody();

    return deliveries.stream()
        .map(delivery -> get(apiKey, (String) delivery.get("id")))
        .filter(delivery -> String.valueOf(delivery.get("payload")).contains(paymentId))
        .findFirst()
        .orElse(null);
  }

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private static String fixture(String name) {
    try (InputStream in =
        WebhookDeliveryFlowIntegrationTest.class.getResourceAsStream("/itau/fixtures/" + name)) {
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
  private Map<String, Object> registerEndpoint(String apiKey) {
    return http()
        .post()
        .uri("/v1/webhooks/endpoints")
        .header("Authorization", "Bearer " + apiKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("url", RECEIVER.baseUrl() + "/hook", "events", List.of("payment.*")))
        .exchange()
        .expectStatus()
        .isCreated()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("unchecked")
  private String rotateSecret(String apiKey, String endpointId) {
    Map<String, Object> rotated =
        http()
            .post()
            .uri("/v1/webhooks/endpoints/" + endpointId + "/rotate-secret")
            .header("Authorization", "Bearer " + apiKey)
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();

    return (String) rotated.get("secret");
  }

  private void deactivate(String apiKey, String endpointId) {
    http()
        .delete()
        .uri("/v1/webhooks/endpoints/" + endpointId)
        .header("Authorization", "Bearer " + apiKey)
        .exchange()
        .expectStatus()
        .isOk();
  }

  @SuppressWarnings("unchecked")
  private String createPayment(String apiKey, String idempotencyKey) {
    Map<String, Object> payment =
        http()
            .post()
            .uri("/v1/payments")
            .header("Authorization", "Bearer " + apiKey)
            .header("Idempotency-Key", idempotencyKey)
            .contentType(MediaType.APPLICATION_JSON)
            .body(
                Map.of(
                    "amount",
                    1000,
                    "currency",
                    "BRL",
                    "method",
                    "PIX",
                    "reference",
                    idempotencyKey))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();

    return (String) payment.get("id");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> get(String apiKey, String deliveryId) {
    return http()
        .get()
        .uri("/v1/webhooks/deliveries/" + deliveryId)
        .header("Authorization", "Bearer " + apiKey)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  private EntityExchangeResult<Map> redeliver(String apiKey, String deliveryId, String key) {
    return http()
        .post()
        .uri("/v1/webhooks/deliveries/" + deliveryId + "/redeliver")
        .header("Authorization", "Bearer " + apiKey)
        .header("Idempotency-Key", key)
        .exchange()
        .expectBody(Map.class)
        .returnResult();
  }
}
