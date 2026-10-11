package com.gateway.merchants.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class EmailAddressTest {
  @Test
  void trimsAndNormalizesToLowerCase() {
    EmailAddress email = new EmailAddress("  Ana.Silva@Loja.COM ");

    assertThat(email.value()).isEqualTo("Ana.Silva@Loja.COM");
    assertThat(email.normalized()).isEqualTo("ana.silva@loja.com");
  }

  @Test
  void refusesWhatIsNotAnAddress() {
    assertThatThrownBy(() -> new EmailAddress("ana")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailAddress("ana@loja"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailAddress("")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailAddress("a".repeat(250) + "@x.com"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
