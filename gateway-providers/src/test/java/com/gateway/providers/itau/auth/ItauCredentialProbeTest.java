package com.gateway.providers.itau.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.ProbeResult;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The probe only ever asks the STS for a token; every outcome is one of the fixed phrases. */
class ItauCredentialProbeTest {
  private static final String SANDBOX =
      "{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\"}";

  static WireMockServer server;
  ItauCredentialProbe probe;

  @BeforeAll
  static void start() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @BeforeEach
  void setUp() {
    server.resetAll();
    ItauEndpoints test =
        ItauEndpoints.plain(
            URI.create(server.baseUrl() + "/v2"), URI.create(server.baseUrl() + "/api/oauth/jwt"));
    ItauTokenClient tokens =
        new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(1), Duration.ofSeconds(1));
    probe =
        new ItauCredentialProbe(
            tokens, null, ItauEndpoints.forEnvironment(ProviderEnvironment.LIVE), test);
  }

  @Test
  void aTokenMeansConnected() {
    server.stubFor(
        post("/api/oauth/jwt").willReturn(okJson("{\"access_token\":\"t\",\"expires_in\":300}")));

    ProbeResult result = probe.probe(test(SANDBOX));

    assertThat(result).isEqualTo(new ProbeResult(true, "Conectado"));
    server.verify(1, postRequestedFor(urlEqualTo("/api/oauth/jwt")));
    assertThat(server.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
  }

  @Test
  void asksTheBankAgainInsteadOfTrustingTheCachedToken() {
    server.stubFor(
        post("/api/oauth/jwt").willReturn(okJson("{\"access_token\":\"t\",\"expires_in\":300}")));
    probe.probe(test(SANDBOX));
    server.resetAll();
    server.stubFor(post("/api/oauth/jwt").willReturn(status(401)));

    ProbeResult result = probe.probe(test(SANDBOX));

    assertThat(result).isEqualTo(new ProbeResult(false, "Credencial recusada pelo banco"));
  }

  @Test
  void aRejectedCredentialNeverCarriesTheBankBody() {
    server.stubFor(
        post("/api/oauth/jwt")
            .willReturn(status(401).withBody("{\"error\":\"invalid_client secret-echo\"}")));

    ProbeResult result = probe.probe(test(SANDBOX));

    assertThat(result).isEqualTo(new ProbeResult(false, "Credencial recusada pelo banco"));
  }

  @Test
  void aBankErrorIsNoAnswer() {
    server.stubFor(post("/api/oauth/jwt").willReturn(status(503)));

    assertThat(probe.probe(test(SANDBOX)))
        .isEqualTo(new ProbeResult(false, "O banco não respondeu"));
  }

  @Test
  void aTimeoutIsNoAnswer() {
    server.stubFor(post("/api/oauth/jwt").willReturn(ok().withFixedDelay(1500)));

    assertThat(probe.probe(test(SANDBOX)))
        .isEqualTo(new ProbeResult(false, "O banco não respondeu"));
  }

  @Test
  void anUnexpectedStatusIsUnexpected() {
    server.stubFor(post("/api/oauth/jwt").willReturn(status(302)));

    assertThat(probe.probe(test(SANDBOX)))
        .isEqualTo(new ProbeResult(false, "O banco respondeu de forma inesperada"));
  }

  @Test
  void anIncompleteCredentialNamesTheFieldWithoutGoingToTheNetwork() {
    ProbeResult missingSecret = probe.probe(test("{\"client_id\":\"c\",\"pix_key\":\"k\"}"));
    ProbeResult liveWithoutCertificate =
        probe.probe(
            new ProviderCredentials(
                SANDBOX.getBytes(StandardCharsets.UTF_8), ProviderEnvironment.LIVE));

    assertThat(missingSecret)
        .isEqualTo(new ProbeResult(false, "Credencial incompleta: client_secret"));
    assertThat(liveWithoutCertificate)
        .isEqualTo(new ProbeResult(false, "Credencial incompleta: certificate_pem"));
    assertThat(server.findAll(anyRequestedFor(anyUrl()))).isEmpty();
  }

  @Test
  void aBrokenKeyOnLiveIsAnInvalidCertificate() {
    ItauEndpoints liveAtWireMock =
        ItauEndpoints.mutualTls(
            URI.create(server.baseUrl() + "/v2"), URI.create(server.baseUrl() + "/as/token"));
    ItauTokenClient tokens =
        new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(1), Duration.ofSeconds(1));
    ItauCredentialProbe live =
        new ItauCredentialProbe(
            tokens, null, liveAtWireMock, ItauEndpoints.forEnvironment(ProviderEnvironment.TEST));
    String payload =
        "{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\","
            + "\"x_itau_apikey\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\","
            + "\"certificate_pem\":\"-----BEGIN CERTIFICATE-----\\nAAAA\\n-----END CERTIFICATE-----\","
            + "\"private_key_pem\":\"-----BEGIN PRIVATE KEY-----\\nAAAA\\n-----END PRIVATE KEY-----\"}";

    ProbeResult result =
        live.probe(
            new ProviderCredentials(
                payload.getBytes(StandardCharsets.UTF_8), ProviderEnvironment.LIVE));

    assertThat(result).isEqualTo(new ProbeResult(false, "Certificado ou chave privada inválidos"));
    assertThat(server.findAll(anyRequestedFor(anyUrl()))).isEmpty();
  }

  private static ProviderCredentials test(String json) {
    return new ProviderCredentials(json.getBytes(StandardCharsets.UTF_8), ProviderEnvironment.TEST);
  }
}
