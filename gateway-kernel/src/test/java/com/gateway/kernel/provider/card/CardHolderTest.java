package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CardHolderTest {

  /**
   * Accents are kept here: the name is the payer's. Removing them is the Cielo text rule ("Não
   * aceita caracteres especiais ou acentuação"), applied by the provider (CieloText.holder).
   */
  @Test
  void keepsLettersAndSingleSpacesTrimmed() {
    assertThat(CardHolder.of("  JOÃO  DA SILVA ").value()).isEqualTo("JOÃO DA SILVA");
  }

  @Test
  void refusesDigitsSymbolsAndMoreThanTwentyFiveCharacters() {
    for (String invalid : new String[] {null, "", "  ", "JOAO 2", "JOAO@SILVA", "A".repeat(26)}) {
      assertThatThrownBy(() -> CardHolder.of(invalid))
          .as("holder %s", invalid)
          .isInstanceOf(InvalidCardValue.class)
          .extracting(thrown -> ((InvalidCardValue) thrown).field())
          .isEqualTo("holder");
    }
  }
}
