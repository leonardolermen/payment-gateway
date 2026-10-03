package com.gateway.providers.cielo.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardCustomer;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardIssueRequest;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardSource;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.provider.card.Installments;
import com.gateway.kernel.provider.card.SoftDescriptor;
import com.gateway.kernel.security.Secret;
import com.gateway.providers.cielo.sale.dto.SaleRequest;
import java.time.YearMonth;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

public class SaleRequestFactoryTest {
  static final ObjectMapper JSON = new ObjectMapper();
  static final YearMonth NOW = YearMonth.of(2026, 9);
  static final CardCustomer CUSTOMER =
      new CardCustomer(PersonName.of("João da Silva"), Document.of("123.456.789-09"), "j@x.com");

  public static CardIssueRequest request(CardSource source, boolean saveCard) {
    return new CardIssueRequest(
        "01K0PAYMENTIDULID000000000",
        Money.brl(12990),
        Installments.of(3),
        true,
        saveCard,
        SoftDescriptor.ofNullable("LOJA42"),
        source,
        CUSTOMER);
  }

  public static CardData visa() {
    return CardData.of("4024007153763171", "JOÃO DA SILVA", "12/2030", "123", null, NOW);
  }

  static CardToken masterToken() {
    return new CardToken(
        "6e1bf77a-b28b-4660-b14f-455e2a1c95e9",
        CardBrand.MASTER,
        CardOnFileUsage.USED,
        Secret.of("262"));
  }

  static JsonNode json(SaleRequest request) {
    return JSON.readTree(JSON.writeValueAsString(request));
  }

  @Test
  void aNewCardCarriesTheDocumentedFields() {
    JsonNode body = json(SaleRequestFactory.from(request(visa(), false)));

    assertThat(body.get("MerchantOrderId").asText()).isEqualTo("01K0PAYMENTIDULID000000000");
    assertThat(body.at("/Customer/Name").asText()).isEqualTo("Joao da Silva");
    assertThat(body.at("/Customer/Identity").asText()).isEqualTo("12345678909");
    assertThat(body.at("/Customer/IdentityType").asText()).isEqualTo("CPF");
    assertThat(body.at("/Payment/Type").asText()).isEqualTo("CreditCard");
    assertThat(body.at("/Payment/Amount").asLong()).isEqualTo(12990);
    assertThat(body.at("/Payment/Installments").asInt()).isEqualTo(3);
    assertThat(body.at("/Payment/Interest").asText()).isEqualTo("ByMerchant");
    assertThat(body.at("/Payment/Capture").asBoolean()).isTrue();
    assertThat(body.at("/Payment/SoftDescriptor").asText()).isEqualTo("LOJA42");
    assertThat(body.at("/Payment/CreditCard/CardNumber").asText()).isEqualTo("4024007153763171");
    assertThat(body.at("/Payment/CreditCard/Holder").asText()).isEqualTo("JOAO DA SILVA");
    assertThat(body.at("/Payment/CreditCard/ExpirationDate").asText()).isEqualTo("12/2030");
    assertThat(body.at("/Payment/CreditCard/SecurityCode").asText()).isEqualTo("123");
    assertThat(body.at("/Payment/CreditCard/Brand").asText()).isEqualTo("Visa");
    assertThat(body.at("/Payment/CreditCard/SaveCard").asBoolean()).isFalse();
    assertThat(body.at("/Payment/CreditCard").has("CardOnFile")).isFalse();
    assertThat(body.at("/Payment").has("InitiatedTransactionIndicator")).isFalse();
  }

  /**
   * Every key we send exists in one of the Cielo's own request examples, so a typo in a
   * {@code @JsonProperty} fails here instead of at the sandbox. CardOnFile and
   * InitiatedTransactionIndicator come from the "Cartão de crédito completo" example of the same
   * page, whose Customer block is not copied as a fixture.
   */
  @Test
  void everyKeyWeSendIsADocumentedKey() {
    Set<String> documented = new TreeSet<>();
    paths("", JSON.readTree(CieloFixtures.read("post_sales_request_simplified.json")), documented);
    paths("", JSON.readTree(CieloFixtures.read("post_sales_request_token.json")), documented);
    documented.addAll(
        Set.of(
            "/Payment/CreditCard/CardOnFile",
            "/Payment/CreditCard/CardOnFile/Usage",
            "/Payment/CreditCard/CardOnFile/Reason",
            "/Payment/InitiatedTransactionIndicator",
            "/Payment/InitiatedTransactionIndicator/Category",
            "/Payment/InitiatedTransactionIndicator/Subcategory"));

    Set<String> sent = new TreeSet<>();
    paths("", json(SaleRequestFactory.from(request(visa(), true))), sent);
    paths("", json(SaleRequestFactory.from(request(masterToken(), false))), sent);

    assertThat(documented).containsAll(sent);
  }

  /** docs/card-on-file: First on the charge that stores the card; Reason only with Used. */
  @Test
  void savingAVisaMarksTheFirstUse() {
    JsonNode body = json(SaleRequestFactory.from(request(visa(), true)));

    assertThat(body.at("/Payment/CreditCard/SaveCard").asBoolean()).isTrue();
    assertThat(body.at("/Payment/CreditCard/CardOnFile/Usage").asText()).isEqualTo("First");
    assertThat(body.at("/Payment/CreditCard/CardOnFile").has("Reason")).isFalse();
  }

  /** Spec §6.6, and the indicator only for Mastercard (plan D6). */
  @Test
  void aStoredMastercardIsUsedUnscheduledCustomerInitiated() {
    JsonNode body = json(SaleRequestFactory.from(request(masterToken(), false)));

    assertThat(body.at("/Payment/CreditCard/CardToken").asText())
        .isEqualTo("6e1bf77a-b28b-4660-b14f-455e2a1c95e9");
    assertThat(body.at("/Payment/CreditCard/SecurityCode").asText()).isEqualTo("262");
    assertThat(body.at("/Payment/CreditCard").has("CardNumber")).isFalse();
    assertThat(body.at("/Payment/CreditCard/CardOnFile/Usage").asText()).isEqualTo("Used");
    assertThat(body.at("/Payment/CreditCard/CardOnFile/Reason").asText()).isEqualTo("Unscheduled");
    assertThat(body.at("/Payment/InitiatedTransactionIndicator/Category").asText()).isEqualTo("C1");
    assertThat(body.at("/Payment/InitiatedTransactionIndicator/Subcategory").asText())
        .isEqualTo("CredentialsOnFile");
  }

  /**
   * A subscription charges the token with no customer present, so no CVV: an empty or null
   * SecurityCode key would be a field the acquirer validates, while an absent one is what the token
   * charge documents.
   */
  @Test
  void aStoredCardWithoutCvvOmitsTheSecurityCode() {
    CardToken recurring =
        new CardToken(
            "6e1bf77a-b28b-4660-b14f-455e2a1c95e9", CardBrand.MASTER, CardOnFileUsage.USED, null);

    JsonNode body = json(SaleRequestFactory.from(request(recurring, false)));

    assertThat(body.at("/Payment/CreditCard/CardToken").asText())
        .isEqualTo("6e1bf77a-b28b-4660-b14f-455e2a1c95e9");
    assertThat(body.at("/Payment/CreditCard").has("SecurityCode")).isFalse();
  }

  /** docs/card-on-file: "Bandeiras Suportadas: Mastercard, Visa, Elo". */
  @Test
  void anAmexTokenCarriesNoCardOnFileMarker() {
    CardToken amex = new CardToken("tok", CardBrand.AMEX, CardOnFileUsage.USED, Secret.of("1234"));

    JsonNode body = json(SaleRequestFactory.from(request(amex, false)));

    assertThat(body.at("/Payment/CreditCard").has("CardOnFile")).isFalse();
    assertThat(body.at("/Payment").has("InitiatedTransactionIndicator")).isFalse();
  }

  @Test
  void theRequestNeverPrintsTheCard() {
    SaleRequest request = SaleRequestFactory.from(request(visa(), false));

    assertThat(request.toString())
        .doesNotContain("4024007153763171")
        .isEqualTo("SaleRequest[01K0PAYMENTIDULID000000000]");
    assertThat(request.payment().creditCard().toString()).isEqualTo("CreditCard[***]");
  }

  static void paths(String prefix, JsonNode node, Set<String> into) {
    if (!node.isObject()) {
      return;
    }
    for (String name : node.propertyNames()) {
      into.add(prefix + "/" + name);
      paths(prefix + "/" + name, node.get(name), into);
    }
  }
}
