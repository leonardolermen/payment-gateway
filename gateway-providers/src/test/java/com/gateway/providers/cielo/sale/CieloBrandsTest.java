package com.gateway.providers.cielo.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.card.CardBrand;
import org.junit.jupiter.api.Test;

/** The spelling of the Brand field (reference/criar-pagamento-credito). */
class CieloBrandsTest {

  @Test
  void everyBrandRoundTrips() {
    for (CardBrand brand : CardBrand.values()) {
      assertThat(CieloBrands.of(CieloBrands.nameOf(brand))).isEqualTo(brand);
    }
    assertThat(CieloBrands.nameOf(CardBrand.MASTER)).isEqualTo("Master");
    assertThat(CieloBrands.nameOf(CardBrand.JCB)).isEqualTo("JCB");
    assertThat(CieloBrands.of("VISA")).isEqualTo(CardBrand.VISA);
    assertThat(CieloBrands.of("Hipercard")).isNull();
  }
}
