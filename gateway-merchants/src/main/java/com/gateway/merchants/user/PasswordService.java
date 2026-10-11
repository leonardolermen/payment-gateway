package com.gateway.merchants.user;

import com.gateway.kernel.errors.DomainException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/**
 * Argon2id, 64 MB, 3 passes: ~150 ms on a laptop, the cost a login can pay and a brute force
 * cannot. The dummy hash is what authenticate() compares against when the e-mail is unknown, so
 * that path takes as long as a wrong password.
 *
 * <p>Each hash holds 64 MB for its duration, so the number running at once is capped: a flood of
 * sign-ins would otherwise be a flood of 64 MB allocations and an out-of-memory for the whole app.
 * Past the cap the answer is a fast AUTH_BUSY (503), not a queue that keeps the memory promised.
 */
public class PasswordService {
  private static final int MIN_LENGTH = 10;
  // About one hash: a short burst still gets through, a sustained flood is refused quickly.
  private static final long PERMIT_WAIT_MILLIS = 200;

  private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(16, 32, 1, 65536, 3);
  private final String dummyHash = encoder.encode("not-a-password-anyone-has");
  private final Semaphore permits;

  public PasswordService(int maxConcurrentHashes) {
    this(new Semaphore(maxConcurrentHashes));
  }

  PasswordService(Semaphore permits) {
    this.permits = permits;
  }

  public String hash(String raw) {
    return withPermit(() -> encoder.encode(raw));
  }

  public boolean matches(String raw, String hash) {
    return withPermit(() -> encoder.matches(raw, hash));
  }

  public void burnTime(String raw) {
    withPermit(() -> encoder.matches(raw, dummyHash));
  }

  private <T> T withPermit(Supplier<T> work) {
    boolean acquired;
    try {
      acquired = permits.tryAcquire(PERMIT_WAIT_MILLIS, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      acquired = false;
    }

    if (!acquired) {
      throw new DomainException("AUTH_BUSY", "too many sign-ins at once; retry in a moment");
    }

    try {
      return work.get();
    } finally {
      permits.release();
    }
  }

  public void requireStrong(String raw) {
    if (raw == null || raw.length() < MIN_LENGTH) {
      throw new DomainException(
          "WEAK_PASSWORD", "password must have at least " + MIN_LENGTH + " characters");
    }
  }
}
