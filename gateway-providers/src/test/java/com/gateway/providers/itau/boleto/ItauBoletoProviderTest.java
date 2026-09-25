package com.gateway.providers.itau.boleto;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.address.Uf;
import com.gateway.kernel.address.ZipCode;
import com.gateway.kernel.party.Address;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.Payer;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.boleto.*;
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
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.*;

class ItauBoletoProviderTest {
  static WireMockServer server;
  static final byte[] CREDS = "{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\",\"beneficiary_id\":\"150000052061\",\"wallet_code\":\"109\",\"species_code\":\"01\"}".getBytes();
  static final byte[] PIX_ONLY = "{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\"}".getBytes();

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

  static ItauBoletoProvider provider() {
    String base = server.baseUrl();
    URI token = URI.create(base + "/api/oauth/jwt");
    ItauBoletoEndpoints test = new ItauBoletoEndpoints(
        ItauEndpoints.custom(URI.create(base + "/issue/v1"), token, false),
        ItauEndpoints.custom(URI.create(base + "/query/v2"), token, false),
        ItauEndpoints.custom(URI.create(base + "/instruction/v2"), token, false));
    return new ItauBoletoProvider(new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(3)), null, Duration.ofSeconds(5),
        ItauBoletoEndpoints.forEnvironment(ProviderEnvironment.LIVE), test);
  }

  static ProviderCredentials test(byte[] payload) {
    return new ProviderCredentials(payload, ProviderEnvironment.TEST);
  }

  static BoletoIssueRequest request() {
    return new BoletoIssueRequest("00000001", Money.brl(123456), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 30),
        new Payer(PersonName.of("João da Silva"), Document.of("12345678901"), new Address("Rua das Flores", "Centro", "São Paulo", Uf.of("SP"), ZipCode.of("01310100"))), "Pedido 42");
  }

  @Test void idIsItau() { assertThat(provider().id()).isEqualTo("ITAU"); }

  @Test void requireIssueCredentialsNamesTheMissingField() {
    provider().requireIssueCredentials(test(CREDS));
    assertThatThrownBy(() -> provider().requireIssueCredentials(test(PIX_ONLY))).isInstanceOfSatisfying(ProviderException.class, e -> {
      assertThat(e.code()).isEqualTo(ProviderException.Code.CREDENTIALS_INCOMPLETE);
      assertThat(e.providerType()).isEqualTo("beneficiary_id");
    });
    assertThat(server.findAll(postRequestedFor(urlMatching(".*")))).isEmpty();
  }

  @Test void issueMapsTheBanksAnswer() {
    server.stubFor(post("/issue/v1/boletos-pix").willReturn(okJson(fixture("post_boletos_pix_200.json"))));
    IssuedBoleto b = provider().issue(test(CREDS), request());
    assertThat(b.idBoletoIndividual()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
    assertThat(b.linhaDigitavel()).isEqualTo("34101234567890123456789012345678901234567890123");
    assertThat(b.codigoBarras()).isEqualTo("34191234567890123456789012345678901234567890");
    assertThat(b.paymentLimitDate()).isEqualTo(LocalDate.of(2027, 1, 31));
    assertThat(b.pixTxid()).isEqualTo("BL1234567890123456789012345678901");
    assertThat(b.pixCopiaECola()).startsWith("000201");
    assertThat(b.pixKey()).isEqualTo("12345678000190");
  }

  @Test void findMapsSituationPaymentAndIdentity() {
    server.stubFor(get(urlPathEqualTo("/query/v2/boletos")).withQueryParam("nosso_numero", equalTo("00000001")).willReturn(okJson(fixture("get_boletos_200_paid.json"))));
    BoletoStatus s = provider().find(test(CREDS), "00000001").orElseThrow();
    assertThat(s.situation()).isEqualTo(BoletoSituation.PAID);
    assertThat(s.paid()).isTrue();
    assertThat(s.paidAmount()).isEqualTo(Money.brl(210000));
    assertThat(s.paidAt()).isEqualTo(Instant.parse("2020-01-20T03:00:00Z"));
    assertThat(s.paidChannel()).isEqualTo("DÉBITO EM CONTA");
    assertThat(s.linhaDigitavel()).hasSize(47);
    assertThat(s.codigoBarras()).hasSize(44);
    assertThat(s.paymentLimitDate()).isEqualTo(LocalDate.of(2030, 8, 6));
    assertThat(s.pixCopiaECola()).isNull();
  }

  @Test void findOpenHasNoPaymentAndEmptyIsEmpty() {
    server.stubFor(get(urlPathEqualTo("/query/v2/boletos")).willReturn(okJson(fixture("get_boletos_200_awaiting.json"))));
    BoletoStatus s = provider().find(test(CREDS), "00000001").orElseThrow();
    assertThat(s.situation()).isEqualTo(BoletoSituation.AWAITING_CREDIT);
    assertThat(s.paidAmount()).isNull();
    server.stubFor(get(urlPathEqualTo("/query/v2/boletos")).willReturn(okJson(fixture("get_boletos_200_empty.json"))));
    assertThat(provider().find(test(CREDS), "00000001")).isEmpty();
  }

  @Test void cancelUsesTheCompositeId() {
    server.stubFor(patch(urlEqualTo("/instruction/v2/boletos/15000005206110900000001/baixa")).willReturn(aResponse().withStatus(204)));
    provider().cancel(test(CREDS), "00000001");
    server.verify(1, patchRequestedFor(urlEqualTo("/instruction/v2/boletos/15000005206110900000001/baixa")));
  }

  /** Issue OpenAPI, dados_qrcode.txid: BL + agência (4) + conta (7) + carteira (3) + nosso número (15) — the DAC is not part of it. */
  @Test void pixTxidFollowsTheBanksFormula() {
    // Brief's regex said {31} digits, but its own literal (and the comment above) is agência(4)+conta(7)+carteira(3)+nosso número(15) = 29;
    // {31} could never match a 29-digit string, so this is a typo in the brief fixed to the formula both sources agree on.
    assertThat(provider().pixTxidFor(test(CREDS), "00000001")).isEqualTo("BL" + "15000005206" + "109" + "000000000000001").matches("^BL[0-9]{29}$");
  }

  @Test void pixTxidRejectsANossoNumeroOver15Digits() {
    assertThatThrownBy(() -> provider().pixTxidFor(test(CREDS), "12345678901234567"))
        .isInstanceOfSatisfying(ProviderException.class, e -> {
          assertThat(e.code()).isEqualTo(ProviderException.Code.INVALID);
          assertThat(e.providerType()).isEqualTo("nosso_numero");
        });
    assertThat(server.findAll(anyRequestedFor(urlMatching(".*")))).isEmpty();
  }

  @Test void baixaIdRejectsANonDigitNossoNumero() {
    assertThatThrownBy(() -> provider().cancel(test(CREDS), "0000000A"))
        .isInstanceOfSatisfying(ProviderException.class, e -> {
          assertThat(e.code()).isEqualTo(ProviderException.Code.INVALID);
          assertThat(e.providerType()).isEqualTo("nosso_numero");
        });
    assertThat(server.findAll(anyRequestedFor(urlMatching(".*")))).isEmpty();
  }
}
