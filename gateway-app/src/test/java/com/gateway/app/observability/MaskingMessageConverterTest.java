package com.gateway.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class MaskingMessageConverterTest {

  @Test
  void masksTheFormattedMessage() {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext((LoggerContext) LoggerFactory.getILoggerFactory());
    event.setLoggerName("test");
    event.setLevel(Level.INFO);
    event.setMessage("key {}");
    event.setArgumentArray(new Object[] {"gk_live_01ARZ3NDEKTSV4RRFFQ69G5FAV"});

    String line = new MaskingMessageConverter().convert(event);

    assertThat(line).isEqualTo("key ***");
  }
}
