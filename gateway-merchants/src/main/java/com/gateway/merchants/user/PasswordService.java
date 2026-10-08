package com.gateway.merchants.user;

import com.gateway.kernel.errors.DomainException;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Argon2id, 64 MB, 3 passes: ~150 ms on a laptop, the cost a login can pay and a brute force
 * cannot. The dummy hash is what authenticate() compares against when the e-mail is unknown, so
 * that path takes as long as a wrong password.
 */
public class PasswordService {
  private static final int MIN_LENGTH = 10;

  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 65536, 3);
  private final String dummyHash = encoder.encode("not-a-password-anyone-has");

  public String hash(String raw) {
    return encoder.encode(raw);
  }

  public boolean matches(String raw, String hash) {
    return encoder.matches(raw, hash);
  }

  public void burnTime(String raw) {
    encoder.matches(raw, dummyHash);
  }

  public void requireStrong(String raw) {
    if (raw == null || raw.length() < MIN_LENGTH) {
      throw new DomainException(
          "WEAK_PASSWORD", "password must have at least " + MIN_LENGTH + " characters");
    }
  }
}
