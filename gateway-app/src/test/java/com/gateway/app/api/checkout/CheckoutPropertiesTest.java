package com.gateway.app.api.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheckoutPropertiesTest {
  @Test
  void aBlankEnvVariableMeansNoOrigins() {
    assertThat(new CheckoutProperties(null, Arrays.asList(""), 60).corsOrigins()).isEmpty();
  }

  @Test
  void entriesAreStrippedAndBlanksDropped() {
    CheckoutProperties properties =
        new CheckoutProperties(null, List.of("https://a.test", " ", " https://b.test ", ""), 60);

    assertThat(properties.corsOrigins()).containsExactly("https://a.test", "https://b.test");
  }
}
