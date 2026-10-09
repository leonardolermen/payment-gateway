package com.gateway.providers.cielo.auth;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.ProbeResult;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.providers.cielo.CieloHttp;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One GET on the query host for a sale that cannot exist: the Cielo authenticates before it looks,
 * so a 404 proves the credential and nothing was created. Both environments point at WireMock under
 * their own prefix, so a probe reaching the wrong one shows up as a request where none is expected
 * rather than a call to the real bank.
 */
class CieloCredentialProbeTest {
  private static final String NULL_SALE = "/1/sales/00000000-0000-0000-0000-000000000000";
  private static final String TEST_NULL_SALE = "/test/query" + NULL_SALE;
  private static final String LIVE_NULL_SALE = "/live/query" + NULL_SALE;
  private static final String CREDENTIAL =
      "{\"merchant_id\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\","
          + "\"merchant_key\":\"ABCDEFGHIJKLMNOPQRSTUVWXYZ01234567890123\"}";

  static WireMockServer server;
  CieloCredentialProbe probe;

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
    probe =
        new CieloCredentialProbe(
            new CieloHttp(Duration.ofSeconds(1), Duration.ofSeconds(1)),
            endpointsUnder("/live"),
            endpointsUnder("/test"));
  }

  @Test
  void aNotFoundMeansAuthenticatedAndOnlyTheTestQueryHostIsAsked() {
    server.stubFor(get(TEST_NULL_SALE).willReturn(status(404)));

    ProbeResult result = probe.probe(test(CREDENTIAL));

    assertThat(result).isEqualTo(new ProbeResult(true, "Conectado"));
    server.verify(
        1,
        getRequestedFor(urlEqualTo(TEST_NULL_SALE))
            .withHeader("MerchantId", equalTo("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")));
    assertThat(server.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
    assertThat(server.findAll(anyRequestedFor(urlMatching("/live/.*")))).isEmpty();
  }

  @Test
  void aLiveCredentialAsksOnlyTheLiveQueryHost() {
    server.stubFor(get(LIVE_NULL_SALE).willReturn(status(404).withBody("[]")));

    ProbeResult result = probe.probe(live(CREDENTIAL));

    assertThat(result).isEqualTo(new ProbeResult(true, "Conectado"));
    server.verify(1, getRequestedFor(urlEqualTo(LIVE_NULL_SALE)));
    assertThat(server.findAll(anyRequestedFor(urlMatching("/test/.*")))).isEmpty();
  }

  @Test
  void aJsonNotFoundIsTheCielosOwn() {
    server.stubFor(
        get(TEST_NULL_SALE)
            .willReturn(
                status(404)
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"Code\":\"404\"}")));

    assertThat(probe.probe(test(CREDENTIAL))).isEqualTo(new ProbeResult(true, "Conectado"));
  }

  @Test
  void anHtmlNotFoundIsSomeoneElseAnswering() {
    server.stubFor(
        get(TEST_NULL_SALE)
            .willReturn(
                status(404)
                    .withHeader("Content-Type", "text/html")
                    .withBody("<html><body>404 Not Found</body></html>")));

    assertThat(probe.probe(test(CREDENTIAL)))
        .isEqualTo(new ProbeResult(false, "O banco respondeu de forma inesperada"));
  }

  @Test
  void anUnauthorizedIsRefused() {
    server.stubFor(get(TEST_NULL_SALE).willReturn(status(401).withBody("secret-echo")));

    assertThat(probe.probe(test(CREDENTIAL)))
        .isEqualTo(new ProbeResult(false, "Credencial recusada pelo banco"));
  }

  @Test
  void aTimeoutIsNoAnswer() {
    server.stubFor(get(TEST_NULL_SALE).willReturn(status(404).withFixedDelay(1500)));

    assertThat(probe.probe(test(CREDENTIAL)))
        .isEqualTo(new ProbeResult(false, "O banco não respondeu"));
  }

  @Test
  void anythingElseIsUnexpected() {
    server.stubFor(get(TEST_NULL_SALE).willReturn(status(500)));

    assertThat(probe.probe(test(CREDENTIAL)))
        .isEqualTo(new ProbeResult(false, "O banco respondeu de forma inesperada"));
  }

  @Test
  void anIncompleteCredentialNamesTheFieldWithoutGoingToTheNetwork() {
    ProbeResult result =
        probe.probe(test("{\"merchant_id\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}"));

    assertThat(result).isEqualTo(new ProbeResult(false, "Credencial incompleta: merchant_key"));
    assertThat(server.findAll(anyRequestedFor(anyUrl()))).isEmpty();
  }

  /** An admin-seeded payload with a secret as an object: a verdict with the field, never a 500. */
  @Test
  void aSecretOfTheWrongTypeIsIncompleteWithoutGoingToTheNetwork() {
    ProbeResult result =
        probe.probe(
            test(
                "{\"merchant_id\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\",\"merchant_key\":[1]}"));

    assertThat(result).isEqualTo(new ProbeResult(false, "Credencial incompleta: merchant_key"));
    assertThat(server.findAll(anyRequestedFor(anyUrl()))).isEmpty();
  }

  private static CieloEndpoints endpointsUnder(String prefix) {
    return new CieloEndpoints(
        URI.create(server.baseUrl() + prefix + "/api"),
        URI.create(server.baseUrl() + prefix + "/query"));
  }

  private static ProviderCredentials test(String json) {
    return new ProviderCredentials(json.getBytes(StandardCharsets.UTF_8), ProviderEnvironment.TEST);
  }

  private static ProviderCredentials live(String json) {
    return new ProviderCredentials(json.getBytes(StandardCharsets.UTF_8), ProviderEnvironment.LIVE);
  }
}
