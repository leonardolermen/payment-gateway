package com.gateway.providers.itau.boleto;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The query OpenAPI enum has accents ("Aguardando Crédito"); the mainframe may not. Both spellings
 * map.
 */
class BoletoSituationsTest {
  @ParameterizedTest
  @CsvSource({
    "Em Aberto,OPEN", "em aberto,OPEN", "EM ABERTO,OPEN",
    "Pago,PAID", "Liquidado,SETTLED", "Pagamento Rejeitado,PAYMENT_REJECTED",
    "Aguardando Crédito,AWAITING_CREDIT", "Aguardando Credito,AWAITING_CREDIT",
        "AGUARDANDO CRÉDITO,AWAITING_CREDIT",
    "Creditado,CREDITED", "Baixado,CANCELED"
  })
  void mapsEverySpelling(String in, String out) {
    assertThat(BoletoSituations.parse(in)).isEqualTo(BoletoSituation.valueOf(out));
  }

  @Test
  void unknownIsAProviderError() {
    assertThatThrownBy(() -> BoletoSituations.parse("Protestado"))
        .isInstanceOfSatisfying(
            ProviderException.class,
            e -> assertThat(e.code()).isEqualTo(ProviderException.Code.UNKNOWN));
    assertThatThrownBy(() -> BoletoSituations.parse(null)).isInstanceOf(ProviderException.class);
  }
}
