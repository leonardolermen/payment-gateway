package com.gateway.providers.cielo;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardIssueRequest;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardNotification;
import com.gateway.kernel.provider.card.CardNotificationKind;
import com.gateway.kernel.provider.card.CardRefundResult;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.kernel.provider.card.StoredCard;
import com.gateway.providers.cielo.auth.CieloCredentials;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import com.gateway.providers.cielo.card.CardTokenRequestFactory;
import com.gateway.providers.cielo.card.CieloCardClient;
import com.gateway.providers.cielo.notification.NotificationBody;
import com.gateway.providers.cielo.sale.CieloSalesClient;
import com.gateway.providers.cielo.sale.CieloStatuses;
import com.gateway.providers.cielo.sale.SaleRequestFactory;
import com.gateway.providers.cielo.sale.SaleResponses;
import com.gateway.providers.cielo.sale.dto.SaleUpdateResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import tools.jackson.databind.ObjectMapper;

/** The only class that knows the Cielo's vocabulary and the gateway's card contract at once. */
public class CieloCardProvider implements CardMethodProvider {
  /** docs/webhook, "Tabela de ChangeType"; everything else is not this phase's (plan D14). */
  private static final Map<Integer, CardNotificationKind> CHANGE_TYPES =
      Map.of(
          1, CardNotificationKind.STATUS_CHANGED,
          25, CardNotificationKind.PARTIAL_REFUND,
          5, CardNotificationKind.VOID_DENIED,
          8, CardNotificationKind.FRAUD_ALERT);

  private final CieloSalesClient liveSales;
  private final CieloSalesClient testSales;
  private final CieloCardClient liveCards;
  private final CieloCardClient testCards;
  private final ObjectMapper mapper = new ObjectMapper();

  public CieloCardProvider(CieloHttp http, CieloEndpoints live, CieloEndpoints test) {
    this.liveSales = new CieloSalesClient(http, live);
    this.testSales = new CieloSalesClient(http, test);
    this.liveCards = new CieloCardClient(http, live);
    this.testCards = new CieloCardClient(http, test);
  }

  @Override
  public String id() {
    return "CIELO";
  }

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  @Override
  public void requireIssueCredentials(ProviderCredentials credentials) {
    credentialsOf(credentials);
  }

  @Override
  public CardAuthorization issue(ProviderCredentials credentials, CardIssueRequest request) {
    return SaleResponses.toAuthorization(
        sales(credentials).authorize(credentialsOf(credentials), SaleRequestFactory.from(request)),
        request.source());
  }

  @Override
  public Optional<CardAuthorization> find(ProviderCredentials credentials, String bankReference) {
    return sales(credentials)
        .findByPaymentId(credentialsOf(credentials), bankReference)
        .map(sale -> SaleResponses.toAuthorization(sale, null));
  }

  @Override
  public Optional<CardAuthorization> findByOrder(
      ProviderCredentials credentials, String merchantOrderId) {
    List<String> paymentIds =
        sales(credentials).findPaymentIdsByOrder(credentialsOf(credentials), merchantOrderId);

    if (paymentIds.isEmpty()) {
      return Optional.empty();
    }

    return find(credentials, paymentIds.getFirst());
  }

  /** A void of an authorization is total by definition: no amount, and the answer must be 10. */
  @Override
  public void cancel(ProviderCredentials credentials, String bankReference) {
    SaleUpdateResponse voided =
        sales(credentials).voidSale(credentialsOf(credentials), bankReference, Optional.empty());
    CardStatus status = CieloStatuses.of(voided.status());

    if (status != CardStatus.VOIDED) {
      throw new ProviderException(
          ProviderException.Code.CONFLICT,
          200,
          voided.returnCode(),
          "void answered status " + status);
    }
  }

  /**
   * The capture answer carries no captured amount or date (reference/capturar-apos-autorizacao),
   * which is what the payment stores: one GET after it, on the query host (plan D17).
   */
  @Override
  public CardAuthorization capture(
      ProviderCredentials credentials, String bankReference, Optional<Money> amount) {
    sales(credentials).capture(credentialsOf(credentials), bankReference, amount);

    return find(credentials, bankReference)
        .orElseThrow(
            () ->
                new ProviderException(
                    ProviderException.Code.UNKNOWN,
                    0,
                    null,
                    "sale " + bankReference + " not found right after its capture"));
  }

  @Override
  public CardRefundResult refund(
      ProviderCredentials credentials, String bankReference, Optional<Money> amount) {
    SaleUpdateResponse refunded =
        sales(credentials).voidSale(credentialsOf(credentials), bankReference, amount);

    return new CardRefundResult(
        CieloStatuses.of(refunded.status()),
        amount.orElse(null),
        refunded.returnCode(),
        refunded.returnMessage());
  }

  @Override
  public StoredCard tokenize(ProviderCredentials credentials, CardData card, String customerName) {
    String token =
        cards(credentials)
            .create(
                credentialsOf(credentials),
                CardTokenRequestFactory.from(card, PersonName.of(customerName)))
            .cardToken();

    return new StoredCard(token, card.brand(), card.last4(), card.expiry().value());
  }

  @Override
  public CardNotification parseWebhook(byte[] body) {
    NotificationBody notification = mapper.readValue(body, NotificationBody.class);

    if (notification.paymentId() == null || notification.paymentId().isBlank()) {
      throw new IllegalArgumentException("notification without PaymentId");
    }

    int changeType = notification.changeType() == null ? 0 : notification.changeType();
    return new CardNotification(
        notification.paymentId(),
        CHANGE_TYPES.getOrDefault(changeType, CardNotificationKind.IGNORED),
        changeType);
  }

  /**
   * CREDENTIALS_INCOMPLETE with the field as providerType: CieloCredentials starts every message
   * with the field name, so the merchant's 422 can say which one.
   */
  private static CieloCredentials credentialsOf(ProviderCredentials credentials) {
    try {
      return CieloCredentials.parse(credentials.payload());
    } catch (IllegalArgumentException e) {
      String field = e.getMessage().split(" ")[0];
      throw new ProviderException(
          ProviderException.Code.CREDENTIALS_INCOMPLETE,
          0,
          field,
          "the CIELO credential is incomplete: " + e.getMessage());
    }
  }

  private CieloSalesClient sales(ProviderCredentials credentials) {
    return credentials.environment() == ProviderEnvironment.LIVE ? liveSales : testSales;
  }

  private CieloCardClient cards(ProviderCredentials credentials) {
    return credentials.environment() == ProviderEnvironment.LIVE ? liveCards : testCards;
  }
}
