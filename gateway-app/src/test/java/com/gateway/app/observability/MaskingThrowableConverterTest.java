package com.gateway.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import com.gateway.kernel.provider.ProviderException;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class MaskingThrowableConverterTest {

  static LoggingEvent eventWith(Throwable thrown) {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext((LoggerContext) LoggerFactory.getILoggerFactory());
    event.setLoggerName("test");
    event.setLevel(Level.ERROR);
    event.setMessage("boom");
    event.setThrowableProxy(new ThrowableProxy(thrown));
    return event;
  }

  static MaskingThrowableConverter startedConverter() {
    MaskingThrowableConverter converter = new MaskingThrowableConverter();
    converter.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    converter.start();
    return converter;
  }

  @Test
  void masksTheStackTrace() {
    LoggingEvent event =
        eventWith(
            new IllegalStateException(
                "outer", new RuntimeException("rejected key gk_live_01ARZ3NDEKTSV4RRFFQ69G5FAV")));

    String trace = startedConverter().convert(event);

    assertThat(trace).contains("IllegalStateException").contains("rejected key ***");
    assertThat(trace).doesNotContain("gk_live_01ARZ3");
  }

  @Test
  void writesNothingWithoutAThrowable() {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerContext((LoggerContext) LoggerFactory.getILoggerFactory());
    event.setLoggerName("test");
    event.setLevel(Level.INFO);
    event.setMessage("fine");

    assertThat(startedConverter().convert(event)).isEmpty();
  }

  @Test
  void writesOneMaskedLineForAnExpectedProviderFailure() {
    ProviderException unavailable =
        new ProviderException(
            ProviderException.Code.UNAVAILABLE,
            "key gk_live_01ARZ3NDEKTSV4RRFFQ69G5FAV refused",
            new java.net.ConnectException());

    String trace = startedConverter().convert(eventWith(unavailable));

    assertThat(trace.strip().lines()).hasSize(1);
    assertThat(trace).contains("ProviderException UNAVAILABLE: key *** refused");
    assertThat(trace).doesNotContain("\tat ");
  }
}
