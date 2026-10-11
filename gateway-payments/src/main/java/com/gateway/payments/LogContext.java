package com.gateway.payments;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Log-only context for work that has no request: which job, merchant, bank call. Never used for
 * {@code correlationId} — that key leaves the process (merchant webhooks, the bank's correlation
 * header), and a job inventing one would change what goes out. Pool threads are reused, so {@link
 * #close()} restores what was there before, nested scopes included.
 */
public final class LogContext implements AutoCloseable {
  private final Map<String, String> previous = new LinkedHashMap<>();

  private LogContext() {}

  public static LogContext with(String key, String value) {
    return new LogContext().and(key, value);
  }

  public LogContext and(String key, String value) {
    if (!previous.containsKey(key)) {
      previous.put(key, MDC.get(key));
    }

    if (value == null) {
      MDC.remove(key);
    } else {
      MDC.put(key, value);
    }

    return this;
  }

  @Override
  public void close() {
    previous.forEach(
        (key, old) -> {
          if (old == null) {
            MDC.remove(key);
          } else {
            MDC.put(key, old);
          }
        });
  }
}
