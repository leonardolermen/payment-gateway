package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
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
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class ObservabilityIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @Test
  void correlationIdIsEchoedOrGenerated() {
    var withHeader =
        http()
            .get()
            .uri("/actuator/health")
            .header("X-Correlation-Id", "abc-123")
            .exchange()
            .expectStatus()
            .isOk()
            .returnResult(String.class);
    assertThat(withHeader.getResponseHeaders().getFirst("X-Correlation-Id")).isEqualTo("abc-123");

    var withoutHeader =
        http().get().uri("/actuator/health").exchange().expectStatus().isOk().returnResult(String.class);
    assertThat(withoutHeader.getResponseHeaders().getFirst("X-Correlation-Id")).isNotBlank();
  }

  @Test
  void oversizedCorrelationIdIsReplaced() {
    String huge = "a".repeat(200);
    var result = http().get().uri("/actuator/health").header("X-Correlation-Id", huge)
        .exchange().expectStatus().isOk().returnResult(String.class);
    String echoed = result.getResponseHeaders().getFirst("X-Correlation-Id");
    assertThat(echoed).isNotBlank().isNotEqualTo(huge).hasSizeLessThanOrEqualTo(64);
  }

  @Test
  void prometheusAndHealthArePublic() {
    http().get().uri("/actuator/prometheus").exchange().expectStatus().isEqualTo(HttpStatus.OK);
    http().get().uri("/actuator/health").exchange().expectStatus().isEqualTo(HttpStatus.OK);
  }
}
