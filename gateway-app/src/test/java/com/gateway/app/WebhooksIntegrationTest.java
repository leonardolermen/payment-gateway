package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.barrier.webhookdelivery.client.HmacSigner;
import com.gateway.app.webhooks.MerchantEvents;
import com.gateway.kernel.ids.MerchantId;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Deviation from the brief, same as {@link AuthenticationIntegrationTest}: {@code TestRestTemplate}
 * does not exist on this classpath (Boot 4 / Spring Framework 7), so scenarios and assertions are
 * expressed with {@link RestTestClient} instead.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "webhook-delivery.retry-delay-ms=200")
@ActiveProfiles("test")
@Testcontainers
class WebhooksIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  record Received(String body, Map<String, String> headers) {}

  static final List<Received> received = new CopyOnWriteArrayList<>();
  static final List<Received> rawReceived = new CopyOnWriteArrayList<>();
  static HttpServer sink;

  @BeforeAll
  static void start() throws Exception {
    sink = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    sink.createContext(
        "/hook",
        ex -> {
          Map<String, String> h = new ConcurrentHashMap<>();
          ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.getFirst()));
          received.add(new Received(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), h));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    sink.createContext(
        "/hook-raw",
        ex -> {
          Map<String, String> h = new ConcurrentHashMap<>();
          ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(), v.getFirst()));
          rawReceived.add(new Received(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), h));
          ex.sendResponseHeaders(200, -1);
          ex.close();
        });
    sink.start();
  }

  @AfterAll
  static void stop() {
    sink.stop(0);
  }

  @LocalServerPort int port;

  @Autowired MerchantEvents events;
  @Autowired HmacSigner signer;

  private RestTestClient http() { return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build(); }

  private RestTestClient.RequestBodySpec adminPost(String uri) {
    return http().post().uri(uri).header("X-Admin-Key", "test-admin").contentType(MediaType.APPLICATION_JSON);
  }

  @SuppressWarnings("unchecked")
  private String[] merchantAndKey(String name) {
    Map<String, Object> m = adminPost("/v1/admin/merchants").body(Map.of("name", name)).exchange()
        .expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
    Map<String, Object> k = adminPost("/v1/admin/merchants/" + m.get("id") + "/api-keys").body(Map.of("environment", "TEST")).exchange()
        .expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();
    return new String[] {(String) m.get("id"), (String) k.get("key")};
  }

  private String url() { return "http://localhost:" + sink.getAddress().getPort() + "/hook"; }

  @Test
  @SuppressWarnings("unchecked")
  void registersEndpointAndReceivesSignedEventWithXGatewayHeaders() {
    String[] mk = merchantAndKey("Store A");
    Map<String, Object> created = http().post().uri("/v1/webhooks/endpoints")
        .header("Authorization", "Bearer " + mk[1]).contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("url", url(), "events", List.of("payment.*")))
        .exchange().expectStatus().isEqualTo(HttpStatus.CREATED).expectBody(Map.class).returnResult().getResponseBody();
    String secret = (String) created.get("secret");
    assertThat(secret).isNotBlank();

    events.emit(new MerchantId(mk[0]), "payment.completed", "pay_1", "pay_1", Map.of("id", "pay_1", "status", "COMPLETED"));
    events.emit(new MerchantId(mk[0]), "refund.completed", "ref_1", "pay_1", Map.of("id", "ref_1"));

    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> received.size() == 1);
    Received r = received.getFirst();
    assertThat(r.headers()).containsKey("x-gateway-signature").containsKey("x-gateway-event-id").containsEntry("x-gateway-event-type", "payment.completed");
    String signature = r.headers().get("x-gateway-signature");
    long t = Long.parseLong(signature.substring(2, signature.indexOf(',')));
    assertThat(signature).isEqualTo(signer.sign(r.body(), secret, Instant.ofEpochSecond(t)));
    assertThat(r.body()).contains("\"status\":\"COMPLETED\"");
  }

  @Test
  void emitRawCarriesTheCallersEventIdToTheMerchant() {
    String[] mk = merchantAndKey("Store E");
    String hookUrl = "http://localhost:" + sink.getAddress().getPort() + "/hook-raw";
    http().post().uri("/v1/webhooks/endpoints")
        .header("Authorization", "Bearer " + mk[1]).contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("url", hookUrl, "events", List.of("payment.*"))).exchange().expectStatus().isCreated();

    java.util.UUID eventId = java.util.UUID.nameUUIDFromBytes("outbox-row-1".getBytes(StandardCharsets.UTF_8));
    events.emitRaw(new MerchantId(mk[0]), "payment.pending", "pay_2", "pay_2", "{\"id\":\"pay_2\"}", eventId);

    Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> rawReceived.size() == 1);
    assertThat(rawReceived.getFirst().headers()).containsEntry("x-gateway-event-id", eventId.toString());
    assertThat(rawReceived.getFirst().body()).isEqualTo("{\"id\":\"pay_2\"}");
  }

  @Test
  @SuppressWarnings("unchecked")
  void merchantCannotSeeAnotherMerchantsEndpoint() {
    String[] a = merchantAndKey("Store B");
    String[] b = merchantAndKey("Store C");
    Map<String, Object> e = http().post().uri("/v1/webhooks/endpoints")
        .header("Authorization", "Bearer " + a[1]).contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("url", url())).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();

    http().get().uri("/v1/webhooks/endpoints/" + e.get("id"))
        .header("Authorization", "Bearer " + b[1]).exchange().expectStatus().isNotFound();

    List<?> list = http().get().uri("/v1/webhooks/endpoints")
        .header("Authorization", "Bearer " + b[1]).exchange().expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    assertThat(list).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void getHidesTheSecretAndRotationReturnsANewOne() {
    String[] mk = merchantAndKey("Store D");
    Map<String, Object> e = http().post().uri("/v1/webhooks/endpoints")
        .header("Authorization", "Bearer " + mk[1]).contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("url", url())).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody();

    Map<String, Object> get = http().get().uri("/v1/webhooks/endpoints/" + e.get("id"))
        .header("Authorization", "Bearer " + mk[1]).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    assertThat(get).doesNotContainKey("secret");

    Map<String, Object> rot = http().post().uri("/v1/webhooks/endpoints/" + e.get("id") + "/rotate-secret")
        .header("Authorization", "Bearer " + mk[1]).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    assertThat((String) rot.get("secret")).isNotEqualTo(e.get("secret"));
    assertThat(rot).containsKey("previous_secret_until");
  }
}
