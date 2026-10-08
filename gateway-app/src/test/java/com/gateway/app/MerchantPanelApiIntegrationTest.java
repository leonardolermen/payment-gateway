package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.EntityExchangeResult;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * What the merchant panel reads: orders and customers by cursor in the key's environment, orders by
 * status, and the customer's name on every order (spec 2026-10-06-painel-do-merchant).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "gateway.rate-limit.requests-per-minute=1000")
@ActiveProfiles("test")
@Testcontainers
class MerchantPanelApiIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;

  RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> admin(String uri, Object body) {
    return http()
        .post()
        .uri(uri)
        .header("X-Admin-Key", "test-admin")
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectStatus()
        .is2xxSuccessful()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  private record Keys(String test, String live) {}

  private Keys newMerchant() {
    String merchantId =
        (String) admin("/v1/admin/merchants", Map.of("name", "Loja de Dev")).get("id");
    String keys = "/v1/admin/merchants/" + merchantId + "/api-keys";

    return new Keys(
        (String) admin(keys, Map.of("environment", "TEST")).get("key"),
        (String) admin(keys, Map.of("environment", "LIVE")).get("key"));
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> post(String key, String idempotencyKey, String uri, Object body) {
    return http()
        .post()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .body(body)
        .exchange()
        .expectStatus()
        .is2xxSuccessful()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("rawtypes")
  private EntityExchangeResult<List> getList(String key, String uri) {
    return http()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectBody(List.class)
        .returnResult();
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> list(String key, String uri) {
    EntityExchangeResult<List> result = getList(key, uri);
    assertThat(result.getStatus().value()).isEqualTo(200);

    return result.getResponseBody();
  }

  /** As text: an error is a problem+json object, not the list a success would be. */
  private int status(String key, String uri) {
    return http()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectBody(String.class)
        .returnResult()
        .getStatus()
        .value();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> get(String key, String uri) {
    return http()
        .get()
        .uri(uri)
        .header("Authorization", "Bearer " + key)
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }

  private String customer(String key, String name, String document) {
    return (String)
        post(key, "cust-" + document, "/v1/customers", Map.of("name", name, "document", document))
            .get("id");
  }

  private String order(String key, String idempotencyKey, String customerId) {
    return (String)
        post(
                key,
                idempotencyKey,
                "/v1/orders",
                Map.of("amount", 4990, "currency", "BRL", "customer_id", customerId))
            .get("id");
  }

  private String plan(String key) {
    return (String)
        post(
                key,
                "plan-" + key.hashCode(),
                "/v1/plans",
                Map.of("name", "Mensal", "amount", 10000, "currency", "BRL", "interval", "MONTH"))
            .get("id");
  }

  /** Pix: creating a subscription moves no money, so the panel's lists need no provider here. */
  private String subscription(String key, String idempotencyKey, String customerId, String planId) {
    return (String)
        post(
                key,
                idempotencyKey,
                "/v1/subscriptions",
                Map.of("customer_id", customerId, "plan_id", planId, "method", "PIX"))
            .get("id");
  }

  private static List<Object> ids(List<Map<String, Object>> page) {
    return page.stream().map(item -> item.get("id")).toList();
  }

  @Test
  void subscriptionsPageByCursorAndStatusInTheKeysEnvironment() {
    Keys keys = newMerchant();
    String plan = plan(keys.test());
    String ana = customer(keys.test(), "Ana Silva", "52998224725");
    String joao = customer(keys.test(), "Joao Souza", "11144477735");
    String first = subscription(keys.test(), "s-1", ana, plan);
    String second = subscription(keys.test(), "s-2", joao, plan);
    http()
        .post()
        .uri("/v1/subscriptions/" + second + "/cancel")
        .header("Authorization", "Bearer " + keys.test())
        .header("Idempotency-Key", "s-2-cancel")
        .contentType(MediaType.APPLICATION_JSON)
        .body(Map.of("at_period_end", false))
        .exchange()
        .expectStatus()
        .isOk();

    List<Map<String, Object>> page1 = list(keys.test(), "/v1/subscriptions?limit=1");
    List<Map<String, Object>> page2 =
        list(keys.test(), "/v1/subscriptions?limit=1&cursor=" + page1.getLast().get("id"));

    assertThat(ids(page1)).containsExactly(second);
    assertThat(ids(page2)).containsExactly(first);
    assertThat(ids(list(keys.test(), "/v1/subscriptions?status=CANCELED"))).containsExactly(second);
    assertThat(ids(list(keys.test(), "/v1/subscriptions?status=ACTIVE"))).containsExactly(first);
    assertThat(list(keys.live(), "/v1/subscriptions")).isEmpty();
    assertThat(list(keys.test(), "/v1/subscriptions?customer_id=" + ana)).hasSize(1);
  }

  @Test
  void ordersFilterByCustomerTogetherWithStatus() {
    Keys keys = newMerchant();
    String ana = customer(keys.test(), "Ana Silva", "52998224725");
    String joao = customer(keys.test(), "Joao Souza", "11144477735");
    String anaOpen = order(keys.test(), "o-1", ana);
    String anaCanceled = order(keys.test(), "o-2", ana);
    order(keys.test(), "o-3", joao);
    http()
        .post()
        .uri("/v1/orders/" + anaCanceled + "/cancel")
        .header("Authorization", "Bearer " + keys.test())
        .header("Idempotency-Key", "o-2-cancel")
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(ids(list(keys.test(), "/v1/orders?customer_id=" + ana)))
        .containsExactly(anaCanceled, anaOpen);
    assertThat(ids(list(keys.test(), "/v1/orders?customer_id=" + ana + "&status=OPEN")))
        .containsExactly(anaOpen);
    assertThat(
            ids(
                list(
                    keys.test(),
                    "/v1/orders?customer_id=" + ana + "&limit=1&cursor=" + anaCanceled)))
        .containsExactly(anaOpen);
  }

  @Test
  void customersSearchByPartOfTheNameIgnoringCase() {
    Keys keys = newMerchant();
    String ana = customer(keys.test(), "Ana Silva", "52998224725");
    customer(keys.test(), "Joao Souza", "11144477735");
    String gone = customer(keys.test(), "Mariana Silveira", "39053344705");
    http()
        .delete()
        .uri("/v1/customers/" + gone)
        .header("Authorization", "Bearer " + keys.test())
        .exchange()
        .expectStatus()
        .isNoContent();

    assertThat(ids(list(keys.test(), "/v1/customers?q=silv"))).containsExactly(ana);
    assertThat(ids(list(keys.test(), "/v1/customers?q=ANA"))).containsExactly(ana);
    assertThat(list(keys.test(), "/v1/customers?q=zzz")).isEmpty();
    assertThat(list(keys.live(), "/v1/customers?q=ana")).isEmpty();
  }

  @Test
  void contradictoryListFiltersAre400() {
    Keys keys = newMerchant();

    assertThat(status(keys.test(), "/v1/subscriptions?status=XYZ")).isEqualTo(400);
    assertThat(status(keys.test(), "/v1/subscriptions?customer_id=01J&cursor=01J")).isEqualTo(400);
    assertThat(status(keys.test(), "/v1/subscriptions?customer_id=01J&status=ACTIVE"))
        .isEqualTo(400);
    assertThat(status(keys.test(), "/v1/subscriptions?limit=0")).isEqualTo(400);
    assertThat(status(keys.test(), "/v1/orders?customer_id=01J&reference=r-1")).isEqualTo(400);
    assertThat(status(keys.test(), "/v1/customers?q=ana&document=52998224725")).isEqualTo(400);
  }

  @Test
  void ordersPageByCursorFilterByStatusAndCarryTheCustomerName() {
    Keys keys = newMerchant();
    String ana = customer(keys.test(), "Ana Silva", "52998224725");
    String first = order(keys.test(), "o-1", ana);
    String second = order(keys.test(), "o-2", ana);
    String third = order(keys.test(), "o-3", ana);
    post(keys.test(), "cancel-2", "/v1/orders/" + second + "/cancel", Map.of());

    List<Map<String, Object>> page1 = list(keys.test(), "/v1/orders?limit=2");
    List<Map<String, Object>> page2 =
        list(keys.test(), "/v1/orders?limit=2&cursor=" + page1.getLast().get("id"));

    assertThat(ids(page1)).containsExactly(third, second);
    assertThat(ids(page2)).containsExactly(first);
    assertThat(page1.getFirst()).containsEntry("customer_name", "Ana Silva");
    assertThat(page1.getFirst()).containsKey("payments");
    assertThat(ids(list(keys.test(), "/v1/orders?status=CANCELED"))).containsExactly(second);
    assertThat(ids(list(keys.test(), "/v1/orders?status=OPEN"))).containsExactly(third, first);
    assertThat(get(keys.test(), "/v1/orders/" + first)).containsEntry("customer_name", "Ana Silva");
  }

  @Test
  void anOrderWithAnInlinePayerCarriesThePayersName() {
    Keys keys = newMerchant();
    String orderId =
        (String)
            post(
                    keys.test(),
                    "o-inline",
                    "/v1/orders",
                    Map.of(
                        "amount",
                        12000,
                        "currency",
                        "BRL",
                        "customer",
                        Map.of("name", "Joao Souza", "document", "11144477735")))
                .get("id");

    assertThat(get(keys.test(), "/v1/orders/" + orderId))
        .containsEntry("customer_name", "Joao Souza");
    assertThat(list(keys.test(), "/v1/orders").getFirst())
        .containsEntry("customer_id", null)
        .containsEntry("customer_name", "Joao Souza");
  }

  @Test
  void aLiveKeySeesNoTestOrderOrCustomer() {
    Keys keys = newMerchant();
    order(keys.test(), "o-1", customer(keys.test(), "Ana Silva", "52998224725"));

    assertThat(list(keys.live(), "/v1/orders")).isEmpty();
    assertThat(list(keys.live(), "/v1/customers")).isEmpty();
    assertThat(list(keys.test(), "/v1/orders")).hasSize(1);
  }

  @Test
  void aDeletedCustomerLeavesTheOrderWithoutAName() {
    Keys keys = newMerchant();
    String ana = customer(keys.test(), "Ana Silva", "52998224725");
    String orderId = order(keys.test(), "o-1", ana);

    http()
        .delete()
        .uri("/v1/customers/" + ana)
        .header("Authorization", "Bearer " + keys.test())
        .exchange()
        .expectStatus()
        .isNoContent();

    assertThat(get(keys.test(), "/v1/orders/" + orderId)).containsEntry("customer_name", null);
    assertThat(list(keys.test(), "/v1/orders").getFirst()).containsEntry("customer_name", null);
  }

  @Test
  void customersPageByCursorWithoutTheDeletedOnesAndWithTheDocumentMasked() {
    Keys keys = newMerchant();
    String ana = customer(keys.test(), "Ana Silva", "52998224725");
    String joao = customer(keys.test(), "Joao Souza", "11144477735");
    String gone = customer(keys.test(), "Ex Cliente", "39053344705");
    http()
        .delete()
        .uri("/v1/customers/" + gone)
        .header("Authorization", "Bearer " + keys.test())
        .exchange()
        .expectStatus()
        .isNoContent();

    List<Map<String, Object>> page1 = list(keys.test(), "/v1/customers?limit=1");
    List<Map<String, Object>> page2 =
        list(keys.test(), "/v1/customers?limit=1&cursor=" + page1.getLast().get("id"));

    assertThat(ids(page1)).containsExactly(joao);
    assertThat(ids(page2)).containsExactly(ana);
    assertThat((String) page1.getFirst().get("document")).doesNotContain("11144477735");
    assertThat(list(keys.test(), "/v1/customers?document=52998224725")).hasSize(1);
  }

  @Test
  void contradictoryOrUnknownFiltersAre400() {
    Keys keys = newMerchant();

    assertThat(status(keys.test(), "/v1/orders?status=XYZ")).isEqualTo(400);
    assertThat(status(keys.test(), "/v1/orders?reference=r-1&cursor=01J")).isEqualTo(400);
    assertThat(status(keys.test(), "/v1/orders?reference=r-1&status=OPEN")).isEqualTo(400);
    assertThat(status(keys.test(), "/v1/customers?document=52998224725&cursor=01J")).isEqualTo(400);
    assertThat(status(keys.test(), "/v1/customers?limit=0")).isEqualTo(400);
  }
}
