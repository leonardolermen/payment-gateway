package com.gateway.merchants.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import java.util.concurrent.Semaphore;
import org.junit.jupiter.api.Test;

class PasswordServiceTest {
  PasswordService passwords = new PasswordService(8);

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

  @Test
  void answersAuthBusyInsteadOfQueueingAnotherSixtyFourMegabytes() {
    Semaphore permits = new Semaphore(1);
    PasswordService capped = new PasswordService(permits);
    String hash = capped.hash("correct horse battery");
    permits.acquireUninterruptibly();

    assertThatThrownBy(() -> capped.hash("correct horse battery"))
        .hasFieldOrPropertyWithValue("code", "AUTH_BUSY");
    assertThatThrownBy(() -> capped.matches("correct horse battery", hash))
        .hasFieldOrPropertyWithValue("code", "AUTH_BUSY");
    assertThatThrownBy(() -> capped.burnTime("correct horse battery"))
        .hasFieldOrPropertyWithValue("code", "AUTH_BUSY");

    permits.release();
    assertThat(capped.matches("correct horse battery", hash)).isTrue();
  }
}
