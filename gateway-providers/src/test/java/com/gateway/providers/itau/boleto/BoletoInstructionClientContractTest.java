package com.gateway.providers.itau.boleto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.*;

/** PATCH /boletos/{id_boleto}/baixa, whose id is agencia+conta+DAC+carteira+nosso numero (cash_management OpenAPI, path parameter description). */
class BoletoInstructionClientContractTest {
  static final String ID = "15000005206110900000001"; // 150000052061 + 109 + 00000001
  static WireMockServer server;

  @BeforeAll static void start() { server = new WireMockServer(WireMockConfiguration.options().dynamicPort()); server.start(); }
  @AfterAll static void stop() { server.stop(); }
  @BeforeEach void reset() {
    server.resetAll();
    // The instruction API authenticates at its own token URL (spec §1): the stub is on /api/oauth/token, not /jwt.
    server.stubFor(post("/api/oauth/token").willReturn(okJson("{\"access_token\":\"tok-cash\",\"expires_in\":300}")));
  }

  static String fixture(String f) {
    try { return Files.readString(Path.of("src/test/resources/itau/boleto/fixtures/" + f), StandardCharsets.UTF_8); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  static ItauCredentials creds() {
    return ItauCredentials.parse("{\"client_id\":\"c\",\"client_secret\":\"s\",\"x_itau_apikey\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\"}".getBytes());
  }

  static BoletoInstructionClient client() {
    ItauEndpoints e = ItauEndpoints.custom(URI.create(server.baseUrl() + "/cash_management/v2"), URI.create(server.baseUrl() + "/api/oauth/token"), false);
    return new BoletoInstructionClient(new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(3)), e, null, Duration.ofSeconds(5));
  }

  @Test void patchesWithoutBodyAndAccepts200() {
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa"))
        .withHeader("Authorization", equalTo("Bearer tok-cash"))
        .withHeader("x-itau-apikey", equalTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"))
        .willReturn(okJson(fixture("patch_baixa_200.json"))));
    client().baixa(creds(), ID);
    server.verify(patchRequestedFor(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).withRequestBody(absent()));
    server.verify(1, postRequestedFor(urlEqualTo("/api/oauth/token")));
  }

  @Test void accepts204And202() {
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).willReturn(aResponse().withStatus(204)));
    client().baixa(creds(), ID);
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).willReturn(aResponse().withStatus(202)));
    client().baixa(creds(), ID);
  }

  @Test void alreadyPaidIsConflict() {
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).willReturn(aResponse().withStatus(422).withBody(fixture("patch_baixa_422_paid.json"))));
    assertThatThrownBy(() -> client().baixa(creds(), ID)).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.CONFLICT);
      assertThat(e.httpStatus()).isEqualTo(422);
    });
  }

  @Test void other422IsDeclinedAnd404IsNotFound() {
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).willReturn(aResponse().withStatus(422).withBody("{\"codigo\":\"422\",\"mensagem\":\"Instrução não permitida para a carteira\",\"campos\":[]}")));
    assertThatThrownBy(() -> client().baixa(creds(), ID)).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.DECLINED));
    server.stubFor(patch(urlEqualTo("/cash_management/v2/boletos/" + ID + "/baixa")).willReturn(aResponse().withStatus(404)));
    assertThatThrownBy(() -> client().baixa(creds(), ID)).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.NOT_FOUND));
  }
}
