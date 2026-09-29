package com.gateway.providers.cielo;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.card.CardHolder;
import org.junit.jupiter.api.Test;

/**
 * Holder: "Não aceita caracteres especiais ou acentuação" (reference/criar-pagamento-credito) and
 * code 214 "Credit Card Holder Must Have Only Letters". Customer.Name: "apenas a-z, A-Z".
 */
class CieloTextTest {

  @Test
  void theHolderIsTransliteratedNotStripped() {
    assertThat(CieloText.holder(CardHolder.of("JOÃO DA CONCEIÇÃO"))).isEqualTo("JOAO DA CONCEICAO");
    assertThat(CieloText.holder(CardHolder.of("Zoë Ñúñez"))).isEqualTo("Zoe Nunez");
  }

  @Test
  void theCustomerNameKeepsOnlyLettersAndSpaces() {
    assertThat(CieloText.customerName(PersonName.of("  Joana D'Arc-Silva 3 ")))
        .isEqualTo("Joana DArcSilva");
  }
}
