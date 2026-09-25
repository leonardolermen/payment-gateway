package com.gateway.providers.itau.boleto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;
import com.gateway.kernel.party.Address;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.kernel.party.Payer;
import com.gateway.providers.itau.auth.ItauCredentials;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.dto.BoletoPixRequest;
import com.gateway.providers.itau.boleto.dto.BoletoPixResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.*;

/** POST /boletos-pix against the bank's own examples. Plain HTTP, sandbox-shaped credentials (mTLS is proven in ItauTokenClientMtlsTest). */
class BoletoPixApiClientContractTest {
  static final String UUID_RE = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
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
    return ItauCredentials.parse(("{\"client_id\":\"sandbox-client\",\"client_secret\":\"sandbox-secret\",\"x_itau_apikey\":\"" + APIKEY
        + "\",\"pix_key\":\"60701190000104\",\"beneficiary_id\":\"150000052061\",\"wallet_code\":\"109\",\"species_code\":\"01\"}").getBytes());
  }

  static BoletoPixApiClient client(Duration readTimeout) {
    ItauEndpoints e = ItauEndpoints.custom(URI.create(server.baseUrl() + "/v1"), URI.create(server.baseUrl() + "/api/oauth/jwt"), false);
    return new BoletoPixApiClient(new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(3)), e, null, readTimeout);
  }
  static BoletoPixApiClient client() {
    return client(Duration.ofSeconds(5));
  }

  /** The bank's minimal example, built from our request: same payer, same number, same amount and dates. */
  static BoletoPixRequest exampleRequest() {
    return BoletoPixRequest.forIssue(new BoletoIssueRequest("12345678", Money.brl(123456), LocalDate.of(2026, 12, 31), null,
        new Payer(PersonName.of("João da Silva"), Document.of("12345678901"), new Address("Rua das Flores", "Centro", "São Paulo", Uf.of("SP"), ZipCode.of("01310100"))), null), creds());
  }

  @Test void postSendsHeadersAndTheBanksMinimalBodyAndParses200() {
    server.stubFor(post("/v1/boletos-pix")
        .withHeader("Authorization", equalTo("Bearer tok"))
        .withHeader("x-itau-apikey", equalTo(APIKEY))
        .withHeader("x-itau-correlationID", matching(UUID_RE))
        .withHeader("Content-Type", containing("application/json"))
        .withRequestBody(equalToJson(fixture("post_boletos_pix_request_min.json"), false, true))
        .willReturn(okJson(fixture("post_boletos_pix_200.json"))));

    BoletoPixResponse r = client().post(creds(), exampleRequest());

    BoletoPixResponse.Individual i = r.first();
    assertThat(i.idBoletoIndividual()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    assertThat(i.numeroLinhaDigitavel()).hasSize(47);
    assertThat(i.codigoBarras()).hasSize(44);
    assertThat(i.dataLimitePagamento()).isEqualTo("2027-01-31");
    assertThat(r.dadosQrcode().txid()).isEqualTo("BL1234567890123456789012345678901");
    assertThat(r.dadosQrcode().emv()).startsWith("000201");
    assertThat(r.dadosQrcode().chave()).isEqualTo("12345678000190");
  }

  @Test void sanitizedTextsAreWhatGoesOnTheWire() {
    server.stubFor(post("/v1/boletos-pix").willReturn(okJson(fixture("post_boletos_pix_200.json"))));
    BoletoPixRequest dirty = BoletoPixRequest.forIssue(new BoletoIssueRequest("12345678", Money.brl(100), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 30),
        new Payer(PersonName.of("Ana & Cia (Ltda)"), Document.of("12345678000190"), new Address("Av. Paulista, 1000 / 10", "Bela Vista <x>", "São Paulo", Uf.of("SP"), ZipCode.of("01310100"))), "javascript Pedido #42"), creds());
    client().post(creds(), dirty);
    server.verify(postRequestedFor(urlEqualTo("/v1/boletos-pix"))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.pessoa.nome_pessoa", equalTo("Ana Cia Ltda")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.pessoa.tipo_pessoa.codigo_tipo_pessoa", equalTo("J")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.pessoa.tipo_pessoa.numero_cadastro_nacional_pessoa_juridica", equalTo("12345678000190")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.endereco.nome_logradouro", equalTo("Av. Paulista, 1000 10")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.pagador.endereco.nome_bairro", equalTo("Bela Vista x")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.dados_individuais_boleto[0].data_limite_pagamento", equalTo("2027-01-30")))
        .withRequestBody(matchingJsonPath("$.dado_boleto.dados_individuais_boleto[0].texto_uso_beneficiario", equalTo("Pedido 42")))
        .withRequestBody(matchingJsonPath("$.etapa_processo_boleto", equalTo("efetivacao"))));
  }

  @Test void accepted202IsATimeout() {
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(202).withHeader("Content-Type", "application/json").withBody(fixture("post_boletos_pix_202.json"))));
    assertThatThrownBy(() -> client().post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.TIMEOUT);
      assertThat(e.httpStatus()).isEqualTo(202);
    });
  }

  @Test void businessRejectionIsDeclinedWithFieldsButNeverValues() {
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(422).withHeader("Content-Type", "application/json").withBody(fixture("post_boletos_pix_422.json"))));
    assertThatThrownBy(() -> client().post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.DECLINED);
      assertThat(e.httpStatus()).isEqualTo(422);
      assertThat(e.providerType()).isEqualTo("422");
      assertThat(e.getMessage()).contains("data_vencimento").contains("Vencimento menor").doesNotContain("2024-01-01");
    });
  }

  @Test void validation400IsDeclined() {
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(400).withBody("{\"codigo\":\"400\",\"mensagem\":\"Erro na validação de Campos\",\"campos\":[{\"campo\":\"data.dado_boleto.tipo_boleto\",\"mensagem\":\"Tipo de boleto inválido\"}]}")));
    assertThatThrownBy(() -> client().post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.DECLINED));
  }

  @Test void unauthorizedEvictsTheTokenAndIsUnauthenticated() {
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(401)));
    BoletoPixApiClient c = client();
    assertThatThrownBy(() -> c.post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNAUTHENTICATED));
    assertThatThrownBy(() -> c.post(creds(), exampleRequest())).isInstanceOf(ProviderException.class);
    server.verify(2, postRequestedFor(urlEqualTo("/api/oauth/jwt")));
  }

  @Test void gatewayTimeoutAndUnavailable() {
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(504)));
    assertThatThrownBy(() -> client().post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.TIMEOUT));
    server.stubFor(post("/v1/boletos-pix").willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> client().post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNAVAILABLE));
  }

  @Test void socketTimeoutIsTimeout() {
    server.stubFor(post("/v1/boletos-pix").willReturn(okJson(fixture("post_boletos_pix_200.json")).withFixedDelay(3000)));
    assertThatThrownBy(() -> client(Duration.ofSeconds(1)).post(creds(), exampleRequest())).isInstanceOfSatisfying(ProviderException.class, e -> assertThat(e.code()).isEqualTo(ProviderException.Code.TIMEOUT));
  }

  @Test void sandboxWithoutApiKeyOmitsTheHeader() {
    server.stubFor(post("/v1/boletos-pix").willReturn(okJson(fixture("post_boletos_pix_200.json"))));
    ItauCredentials noKey = ItauCredentials.parse("{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\"}".getBytes());
    client().post(noKey, BoletoPixRequest.forIssue(new BoletoIssueRequest("12345678", Money.brl(100), LocalDate.of(2026, 12, 31), null,
        new Payer(PersonName.of("João da Silva"), Document.of("12345678901"), new Address("Rua das Flores", "Centro", "São Paulo", Uf.of("SP"), ZipCode.of("01310100"))), null), noKey));
    server.verify(postRequestedFor(urlEqualTo("/v1/boletos-pix")).withoutHeader("x-itau-apikey").withHeader("Authorization", equalTo("Bearer tok")));
  }
}
