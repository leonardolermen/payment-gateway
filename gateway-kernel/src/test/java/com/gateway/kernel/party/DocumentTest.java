package com.gateway.kernel.party;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.InvalidValue;
import org.junit.jupiter.api.Test;

class DocumentTest {

  @Test
  void acceptsACpfAndKeepsOnlyItsDigits() {
    assertThat(Document.of("529.982.247-25").digits()).isEqualTo("52998224725");
  }

  @Test
  void acceptsACnpj() {
    assertThat(Document.of("11.222.333/0001-81").digits()).isEqualTo("11222333000181");
  }

  @Test
  void refusesAnythingThatIsNotElevenOrFourteenDigits() {
    for (String invalid :
        new String[] {null, "", "123", "1234567890", "123456789012", "abcdefghijk"}) {
      assertThatThrownBy(() -> Document.of(invalid))
          .as("document %s", invalid)
          .isInstanceOf(InvalidValue.class)
          .extracting(thrown -> ((InvalidValue) thrown).reason())
          .isEqualTo("must be a CPF (11 digits) or CNPJ (14 digits)");
    }
  }
}
