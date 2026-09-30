package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class CardBrandTest {

  @Test
  void recognisesTheBinsItKnows() {
    assertThat(CardBrand.fromBin("4024007153763171")).contains(CardBrand.VISA);
    assertThat(CardBrand.fromBin("5371542802050634")).contains(CardBrand.MASTER);
    assertThat(CardBrand.fromBin("2221000000000009")).contains(CardBrand.MASTER);
    assertThat(CardBrand.fromBin("378282246310005")).contains(CardBrand.AMEX);
    assertThat(CardBrand.fromBin("6362970000457013")).contains(CardBrand.ELO);
    assertThat(CardBrand.fromBin("4389350000000000")).contains(CardBrand.ELO);
    assertThat(CardBrand.fromBin("6011000990139424")).contains(CardBrand.DISCOVER);
    assertThat(CardBrand.fromBin("3530111333300000")).contains(CardBrand.JCB);
    assertThat(CardBrand.fromBin("30569309025904")).contains(CardBrand.DINERS);
    assertThat(CardBrand.fromBin("5078601870000127985")).contains(CardBrand.AURA);
  }

  /** Hipercard is not in the Cielo Brand list (plan table D4), so its BIN is simply unknown. */
  @Test
  void anUnknownBinIsEmptyNotAGuess() {
    assertThat(CardBrand.fromBin("6062825624254001")).isEqualTo(Optional.empty());
  }

  @Test
  void parsesTheApiSpellingCaseInsensitively() {
    assertThat(CardBrand.of("visa")).isEqualTo(CardBrand.VISA);
    assertThat(CardBrand.of("Master")).isEqualTo(CardBrand.MASTER);
    assertThatThrownBy(() -> CardBrand.of("HIPERCARD"))
        .isInstanceOf(InvalidCardValue.class)
        .extracting(thrown -> ((InvalidCardValue) thrown).reason())
        .isEqualTo("must be one of VISA, MASTER, AMEX, ELO, AURA, JCB, DINERS, DISCOVER");
  }

  @Test
  void amexIsTheOnlyFourDigitCvv() {
    for (CardBrand brand : CardBrand.values()) {
      assertThat(brand.cvvLength()).as("%s", brand).isEqualTo(brand == CardBrand.AMEX ? 4 : 3);
    }
  }
}
