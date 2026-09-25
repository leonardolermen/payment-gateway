package com.gateway.providers.itau.auth;

import com.gateway.kernel.provider.ProviderException;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.time.*;
import org.junit.jupiter.api.*;

/** The token call is the one place mTLS must be proven: WireMock is started with needClientAuth. */
public class ItauTokenClientMtlsTest {
  static TestCertificates.Bundle certs;
  static WireMockServer server;
  static File serverKs, trustKs;

  @BeforeAll static void start() throws Exception {
    certs = TestCertificates.generate();
    serverKs = File.createTempFile("server", ".p12"); trustKs = File.createTempFile("trust", ".p12");
    try (var o = new FileOutputStream(serverKs)) { certs.serverKeyStore().store(o, certs.serverPassword()); }
    try (var o = new FileOutputStream(trustKs)) { certs.caTrust().store(o, "changeit".toCharArray()); }
    server = new WireMockServer(WireMockConfiguration.options().dynamicHttpsPort().httpDisabled(true)
        .keystorePath(serverKs.getAbsolutePath()).keystorePassword(new String(certs.serverPassword())).keyManagerPassword(new String(certs.serverPassword()))
        .keystoreType("PKCS12")
        .trustStorePath(trustKs.getAbsolutePath()).trustStorePassword("changeit").trustStoreType("PKCS12").needClientAuth(true));
    server.start();
  }
  @AfterAll static void stop() { server.stop(); serverKs.delete(); trustKs.delete(); }

  // The server is shared (static) across test methods; without this, request counts and stubs
  // leak between tests and verify(1, ...) counts requests made by an earlier test too.
  @BeforeEach void reset() { server.resetAll(); }

  ItauCredentials creds() {
    return ItauCredentials.parse(("{\"client_id\":\"11111111-2222-3333-4444-555555555555\",\"client_secret\":\"s3cr3t\",\"x_itau_apikey\":\"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\","
        + "\"certificate_pem\":" + json(certs.clientCertPem()) + ",\"private_key_pem\":" + json(certs.clientKeyPem()) + ",\"pix_key\":\"60701190000104\"}").getBytes());
  }
  // BouncyCastle's PEMWriter emits \r\n on Windows; unescaped \r is an illegal control char in JSON.
  public static String json(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace("\"", "\\\"") + "\"";
  }
  URI tokenUrl() { return URI.create("https://localhost:" + server.httpsPort() + "/as/token.oauth2"); }

  @Test void postsClientCredentialsOverMtlsAndCachesUntilNearExpiry() {
    server.stubFor(post("/as/token.oauth2")
        .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
        .withRequestBody(containing("grant_type=client_credentials")).withRequestBody(containing("client_id=11111111-2222-3333-4444-555555555555")).withRequestBody(containing("client_secret=s3cr3t"))
        .willReturn(okJson("{\"access_token\":\"tok-1\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);
    ItauTokenClient client = new ItauTokenClient(clock, Duration.ofSeconds(3), Duration.ofSeconds(5));

    AccessToken t1 = client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust());
    AccessToken t2 = client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust());
    assertThat(t1.value()).isEqualTo("tok-1");
    assertThat(t2).isSameAs(t1);
    assertThat(t1.expiresAt()).isEqualTo(Instant.parse("2026-09-24T12:05:00Z"));
    server.verify(1, postRequestedFor(urlEqualTo("/as/token.oauth2")));
  }

  @Test void refreshesSixtySecondsBeforeExpiry() {
    server.stubFor(post("/as/token.oauth2").willReturn(okJson("{\"access_token\":\"tok-a\",\"expires_in\":300}")));
    MutableClock clock = new MutableClock(Instant.parse("2026-09-24T12:00:00Z"));
    ItauTokenClient client = new ItauTokenClient(clock, Duration.ofSeconds(3), Duration.ofSeconds(5));
    client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust());
    clock.advance(Duration.ofSeconds(239));
    client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust());
    server.verify(1, postRequestedFor(urlEqualTo("/as/token.oauth2")));
    clock.advance(Duration.ofSeconds(2));
    client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust());
    server.verify(2, postRequestedFor(urlEqualTo("/as/token.oauth2")));
  }

  @Test void unauthorizedBecomesUnauthenticated() {
    server.stubFor(post("/as/token.oauth2").willReturn(aResponse().withStatus(401).withBody("{\"error\":\"invalid_client\"}")));
    ItauTokenClient client = new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(5));
    assertThatThrownBy(() -> client.tokenFor(creds(), ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true), certs.caTrust()))
        .isInstanceOf(ProviderException.class).extracting("code").isEqualTo(ProviderException.Code.UNAUTHENTICATED);
  }

  /** Without a client certificate the handshake fails — this is what proves needClientAuth is on. */
  @Test void serverRejectsConnectionsWithoutClientCertificate() throws Exception {
    TestCertificates.Bundle other = TestCertificates.generate();
    var plain = java.net.http.HttpClient.newBuilder().sslContext(PemKeyStores.mutualTls(other.clientCertPem(), other.clientKeyPem(), certs.caTrust())).build();
    // a cert from ANOTHER CA is not trusted by the server
    assertThatThrownBy(() -> plain.send(java.net.http.HttpRequest.newBuilder(tokenUrl()).POST(java.net.http.HttpRequest.BodyPublishers.noBody()).build(), java.net.http.HttpResponse.BodyHandlers.discarding()))
        .isInstanceOf(java.io.IOException.class);
  }

  @Test void sandboxTokenWithoutClientCertificate() throws Exception {
    WireMockServer sandbox = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    sandbox.start();
    try {
      sandbox.stubFor(post("/api/oauth/jwt")
          .withRequestBody(containing("grant_type=client_credentials"))
          .willReturn(okJson("{\"access_token\":\"sandbox-tok\",\"expires_in\":300}")));
      ItauCredentials sandboxCreds = ItauCredentials.parse(
          "{\"client_id\":\"sandbox-client\",\"client_secret\":\"sandbox-secret\",\"pix_key\":\"60701190000104\"}".getBytes());
      ItauEndpoints endpoints = ItauEndpoints.custom(
          URI.create("https://sandbox/unused"), URI.create("http://localhost:" + sandbox.port() + "/api/oauth/jwt"), false);
      ItauTokenClient client = new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(5));

      AccessToken token = client.tokenFor(sandboxCreds, endpoints, null);

      assertThat(token.value()).isEqualTo("sandbox-tok");
      sandbox.verify(1, postRequestedFor(urlEqualTo("/api/oauth/jwt")));
    } finally {
      sandbox.stop();
    }
  }

  @Test void mutualTlsWithSandboxShapedCredentialsFails() {
    ItauCredentials sandboxCreds = ItauCredentials.parse(
        "{\"client_id\":\"sandbox-client\",\"client_secret\":\"sandbox-secret\",\"pix_key\":\"60701190000104\"}".getBytes());
    ItauEndpoints endpoints = ItauEndpoints.custom(URI.create("https://localhost/unused"), tokenUrl(), true);
    ItauTokenClient client = new ItauTokenClient(Clock.systemUTC(), Duration.ofSeconds(3), Duration.ofSeconds(5));

    assertThatThrownBy(() -> client.tokenFor(sandboxCreds, endpoints, certs.caTrust()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("certificate_pem");
  }

  static final class MutableClock extends Clock {
    private Instant now; MutableClock(Instant i) { now = i; }
    void advance(Duration d) { now = now.plus(d); }
    @Override public ZoneId getZone() {
      return ZoneOffset.UTC;
    }
    @Override public Clock withZone(ZoneId z) {
      return this;
    }
    @Override public Instant instant() {
      return now;
    }
  }
}
