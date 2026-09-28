package com.gateway.app.observability;

import com.fasterxml.jackson.annotation.JsonIgnoreType;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardNumber;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.security.Secret;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spec §7 asks for {@code @JsonIgnoreType} on CardData; the kernel has no Jackson (plan C1), so the
 * annotation is applied here, as a mixin on the app's JsonMapper — the one that writes responses,
 * idempotency replays and JSON logs. A property of these types is left out of anything it writes.
 */
@Configuration(proxyBeanMethods = false)
public class CardJsonMixins {

  @JsonIgnoreType
  private interface NeverSerialized {}

  @Bean
  public JsonMapperBuilderCustomizer cardDataIsNeverSerialized() {
    return builder ->
        builder
            .addMixIn(CardData.class, NeverSerialized.class)
            .addMixIn(CardToken.class, NeverSerialized.class)
            .addMixIn(CardNumber.class, NeverSerialized.class)
            .addMixIn(Secret.class, NeverSerialized.class);
  }
}
