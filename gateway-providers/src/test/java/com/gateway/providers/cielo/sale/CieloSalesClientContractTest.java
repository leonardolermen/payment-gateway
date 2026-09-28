package com.gateway.providers.cielo.sale;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.money.Money;
import com.gateway.providers.cielo.sale.dto.SaleResponse;
import com.gateway.providers.cielo.sale.dto.SaleUpdateResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Capture, void and the two queries, against the pages' own examples (fixtures README). */
class CieloSalesClientContractTest {
  static final String PAYMENT_ID = "2352fc91-f9a4-4ca2-aedb-31488b9658c9";
  static WireMockServer server;

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
  void reset() {
    server.resetAll();
  }

  static CieloSalesClient client() {
    return CieloSalesClientAuthorizeContractTest.client(server, Duration.ofSeconds(5));
  }

  @Test
  void aTotalCaptureSendsNoAmount() {
    server.stubFor(
        put(urlEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .willReturn(okJson(CieloFixtures.read("put_capture_200.json"))));

    SaleUpdateResponse captured =
        client()
            .capture(
                CieloSalesClientAuthorizeContractTest.credentials(), PAYMENT_ID, Optional.empty());

    assertThat(captured.status()).isEqualTo(2);
    assertThat(captured.returnCode()).isEqualTo("6");
    server.verify(putRequestedFor(urlEqualTo("/1/sales/" + PAYMENT_ID + "/capture")));
  }

  /** "Para captura parcial, envie o campo Amount" — as the query string, in cents. */
  @Test
  void aPartialCaptureSendsTheAmountInCents() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .willReturn(okJson(CieloFixtures.read("put_capture_200.json"))));

    client()
        .capture(
            CieloSalesClientAuthorizeContractTest.credentials(),
            PAYMENT_ID,
            Optional.of(Money.brl(5000)));

    server.verify(
        putRequestedFor(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .withQueryParam("amount", equalTo("5000")));
  }

  @Test
  void aVoidBeforeCaptureIsTotalAndAnswersTen() {
    server.stubFor(
        put(urlEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200.json"))));

    SaleUpdateResponse voided =
        client()
            .voidSale(
                CieloSalesClientAuthorizeContractTest.credentials(), PAYMENT_ID, Optional.empty());

    assertThat(voided.status()).isEqualTo(10);
  }

  @Test
  void aPartialVoidAfterCaptureSendsTheAmount() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200_refunded.json"))));

    SaleUpdateResponse refunded =
        client()
            .voidSale(
                CieloSalesClientAuthorizeContractTest.credentials(),
                PAYMENT_ID,
                Optional.of(Money.brl(700)));

    assertThat(refunded.status()).isEqualTo(11);
    server.verify(
        putRequestedFor(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .withQueryParam("amount", equalTo("700")));
  }

  @Test
  void aQueryByPaymentIdGoesToTheQueryHost() {
    server.stubFor(
        get(urlEqualTo("/1/sales/" + PAYMENT_ID))
            .willReturn(okJson(CieloFixtures.read("get_sale_200_credit.json"))));

    Optional<SaleResponse> sale =
        client().findByPaymentId(CieloSalesClientAuthorizeContractTest.credentials(), PAYMENT_ID);

    assertThat(sale).isPresent();
    assertThat(sale.get().payment().status()).isEqualTo(2);
    assertThat(sale.get().payment().capturedAmount()).isEqualTo(15700);
  }

  /** Plan D10: 404 and 400/307 both mean "not at the Cielo". */
  @Test
  void anUnknownPaymentIdIsEmpty() {
    server.stubFor(get(urlEqualTo("/1/sales/unknown-404")).willReturn(aResponse().withStatus(404)));
    server.stubFor(
        get(urlEqualTo("/1/sales/unknown-307"))
            .willReturn(
                aResponse()
                    .withStatus(400)
                    .withBody("[{\"Code\":307,\"Message\":\"Transaction not found\"}]")));

    assertThat(
            client()
                .findByPaymentId(
                    CieloSalesClientAuthorizeContractTest.credentials(), "unknown-404"))
        .isEmpty();
    assertThat(
            client()
                .findByPaymentId(
                    CieloSalesClientAuthorizeContractTest.credentials(), "unknown-307"))
        .isEmpty();
  }

  /** One garbage date must not break recovery of the order: that sale just sorts last. */
  @Test
  void aQueryByOrderSortsAnUnreadableDateLast() {
    server.stubFor(
        get(urlPathEqualTo("/1/sales"))
            .withQueryParam("merchantOrderId", equalTo("Loja123456"))
            .willReturn(
                okJson(
                    CieloFixtures.read("get_sales_by_order_200.json")
                        .replace("2025-05-17T07:48:10.677", "not-a-date"))));

    assertThat(
            client()
                .findPaymentIdsByOrder(
                    CieloSalesClientAuthorizeContractTest.credentials(), "Loja123456"))
        .containsExactly(
            "1e7abcb6-39be-4aae-b70b-002889ee15d0",
            "55f0a6c8-387e-476b-a6e8-ffef82e9a18e",
            "4b62cc74-bb20-4629-ab2d-002262738481",
            "6c936046-f8df-4e4a-ba8c-003b28879055");
  }

  @Test
  void aQueryByOrderListsTheNewestFirst() {
    server.stubFor(
        get(urlPathEqualTo("/1/sales"))
            .withQueryParam("merchantOrderId", equalTo("Loja123456"))
            .willReturn(okJson(CieloFixtures.read("get_sales_by_order_200.json"))));

    assertThat(
            client()
                .findPaymentIdsByOrder(
                    CieloSalesClientAuthorizeContractTest.credentials(), "Loja123456"))
        .containsExactly(
            "6c936046-f8df-4e4a-ba8c-003b28879055",
            "1e7abcb6-39be-4aae-b70b-002889ee15d0",
            "55f0a6c8-387e-476b-a6e8-ffef82e9a18e",
            "4b62cc74-bb20-4629-ab2d-002262738481");
  }

  @Test
  void anOrderTheCieloDoesNotKnowIsAnEmptyList() {
    server.stubFor(get(urlPathEqualTo("/1/sales")).willReturn(aResponse().withStatus(404)));
    server.stubFor(
        get(urlPathEqualTo("/1/sales"))
            .withQueryParam("merchantOrderId", equalTo("empty"))
            .willReturn(okJson("{\"ReasonCode\":0,\"ReasonMessage\":\"Successful\"}")));

    assertThat(
            client()
                .findPaymentIdsByOrder(CieloSalesClientAuthorizeContractTest.credentials(), "nope"))
        .isEmpty();
    assertThat(
            client()
                .findPaymentIdsByOrder(
                    CieloSalesClientAuthorizeContractTest.credentials(), "empty"))
        .isEmpty();
  }
}
