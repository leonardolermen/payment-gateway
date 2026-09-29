package com.gateway.providers.cielo.sale;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardDeclineCode;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.providers.cielo.CieloHttp;
import com.gateway.providers.cielo.auth.CieloCredentials;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.sale.dto.SaleResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * POST /1/sales against the Cielo's own examples (fixtures README) and states derived from them.
 */
public class CieloSalesClientAuthorizeContractTest {
  static final String MERCHANT_ID = "11111111-2222-3333-4444-555555555555";
  static final String MERCHANT_KEY = "A".repeat(40);
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

  public static CieloCredentials credentials() {
    return CieloCredentials.parse(
        ("{\"merchant_id\":\"" + MERCHANT_ID + "\",\"merchant_key\":\"" + MERCHANT_KEY + "\"}")
            .getBytes(StandardCharsets.UTF_8));
  }

  static CieloSalesClient client(WireMockServer server, Duration readTimeout) {
    URI base = URI.create(server.baseUrl());
    return new CieloSalesClient(
        new CieloHttp(Duration.ofSeconds(2), readTimeout), new CieloEndpoints(base, base));
  }

  public static CardData visa() {
    return SaleRequestFactoryTest.visa();
  }

  static SaleResponse authorize(Duration readTimeout) {
    return client(server, readTimeout)
        .authorize(
            credentials(), SaleRequestFactory.from(SaleRequestFactoryTest.request(visa(), false)));
  }

  static void answer(int status, String fixture) {
    server.stubFor(
        post(urlEqualTo("/1/sales"))
            .willReturn(
                aResponse()
                    .withStatus(status)
                    .withHeader("Content-Type", "application/json")
                    .withBody(CieloFixtures.read(fixture))));
  }

  @Test
  void anApprovedCapturedSaleWithTheRequiredHeaders() {
    answer(201, "post_sales_201_captured.json");

    CardAuthorization authorization =
        SaleResponses.toAuthorization(authorize(Duration.ofSeconds(5)), visa());

    server.verify(
        postRequestedFor(urlEqualTo("/1/sales"))
            .withHeader("MerchantId", equalTo(MERCHANT_ID))
            .withHeader("MerchantKey", equalTo(MERCHANT_KEY))
            .withHeader("RequestId", matching("[0-9a-f-]{36}"))
            .withHeader("Content-Type", equalTo("application/json"))
            .withRequestBody(
                matchingJsonPath("$.Payment.CreditCard.CardNumber", equalTo("4024007153763171"))));
    assertThat(authorization.paymentId()).isEqualTo("6f8d1753-86bb-4dc0-9ebb-09a29093e1fb");
    assertThat(authorization.status()).isEqualTo(CardStatus.PAID);
    assertThat(authorization.returnCode()).isEqualTo("6");
    assertThat(authorization.declineCode()).isNull();
    assertThat(authorization.tid()).isEqualTo("1124060407175");
    assertThat(authorization.authorizationCode()).isEqualTo("663864");
    assertThat(authorization.proofOfSale()).isEqualTo("182738");
    assertThat(authorization.amount()).isEqualTo(Money.brl(10000));
    assertThat(authorization.capturedAmount()).isEqualTo(Money.brl(10000));
    assertThat(authorization.brand()).isEqualTo(CardBrand.VISA);
    assertThat(authorization.last4()).isEqualTo("7641");
    assertThat(authorization.cardToken()).isEmpty();
    assertThat(authorization.receivedAt()).isEqualTo(Instant.parse("2025-11-24T21:04:07Z"));
    assertThat(authorization.capturedAt()).contains(Instant.parse("2025-11-24T21:04:07Z"));
  }

  @Test
  void anAuthorizationThatSavedTheCardCarriesTheToken() {
    answer(201, "post_sales_201_authorized_saved.json");

    CardAuthorization authorization =
        SaleResponses.toAuthorization(authorize(Duration.ofSeconds(5)), visa());

    assertThat(authorization.status()).isEqualTo(CardStatus.AUTHORIZED);
    assertThat(authorization.capturedAmount()).isNull();
    assertThat(authorization.capturedAt()).isEmpty();
    assertThat(authorization.cardToken()).contains("db62dc71-d07b-4745-9969-42697b988ccb");
    assertThat(authorization.toString()).doesNotContain("db62dc71");
  }

  /** The tokenized 201 has no CardNumber: last four come from what was sent. */
  @Test
  void aTokenizedAnswerFallsBackToTheSourceForTheBrand() {
    answer(201, "post_sales_201_token_authorized.json");

    CardAuthorization authorization =
        SaleResponses.toAuthorization(authorize(Duration.ofSeconds(5)), visa());

    assertThat(authorization.status()).isEqualTo(CardStatus.AUTHORIZED);
    assertThat(authorization.returnCode()).isNull();
    assertThat(authorization.brand()).isEqualTo(CardBrand.VISA);
    assertThat(authorization.last4()).isEqualTo("3171");
  }

  /** A decline is a 201 (spec §5): a result, never an exception. */
  @Test
  void aDeclineIsAResultWithOurCode() {
    answer(201, "post_sales_201_denied.json");

    CardAuthorization authorization =
        SaleResponses.toAuthorization(authorize(Duration.ofSeconds(5)), visa());

    assertThat(authorization.status()).isEqualTo(CardStatus.DENIED);
    assertThat(authorization.declineCode()).isEqualTo(CardDeclineCode.INSUFFICIENT_FUNDS);
    assertThat(authorization.authorizationCode()).isNull();
  }

  @Test
  void notFinishedIsInDoubt() {
    answer(201, "post_sales_201_not_finished.json");

    CardAuthorization authorization =
        SaleResponses.toAuthorization(authorize(Duration.ofSeconds(5)), visa());

    assertThat(authorization.status()).isEqualTo(CardStatus.NOT_FINISHED);
    assertThat(authorization.status().inDoubt()).isTrue();
    assertThat(authorization.declineCode()).isNull();
  }

  @Test
  void aFourHundredListIsInvalid() {
    answer(400, "error_400_expiration.json");

    assertThatThrownBy(() -> authorize(Duration.ofSeconds(5)))
        .isInstanceOf(ProviderException.class)
        .satisfies(
            thrown -> {
              ProviderException e = (ProviderException) thrown;
              assertThat(e.code()).isEqualTo(ProviderException.Code.INVALID);
              assertThat(e.providerType()).isEqualTo("126");
              assertThat(e.getMessage()).doesNotContain("4024007153763171");
            });
  }

  @Test
  void aWrongKeyIsUnauthenticated() {
    server.stubFor(
        post(urlEqualTo("/1/sales"))
            .willReturn(aResponse().withStatus(401).withBody("Unauthorized")));

    assertThatThrownBy(() -> authorize(Duration.ofSeconds(5)))
        .isInstanceOf(ProviderException.class)
        .extracting(thrown -> ((ProviderException) thrown).code())
        .isEqualTo(ProviderException.Code.UNAUTHENTICATED);
  }

  @Test
  void aSlowCieloIsATimeout() {
    server.stubFor(
        post(urlEqualTo("/1/sales"))
            .willReturn(
                aResponse()
                    .withStatus(201)
                    .withFixedDelay(1500)
                    .withBody(CieloFixtures.read("post_sales_201_captured.json"))));

    assertThatThrownBy(() -> authorize(Duration.ofMillis(300)))
        .isInstanceOf(ProviderException.class)
        .extracting(thrown -> ((ProviderException) thrown).code())
        .isEqualTo(ProviderException.Code.TIMEOUT);
  }
}
