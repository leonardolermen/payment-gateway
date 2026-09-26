package com.gateway.kernel.ids;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class UlidTest {
  @Test
  void is26CrockfordCharsAndValidates() {
    String id = Ulid.next();
    assertThat(id).hasSize(26).matches("[0-9A-HJKMNP-TV-Z]{26}");
    assertThat(Ulid.isValid(id)).isTrue();
    assertThat(Ulid.isValid("abc")).isFalse();
    assertThat(Ulid.isValid(null)).isFalse();
  }

  /**
   * The Pix txid is derived from this id: [a-zA-Z0-9]{26,35}. A ULID fits with no transformation.
   */
  @Test
  void fitsTheBacenTxidFormat() {
    assertThat(Ulid.next()).matches("[a-zA-Z0-9]{26,35}");
  }

  @Test
  void isMonotonicWithinTheSameMillisecond() {
    String a = Ulid.next(), b = Ulid.next();
    assertThat(a.compareTo(b)).isNegative();
  }
}
