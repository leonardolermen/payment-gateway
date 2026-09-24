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
    assertThat(Masker.mask("key gk_test_01ARZ3NDEKTSV4RRFFQ69G5FAV used")).isEqualTo("key *** used");
  }

  @Test
  void masksSecretJsonFields() {
    assertThat(
            Masker.mask(
                "{\"client_secret\":\"abc\",\"secret\":\"x\",\"pix_copia_e_cola\":\"000201…\",\"name\":\"ok\"}"))
        .isEqualTo("{\"client_secret\":\"***\",\"secret\":\"***\",\"pix_copia_e_cola\":\"***\",\"name\":\"ok\"}");
  }

  @Test
  void leavesTheRestAlone() {
    assertThat(Masker.mask("payment pay_01ARZ3 COMPLETED at 15990 cents"))
        .isEqualTo("payment pay_01ARZ3 COMPLETED at 15990 cents");
  }
}
