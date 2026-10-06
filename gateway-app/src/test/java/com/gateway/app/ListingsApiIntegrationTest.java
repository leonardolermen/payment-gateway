package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The merchant panel's listings: orders and customers, newest first, paged by the id of the last
 * item seen, the same shape as GET /v1/payments. No provider is involved, so no stubs.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"gateway.rate-limit.requests-per-minute=1000"})
@ActiveProfiles("test")
@Testcontainers
class ListingsApiIntegrationTest {
  static final List<String> DOCUMENTS =
      List.of("52998224725", "11144477735", "12345678909", "39053344705");

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  String key;
  int sequence;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void merchant() {
    String merchantId =
        (String)
            send("POST", "/v1/admin/merchants", "test-admin", null, Map.of("name", "Listing Store"))
                .get("id");
    key =
        (String)
            send(
                    "POST",
                    "/v1/admin/merchants/" + merchantId + "/api-keys",
                    "test-admin",
                    null,
                    Map.of("environment", "TEST"))
                .get("key");
  }

  @Test
  void ordersAreListedNewestFirstAndPagedByCursor() {
    List<String> ids = createOrders(5);

    List<Map<String, Object>> first = list("/v1/orders?limit=3");
    assertThat(idsOf(first)).containsExactly(ids.get(4), ids.get(3), ids.get(2));

    List<Map<String, Object>> second = list("/v1/orders?limit=3&cursor=" + ids.get(2));
    assertThat(idsOf(second)).containsExactly(ids.get(1), ids.get(0));
    assertThat(idsOf(second)).doesNotContainAnyElementsOf(idsOf(first));
  }

  @Test
  void ordersCanBeFilteredByStatus() {
    List<String> ids = createOrders(3);
    send("POST", "/v1/orders/" + ids.get(1) + "/cancel", key, "cancel-1", Map.of());

    assertThat(idsOf(list("/v1/orders?status=CANCELED"))).containsExactly(ids.get(1));
    assertThat(idsOf(list("/v1/orders?status=OPEN"))).containsExactly(ids.get(2), ids.get(0));
  }

  @Test
  void anUnknownOrderStatusIsRejected() {
    EntityExchange rejected = get("/v1/orders?status=NOPE");

    assertThat(rejected.status()).isEqualTo(400);
    assertThat(rejected.body())
        .containsEntry("type", "urn:gateway:INVALID_REQUEST")
        .containsEntry("detail", "status is not valid");
  }

  @Test
  void aCursorCannotBeCombinedWithAReference() {
    assertThat(get("/v1/orders?reference=pedido-1&cursor=01ABC").status()).isEqualTo(400);
  }

  @Test
  void aLimitOutOfRangeIsRejected() {
    assertThat(get("/v1/orders?limit=0").status()).isEqualTo(400);
    assertThat(get("/v1/orders?limit=101").status()).isEqualTo(400);
    assertThat(get("/v1/customers?limit=0").status()).isEqualTo(400);
  }

  @Test
  void customersAreListedNewestFirstPagedAndWithoutTheDeletedOne() {
    List<String> ids = new ArrayList<>();
    for (String document : DOCUMENTS) {
      ids.add(createCustomer(document));
    }
    send("DELETE", "/v1/customers/" + ids.get(1), key, null, null);

    List<Map<String, Object>> first = list("/v1/customers?limit=2");
    assertThat(idsOf(first)).containsExactly(ids.get(3), ids.get(2));

    List<Map<String, Object>> second = list("/v1/customers?limit=2&cursor=" + ids.get(2));
    assertThat(idsOf(second)).containsExactly(ids.get(0));
  }

  private List<String> createOrders(int count) {
    List<String> ids = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      Map<String, Object> order =
          send(
              "POST",
              "/v1/orders",
              key,
              "order-" + sequence++,
              Map.of(
                  "amount",
                  10000,
                  "currency",
                  "BRL",
                  "reference",
                  "pedido-" + i,
                  "customer",
                  customerBody(DOCUMENTS.get(0))));
      ids.add((String) order.get("id"));
    }

    return ids;
  }

  private String createCustomer(String document) {
    return (String)
        send("POST", "/v1/customers", key, "customer-" + sequence++, customerBody(document))
            .get("id");
  }

  private static Map<String, Object> customerBody(String document) {
    return Map.of("name", "Ana Souza", "document", document, "email", "ana@example.com");
  }

  record EntityExchange(int status, Map<String, Object> body) {}

  @SuppressWarnings("unchecked")
  private EntityExchange get(String uri) {
    var result =
        client()
            .get()
            .uri(uri)
            .header("Authorization", "Bearer " + key)
            .exchange()
            .expectBody(Map.class)
            .returnResult();

    return new EntityExchange(result.getStatus().value(), result.getResponseBody());
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(String uri) {
    return client()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(List.class)
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> send(
      String method, String uri, String credential, String idempotencyKey, Object body) {
    boolean admin = uri.startsWith("/v1/admin");
    RestTestClient.RequestBodySpec spec =
        client().method(org.springframework.http.HttpMethod.valueOf(method)).uri(uri);
    spec =
        admin
            ? spec.header("X-Admin-Key", credential)
            : spec.header("Authorization", "Bearer " + credential);
    if (idempotencyKey != null) {
      spec = spec.header("Idempotency-Key", idempotencyKey);
    }

    RestTestClient.RequestHeadersSpec<?> ready =
        body == null ? spec : spec.contentType(MediaType.APPLICATION_JSON).body(body);

    var result = ready.exchange().expectBody(Map.class).returnResult();
    assertThat(result.getStatus().is2xxSuccessful()).as(uri + " " + result.getStatus()).isTrue();

    return result.getResponseBody();
  }

  private static List<String> idsOf(List<Map<String, Object>> items) {
    return items.stream().map(item -> (String) item.get("id")).toList();
  }

  private RestTestClient client() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }
}
