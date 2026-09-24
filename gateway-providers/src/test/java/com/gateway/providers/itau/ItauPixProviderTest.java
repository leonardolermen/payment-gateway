package com.gateway.providers.itau;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.gateway.providers.itau.PixApiClientContractTest.E2E;
import static com.gateway.providers.itau.PixApiClientContractTest.TXID;
import static com.gateway.providers.itau.PixApiClientContractTest.fixture;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.*;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.*;

/** The mapping from Itaú's vocabulary to the gateway's, over the same fixtures as the contract test. */
class ItauPixProviderTest {
  static WireMockServer server;
  static ItauPixProvider provider;
  static final ProviderCredentials CREDS = new ProviderCredentials(
      "{\"client_id\":\"sandbox-client\",\"client_secret\":\"sandbox-secret\",\"pix_key\":\"60701190000104\"}".getBytes(StandardCharsets.UTF_8),
      ProviderEnvironment.TEST);

  @BeforeAll static void start() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    ItauEndpoints test = ItauEndpoints.custom(URI.create(server.baseUrl() + "/v2"), URI.create(server.baseUrl() + "/api/oauth/jwt"), false);
    // LIVE points nowhere reachable: a TEST credential that leaked to it would fail loudly.
    ItauEndpoints live = ItauEndpoints.custom(URI.create("https://live.invalid/v2"), URI.create("https://live.invalid/token"), true);
    provider = new ItauPixProvider(new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(5)), null,
        Duration.ofSeconds(5), Clock.systemUTC(), live, test);
  }
  @AfterAll static void stop() { server.stop(); }
  @BeforeEach void reset() {
    server.resetAll();
    server.stubFor(post("/api/oauth/jwt").willReturn(okJson("{\"access_token\":\"tok\",\"expires_in\":300}")));
  }

  @Test void idIsItau() { assertThat(provider.id()).isEqualTo("ITAU"); }

  @Test void createCharge() {
    server.stubFor(put("/v2/cob/" + TXID)
        .withRequestBody(matchingJsonPath("$.chave", equalTo("60701190000104")))
        .withRequestBody(matchingJsonPath("$.valor.original", equalTo("567.89")))
        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(fixture("put_cob_201.json"))));

    Charge c = provider.createCharge(CREDS, TXID, Money.brl(56789), 3600, null, null, null);

    assertThat(c.txid()).isEqualTo(TXID);
    assertThat(c.status()).isEqualTo(ChargeStatus.ACTIVE);
    assertThat(c.amount()).isEqualTo(Money.brl(56789));
    assertThat(c.pixCopiaECola()).startsWith("000201");
    assertThat(c.location()).isEqualTo("pix.example.com/pix/qr/v2/c8cff0b5-5b2f-4154-835f-14ede94a2afc");
    assertThat(c.createdAt()).isEqualTo(Instant.parse("2023-01-01T00:00:00Z"));
    assertThat(c.expiresInSeconds()).isEqualTo(3600);
    assertThat(c.received()).isEmpty();
  }

  @Test void findChargeCompletedCarriesThePayment() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(okJson(fixture("get_cob_200_completed.json"))));
    Charge c = provider.findCharge(CREDS, TXID).orElseThrow();
    assertThat(c.status()).isEqualTo(ChargeStatus.COMPLETED);
    ReceivedPix p = c.firstPix().orElseThrow();
    assertThat(p.endToEndId()).isEqualTo(E2E);
    assertThat(p.amount()).isEqualTo(Money.brl(56789));
    assertThat(p.paidAt()).isEqualTo(Instant.parse("2020-01-01T00:00:00Z"));
  }

  @Test void findChargeMissingIsEmpty() {
    server.stubFor(get("/v2/cob/" + TXID).willReturn(aResponse().withStatus(404).withBody(fixture("error_404_cob_nao_encontrado.json"))));
    assertThat(provider.findCharge(CREDS, TXID)).isEmpty();
  }

  @Test void cancelCharge() {
    server.stubFor(patch(urlEqualTo("/v2/cob/" + TXID)).withRequestBody(equalToJson(fixture("patch_cob_cancel_request.json")))
        .willReturn(okJson(fixture("get_cob_200_active.json").replace("\"ATIVA\"", "\"REMOVIDA_PELO_USUARIO_RECEBEDOR\""))));
    provider.cancelCharge(CREDS, TXID);
    server.verify(patchRequestedFor(urlEqualTo("/v2/cob/" + TXID)));
  }

  @Test void requestRefundIsProcessing() {
    server.stubFor(put("/v2/pix/" + E2E + "/devolucao/123456").withRequestBody(equalToJson(fixture("put_devolucao_request.json")))
        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(fixture("put_devolucao_201_processing.json"))));
    RefundResult r = provider.requestRefund(CREDS, new RefundRequest(E2E, "123456", Money.brl(10000)));
    assertThat(r.status()).isEqualTo(RefundStatus.PROCESSING);
    assertThat(r.refundId()).isEqualTo("123456");
    assertThat(r.settledAt()).isNull();
  }

  @Test void findRefundCompleted() {
    server.stubFor(get("/v2/pix/" + E2E + "/devolucao/123456").willReturn(okJson(fixture("get_devolucao_200_done.json"))));
    RefundResult r = provider.findRefund(CREDS, E2E, "123456").orElseThrow();
    assertThat(r.status()).isEqualTo(RefundStatus.COMPLETED);
    assertThat(r.amount()).isEqualTo(Money.brl(789));
    assertThat(r.requestedAt()).isEqualTo(Instant.parse("2020-09-11T15:25:59.411Z"));
    assertThat(r.settledAt()).isEqualTo(Instant.parse("2020-09-11T15:27:23.411Z"));
  }

  @Test void findRefundNotDoneCarriesTheReason() {
    server.stubFor(get("/v2/pix/" + E2E + "/devolucao/123456")
        .willReturn(okJson(fixture("get_devolucao_200_done.json").replace("\"DEVOLVIDO\"", "\"NAO_REALIZADO\""))));
    RefundResult r = provider.findRefund(CREDS, E2E, "123456").orElseThrow();
    assertThat(r.status()).isEqualTo(RefundStatus.FAILED);
    assertThat(r.reason()).isNotBlank();
  }

  @Test void listChargesMapsBothCobs() {
    server.stubFor(get(urlPathEqualTo("/v2/cob")).willReturn(okJson(fixture("get_cob_list_200.json"))));
    List<Charge> cs = provider.listCharges(CREDS, Instant.parse("2020-04-01T00:00:00Z"), Instant.parse("2020-04-02T10:00:00Z"));
    assertThat(cs).extracting(Charge::status).containsExactly(ChargeStatus.ACTIVE, ChargeStatus.COMPLETED);
    assertThat(cs.get(1).firstPix().orElseThrow().amount()).isEqualTo(Money.brl(11000));
    server.verify(1, getRequestedFor(urlPathEqualTo("/v2/cob")));
  }

  @Test void listChargesFollowsPages() {
    String page = fixture("get_cob_list_200.json").replace("\"quantidadeDePaginas\": 1", "\"quantidadeDePaginas\": 2");
    server.stubFor(get(urlPathEqualTo("/v2/cob")).willReturn(okJson(page)));
    List<Charge> cs = provider.listCharges(CREDS, Instant.parse("2020-04-01T00:00:00Z"), Instant.parse("2020-04-02T10:00:00Z"));
    assertThat(cs).hasSize(4);
    server.verify(getRequestedFor(urlPathEqualTo("/v2/cob")).withQueryParam("paginacao.paginaAtual", equalTo("1")));
  }

  @Test void bothRemovedSpellingsMap() {
    assertThat(ItauPixProvider.toStatus("REMOVIDA_PELO_USUARIO_RECEBEDOR")).isEqualTo(ChargeStatus.REMOVED_BY_MERCHANT);
    assertThat(ItauPixProvider.toStatus("REMOVIDO_PELO_USUARIO_RECEBEDOR")).isEqualTo(ChargeStatus.REMOVED_BY_MERCHANT);
    assertThat(ItauPixProvider.toStatus("REMOVIDA_PELO_PSP")).isEqualTo(ChargeStatus.REMOVED_BY_PSP);
    assertThat(ItauPixProvider.toStatus("REMOVIDO_PELO_PSP")).isEqualTo(ChargeStatus.REMOVED_BY_PSP);
    assertThatThrownBy(() -> ItauPixProvider.toStatus("SOMETHING")).isInstanceOf(ProviderException.class);
  }

  @Test void parseWebhook() {
    ProviderWebhookEvent ev = provider.parseWebhook(fixture("webhook_pix.json").getBytes(StandardCharsets.UTF_8));
    assertThat(ev.received()).singleElement().satisfies(p -> {
      assertThat(p.endToEndId()).isEqualTo(E2E);
      assertThat(p.amount()).isEqualTo(Money.brl(11000));
    });
    assertThat(ev.txidByEndToEndId()).containsEntry(E2E, TXID);
    assertThat(ev.refundUpdates()).singleElement().satisfies(r -> {
      assertThat(r.status()).isEqualTo(RefundStatus.COMPLETED);
      assertThat(r.refundId()).isEqualTo("123ABC");
    });
  }

  @Test void parseWebhookWithNullFields() {
    ProviderWebhookEvent ev = provider.parseWebhook(fixture("webhook_pix_null_fields.json").getBytes(StandardCharsets.UTF_8));
    assertThat(ev.received()).hasSize(1);
    assertThat(ev.refundUpdates()).isEmpty();
  }

  @Test void parseWebhookWithoutPixIsRejected() {
    assertThatThrownBy(() -> provider.parseWebhook("{}".getBytes())).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> provider.parseWebhook("not json".getBytes())).isInstanceOf(IllegalArgumentException.class);
  }
}
