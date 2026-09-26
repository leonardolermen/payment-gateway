package com.gateway.kernel.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.InvalidValue;
import org.junit.jupiter.api.Test;

class PersonNameTest {

  @Test
  void acceptsANameWithAtLeastOneLetter() {
    assertThat(PersonName.of("Ana Índia").value()).isEqualTo("Ana Índia");
  }

  /** A name is not trimmed away: the bank's layout truncates, it does not reject. */
  @Test
  void keepsTheNameAsTyped() {
    assertThat(PersonName.of("  Ana  ").value()).isEqualTo("  Ana  ");
  }

  @Test
  void refusesANameWithNoLetter() {
    for (String invalid : new String[] {null, "", "   ", "123", "---"}) {
      assertThatThrownBy(() -> PersonName.of(invalid))
          .as("name %s", invalid)
          .isInstanceOf(InvalidValue.class)
          .extracting(thrown -> ((InvalidValue) thrown).reason())
          .isEqualTo("is required");
    }
  }
}
