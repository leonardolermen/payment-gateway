package com.gateway.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MaskerTest {
  @Test
  void masksCpfWithAndWithoutPunctuation() {
    assertThat(Masker.mask("cpf 123.456.789-09 and 12345678909")).isEqualTo("cpf *** and ***");
  }

  @Test
  void masksApiKeyAndBearer() {
    assertThat(Masker.mask("Authorization: Bearer gk_live_01ARZ3NDEKTSV4RRFFQ69G5FAV"))
        .isEqualTo("Authorization: Bearer ***");
    assertThat(Masker.mask("key gk_test_01ARZ3NDEKTSV4RRFFQ69G5FAV used"))
        .isEqualTo("key *** used");
  }

  @Test
  void masksSecretJsonFields() {
    assertThat(
            Masker.mask(
                "{\"client_secret\":\"abc\",\"secret\":\"x\",\"pix_copia_e_cola\":\"000201…\",\"name\":\"ok\"}"))
        .isEqualTo(
            "{\"client_secret\":\"***\",\"secret\":\"***\",\"pix_copia_e_cola\":\"***\",\"name\":\"ok\"}");
  }

  @Test
  void leavesTheRestAlone() {
    assertThat(Masker.mask("payment pay_01ARZ3 COMPLETED at 15990 cents"))
        .isEqualTo("payment pay_01ARZ3 COMPLETED at 15990 cents");
  }

  /** Spec §7: 13–19 digits that pass Luhn become ****last4, however the payer grouped them. */
  @Test
  void masksCardNumbersThatPassLuhn() {
    assertThat(Masker.mask("number 4024007153763171 ok")).isEqualTo("number ****3171 ok");
    assertThat(Masker.mask("{\"number\":\"4024 0071 5376 3171\"}"))
        .isEqualTo("{\"number\":\"****3171\"}");
    assertThat(Masker.mask("pan 4024-0071-5376-3171")).isEqualTo("pan ****3171");
    assertThat(Masker.mask("amex 378282246310005")).isEqualTo("amex ****0005");
  }

  /** A long number that fails Luhn is not a card: an order id stays readable. */
  @Test
  void leavesLongNumbersThatFailLuhnAlone() {
    assertThat(Masker.mask("order 4024007153763172")).isEqualTo("order 4024007153763172");
  }

  @Test
  void masksTheCvvInOursAndTheCielosSpelling() {
    assertThat(Masker.mask("{\"cvv\":\"123\",\"SecurityCode\": \"4567\",\"holder\":\"JOAO\"}"))
        .isEqualTo("{\"cvv\":\"***\",\"SecurityCode\": \"***\",\"holder\":\"JOAO\"}");
  }
}
