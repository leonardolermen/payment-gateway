package com.gateway.providers.itau.boleto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.dto.BoletoQueryItem;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.*;

/** GET /boletos (consulta de detalhe) against the bank's own example and states derived from it. */
class BoletoQueryClientContractTest {
  static final String APIKEY = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
  static WireMockServer server;

  @BeforeAll static void start() { server = new WireMockServer(WireMockConfiguration.options().dynamicPort()); server.start(); }
  @AfterAll static void stop() { server.stop(); }
  @BeforeEach void reset() {
    server.resetAll();
    server.stubFor(post("/api/oauth/jwt").willReturn(okJson("{\"access_token\":\"tok\",\"expires_in\":300}")));
  }

  static String fixture(String f) {
    try { return Files.readString(Path.of("src/test/resources/itau/boleto/fixtures/" + f), StandardCharsets.UTF_8); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  static ItauCredentials creds() {
    return ItauCredentials.parse(("{\"client_id\":\"c\",\"client_secret\":\"s\",\"x_itau_apikey\":\"" + APIKEY
        + "\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\",\"wallet_code\":\"109\"}").getBytes());
  }

  static BoletoQueryClient client() {
    ItauEndpoints e = ItauEndpoints.custom(URI.create(server.baseUrl() + "/v2"), URI.create(server.baseUrl() + "/api/oauth/jwt"), false);
    return new BoletoQueryClient(new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(3)), e, null, Duration.ofSeconds(5));
  }

  @Test void queriesByBeneficiaryWalletAndNumberWithTheRequiredHeaders() {
    server.stubFor(get(urlPathEqualTo("/v2/boletos"))
        .withQueryParam("id_beneficiario", equalTo("150000052061"))
        .withQueryParam("codigo_carteira", equalTo("109"))
        .withQueryParam("nosso_numero", equalTo("00000000"))
        .withHeader("Authorization", equalTo("Bearer tok"))
        .withHeader("x-itau-apikey", equalTo(APIKEY))
        .withHeader("x-itau-correlationID", matching("[0-9a-f-]{36}"))
        .willReturn(okJson(fixture("get_boletos_200.json"))));

    Optional<BoletoQueryItem> found = client().find(creds(), "00000000");

    assertThat(found).isPresent();
    BoletoQueryItem.Individual i = found.get().individual("00000000").orElseThrow();
    assertThat(i.situacaoGeralBoleto()).isEqualTo("Em Aberto");
    assertThat(i.codigoBarras()).hasSize(44);
    assertThat(i.numeroLinhaDigitavel()).hasSize(47);
    assertThat(i.dataLimitePagamento()).isEqualTo("2030-08-06");
    assertThat(found.get().lastPayment()).isPresent().get().satisfies(p -> {
      assertThat(p.valorPagoTotalCobranca()).isEqualTo("2100.00");
      assertThat(p.dataInclusaoPagamento()).isEqualTo("2020-01-20");
    });
    assertThat(found.get().dadoBoleto().qrcodePix()).isNull();
    assertThat(found.get().dadoBoleto().baixa().motivo()).isEqualTo("Baixa por ter sido liquidado");
  }

  /** The bank filters by nosso_numero; a mock (the sandbox) may not. The client picks the item that carries the number it asked for. */
  @Test void aListWithoutTheNumberIsEmpty() {
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(okJson(fixture("get_boletos_200.json"))));
    assertThat(client().find(creds(), "99999999")).isEmpty();
  }

  @Test void derivedStatesParse() {
    for (String[] f : new String[][] {{"get_boletos_200_paid.json", "Pago"}, {"get_boletos_200_canceled.json", "Baixado"},
        {"get_boletos_200_rejected.json", "Pagamento Rejeitado"}, {"get_boletos_200_awaiting.json", "Aguardando Crédito"}}) {
      server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(okJson(fixture(f[0]))));
      BoletoQueryItem item = client().find(creds(), "00000001").orElseThrow();
      assertThat(item.individual("00000001").orElseThrow().situacaoGeralBoleto()).as(f[0]).isEqualTo(f[1]);
    }
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(okJson(fixture("get_boletos_200_paid.json"))));
    assertThat(client().find(creds(), "00000001").orElseThrow().lastPayment()).isPresent();
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(okJson(fixture("get_boletos_200_canceled.json"))));
    assertThat(client().find(creds(), "00000001").orElseThrow().lastPayment()).isEmpty();
  }

  @Test void emptyListAnd404AreBothEmpty() {
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(okJson(fixture("get_boletos_200_empty.json"))));
    assertThat(client().find(creds(), "00000001")).isEmpty();
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/json").withBody(fixture("get_boletos_404.json"))));
    assertThat(client().find(creds(), "00000001")).isEmpty();
  }

  @Test void unauthorizedAndUnavailableAreErrors() {
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(aResponse().withStatus(401).withBody("{\"codigo\":\"401\",\"mensagem\":\"Unauthorized\"}")));
    assertThatThrownBy(() -> client().find(creds(), "00000001")).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNAUTHENTICATED));
    server.stubFor(get(urlPathEqualTo("/v2/boletos")).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> client().find(creds(), "00000001")).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNAVAILABLE));
  }
}
