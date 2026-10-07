package com.gateway.app.observability;

import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.gateway.kernel.provider.ProviderException;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

/**
 * A bank that is down or slow is operations, not a bug: forty lines of HttpClient frames per
 * reconciliation pass buried the one line that mattered. Collapsed only for UNAVAILABLE and
 * TIMEOUT; UNAUTHENTICATED, INVALID and anything that is not a ProviderException keep the stack,
 * because someone has to read those. The chain's classes and messages stay, so the root cause is
 * still on the line (DECISOES 2026-10-07).
 */
final class ExpectedProviderFailure {
  private static final Set<ProviderException.Code> EXPECTED =
      EnumSet.of(ProviderException.Code.UNAVAILABLE, ProviderException.Code.TIMEOUT);

  private ExpectedProviderFailure() {}

  static Optional<String> oneLine(IThrowableProxy proxy) {
    if (!(proxy instanceof ThrowableProxy throwableProxy)) {
      return Optional.empty();
    }
    Throwable logged = throwableProxy.getThrowable();

    StringJoiner chain = new StringJoiner(" ← ");
    boolean expected = false;
    Map<Throwable, Boolean> seen = new IdentityHashMap<>();
    for (Throwable current = logged; current != null; current = current.getCause()) {
      if (seen.put(current, Boolean.TRUE) != null) {
        return Optional.empty();
      }
      if (current instanceof ProviderException provider && !expected) {
        if (!EXPECTED.contains(provider.code())) {
          return Optional.empty();
        }
        expected = true;
      }
      chain.add(describe(current));
    }

    return expected ? Optional.of(chain.toString()) : Optional.empty();
  }

  private static String describe(Throwable thrown) {
    String name = thrown.getClass().getSimpleName();
    if (thrown instanceof ProviderException provider) {
      name = name + " " + provider.code();
    }
    String message = thrown.getMessage();

    return message == null || message.isBlank() ? name : name + ": " + message;
  }
}
