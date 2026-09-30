package com.gateway.providers.cielo.card;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.providers.cielo.CieloHttp;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.card.dto.CardTokenRequest;
import com.gateway.providers.cielo.sale.CieloFixtures;
import com.gateway.providers.cielo.sale.CieloSalesClientAuthorizeContractTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.time.Duration;
import java.time.YearMonth;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** POST /1/card/ (reference/criar-cardtoken): our body equals the page's example for its card. */
class CieloCardClientContractTest {
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
  void tokenizesWithTheDocumentedBody() {
    server.stubFor(
        post(urlEqualTo("/1/card/"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(CieloFixtures.read("post_card_201.json"))));
    URI base = URI.create(server.baseUrl());
    CieloCardClient client =
        new CieloCardClient(
            new CieloHttp(Duration.ofSeconds(2), Duration.ofSeconds(5)),
            new CieloEndpoints(base, base));
    CardData card =
        CardData.of(
            "4024007110880035", "Comprador T Cielo", "10/2026", "123", null, YearMonth.of(2026, 9));
    CardTokenRequest request =
        CardTokenRequestFactory.from(card, PersonName.of("Comprador Teste Cielo"));

    String token =
        client.create(CieloSalesClientAuthorizeContractTest.credentials(), request).cardToken();

    assertThat(token).isEqualTo("db62dc71-d07b-4745-9969-42697b988ccb");
    server.verify(
        postRequestedFor(urlEqualTo("/1/card/"))
            .withRequestBody(equalToJson(CieloFixtures.read("post_card_request.json"))));
    assertThat(request.toString()).doesNotContain("4024007110880035");
  }
}
