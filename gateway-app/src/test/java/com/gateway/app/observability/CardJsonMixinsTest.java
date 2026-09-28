package com.gateway.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.security.Secret;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Spec §7: nothing the app's Jackson writes can carry a CardData, a CardToken or a Secret. */
class CardJsonMixinsTest {

  record Holder(String id, CardData card, CardToken token, Secret cvv) {}

  @Test
  void cardTypesAreLeftOutOfAnyJsonTheAppWrites() {
    JsonMapper.Builder builder = JsonMapper.builder();
    new CardJsonMixins().cardDataIsNeverSerialized().customize(builder);
    JsonMapper mapper = builder.build();

    String json =
        mapper.writeValueAsString(
            new Holder(
                "pay-1",
                CardData.of(
                    "4024007153763171",
                    "JOAO DA SILVA",
                    "12/2030",
                    "123",
                    null,
                    YearMonth.of(2026, 9)),
                new CardToken("tok", CardBrand.VISA, CardOnFileUsage.USED, Secret.of("123")),
                Secret.of("123")));

    assertThat(json).isEqualTo("{\"id\":\"pay-1\"}");
  }
}
