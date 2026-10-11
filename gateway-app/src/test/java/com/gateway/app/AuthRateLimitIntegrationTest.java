package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Its own class: the default per-IP limit of 10, which AuthApiIntegrationTest raises. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class AuthRateLimitIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  @Test
  void authRoutesAreRateLimitedPerIp() {
    for (int i = 0; i < 10; i++) {
      login();
    }

    assertThat(login()).isEqualTo(429);
  }

  private int login() {
    return RestTestClient.bindToServer()
        .baseUrl("http://localhost:" + port)
        .build()
        .post()
        .uri("/v1/auth/login")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("email", "a@b.co", "password", "xxxxxxxxxx"))
        .exchange()
        .expectBody(String.class)
        .returnResult()
        .getStatus()
        .value();
  }
}
