package com.gateway.kernel.security;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class SecretTest {
  @Test void toStringNeverReveals() {
    Secret s = Secret.of("gk_live_ABC");
    assertThat(s.toString()).doesNotContain("ABC").isEqualTo("***");
    assertThat(s.reveal()).isEqualTo("gk_live_ABC");
  }
  @Test void equalsByValue() {
    assertThat(Secret.of("x")).isEqualTo(Secret.of("x")).isNotEqualTo(Secret.of("y"));
  }
  @Test void rejectsBlank() {
    assertThatThrownBy(() -> Secret.of(" ")).isInstanceOf(IllegalArgumentException.class);
  }
}
