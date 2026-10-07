package com.gateway.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LogContextConverterTest {

  private static LoggingEvent eventWithMdc(Map<String, String> mdc) {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext((LoggerContext) LoggerFactory.getILoggerFactory());
    event.setLoggerName("test");
    event.setLevel(Level.INFO);
    event.setMessage("x");
    event.setMDCPropertyMap(mdc);
    return event;
  }

  @Test
  void writesNothingWhenNoKnownKeyIsSet() {
    String context = new LogContextConverter().convert(eventWithMdc(Map.of("other", "1")));

    assertThat(context).isEmpty();
  }

  @Test
  void writesTheKnownKeysInAFixedOrderWithFullValues() {
    Map<String, String> mdc = new LinkedHashMap<>();
    mdc.put("op", "listCharges");
    mdc.put("merchant", "01M4922VMMAK6D2XF54CD58DQD");
    mdc.put("job", "RECONCILE");
    mdc.put("jobId", "01M492GSMHE11NYH0VZDW9K5JZ");
    mdc.put("correlationId", "7f3a9c");

    String context = new LogContextConverter().convert(eventWithMdc(mdc));

    assertThat(context)
        .isEqualTo(
            "[cid=7f3a9c job=RECONCILE merchant=01M4922VMMAK6D2XF54CD58DQD op=listCharges] ");
  }
}
