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

/**
 * The probe only ever asks the STS for a token; every outcome is one of the fixed phrases. Both
 * environments point at WireMock under their own prefix, so a probe reaching the wrong one shows up
 * as a request where none is expected rather than a call to the real bank.
 */
class ItauCredentialProbeTest {
  private static final String SANDBOX =
      "{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\"}";
  private static final String TEST_TOKEN = "/test/api/oauth/jwt";
  private static final String LIVE_TOKEN = "/live/as/token.oauth2";

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
    probe = new ItauCredentialProbe(tokenClient(), null, liveAtWireMock(), testAtWireMock());
  }

  @Test
  void aTokenMeansConnectedAndOnlyTheTestStsIsAsked() {
    server.stubFor(
        post(TEST_TOKEN).willReturn(okJson("{\"access_token\":\"t\",\"expires_in\":300}")));

    ProbeResult result = probe.probe(test(SANDBOX));

    assertThat(result).isEqualTo(new ProbeResult(true, "Conectado"));
    server.verify(1, postRequestedFor(urlEqualTo(TEST_TOKEN)));
    assertThat(server.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
    assertThat(server.findAll(anyRequestedFor(urlMatching("/live/.*")))).isEmpty();
  }

  @Test
  void asksTheBankAgainInsteadOfTrustingTheCachedToken() {
    server.stubFor(
        post(TEST_TOKEN).willReturn(okJson("{\"access_token\":\"t\",\"expires_in\":300}")));
    probe.probe(test(SANDBOX));
    server.resetAll();
    server.stubFor(post(TEST_TOKEN).willReturn(status(401)));

    ProbeResult result = probe.probe(test(SANDBOX));

    assertThat(result).isEqualTo(new ProbeResult(false, "Credencial recusada pelo banco"));
  }

  @Test
  void aRejectedCredentialNeverCarriesTheBankBody() {
    server.stubFor(
        post(TEST_TOKEN)
            .willReturn(status(401).withBody("{\"error\":\"invalid_client secret-echo\"}")));

    ProbeResult result = probe.probe(test(SANDBOX));

    assertThat(result).isEqualTo(new ProbeResult(false, "Credencial recusada pelo banco"));
  }

  @Test
  void aBankErrorIsNoAnswer() {
    server.stubFor(post(TEST_TOKEN).willReturn(status(503)));

    assertThat(probe.probe(test(SANDBOX)))
        .isEqualTo(new ProbeResult(false, "O banco não respondeu"));
  }

  @Test
  void aTimeoutIsNoAnswer() {
    server.stubFor(post(TEST_TOKEN).willReturn(ok().withFixedDelay(1500)));

    assertThat(probe.probe(test(SANDBOX)))
        .isEqualTo(new ProbeResult(false, "O banco não respondeu"));
  }

  @Test
  void anUnexpectedStatusIsUnexpected() {
    server.stubFor(post(TEST_TOKEN).willReturn(status(302)));

    assertThat(probe.probe(test(SANDBOX)))
        .isEqualTo(new ProbeResult(false, "O banco respondeu de forma inesperada"));
  }

  @Test
  void aMalformedTokenBodyIsUnexpectedNotAnException() {
    server.stubFor(post(TEST_TOKEN).willReturn(ok("<html>not the STS</html>")));

    assertThat(probe.probe(test(SANDBOX)))
        .isEqualTo(new ProbeResult(false, "O banco respondeu de forma inesperada"));
  }

  @Test
  void anIncompleteCredentialNamesTheFieldWithoutGoingToTheNetwork() {
    ProbeResult missingSecret = probe.probe(test("{\"client_id\":\"c\",\"pix_key\":\"k\"}"));
    ProbeResult liveWithoutCertificate = probe.probe(live(SANDBOX));

    assertThat(missingSecret)
        .isEqualTo(new ProbeResult(false, "Credencial incompleta: client_secret"));
    assertThat(liveWithoutCertificate)
        .isEqualTo(new ProbeResult(false, "Credencial incompleta: certificate_pem"));
    assertThat(server.findAll(anyRequestedFor(anyUrl()))).isEmpty();
  }

  @Test
  void aBrokenKeyOnLiveIsAnInvalidCertificateWithoutGoingToTheNetwork() {
    String payload =
        "{\"client_id\":\"c\",\"client_secret\":\"s\",\"pix_key\":\"k\","
            + "\"x_itau_apikey\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\","
            + "\"certificate_pem\":\"-----BEGIN CERTIFICATE-----\\nAAAA\\n-----END CERTIFICATE-----\","
            + "\"private_key_pem\":\"-----BEGIN PRIVATE KEY-----\\nAAAA\\n-----END PRIVATE KEY-----\"}";

    ProbeResult result = probe.probe(live(payload));

    assertThat(result).isEqualTo(new ProbeResult(false, "Certificado ou chave privada inválidos"));
    assertThat(server.findAll(anyRequestedFor(anyUrl()))).isEmpty();
  }

  private static ItauTokenClient tokenClient() {
    return new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(1), Duration.ofSeconds(1));
  }

  private static ItauEndpoints testAtWireMock() {
    return ItauEndpoints.plain(
        URI.create(server.baseUrl() + "/test/v2"), URI.create(server.baseUrl() + TEST_TOKEN));
  }

  private static ItauEndpoints liveAtWireMock() {
    return ItauEndpoints.mutualTls(
        URI.create(server.baseUrl() + "/live/v2"), URI.create(server.baseUrl() + LIVE_TOKEN));
  }

  private static ProviderCredentials test(String json) {
    return new ProviderCredentials(json.getBytes(StandardCharsets.UTF_8), ProviderEnvironment.TEST);
  }

  private static ProviderCredentials live(String json) {
    return new ProviderCredentials(json.getBytes(StandardCharsets.UTF_8), ProviderEnvironment.LIVE);
  }
}
