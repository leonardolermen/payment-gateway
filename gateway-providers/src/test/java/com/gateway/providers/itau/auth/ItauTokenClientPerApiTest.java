package com.gateway.providers.itau.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * One credential, two token URLs (Pix/issue/query at one STS path, cash_management at another): the
 * cache key is fingerprint + token URL, so each API gets its own token and neither evicts the
 * other.
 */
class ItauTokenClientPerApiTest {
  static WireMockServer server;

  @BeforeAll
  static void start() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @Test
  void eachTokenUrlGetsItsOwnCachedToken() {
    server.stubFor(
        post("/as/token.oauth2")
            .willReturn(okJson("{\"access_token\":\"tok-pix\",\"expires_in\":300}")));
    server.stubFor(
        post("/api/oauth/token")
            .willReturn(okJson("{\"access_token\":\"tok-cash\",\"expires_in\":300}")));
    ItauCredentials creds =
        ItauCredentials.parse(
            "{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\"}"
                .getBytes());
    ItauTokenClient client =
        new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(3));
    ItauEndpoints pixLike =
        ItauEndpoints.custom(
            URI.create(server.baseUrl() + "/v2"),
            URI.create(server.baseUrl() + "/as/token.oauth2"),
            false);
    ItauEndpoints cash =
        ItauEndpoints.custom(
            URI.create(server.baseUrl() + "/cash_management/v2"),
            URI.create(server.baseUrl() + "/api/oauth/token"),
            false);

    assertThat(client.tokenFor(creds, pixLike, null).value()).isEqualTo("tok-pix");
    assertThat(client.tokenFor(creds, cash, null).value()).isEqualTo("tok-cash");
    assertThat(client.tokenFor(creds, pixLike, null).value()).isEqualTo("tok-pix");
    assertThat(client.tokenFor(creds, cash, null).value()).isEqualTo("tok-cash");

    server.verify(1, postRequestedFor(urlEqualTo("/as/token.oauth2")));
    server.verify(1, postRequestedFor(urlEqualTo("/api/oauth/token")));
  }
}
