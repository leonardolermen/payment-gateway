package com.gateway.kernel.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.InvalidValue;
import org.junit.jupiter.api.Test;

class UfTest {

  /**
   * "sp" is a valid UF typed in lowercase, not a wrong one; the bank's enum is uppercase, so it is
   * normalized, not refused.
   */
  @Test
  void normalisesToUpperCase() {
    assertThat(Uf.of("sp").value()).isEqualTo("SP");
    assertThat(Uf.of(" rs ").value()).isEqualTo("RS");
  }

  @Test
  void refusesAnythingThatIsNotTwoLetters() {
    for (String invalid : new String[] {null, "", "S", "SPP", "S1", "12"}) {
      assertThatThrownBy(() -> Uf.of(invalid))
          .as("uf %s", invalid)
          .isInstanceOf(InvalidValue.class)
          .extracting(thrown -> ((InvalidValue) thrown).reason())
          .isEqualTo("must be a two-letter UF");
    }
  }
}
