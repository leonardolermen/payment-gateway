package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Deviation from the brief: same {@code TestRestTemplate} → {@link RestTestClient} substitution as
 * {@code AuthenticationIntegrationTest} — {@code TestRestTemplate} does not exist on this Boot
 * 4.0.7 / Spring Framework 7 classpath. Scenarios and assertions kept as written in the brief.
 *
 * <p>The correlation id is checked on an unauthenticated merchant route (401): the actuator moved
 * to the management port, and the filter runs before authentication, so the 401 carries it too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ObservabilityIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  @Value("${local.management.port}")
  int managementPort;

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void correlationIdIsEchoedOrGenerated() {
    var withHeader =
        http()
            .get()
            .uri("/v1/merchant")
            .header("X-Correlation-Id", "abc-123")
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .returnResult(String.class);
    assertThat(withHeader.getResponseHeaders().getFirst("X-Correlation-Id")).isEqualTo("abc-123");

    var withoutHeader =
        http()
            .get()
            .uri("/v1/merchant")
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .returnResult(String.class);
    assertThat(withoutHeader.getResponseHeaders().getFirst("X-Correlation-Id")).isNotBlank();
  }

  @Test
  void oversizedCorrelationIdIsReplaced() {
    String huge = "a".repeat(200);
    var result =
        http()
            .get()
            .uri("/v1/merchant")
            .header("X-Correlation-Id", huge)
            .exchange()
            .expectStatus()
            .isUnauthorized()
            .returnResult(String.class);
    String echoed = result.getResponseHeaders().getFirst("X-Correlation-Id");
    assertThat(echoed).isNotBlank().isNotEqualTo(huge).hasSizeLessThanOrEqualTo(64);
  }

  /** Public on the management port, which is never published; absent from the merchant port. */
  @Test
  void prometheusAndHealthArePublicOnTheManagementPort() {
    RestTestClient management =
        RestTestClient.bindToServer().baseUrl("http://localhost:" + managementPort).build();

    management.get().uri("/actuator/prometheus").exchange().expectStatus().isEqualTo(HttpStatus.OK);
    management.get().uri("/actuator/health").exchange().expectStatus().isEqualTo(HttpStatus.OK);
  }
}
