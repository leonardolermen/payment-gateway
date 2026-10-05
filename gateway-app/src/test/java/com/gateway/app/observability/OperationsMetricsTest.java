package com.gateway.app.observability;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.Divergences;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The operations gauges, scraped the way Prometheus would: from the management port, with no key.
 * One test method: the empty-database scrape must come before anything is created.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "gateway.rate-limit.requests-per-minute=1000")
@ActiveProfiles("test")
@Testcontainers
class OperationsMetricsTest {

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
  static void stopItau() {
    ITAU.stop();
  }

  @LocalServerPort int port;

  @Value("${local.management.port}")
  int managementPort;

  @Autowired OperationsMetrics metrics;
  @Autowired Divergences divergences;
  @Autowired PaymentRepository payments;

  @Test
  void theScrapeReportsPaymentsAndOpenDivergencesOnTheManagementPortOnly() {
    assertThatCode(metrics::refresh).doesNotThrowAnyException();
    assertThat(scrape()).contains("gateway_outbox_pending");

    String paymentId = createPix();
    Payment payment = payments.findById(paymentId).orElseThrow();
    divergences.open(payment, "CONCLUIDA", "the bank says paid, the gateway says pending");

    metrics.refresh();
    String scraped = scrape();

    assertThat(scraped)
        .containsPattern("gateway_payments\\{[^}]*method=\"PIX\"[^}]*status=\"PENDING\"[^}]*\\} 1");
    assertThat(scraped)
        .containsPattern("gateway_divergences_open\\{kind=\"CONCLUIDA\",origin=\"SYSTEM\"\\} 1");
    assertThat(scraped).contains("gateway_provider_call_seconds");

    int merchantPortStatus =
        http(port).get().uri("/actuator/prometheus").exchange().returnResult().getStatus().value();
    assertThat(merchantPortStatus).isNotEqualTo(200);
  }

  private String scrape() {
    return http(managementPort)
        .get()
        .uri("/actuator/prometheus")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(String.class)
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("unchecked")
  private String createPix() {
    String merchantId =
        (String) adminPost("/v1/admin/merchants", Map.of("name", "Metrics Store")).get("id");
    String testKey =
        (String)
            adminPost(
                    "/v1/admin/merchants/" + merchantId + "/api-keys",
                    Map.of("environment", "TEST"))
                .get("key");

    http(port)
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
        http(port)
            .post()
            .uri("/v1/payments")
            .header("Authorization", "Bearer " + testKey)
            .header("Idempotency-Key", "metrics-1")
            .contentType(MediaType.APPLICATION_JSON)
            .body(Map.of("amount", 1000, "currency", "BRL", "method", "PIX"))
            .exchange()
            .expectStatus()
            .isCreated()
            .expectBody(Map.class)
            .returnResult()
            .getResponseBody();

    return (String) created.get("id");
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> adminPost(String uri, Object body) {
    return http(port)
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

  private static RestTestClient http(int port) {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private static String fixture(String name) {
    try (InputStream in =
        OperationsMetricsTest.class.getResourceAsStream("/itau/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
