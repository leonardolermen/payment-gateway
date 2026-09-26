package com.gateway.kernel.address;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.InvalidValue;
import org.junit.jupiter.api.Test;

class ZipCodeTest {

  @Test
  void keepsOnlyDigits() {
    assertThat(ZipCode.of("01310-100").digits()).isEqualTo("01310100");
    assertThat(ZipCode.of("01310100").digits()).isEqualTo("01310100");
  }

  @Test
  void refusesAnythingThatIsNotEightDigits() {
    for (String invalid : new String[] {null, "", "1310100", "013101000", "abcdefgh"}) {
      assertThatThrownBy(() -> ZipCode.of(invalid))
          .as("zip %s", invalid)
          .isInstanceOf(InvalidValue.class)
          .extracting(thrown -> ((InvalidValue) thrown).reason())
          .isEqualTo("must be 8 digits");
    }
  }
}
