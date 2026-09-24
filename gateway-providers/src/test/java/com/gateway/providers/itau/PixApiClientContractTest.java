package com.gateway.providers.itau;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.providers.itau.dto.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;

/**
 * The client against Itaú's contract, replayed from the bank's own examples. Plain HTTP with
 * sandbox-shaped credentials: mTLS was proven in ItauTokenClientMtlsTest; the one production-shaped
 * test below reuses that HTTPS setup only to prove the apikey header rides along.
 */
class PixApiClientContractTest {
  static final String TXID = "7978c0c97ea847e78e8849634473c1f1";
  static final String E2E = "E12345678202009091221kkkkkkkkkkk";
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
    try { return Files.readString(Path.of("src/test/resources/itau/fixtures/" + f), StandardCharsets.UTF_8); }
    catch (Exception e) { throw new IllegalStateException(e); }
  }

  /** Sandbox shape (no certificate) with the optional apikey, so the header contract is checked. */
  static ItauCredentials creds() {
    return ItauCredentials.parse(("{\"client_id\":\"sandbox-client\",\"client_secret\":\"sandbox-secret\",\"x_itau_apikey\":\"" + APIKEY
        + "\",\"pix_key\":\"60701190000104\"}").getBytes());
  }

  static ItauEndpoints endpoints() {
    return ItauEndpoints.custom(URI.create(server.baseUrl() + "/v2"), URI.create(server.baseUrl() + "/api/oauth/jwt"), false);
  }

  static PixApiClient client(Duration readTimeout) {
    return new PixApiClient(new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(3)), endpoints(), null, readTimeout);
  }
  static PixApiClient client() { return client(Duration.ofSeconds(5)); }

  @Test void putCobSendsHeadersAndBodyAndParses201() {
    server.stubFor(put("/v2/cob/" + TXID)
        .withHeader("Authorization", equalTo("Bearer tok"))
        .withHeader("x-itau-apikey", equalTo(APIKEY))
        .withHeader("x-itau-correlationID", matching(UUID_RE))
        .withHeader("Content-Type", containing("application/json"))
        // ignoreExtraElements: we also send calendario.expiracao, which the minimal example omits
        .withRequestBody(equalToJson(fixture("put_cob_request_min.json"), false, true))
        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(fixture("put_cob_201.json"))));

    CobResponse r = client().putCob(creds(), TXID, CobRequest.forCharge(Money.brl(12345), 3600, "60701190000104", null, null, null));

    assertThat(r.status()).isEqualTo("ATIVA");
    assertThat(r.pixCopiaECola()).isNotBlank();
    assertThat(r.valor().original()).isEqualTo("567.89");
  }

  @Test void correlationIdComesFromMdcWhenItIsAUuid() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(okJson(fixture("get_cob_200_active.json"))));
    MDC.put("correlationId", "01234567-89ab-cdef-0123-456789abcdef");
    try { client().getCob(creds(), TXID); } finally { MDC.remove("correlationId"); }
    server.verify(getRequestedFor(urlEqualTo("/v2/cob/" + TXID)).withHeader("x-itau-correlationID", equalTo("01234567-89ab-cdef-0123-456789abcdef")));
  }

  @Test void nonUuidCorrelationIdIsReplaced() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(okJson(fixture("get_cob_200_active.json"))));
    MDC.put("correlationId", "req-42");
    try { client().getCob(creds(), TXID); } finally { MDC.remove("correlationId"); }
    server.verify(getRequestedFor(urlEqualTo("/v2/cob/" + TXID)).withHeader("x-itau-correlationID", matching(UUID_RE)));
  }

  @Test void sandboxWithoutApiKeyOmitsTheHeader() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(okJson(fixture("get_cob_200_active.json"))));
    ItauCredentials noKey = ItauCredentials.parse("{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"60701190000104\"}".getBytes());
    client().getCob(noKey, TXID);
    server.verify(getRequestedFor(urlEqualTo("/v2/cob/" + TXID)).withoutHeader("x-itau-apikey").withHeader("Authorization", equalTo("Bearer tok")));
  }

  @Test void getCobActive() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(okJson(fixture("get_cob_200_active.json"))));
    CobResponse r = client().getCob(creds(), TXID).orElseThrow();
    assertThat(r.status()).isEqualTo("ATIVA");
    assertThat(r.pix()).isNullOrEmpty();
  }

  @Test void getCobCompletedCarriesPix() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(okJson(fixture("get_cob_200_completed.json"))));
    CobResponse r = client().getCob(creds(), TXID).orElseThrow();
    assertThat(r.status()).isEqualTo("CONCLUIDA");
    assertThat(r.pix()).singleElement().satisfies(p -> {
      assertThat(p.endToEndId()).isEqualTo(E2E);
      assertThat(p.valor()).isEqualTo("567.89");
      assertThat(p.horario()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
    });
  }

  @Test void getCob404WithPixBodyIsEmpty() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(aResponse().withStatus(404).withHeader("Content-Type", "application/problem+json")
        .withBody(fixture("error_404_cob_nao_encontrado.json"))));
    assertThat(client().getCob(creds(), TXID)).isEmpty();
  }

  /** A wrong base URL or a proxy page must not read as "the charge does not exist". */
  @Test void getCob404WithoutPixBodyIsAnError() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(aResponse().withStatus(404).withHeader("Content-Type", "text/html")
        .withBody("<html><body>Not Found</body></html>")));
    assertThatThrownBy(() -> client().getCob(creds(), TXID)).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.UNKNOWN);
      assertThat(e.httpStatus()).isEqualTo(404);
      assertThat(e.getMessage()).contains("Not Found");
    });
    server.stubFor(get("/v2/cob/" + TXID).willReturn(aResponse().withStatus(404)));
    assertThatThrownBy(() -> client().getCob(creds(), TXID)).isInstanceOfSatisfying(ProviderException.class,
        e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNKNOWN));
  }

  @Test void malformed2xxBodyIsUnknown() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(okJson("{not json")));
    assertThatThrownBy(() -> client().getCob(creds(), TXID)).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.UNKNOWN);
      assertThat(e.getMessage()).isEqualTo("unreadable provider response");
    });
  }

  @Test void problemWithoutTitleHasNoNullInTheMessage() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(aResponse().withStatus(400)
        .withBody("{\"type\":\"https://pix.bcb.gov.br/api/v2/error/CobConsultaInvalida\",\"detail\":\"txid invalido\"}")));
    assertThatThrownBy(() -> client().getCob(creds(), TXID)).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.INVALID);
      assertThat(e.getMessage()).isEqualTo("txid invalido");
    });
  }

  @Test void patchCobCancelSendsStatus() {
    server.stubFor(patch(urlEqualTo("/v2/cob/" + TXID)).withRequestBody(equalToJson(fixture("patch_cob_cancel_request.json")))
        .willReturn(okJson(fixture("get_cob_200_active.json").replace("\"ATIVA\"", "\"REMOVIDA_PELO_USUARIO_RECEBEDOR\""))));
    CobResponse r = client().patchCob(creds(), TXID, Map.of("status", "REMOVIDA_PELO_USUARIO_RECEBEDOR"));
    assertThat(r.status()).isEqualTo("REMOVIDA_PELO_USUARIO_RECEBEDOR");
  }

  @Test void patchCobOnConcludedIsInvalid() {
    server.stubFor(patch(urlEqualTo("/v2/cob/" + TXID)).willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/problem+json")
        .withBody(fixture("error_400_cob_operacao_invalida.json"))));
    assertThatThrownBy(() -> client().patchCob(creds(), TXID, Map.of("status", "REMOVIDA_PELO_USUARIO_RECEBEDOR")))
        .isInstanceOfSatisfying(ProviderException.class, e -> {
          assertThat(e.code()).isEqualTo(ProviderException.Code.INVALID);
          assertThat(e.httpStatus()).isEqualTo(400);
          assertThat(e.providerType()).contains("CobOperacaoInvalida");
        });
  }

  @Test void putDevolucaoReturnsProcessing() {
    server.stubFor(put("/v2/pix/" + E2E + "/devolucao/123456").withRequestBody(equalToJson(fixture("put_devolucao_request.json")))
        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(fixture("put_devolucao_201_processing.json"))));
    DevolucaoResponse r = client().putDevolucao(creds(), E2E, "123456", new DevolucaoRequest("100.00"));
    assertThat(r.status()).isEqualTo("EM_PROCESSAMENTO");
    assertThat(r.horario().liquidacao()).isNull();
  }

  @Test void getDevolucaoDone() {
    server.stubFor(get("/v2/pix/" + E2E + "/devolucao/123456").willReturn(okJson(fixture("get_devolucao_200_done.json"))));
    DevolucaoResponse r = client().getDevolucao(creds(), E2E, "123456").orElseThrow();
    assertThat(r.status()).isEqualTo("DEVOLVIDO");
    assertThat(r.horario().liquidacao()).isEqualTo(Instant.parse("2020-09-11T15:27:23.411Z"));
  }

  @Test void listCobWindowAndPagination() {
    server.stubFor(get(urlPathEqualTo("/v2/cob"))
        .withQueryParam("inicio", equalTo("2020-04-01T00:00:00Z"))
        .withQueryParam("fim", equalTo("2020-04-02T10:00:00Z"))
        .withQueryParam("paginacao.paginaAtual", equalTo("0"))
        .withQueryParam("paginacao.itensPorPagina", equalTo("100"))
        .willReturn(okJson(fixture("get_cob_list_200.json"))));
    CobList l = client().listCob(creds(), Instant.parse("2020-04-01T00:00:00Z"), Instant.parse("2020-04-02T10:00:00Z"), 0, 100);
    assertThat(l.cobs()).hasSize(2);
    assertThat(l.parametros().paginacao().quantidadeDePaginas()).isEqualTo(1);
  }

  @Test void unauthorizedEvictsTheTokenAndMaps401() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(aResponse().withStatus(401)));
    PixApiClient c = client();
    assertThatThrownBy(() -> c.getCob(creds(), TXID)).isInstanceOfSatisfying(ProviderException.class,
        e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNAUTHENTICATED));
    server.verify(1, postRequestedFor(urlEqualTo("/api/oauth/jwt")));
    assertThatThrownBy(() -> c.getCob(creds(), TXID)).isInstanceOf(ProviderException.class);
    server.verify(2, postRequestedFor(urlEqualTo("/api/oauth/jwt")));
  }

  @Test void socketTimeoutIsTimeout() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(okJson(fixture("get_cob_200_active.json")).withFixedDelay(3000)));
    assertThatThrownBy(() -> client(Duration.ofSeconds(1)).getCob(creds(), TXID)).isInstanceOfSatisfying(ProviderException.class,
        e -> assertThat(e.code()).isEqualTo(ProviderException.Code.TIMEOUT));
  }

  @Test void serviceUnavailableIsUnavailable() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(aResponse().withStatus(503)));
    assertThatThrownBy(() -> client().getCob(creds(), TXID)).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.UNAVAILABLE);
      assertThat(e.httpStatus()).isEqualTo(503);
    });
  }

  /** Production shape: mTLS (same WireMock setup as ItauTokenClientMtlsTest) and the apikey header. */
  @Test void productionShapeSendsApiKeyHeader() throws Exception {
    TestCertificates.Bundle certs = TestCertificates.generate();
    File serverKs = File.createTempFile("server", ".p12"), trustKs = File.createTempFile("trust", ".p12");
    try (var o = new FileOutputStream(serverKs)) { certs.serverKeyStore().store(o, certs.serverPassword()); }
    try (var o = new FileOutputStream(trustKs)) { certs.caTrust().store(o, "changeit".toCharArray()); }
    WireMockServer https = new WireMockServer(WireMockConfiguration.options().dynamicHttpsPort().httpDisabled(true)
        .keystorePath(serverKs.getAbsolutePath()).keystorePassword(new String(certs.serverPassword())).keyManagerPassword(new String(certs.serverPassword()))
        .keystoreType("PKCS12")
        .trustStorePath(trustKs.getAbsolutePath()).trustStorePassword("changeit").trustStoreType("PKCS12").needClientAuth(true));
    https.start();
    try {
      https.stubFor(post("/as/token.oauth2").willReturn(okJson("{\"access_token\":\"prod-tok\",\"expires_in\":300}")));
      https.stubFor(get("/v2/cob/" + TXID).willReturn(okJson(fixture("get_cob_200_active.json"))));
      ItauCredentials full = ItauCredentials.parse(("{\"client_id\":\"11111111-2222-3333-4444-555555555555\",\"client_secret\":\"s3cr3t\",\"x_itau_apikey\":\"" + APIKEY + "\","
          + "\"certificate_pem\":" + ItauTokenClientMtlsTest.json(certs.clientCertPem()) + ",\"private_key_pem\":" + ItauTokenClientMtlsTest.json(certs.clientKeyPem())
          + ",\"pix_key\":\"60701190000104\"}").getBytes());
      String base = "https://localhost:" + https.httpsPort();
      ItauEndpoints e = ItauEndpoints.custom(URI.create(base + "/v2"), URI.create(base + "/as/token.oauth2"), true);
      PixApiClient c = new PixApiClient(new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(5)), e, certs.caTrust(),
          Duration.ofSeconds(5));

      assertThat(c.getCob(full, TXID)).isPresent();

      https.verify(getRequestedFor(urlEqualTo("/v2/cob/" + TXID))
          .withHeader("x-itau-apikey", equalTo(APIKEY)).withHeader("Authorization", equalTo("Bearer prod-tok")));
    } finally {
      https.stop();
      serverKs.delete();
      trustKs.delete();
    }
  }
}
