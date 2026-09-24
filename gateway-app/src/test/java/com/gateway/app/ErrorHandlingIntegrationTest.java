package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * ErrorHandler used to map every IllegalStateException to 401 and echo its message. With that
 * handler gone, an unrelated bug must be a 500 that does not leak the exception message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@Import(ErrorHandlingIntegrationTest.Boom.class)
class ErrorHandlingIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  @RestController
  static class Boom {
    @GetMapping("/test-only/boom")
    String boom() { throw new IllegalStateException("internal detail gk_live_SHOULDNOTLEAK"); }
  }

  @Test
  void unrelatedIllegalStateIsA500WithoutTheMessage() {
    String body = RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build()
        .get().uri("/test-only/boom").exchange()
        .expectStatus().is5xxServerError().expectBody(String.class).returnResult().getResponseBody();
    assertThat(body).doesNotContain("SHOULDNOTLEAK").doesNotContain("internal detail");
  }
}
