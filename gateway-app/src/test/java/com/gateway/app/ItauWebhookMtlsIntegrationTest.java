package com.gateway.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.providers.itau.TestCertificates;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.awaitility.Awaitility;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The inbound bank webhook over a real second Tomcat connector with client-certificate verification.
 * Certificates come from gateway-providers' TestCertificates (test-jar): the app trusts BANK's CA, and
 * OTHER is an unrelated CA whose client certificate must fail the handshake. The mTLS port is picked
 * by opening and closing a ServerSocket(0) before the context starts; the window for another process
 * to take it is the context startup, acceptable for a test.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"gateway.rate-limit.requests-per-minute=1000", "gateway.payments.jobs-poll-ms=200"})
@ActiveProfiles("test")
@Testcontainers
class ItauWebhookMtlsIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  static final WireMockServer ITAU = new WireMockServer(options().dynamicPort());
  static final TestCertificates.Bundle BANK;
  static final TestCertificates.Bundle OTHER;
  static final int MTLS_PORT;
  static final File SERVER_KEYSTORE;
  static final File TRUSTSTORE;

  static {
    try {
      ITAU.start();
      BANK = TestCertificates.generate();
      OTHER = TestCertificates.generate();
      try (ServerSocket s = new ServerSocket(0)) { MTLS_PORT = s.getLocalPort(); }
      SERVER_KEYSTORE = File.createTempFile("webhook-server", ".p12");
      TRUSTSTORE = File.createTempFile("webhook-trust", ".p12");
      SERVER_KEYSTORE.deleteOnExit();
      TRUSTSTORE.deleteOnExit();
      try (var o = new FileOutputStream(SERVER_KEYSTORE)) { BANK.serverKeyStore().store(o, BANK.serverPassword()); }
      try (var o = new FileOutputStream(TRUSTSTORE)) { BANK.caTrust().store(o, "changeit".toCharArray()); }
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry r) {
    r.add("gateway.providers.itau.test-api-base", ITAU::baseUrl);
    r.add("gateway.providers.itau.test-token-url", () -> ITAU.baseUrl() + "/api/oauth/jwt");
    r.add("gateway.providers.itau.test-mutual-tls", () -> "false");
    r.add("gateway.webhooks.mtls.port", () -> MTLS_PORT);
    r.add("gateway.webhooks.mtls.keystore", SERVER_KEYSTORE::getAbsolutePath);
    r.add("gateway.webhooks.mtls.keystore-password", () -> new String(BANK.serverPassword()));
    r.add("gateway.webhooks.mtls.truststore", TRUSTSTORE::getAbsolutePath);
    r.add("gateway.webhooks.mtls.truststore-password", () -> "changeit");
  }

  @BeforeAll
  static void stubs() {
    ITAU.stubFor(post(urlEqualTo("/api/oauth/jwt"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"access_token\":\"tok-123\",\"token_type\":\"Bearer\",\"expires_in\":300}")));
    ITAU.stubFor(put(urlMatching("/cob/[A-Za-z0-9]+"))
        .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(fixture("put_cob_201.json"))));
  }

  @AfterAll
  static void stop() { ITAU.stop(); }

  @LocalServerPort int port;

  private RestTestClient http() { return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build(); }

  private static String fixture(String name) {
    try (InputStream in = ItauWebhookMtlsIntegrationTest.class.getResourceAsStream("/itau/fixtures/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  record Merchant(String id, String token, String testKey) {}

  @SuppressWarnings("unchecked")
  private Merchant merchant() {
    String id = (String) http().post().uri("/v1/admin/merchants").header("X-Admin-Key", "test-admin").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("name", "Pix Store")).exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("id");
    String url = (String) http().get().uri("/v1/admin/merchants/" + id).header("X-Admin-Key", "test-admin").exchange()
        .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody().get("inbound_webhook_url");
    assertThat(url).startsWith("https://localhost:" + MTLS_PORT + "/v1/providers/itau/webhooks/");
    String token = url.substring(url.lastIndexOf('/') + 1);
    String key = (String) http().post().uri("/v1/admin/merchants/" + id + "/api-keys").header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON).body(Map.of("environment", "TEST")).exchange()
        .expectBody(Map.class).returnResult().getResponseBody().get("key");
    http().put().uri("/v1/admin/merchants/" + id + "/providers/ITAU/credentials")
        .header("X-Admin-Key", "test-admin").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("environment", "TEST",
            "payload", Map.of("client_id", "sandbox-client", "client_secret", "sandbox-secret", "pix_key", "a1f4102e-a446-4a57-bcce-6fa48899c1d1")))
        .exchange().expectStatus().is2xxSuccessful();
    return new Merchant(id, token, key);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> getJson(String apiKey, String uri) {
    return http().get().uri(uri).header("Authorization", "Bearer " + apiKey).exchange()
        .expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
  }

  private static String webhookFor(String txid) {
    return fixture("webhook_pix.json")
        .replace("7978c0c97ea847e78e8849634473c1f1", txid)
        .replace("\"110.00\"", "\"159.90\"")
        .replace("\"2020-01-01T00:00:00Z\"", "\"" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\"")
        .replaceAll("(?s),\\s*\"devolucoes\": \\[.*?\\]\\s*(?=})", "");
  }

  /** A client that trusts the test server and presents {@code clientCert} (or no certificate when null). */
  private static HttpClient client(TestCertificates.Bundle clientCert) throws Exception {
    TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(BANK.caTrust());
    SSLContext ctx = SSLContext.getInstance("TLS");
    if (clientCert == null) {
      ctx.init(null, tmf.getTrustManagers(), null);
    } else {
      KeyStore ks = KeyStore.getInstance("PKCS12");
      ks.load(null, null);
      Certificate cert = CertificateFactory.getInstance("X.509")
          .generateCertificate(new ByteArrayInputStream(clientCert.clientCertPem().getBytes(StandardCharsets.US_ASCII)));
      ks.setKeyEntry("client", privateKey(clientCert.clientKeyPem()), "pw".toCharArray(), new Certificate[] {cert});
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(ks, "pw".toCharArray());
      ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
    }
    return HttpClient.newBuilder().sslContext(ctx).connectTimeout(Duration.ofSeconds(5)).build();
  }

  private static PrivateKey privateKey(String pem) throws Exception {
    try (PEMParser p = new PEMParser(new StringReader(pem))) {
      Object o = p.readObject();
      JcaPEMKeyConverter conv = new JcaPEMKeyConverter();
      return o instanceof PEMKeyPair kp ? conv.getKeyPair(kp).getPrivate() : conv.getPrivateKey((PrivateKeyInfo) o);
    }
  }

  private static HttpResponse<String> postWebhook(HttpClient client, String url, String body) throws Exception {
    return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static String mtlsUrl(String path) { return "https://localhost:" + MTLS_PORT + path; }

  @Test
  @SuppressWarnings("unchecked")
  void webhookOverMtlsWithItauCaIsAcceptedAndProcessed() throws Exception {
    Merchant m = merchant();
    String paymentId = (String) http().post().uri("/v1/payments").header("Authorization", "Bearer " + m.testKey())
        .header("Idempotency-Key", "w1").contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("amount", 15990, "currency", "BRL", "method", "PIX", "reference", "order-9"))
        .exchange().expectStatus().isCreated().expectBody(Map.class).returnResult().getResponseBody().get("id");

    long start = System.nanoTime();
    HttpResponse<String> res = postWebhook(client(BANK), mtlsUrl("/v1/providers/itau/webhooks/" + m.token() + "/pix"), webhookFor(paymentId));
    Duration took = Duration.ofNanos(System.nanoTime() - start);
    assertThat(res.statusCode()).isEqualTo(202);
    assertThat(took).isLessThan(Duration.ofSeconds(2));

    Awaitility.await().atMost(Duration.ofSeconds(15))
        .until(() -> "COMPLETED".equals(getJson(m.testKey(), "/v1/payments/" + paymentId).get("status")));
  }

  @Test
  void theRegisteredUrlWithoutPixIsAcceptedToo() throws Exception {
    Merchant m = merchant();
    assertThat(postWebhook(client(BANK), mtlsUrl("/v1/providers/itau/webhooks/" + m.token()), "{\"pix\":[]}").statusCode()).isEqualTo(202);
  }

  @Test
  void webhookWithoutClientCertificateIsRefusedAtHandshake() {
    assertThatThrownBy(() -> postWebhook(client(null), mtlsUrl("/v1/providers/itau/webhooks/" + "0".repeat(26) + "/pix"), "{}"))
        .isInstanceOf(IOException.class);
  }

  @Test
  void webhookWithCertificateFromAnotherCaIsRefused() {
    assertThatThrownBy(() -> postWebhook(client(OTHER), mtlsUrl("/v1/providers/itau/webhooks/" + "0".repeat(26) + "/pix"), "{}"))
        .isInstanceOf(IOException.class);
  }

  @Test
  void webhookOnThePlainPortIs404() {
    Merchant m = merchant();
    http().post().uri("/v1/providers/itau/webhooks/" + m.token() + "/pix").contentType(MediaType.APPLICATION_JSON).body("{\"pix\":[]}")
        .exchange().expectStatus().isNotFound();
  }

  @Test
  void unknownTokenIs404() throws Exception {
    HttpResponse<String> res = postWebhook(client(BANK), mtlsUrl("/v1/providers/itau/webhooks/" + "0".repeat(26) + "/pix"), "{\"pix\":[]}");
    assertThat(res.statusCode()).isEqualTo(404);
    // The problem's "instance" echoes the caller's own path, which tells it nothing new; what must not
    // happen is a distinguishable answer (401/403/422) that would confirm a token's shape or existence.
    assertThat(res.body()).contains("urn:gateway:NOT_FOUND").doesNotContain("merchant");
  }

  @Test
  void apiRoutesOnTheMtlsPortAre403() throws Exception {
    Merchant m = merchant();
    HttpResponse<String> res = client(BANK).send(HttpRequest.newBuilder(URI.create(mtlsUrl("/v1/me")))
        .header("Authorization", "Bearer " + m.testKey()).GET().build(), HttpResponse.BodyHandlers.ofString());
    assertThat(res.statusCode()).isEqualTo(403);
  }
}
