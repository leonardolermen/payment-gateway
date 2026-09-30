package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.YearMonth;
import org.junit.jupiter.api.Test;

class CardDataTest {
  static final YearMonth NOW = YearMonth.of(2026, 9);

  @Test
  void buildsFromWhatTheMerchantSentAndPrintsOnlyBrandAndLastFour() {
    CardData card = CardData.of("4024007153763171", "JOAO DA SILVA", "12/2030", "123", null, NOW);

    assertThat(card.brand()).isEqualTo(CardBrand.VISA);
    assertThat(card.last4()).isEqualTo("3171");
    assertThat(card.toString())
        .isEqualTo("VISA ****3171")
        .doesNotContain("4024007153763171")
        .doesNotContain("123")
        .doesNotContain("JOAO");
  }

  @Test
  void aBrandThatContradictsTheBinIsRefused() {
    assertThatThrownBy(
            () -> CardData.of("4024007153763171", "JOAO DA SILVA", "12/2030", "123", "MASTER", NOW))
        .isInstanceOf(InvalidCardValue.class)
        .satisfies(
            thrown -> {
              assertThat(((InvalidCardValue) thrown).field()).isEqualTo("brand");
              assertThat(((InvalidCardValue) thrown).reason())
                  .isEqualTo("does not match the card number (VISA)");
            });
  }

  @Test
  void anUnknownBinNeedsTheBrand() {
    assertThatThrownBy(
            () -> CardData.of("6062825624254001", "JOAO DA SILVA", "12/2030", "123", null, NOW))
        .isInstanceOf(InvalidCardValue.class)
        .extracting(thrown -> ((InvalidCardValue) thrown).reason())
        .isEqualTo("is required when the card number does not identify it");
  }

  /** Review Focus 2. */
  @Test
  void amexTakesFourDigitCvvAndVisaThree() {
    CardData amex = CardData.of("378282246310005", "JOAO DA SILVA", "12/2030", "1234", null, NOW);
    assertThat(amex.brand()).isEqualTo(CardBrand.AMEX);
    assertThat(amex.last4()).isEqualTo("0005");

    assertThatThrownBy(
            () -> CardData.of("378282246310005", "JOAO DA SILVA", "12/2030", "123", null, NOW))
        .isInstanceOf(InvalidCardValue.class)
        .satisfies(
            thrown -> {
              assertThat(((InvalidCardValue) thrown).field()).isEqualTo("cvv");
              assertThat(((InvalidCardValue) thrown).reason()).isEqualTo("must be 4 digits");
            });
    assertThatThrownBy(
            () -> CardData.of("4024007153763171", "JOAO DA SILVA", "12/2030", "1234", null, NOW))
        .isInstanceOf(InvalidCardValue.class)
        .extracting(thrown -> ((InvalidCardValue) thrown).reason())
        .isEqualTo("must be 3 digits");
  }

  @Test
  void theCvvIsASecret() {
    CardData card = CardData.of("4024007153763171", "JOAO DA SILVA", "12/2030", "123", null, NOW);

    assertThat(card.securityCode().toString()).isEqualTo("***");
    assertThat(card.securityCode().reveal()).isEqualTo("123");
  }

  @Test
  void aTokenNeverPrintsItsValueNorItsCvv() {
    CardToken token =
        new CardToken(
            "6e1bf77a-b28b-4660-b14f-455e2a1c95e9",
            CardBrand.VISA,
            CardOnFileUsage.USED,
            com.gateway.kernel.security.Secret.of("262"));

    assertThat(token.toString())
        .doesNotContain("6e1bf77a")
        .doesNotContain("262")
        .isEqualTo("CardToken[VISA, USED]");
  }
}
