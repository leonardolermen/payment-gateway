package com.gateway.providers.cielo;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Spec §7: CardNumber becomes first6******last4 and SecurityCode ***. The token and the merchant
 * key are credentials of the merchant's Cielo account, so they go too.
 */
class CieloPayloadMaskerTest {

  @Test
  void masksTheFourSensitiveFields() {
    String body =
        "{\"CardNumber\": \"4024007153763171\",\"SecurityCode\":\"123\","
            + "\"CardToken\":\"6e1bf77a-b28b-4660-b14f-455e2a1c95e9\",\"MerchantKey\":\"KEY\"}";

    assertThat(CieloPayloadMasker.mask(body))
        .isEqualTo(
            "{\"CardNumber\":\"402400******3171\",\"SecurityCode\":\"***\","
                + "\"CardToken\":\"***\",\"MerchantKey\":\"***\"}");
  }

  @Test
  void anAlreadyMaskedNumberAndNullAreLeftAlone() {
    assertThat(CieloPayloadMasker.mask("{\"CardNumber\":\"409168******7641\"}"))
        .isEqualTo("{\"CardNumber\":\"409168******7641\"}");
    assertThat(CieloPayloadMasker.mask(null)).isNull();
  }
}
