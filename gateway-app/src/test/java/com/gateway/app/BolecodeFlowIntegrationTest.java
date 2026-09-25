package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
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
 * The Bolecode path through the real app: create with a payer (201 with boleto and pix blocks),
 * refuse without one (422), cancel (baixa at the bank), a barcode payment found by the poll
 * (paid_via BOLETO, webhook delivered), and the refund refusal. WireMock plays the three Itaú
 * APIs under one host with three path prefixes; sandbox-shaped credentials, no mTLS.
 *
 * <p>One test method on purpose, like PaymentsFlowIntegrationTest: every step builds on the previous.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "webhook-delivery.retry-delay-ms=200",
      "gateway.payments.outbox-relay-ms=200",
      "gateway.payments.jobs-poll-ms=200",
      "gateway.rate-limit.requests-per-minute=1000"
    })
@ActiveProfiles("test")
@Testcontainers
class BolecodeFlowIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer ITAU = new WireMockServer(options().dynamicPort());
  static final String BAIXA_ID_1 = "15000005206110900000001";
  static final List<String> received = new CopyOnWriteArrayList<>();
  static HttpServer sink;

  static { ITAU.start(); }

  @DynamicPropertySource
  static void itau(DynamicPropertyRegistry r) {
    r.add("gateway.providers.itau.test-mutual-tls", () -> "false");
    r.add("gateway.providers.itau.test-api-base", () -> ITAU.baseUrl() + "/pix");
    r.add("gateway.providers.itau.test-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    r.add("gateway.providers.itau.boleto.test-issue-api-base", () -> ITAU.baseUrl() + "/issue");
    r.add("gateway.providers.itau.boleto.test-issue-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    r.add("gateway.providers.itau.boleto.test-query-api-base", () -> ITAU.baseUrl() + "/query");
    r.add("gateway.providers.itau.boleto.test-query-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    r.add("gateway.providers.itau.boleto.test-instruction-api-base", () -> ITAU.baseUrl() + "/instruction");
    r.add("gateway.providers.itau.boleto.test-instruction-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
  }

  @BeforeAll
  static void start() throws IOException {
    sink = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    sink.createContext("/hook", ex -> {
      received.add(ex.getRequestHeaders().getFirst("X-Gateway-Event-Type") + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      ex.sendResponseHeaders(200, -1);
      ex.close();
    });
    sink.start();
    ITAU.stubFor(post(urlEqualTo("/api/oauth/jwt")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
        .withBody("{\"access_token\":\"tok-123\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    ITAU.stubFor(post(urlEqualTo("/issue/boletos-pix")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(fixture("post_boletos_pix_200.json"))));
  }

  @AfterAll
  static void stop() { sink.stop(0); ITAU.stop(); }

  @LocalServerPort int port;
  @Autowired JdbcTemplate jdbc;

  private RestTestClient http() { return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build(); }

  private static String fixture(String name) {
    try (InputStream in = BolecodeFlowIntegrationTest.class.getResourceAsStream("/itau/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> adminPost(String uri, Object body) {
    return http().post().uri(uri).header("X-Admin-Key", "test-admin").contentType(MediaType.APPLICATION_JSON).body(body).exchange()
        .expectStatus().is2xxSuccessful().expectBody(Map.class).returnResult().getResponseBody();
  }

  @SuppressWarnings("unchecked")
  private EntityExchangeResult<Map> postPayment(String apiKey, String key, Map<String, Object> body) {
    return http().post().uri("/v1/payments").header("Authorization", "Bearer " + apiKey).header("Idempotency-Key", key)
        .contentType(MediaType.APPLICATION_JSON).body(body).exchange().expectBody(Map.class).returnResult();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getJson(String apiKey, String uri) {
    return http().get().uri(uri).header("Authorization", "Bearer " + apiKey).exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
  }

  private static Map<String, Object> bolecodeRequest(String reference) {
    Map<String, Object> customer = Map.of("name", "Joao da Silva", "document", "12345678901",
        "address", Map.of("street", "Rua das Flores 10", "district", "Centro", "city", "Sao Paulo", "state", "SP", "zip", "01310100"));
    Map<String, Object> body = new HashMap<>();
    body.put("amount", 12990);
    body.put("currency", "BRL");
    body.put("method", "BOLECODE");
    body.put("reference", reference);
    body.put("description", "Pedido 42");
    body.put("customer", customer);
    return body;
  }

  @Test
  @SuppressWarnings("unchecked")
  void issueRefuseCancelPollAndRefuseRefund() {
    // 1. Merchant, TEST key, credential with the boleto account, webhook endpoint.
    String merchantId = (String) adminPost("/v1/admin/merchants", Map.of("name", "Boleto Store")).get("id");
    String testKey = (String) adminPost("/v1/admin/merchants/" + merchantId + "/api-keys", Map.of("environment", "TEST")).get("key");
    http().put().uri("/v1/admin/merchants/" + merchantId + "/providers/ITAU/credentials").header("X-Admin-Key", "test-admin").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("environment", "TEST", "payload", Map.of("client_id", "sandbox-client", "client_secret", "sandbox-secret", "pix_key", "a1f4102e-a446-4a57-bcce-6fa48899c1d1",
            "beneficiary_id", "150000052061", "wallet_code", "109", "species_code", "01")))
        .exchange().expectStatus().is2xxSuccessful();
    http().post().uri("/v1/webhooks/endpoints").header("Authorization", "Bearer " + testKey).contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("url", "http://localhost:" + sink.getAddress().getPort() + "/hook", "events", List.of("payment.*"))).exchange().expectStatus().isCreated();

    // 2. Create: 201 with both blocks.
    EntityExchangeResult<Map> created = postPayment(testKey, "b1", bolecodeRequest("order-42"));
    assertThat(created.getStatus().value()).isEqualTo(201);
    Map<String, Object> payment = created.getResponseBody();
    String paymentId = (String) payment.get("id");
    LocalDate today = LocalDate.now(ZoneId.of("America/Sao_Paulo"));
    assertThat(payment).containsEntry("status", "PENDING").containsEntry("method", "BOLECODE").containsEntry("amount", 12990).containsKey("expires_at");
    Map<String, Object> boleto = (Map<String, Object>) payment.get("boleto");
    assertThat(boleto).containsEntry("linha_digitavel", "34101234567890123456789012345678901234567890123")
        .containsEntry("codigo_barras", "34191234567890123456789012345678901234567890")
        .containsEntry("due_date", today.plusDays(3).toString())
        .containsEntry("payment_limit_date", "2027-01-31")   // the bank's answer wins over our due + 30
        .containsEntry("paid_via", null);
    Map<String, Object> pix = (Map<String, Object>) payment.get("pix");
    assertThat(pix).containsEntry("txid", "BL1234567890123456789012345678901").containsEntry("end_to_end_id", null);
    assertThat((String) pix.get("copia_e_cola")).startsWith("000201");
    ITAU.verify(1, postRequestedFor(urlEqualTo("/issue/boletos-pix"))
        .withHeader("Authorization", equalTo("Bearer tok-123"))
        .withRequestBody(matchingJsonPath("$.beneficiario.id_beneficiario", equalTo("150000052061")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.pessoa.nome_pessoa", equalTo("Joao da Silva")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.dados_individuais_boleto[0].numero_nosso_numero", equalTo("00000001")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.dados_individuais_boleto[0].valor_titulo", equalTo("129.90")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.dados_individuais_boleto[0].data_vencimento", equalTo(today.plusDays(3).toString()))));
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> received.stream().anyMatch(r -> r.startsWith("payment.pending") && r.contains(paymentId)));

    // 3. No customer: 422 naming the field, nothing sent to the bank.
    Map<String, Object> noCustomer = new HashMap<>(bolecodeRequest("order-43"));
    noCustomer.remove("customer");
    EntityExchangeResult<Map> refused = postPayment(testKey, "b2", noCustomer);
    assertThat(refused.getStatus().value()).isEqualTo(422);
    assertThat(refused.getResponseBody()).containsEntry("type", "urn:gateway:CUSTOMER_REQUIRED");
    Map<String, Object> noZip = bolecodeRequest("order-44");
    noZip.put("customer", Map.of("name", "Joao", "document", "12345678901", "address", Map.of("street", "Rua A", "district", "Centro", "city", "Sao Paulo", "state", "SP")));
    EntityExchangeResult<Map> refusedZip = postPayment(testKey, "b3", noZip);
    assertThat(refusedZip.getStatus().value()).isEqualTo(422);
    assertThat((String) refusedZip.getResponseBody().get("detail")).contains("customer.address.zip");
    ITAU.verify(1, postRequestedFor(urlEqualTo("/issue/boletos-pix")));

    // 4. Cancel: the query says open, the baixa goes out with the composite id.
    ITAU.stubFor(get(urlPathEqualTo("/query/boletos")).withQueryParam("nosso_numero", equalTo("00000001"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(fixture("get_boletos_200_canceled.json").replace("\"Baixado\"", "\"Em Aberto\""))));
    ITAU.stubFor(patch(urlEqualTo("/instruction/boletos/" + BAIXA_ID_1 + "/baixa")).willReturn(aResponse().withStatus(204)));
    Map<String, Object> canceled = http().post().uri("/v1/payments/" + paymentId + "/cancel").header("Authorization", "Bearer " + testKey).header("Idempotency-Key", "c1")
        .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
    assertThat(canceled).containsEntry("status", "CANCELED");
    ITAU.verify(1, patchRequestedFor(urlEqualTo("/instruction/boletos/" + BAIXA_ID_1 + "/baixa")).withHeader("Authorization", equalTo("Bearer tok-123")));

    // 5. Second Bolecode (a different txid from the bank, or the unique index refuses it), paid by barcode and found by the poll.
    ITAU.stubFor(post(urlEqualTo("/issue/boletos-pix")).willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
        .withBody(fixture("post_boletos_pix_200.json").replace("BL1234567890123456789012345678901", "BL1234567890123456789012345678902"))));
    EntityExchangeResult<Map> second = postPayment(testKey, "b4", bolecodeRequest("order-45"));
    assertThat(second.getStatus().value()).isEqualTo(201);
    String secondId = (String) second.getResponseBody().get("id");
    ITAU.stubFor(get(urlPathEqualTo("/query/boletos")).withQueryParam("nosso_numero", equalTo("00000002"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody(fixture("get_boletos_200_paid.json").replace("\"00000001\"", "\"00000002\"").replace("\"2100.00\"", "\"129.90\""))));
    // The poll is due in 6 hours; bring it forward instead of waiting.
    jdbc.update("UPDATE payments.jobs SET next_run_at = now() WHERE type = 'POLL_BOLETO' AND ref_id = ?", secondId);
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> "COMPLETED".equals(getJson(testKey, "/v1/payments/" + secondId).get("status")));
    Map<String, Object> paid = getJson(testKey, "/v1/payments/" + secondId);
    assertThat(paid).containsEntry("paid_amount", 12990);
    assertThat((Map<String, Object>) paid.get("boleto")).containsEntry("paid_via", "BOLETO");
    assertThat((Map<String, Object>) paid.get("pix")).containsEntry("end_to_end_id", null);
    Awaitility.await().atMost(Duration.ofSeconds(15)).until(() -> received.stream().anyMatch(r -> r.startsWith("payment.completed") && r.contains(secondId)));
    assertThat(received.stream().filter(r -> r.startsWith("payment.completed") && r.contains(secondId)).findFirst().orElseThrow()).contains("\"paid_via\":\"BOLETO\"");
    List<Map<String, Object>> events = http().get().uri("/v1/payments/" + secondId + "/events").header("Authorization", "Bearer " + testKey)
        .exchange().expectStatus().isOk().expectBody(List.class).returnResult().getResponseBody();
    assertThat(events).extracting(e -> e.get("type")).containsExactly("created", "pending", "completed");
    assertThat(events.getLast()).containsEntry("source", "PROVIDER_POLL");

    // 6. A boleto settlement has no refund.
    EntityExchangeResult<Map> refund = http().post().uri("/v1/payments/" + secondId + "/refunds").header("Authorization", "Bearer " + testKey)
        .header("Idempotency-Key", "r1").contentType(MediaType.APPLICATION_JSON).body(Map.of("amount", 1000)).exchange().expectBody(Map.class).returnResult();
    assertThat(refund.getStatus().value()).isEqualTo(422);
    assertThat(refund.getResponseBody()).containsEntry("type", "urn:gateway:REFUND_NOT_SUPPORTED");

    // 7. Request-shape errors are 400s from the DTO, before any service.
    Map<String, Object> mixed = bolecodeRequest("order-46");
    mixed.put("expires_in", 600);
    assertThat(postPayment(testKey, "b5", mixed).getStatus().value()).isEqualTo(400);
    Map<String, Object> pixWithDue = new HashMap<>(Map.of("amount", 100, "currency", "BRL", "method", "PIX", "due_date", "2026-12-31"));
    assertThat(postPayment(testKey, "b6", pixWithDue).getStatus().value()).isEqualTo(400);
  }
}
