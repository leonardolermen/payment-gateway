package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.apikey.ApiKeyService;
import com.gateway.merchants.credential.Provider;
import com.gateway.merchants.credential.ProviderCredentialService;
import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.session.SessionService;
import com.gateway.merchants.user.EmailAddress;
import com.gateway.merchants.user.Role;
import com.gateway.merchants.user.User;
import com.gateway.merchants.user.UserService;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The owner's own provider configuration: credentials in, state (never a secret) out, the Cielo
 * notification key, and the gates — owner only, session only, environment from the header.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "gateway.rate-limit.requests-per-minute=1000")
@ActiveProfiles("test")
@Testcontainers
@SuppressWarnings({"rawtypes", "unchecked"})
class ProvidersApiIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  private static final String PROVIDERS = "/v1/merchant/providers";
  private static final String ITAU_CREDENTIALS = PROVIDERS + "/ITAU/credentials";
  private static final String NOTIFICATION_KEY = PROVIDERS + "/CIELO/notification-key";

  @LocalServerPort int port;

  @Autowired MerchantService merchants;
  @Autowired UserService users;
  @Autowired SessionService sessions;
  @Autowired ApiKeyService apiKeys;
  @Autowired ProviderCredentialService credentials;

  record Logged(String access, User user, Merchant store) {}

  @Test
  void anOwnerStoresItauTestCredentialsAndTheGetShowsStateNotSecrets() {
    Logged owner = user(Role.OWNER);

    EntityExchangeResult<String> stored =
        put(owner.access(), ITAU_CREDENTIALS, null, Map.of("payload", itauSandbox()));
    assertThat(stored.getStatus().value()).isEqualTo(204);

    Map body = get(owner.access(), null);
    assertThat(body.get("environment")).isEqualTo("TEST");
    assertThat(body).containsKey("inbound_webhook_url");

    Map itau = providerIn(body, "ITAU");
    assertThat(itau.get("methods")).isEqualTo(List.of("PIX", "BOLECODE"));
    assertThat(itau.get("configured")).isEqualTo(true);
    assertThat(itau.get("updated_at")).isNotNull();
    assertThat((String) itau.get("fingerprint")).hasSize(8).matches("[0-9a-f]{8}");
    assertThat((Map) itau.get("secrets_set"))
        .containsEntry("client_secret", true)
        .containsEntry("x_itau_apikey", false)
        .containsEntry("private_key_pem", false);
    assertThat(itau.get("last_test")).isNull();
    assertThat(itau).containsEntry("notification_key_set", null);

    Map cielo = providerIn(body, "CIELO");
    assertThat(cielo.get("methods")).isEqualTo(List.of("CARD"));
    assertThat(cielo.get("configured")).isEqualTo(false);
    assertThat(cielo.get("fingerprint")).isNull();
    assertThat((Map) cielo.get("fields")).isEmpty();
    assertThat(cielo.get("notification_key_set")).isEqualTo(false);
  }

  @Test
  void theGetCarriesThePublicFields() {
    Logged owner = user(Role.OWNER);
    put(owner.access(), ITAU_CREDENTIALS, null, Map.of("payload", itauSandbox()));

    Map fields = (Map) providerIn(get(owner.access(), null), "ITAU").get("fields");

    assertThat(fields)
        .containsEntry("client_id", "sbx-id")
        .containsEntry("pix_key", "60701190000104")
        .doesNotContainKey("client_secret");
  }

  @Test
  void anOmittedSecretIsKept() {
    Logged owner = user(Role.OWNER);
    put(owner.access(), ITAU_CREDENTIALS, null, Map.of("payload", itauSandbox()));

    Map<String, Object> withoutSecret = new HashMap<>(itauSandbox());
    withoutSecret.remove("client_secret");
    withoutSecret.put("pix_key", "outra-chave@loja.com");
    EntityExchangeResult<String> edited =
        put(owner.access(), ITAU_CREDENTIALS, null, Map.of("payload", withoutSecret));
    assertThat(edited.getStatus().value()).isEqualTo(204);

    String plaintext = decrypted(owner, ApiKeyEnvironment.TEST);
    assertThat(plaintext).contains("\"client_secret\":\"sbx-secret\"");
    assertThat(plaintext).contains("\"pix_key\":\"outra-chave@loja.com\"");
  }

  @Test
  void anEmptySecretIsRemoved() {
    Logged owner = user(Role.OWNER);
    Map<String, Object> withApiKey = new HashMap<>(itauSandbox());
    withApiKey.put("x_itau_apikey", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    put(owner.access(), ITAU_CREDENTIALS, null, Map.of("payload", withApiKey));
    assertThat((Map) providerIn(get(owner.access(), null), "ITAU").get("secrets_set"))
        .containsEntry("x_itau_apikey", true);

    Map<String, Object> removing = new HashMap<>(itauSandbox());
    removing.remove("client_secret");
    removing.put("x_itau_apikey", "");
    EntityExchangeResult<String> edited =
        put(owner.access(), ITAU_CREDENTIALS, null, Map.of("payload", removing));
    assertThat(edited.getStatus().value()).isEqualTo(204);

    assertThat(decrypted(owner, ApiKeyEnvironment.TEST)).doesNotContain("x_itau_apikey");
    assertThat((Map) providerIn(get(owner.access(), null), "ITAU").get("secrets_set"))
        .containsEntry("x_itau_apikey", false);
  }

  @Test
  void liveNeedsTheCertificateEvenIfTheBodySaysTest() {
    Logged owner = user(Role.OWNER);

    EntityExchangeResult<String> refused =
        put(
            owner.access(),
            ITAU_CREDENTIALS,
            "LIVE",
            Map.of("environment", "TEST", "payload", itauSandbox()));

    assertThat(refused.getStatus().value()).isEqualTo(422);
    assertThat(refused.getResponseBody())
        .contains("urn:gateway:PROVIDER_CREDENTIALS_INVALID")
        .contains("\"field\":\"certificate_pem\"");
    assertThat(credentials.find(owner.store().id(), Provider.ITAU, ApiKeyEnvironment.LIVE))
        .isEmpty();
  }

  @Test
  void aSecretOfTheWrongTypeNamesTheFieldNotTheBody() {
    Logged owner = user(Role.OWNER);
    Map<String, Object> wrongType = new HashMap<>(itauSandbox());
    wrongType.put("client_secret", Map.of("nested", "object"));

    EntityExchangeResult<String> refused =
        put(owner.access(), ITAU_CREDENTIALS, null, Map.of("payload", wrongType));

    assertThat(refused.getStatus().value()).isEqualTo(422);
    assertThat(refused.getResponseBody())
        .contains("urn:gateway:PROVIDER_CREDENTIALS_INVALID")
        .doesNotContain("nested");
  }

  @Test
  void aMisspelledSecretKeyIsRefusedAndNothingIsStored() {
    Logged owner = user(Role.OWNER);
    Map<String, Object> misspelled = new HashMap<>(itauSandbox());
    misspelled.remove("client_secret");
    misspelled.put("clientSecret", "would-leak-in-the-clear");

    EntityExchangeResult<String> refused =
        put(owner.access(), ITAU_CREDENTIALS, null, Map.of("payload", misspelled));

    assertThat(refused.getStatus().value()).isEqualTo(422);
    assertThat(refused.getResponseBody())
        .contains("urn:gateway:PROVIDER_CREDENTIALS_INVALID")
        .contains("\"field\":\"clientSecret\"")
        .doesNotContain("would-leak");
    Map itau = providerIn(get(owner.access(), null), "ITAU");
    assertThat(itau.get("configured")).isEqualTo(false);
    assertThat((Map) itau.get("fields")).isEmpty();
  }

  @Test
  void theGetNeverEchoesASecretAndNeitherDoesTheAudit() {
    Logged owner = user(Role.OWNER);
    Map<String, Object> full = new HashMap<>(itauSandbox());
    full.put("x_itau_apikey", "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    full.put("certificate_pem", "-----BEGIN CERTIFICATE-----cert-----END CERTIFICATE-----");
    full.put("private_key_pem", "-----BEGIN PRIVATE KEY-----pk-----END PRIVATE KEY-----");

    Logger audit = (Logger) LoggerFactory.getLogger("gateway.audit.account");
    ListAppender<ILoggingEvent> lines = new ListAppender<>();
    lines.start();
    audit.addAppender(lines);
    try {
      put(owner.access(), ITAU_CREDENTIALS, null, Map.of("payload", full));
    } finally {
      audit.detachAppender(lines);
    }

    String body = exchange(owner.access(), "GET", PROVIDERS, null, null).getResponseBody();
    // certificate_pem is public (the bank hands it out); the key beside it is not.
    assertThat(body).doesNotContain("sbx-secret", "aaaaaaaa-bbbb", "pk-----", "PRIVATE KEY");
    assertThat(lines.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .filteredOn(line -> line.contains("provider.credentials.set"))
        .singleElement()
        .satisfies(
            line ->
                assertThat(line)
                    .contains("provider=ITAU")
                    .contains("env=TEST")
                    .doesNotContain("sbx-secret", "aaaaaaaa-bbbb", "pk-----", "sbx-id"));
  }

  @Test
  void financeIsForbiddenAndAnApiKeyNeedsASession() {
    Logged finance = user(Role.FINANCE);
    EntityExchangeResult<String> financeWrite =
        put(finance.access(), ITAU_CREDENTIALS, null, Map.of("payload", itauSandbox()));
    EntityExchangeResult<String> financeRead =
        exchange(finance.access(), "GET", PROVIDERS, null, null);
    assertThat(financeWrite.getStatus().value()).isEqualTo(403);
    assertThat(financeWrite.getResponseBody()).contains("urn:gateway:FORBIDDEN_FOR_ROLE");
    assertThat(financeRead.getStatus().value()).isEqualTo(403);
    assertThat(financeRead.getResponseBody()).contains("urn:gateway:FORBIDDEN_FOR_ROLE");

    String apiKey = apiKeys.issue(finance.store().id(), ApiKeyEnvironment.TEST).plainKey().reveal();
    EntityExchangeResult<String> keyRead = exchange(apiKey, "GET", PROVIDERS, null, null);
    EntityExchangeResult<String> keyWrite =
        put(apiKey, ITAU_CREDENTIALS, null, Map.of("payload", itauSandbox()));
    assertThat(keyRead.getStatus().value()).isEqualTo(403);
    assertThat(keyRead.getResponseBody()).contains("urn:gateway:USER_SESSION_REQUIRED");
    assertThat(keyWrite.getStatus().value()).isEqualTo(403);
    assertThat(keyWrite.getResponseBody()).contains("urn:gateway:USER_SESSION_REQUIRED");
  }

  @Test
  void anUnknownProviderIsABadRequest() {
    Logged owner = user(Role.OWNER);

    EntityExchangeResult<String> unknown =
        put(owner.access(), PROVIDERS + "/BRADESCO/credentials", null, Map.of("payload", Map.of()));
    EntityExchangeResult<String> unlisted =
        put(owner.access(), PROVIDERS + "/FAKE/credentials", null, Map.of("payload", Map.of()));

    assertThat(unknown.getStatus().value()).isEqualTo(400);
    assertThat(unlisted.getStatus().value()).isEqualTo(400);
  }

  @Test
  void notificationKeyIsStoredAndTooLongIsRefused() {
    Logged owner = user(Role.OWNER);
    assertThat(providerIn(get(owner.access(), null), "CIELO").get("notification_key_set"))
        .isEqualTo(false);

    EntityExchangeResult<String> stored =
        put(owner.access(), NOTIFICATION_KEY, null, Map.of("key", "chave-fixa-da-cielo"));
    assertThat(stored.getStatus().value()).isEqualTo(204);
    assertThat(providerIn(get(owner.access(), null), "CIELO").get("notification_key_set"))
        .isEqualTo(true);

    EntityExchangeResult<String> tooLong =
        put(owner.access(), NOTIFICATION_KEY, null, Map.of("key", "k".repeat(1501)));
    EntityExchangeResult<String> blank =
        put(owner.access(), NOTIFICATION_KEY, null, Map.of("key", "   "));
    assertThat(tooLong.getStatus().value()).isEqualTo(400);
    assertThat(blank.getStatus().value()).isEqualTo(400);
  }

  private static Map<String, Object> itauSandbox() {
    return Map.of(
        "client_id", "sbx-id", "client_secret", "sbx-secret", "pix_key", "60701190000104");
  }

  private static Map providerIn(Map body, String provider) {
    List<Map> providers = (List<Map>) body.get("providers");
    return providers.stream()
        .filter(entry -> provider.equals(entry.get("provider")))
        .findFirst()
        .orElseThrow();
  }

  private String decrypted(Logged owner, ApiKeyEnvironment environment) {
    byte[] plaintext =
        credentials.decrypt(owner.store().id(), Provider.ITAU, environment).orElseThrow();
    return new String(plaintext, StandardCharsets.UTF_8);
  }

  private Logged user(Role role) {
    Merchant store = merchants.create("Loja");
    // Globally unique: the e-mail is unique across merchants and the context outlives one test.
    EmailAddress email = new EmailAddress(role + "-" + UUID.randomUUID() + "@loja.com");
    User user = users.register(store.id(), "Ana", email, role, "senha-forte-1");
    user = users.markEmailVerified(user.id());

    String access = sessions.open(user.id(), null, null).accessToken().reveal();
    return new Logged(access, user, store);
  }

  private Map get(String bearer, String environment) {
    RestTestClient.RequestBodySpec request =
        http()
            .method(HttpMethod.GET)
            .uri(PROVIDERS)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
    if (environment != null) {
      request.header("X-Environment", environment);
    }

    EntityExchangeResult<Map> result = request.exchange().expectBody(Map.class).returnResult();
    assertThat(result.getStatus().value()).isEqualTo(200);
    return result.getResponseBody();
  }

  private EntityExchangeResult<String> put(
      String bearer, String path, String environment, Object body) {
    return exchange(bearer, "PUT", path, environment, body);
  }

  private EntityExchangeResult<String> exchange(
      String bearer, String method, String path, String environment, Object body) {
    RestTestClient.RequestBodySpec request =
        http()
            .method(HttpMethod.valueOf(method))
            .uri(path)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer)
            .contentType(MediaType.APPLICATION_JSON);
    if (environment != null) {
      request.header("X-Environment", environment);
    }
    if (body != null) {
      request.body(body);
    }

    return request.exchange().expectBody(String.class).returnResult();
  }

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }
}
