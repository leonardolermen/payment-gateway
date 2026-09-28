package com.gateway.providers.cielo;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardNotification;
import com.gateway.kernel.provider.card.CardNotificationKind;
import com.gateway.kernel.provider.card.CardRefundResult;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.kernel.provider.card.StoredCard;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.sale.CieloFixtures;
import com.gateway.providers.cielo.sale.SaleRequestFactoryTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CieloCardProviderTest {
  static final String PAYMENT_ID = "2352fc91-f9a4-4ca2-aedb-31488b9658c9";
  static WireMockServer server;
  static CieloCardProvider provider;

  @BeforeAll
  static void start() {
    server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    server.start();
    URI base = URI.create(server.baseUrl());
    CieloEndpoints wiremock = new CieloEndpoints(base, base);
    provider =
        new CieloCardProvider(
            new CieloHttp(Duration.ofSeconds(2), Duration.ofSeconds(5)), wiremock, wiremock);
  }

  @AfterAll
  static void stop() {
    server.stop();
  }

  @BeforeEach
  void reset() {
    server.resetAll();
  }

  static ProviderCredentials credentials() {
    return new ProviderCredentials(
        ("{\"merchant_id\":\"11111111-2222-3333-4444-555555555555\",\"merchant_key\":\""
                + "A".repeat(40)
                + "\"}")
            .getBytes(StandardCharsets.UTF_8),
        ProviderEnvironment.TEST);
  }

  @Test
  void isTheCieloCardProduct() {
    assertThat(provider.id()).isEqualTo("CIELO");
    assertThat(provider.method()).isEqualTo(PaymentMethod.CARD);
  }

  @Test
  void anIncompleteCredentialNamesTheFieldBeforeAnyHttp() {
    ProviderCredentials noKey =
        new ProviderCredentials(
            "{\"merchant_id\":\"11111111-2222-3333-4444-555555555555\"}"
                .getBytes(StandardCharsets.UTF_8),
            ProviderEnvironment.TEST);

    assertThatThrownBy(() -> provider.requireIssueCredentials(noKey))
        .isInstanceOf(ProviderException.class)
        .satisfies(
            thrown -> {
              ProviderException e = (ProviderException) thrown;
              assertThat(e.code()).isEqualTo(ProviderException.Code.CREDENTIALS_INCOMPLETE);
              assertThat(e.providerType()).isEqualTo("merchant_key");
            });
    assertThat(server.getAllServeEvents()).isEmpty();
  }

  @Test
  void issueAuthorizes() {
    server.stubFor(
        post(urlEqualTo("/1/sales"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(CieloFixtures.read("post_sales_201_captured.json"))));

    CardAuthorization authorization =
        provider.issue(
            credentials(), SaleRequestFactoryTest.request(SaleRequestFactoryTest.visa(), false));

    assertThat(authorization.status()).isEqualTo(CardStatus.PAID);
  }

  /** The capture answer has no captured amount or date (plan D17): the provider asks after it. */
  @Test
  void captureReturnsTheSaleAsTheQuerySeesItAfterwards() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .willReturn(okJson(CieloFixtures.read("put_capture_200.json"))));
    server.stubFor(
        get(urlEqualTo("/1/sales/" + PAYMENT_ID))
            .willReturn(okJson(CieloFixtures.read("get_sale_200_credit.json"))));

    CardAuthorization captured =
        provider.capture(credentials(), PAYMENT_ID, Optional.of(Money.brl(15700)));

    assertThat(captured.status()).isEqualTo(CardStatus.PAID);
    assertThat(captured.capturedAmount()).isEqualTo(Money.brl(15700));
    assertThat(captured.capturedAt()).isPresent();
  }

  /**
   * The PUT already moved the money; a re-query that blows up must not turn an acknowledged capture
   * into an exception the caller cannot distinguish from "it did not happen".
   */
  @Test
  void aCaptureFallsBackToThePutWhenTheRequeryFails() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .willReturn(okJson(CieloFixtures.read("put_capture_200.json"))));
    server.stubFor(
        get(urlEqualTo("/1/sales/" + PAYMENT_ID)).willReturn(aResponse().withStatus(500)));

    CardAuthorization captured =
        provider.capture(credentials(), PAYMENT_ID, Optional.of(Money.brl(15700)));

    assertThat(captured.status()).isEqualTo(CardStatus.PAID);
    assertThat(captured.capturedAmount()).isEqualTo(Money.brl(15700));
    assertThat(captured.capturedAt()).isPresent();
  }

  /** Same as above, but the re-query comes back 404 (Cielo host lag) instead of throwing. */
  @Test
  void aCaptureFallsBackToThePutWhenTheRequeryIsEmpty() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .willReturn(okJson(CieloFixtures.read("put_capture_200.json"))));
    server.stubFor(
        get(urlEqualTo("/1/sales/" + PAYMENT_ID)).willReturn(aResponse().withStatus(404)));

    CardAuthorization captured =
        provider.capture(credentials(), PAYMENT_ID, Optional.of(Money.brl(15700)));

    assertThat(captured.status()).isEqualTo(CardStatus.PAID);
    assertThat(captured.capturedAmount()).isEqualTo(Money.brl(15700));
    assertThat(captured.capturedAt()).isPresent();
  }

  /** The query host lags the PUT and still shows Status 1 with no CapturedAmount. */
  @Test
  void aCaptureFallsBackToThePutWhenTheRequeryIsStillAuthorized() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .willReturn(okJson(CieloFixtures.read("put_capture_200.json"))));
    server.stubFor(
        get(urlEqualTo("/1/sales/" + PAYMENT_ID))
            .willReturn(okJson(CieloFixtures.read("get_sale_200_authorized.json"))));

    CardAuthorization captured =
        provider.capture(credentials(), PAYMENT_ID, Optional.of(Money.brl(50)));

    assertThat(captured.status()).isEqualTo(CardStatus.PAID);
    assertThat(captured.capturedAmount()).isEqualTo(Money.brl(50));
  }

  /** The fallback only covers a PUT that succeeded: a failed PUT is still an exception. */
  @Test
  void aCaptureWhosePutFailsIsStillAnException() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/capture"))
            .willReturn(
                aResponse().withStatus(400).withBody(CieloFixtures.read("error_400_list.json"))));

    assertThatThrownBy(
            () -> provider.capture(credentials(), PAYMENT_ID, Optional.of(Money.brl(15700))))
        .isInstanceOf(ProviderException.class)
        .extracting(thrown -> ((ProviderException) thrown).code())
        .isEqualTo(ProviderException.Code.INVALID);
  }

  @Test
  void cancelIsATotalVoidThatMustEndVoided() {
    server.stubFor(
        put(urlEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200.json"))));

    provider.cancel(credentials(), PAYMENT_ID);
  }

  /**
   * Plan D2/spec §12.1: a void sent after the sale's day answers REFUNDED, not VOIDED — still ok.
   */
  @Test
  void cancelAcceptsALateVoidThatAnswersRefunded() {
    server.stubFor(
        put(urlEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200_refunded.json"))));

    provider.cancel(credentials(), PAYMENT_ID);
  }

  @Test
  void aVoidThatDoesNotVoidIsAConflict() {
    server.stubFor(
        put(urlEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_capture_200.json"))));

    assertThatThrownBy(() -> provider.cancel(credentials(), PAYMENT_ID))
        .isInstanceOf(ProviderException.class)
        .extracting(thrown -> ((ProviderException) thrown).code())
        .isEqualTo(ProviderException.Code.CONFLICT);
  }

  /** Plan D2: 10 on the day of the sale, 11 after it — both are the money going back. */
  @Test
  void aRefundIsCompletedWhetherTheCieloSaysVoidedOrRefunded() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200_refunded.json"))));

    CardRefundResult refund =
        provider.refund(credentials(), PAYMENT_ID, Optional.of(Money.brl(700)));

    assertThat(refund.completed()).isTrue();
    assertThat(refund.status()).isEqualTo(CardStatus.REFUNDED);
    assertThat(refund.refundedAmount()).isEqualTo(Money.brl(700));
    assertThat(refund.returnCode()).isEqualTo("9");
  }

  /**
   * A partial void leaves the sale PAID (Status 2) and answers ReturnCode 0: reading the status
   * called this a failure while the money had gone back. The code decides.
   */
  @Test
  void aPartialVoidThatLeavesTheSalePaidIsACompletedRefund() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200_partial.json"))));

    CardRefundResult refund =
        provider.refund(credentials(), PAYMENT_ID, Optional.of(Money.brl(700)));

    assertThat(refund.status()).isEqualTo(CardStatus.PAID);
    assertThat(refund.returnCode()).isEqualTo("0");
    assertThat(refund.completed()).isTrue();
  }

  @Test
  void aTotalVoidOnTheSaleDayIsACompletedRefund() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200.json"))));

    CardRefundResult refund =
        provider.refund(credentials(), PAYMENT_ID, Optional.of(Money.brl(15700)));

    assertThat(refund.status()).isEqualTo(CardStatus.VOIDED);
    assertThat(refund.completed()).isTrue();
  }

  /** 100 = partial void before settlement: a 200 whose code says the void was not performed. */
  @Test
  void aVoidAnsweredWithAnotherReturnCodeWasNotPerformed() {
    server.stubFor(
        put(urlPathEqualTo("/1/sales/" + PAYMENT_ID + "/void"))
            .willReturn(okJson(CieloFixtures.read("put_void_200_not_performed.json"))));

    CardRefundResult refund =
        provider.refund(credentials(), PAYMENT_ID, Optional.of(Money.brl(700)));

    assertThat(refund.returnCode()).isEqualTo("100");
    assertThat(refund.completed()).isFalse();
  }

  @Test
  void findByOrderReadsTheNewestSale() {
    server.stubFor(
        get(urlPathEqualTo("/1/sales"))
            .withQueryParam("merchantOrderId", equalTo("01K0PAYMENTIDULID000000000"))
            .willReturn(okJson(CieloFixtures.read("get_sales_by_order_200.json"))));
    server.stubFor(
        get(urlEqualTo("/1/sales/6c936046-f8df-4e4a-ba8c-003b28879055"))
            .willReturn(okJson(CieloFixtures.read("get_sale_200_credit.json"))));

    Optional<CardAuthorization> found =
        provider.findByOrder(credentials(), "01K0PAYMENTIDULID000000000");

    assertThat(found).isPresent();
    server.verify(getRequestedFor(urlEqualTo("/1/sales/6c936046-f8df-4e4a-ba8c-003b28879055")));
  }

  @Test
  void findByOrderIsEmptyWhenTheCieloHasNothing() {
    server.stubFor(get(urlPathEqualTo("/1/sales")).willReturn(aResponse().withStatus(404)));

    assertThat(provider.findByOrder(credentials(), "01K0NOTHERE00000000000000")).isEmpty();
  }

  @Test
  void tokenizeStoresTheCard() {
    server.stubFor(
        post(urlEqualTo("/1/card/"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withHeader("Content-Type", "application/json")
                    .withBody(CieloFixtures.read("post_card_201.json"))));
    CardData card =
        CardData.of(
            "4024007110880035", "Comprador T Cielo", "10/2026", "123", null, YearMonth.of(2026, 9));

    StoredCard stored = provider.tokenize(credentials(), card, "Comprador Teste Cielo");

    assertThat(stored.token()).isEqualTo("db62dc71-d07b-4745-9969-42697b988ccb");
    assertThat(stored.last4()).isEqualTo("0035");
    assertThat(stored.expiry()).isEqualTo(YearMonth.of(2026, 10));
  }

  @Test
  void notificationsAreClassifiedByChangeType() {
    CardNotification status =
        provider.parseWebhook(
            CieloFixtures.read("notification_status_changed.json")
                .getBytes(StandardCharsets.UTF_8));
    CardNotification recurrence =
        provider.parseWebhook(
            CieloFixtures.read("notification_change_type_2.json").getBytes(StandardCharsets.UTF_8));

    assertThat(status.paymentId()).isEqualTo(PAYMENT_ID);
    assertThat(status.kind()).isEqualTo(CardNotificationKind.STATUS_CHANGED);
    assertThat(recurrence.kind()).isEqualTo(CardNotificationKind.IGNORED);
    for (int[] pair : new int[][] {{25, 1}, {5, 2}, {8, 3}, {3, 4}, {4, 4}, {6, 4}, {7, 4}}) {
      CardNotificationKind expected =
          new CardNotificationKind[] {
                null,
                CardNotificationKind.PARTIAL_REFUND,
                CardNotificationKind.VOID_DENIED,
                CardNotificationKind.FRAUD_ALERT,
                CardNotificationKind.IGNORED
              }
              [pair[1]];
      byte[] body =
          ("{\"PaymentId\":\"" + PAYMENT_ID + "\",\"ChangeType\":" + pair[0] + "}")
              .getBytes(StandardCharsets.UTF_8);
      assertThat(provider.parseWebhook(body).kind())
          .as("ChangeType %s", pair[0])
          .isEqualTo(expected);
    }
  }

  @Test
  void aNotificationWithoutPaymentIdIsUnreadable() {
    assertThatThrownBy(
            () -> provider.parseWebhook("{\"ChangeType\":1}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("notification without PaymentId");
  }
}
