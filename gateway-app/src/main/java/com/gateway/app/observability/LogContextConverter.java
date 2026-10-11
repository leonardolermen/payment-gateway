package com.gateway.app.observability;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The pretty format's {@code %logContext}: the MDC keys a reader needs, in a fixed order, values
 * whole so an id can be pasted into a query. {@code jobId} stays out on purpose (JSON has it).
 */
public class LogContextConverter extends ClassicConverter {
  private static final Map<String, String> LABELS = new LinkedHashMap<>();

  static {
    LABELS.put("correlationId", "cid");
    LABELS.put("job", "job");
    LABELS.put("merchant", "merchant");
    LABELS.put("env", "env");
    LABELS.put("provider", "provider");
    LABELS.put("op", "op");
  }

  @Override
  public String convert(ILoggingEvent event) {
    Map<String, String> mdc = event.getMDCPropertyMap();
    StringJoiner context = new StringJoiner(" ", "[", "] ");
    context.setEmptyValue("");

    LABELS.forEach(
        (key, label) -> {
          String value = mdc.get(key);
          if (value != null && !value.isBlank()) {
            context.add(label + "=" + value);
          }
        });

    return context.toString();
  }
}
