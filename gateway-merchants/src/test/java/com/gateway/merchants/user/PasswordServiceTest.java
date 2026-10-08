package com.gateway.merchants.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import org.junit.jupiter.api.Test;

class PasswordServiceTest {
  PasswordService passwords = new PasswordService();

  @Test
  void hashesWithArgon2idAndMatchesOnlyTheSamePassword() {
    String hash = passwords.hash("correct horse battery");

    assertThat(hash).startsWith("$argon2id$");
    assertThat(passwords.matches("correct horse battery", hash)).isTrue();
    assertThat(passwords.matches("correct horse batter", hash)).isFalse();
  }

  @Test
  void tenCharactersIsTheOnlyRule() {
    passwords.requireStrong("abcdefghij");

    assertThatThrownBy(() -> passwords.requireStrong("abcdefghi"))
        .isInstanceOf(DomainException.class)
        .hasMessageContaining("10");
  }
}
