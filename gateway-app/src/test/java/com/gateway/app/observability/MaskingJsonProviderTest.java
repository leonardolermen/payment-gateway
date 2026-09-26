package com.gateway.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * The encoder API the brief left open: {@code MessageJsonProvider.writeTo(JsonGenerator,
 * ILoggingEvent)} is the exact override point in logstash-logback-encoder 8.1 — verified by reading
 * {@code MessageJsonProvider}'s source in the local repository jar. This test is the arbiter it
 * asked for: it drives {@link MaskingJsonProvider} the same way the real encoder does, through a
 * {@link JsonGenerator} over a {@link StringWriter}, and checks the masked output rather than the
 * API shape.
 */
class MaskingJsonProviderTest {

  @Test
  void masksTheMessageFieldItWrites() throws Exception {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerName("test");
    event.setLevel(Level.INFO);
    event.setMessage("key gk_live_01ARZ3NDEKTSV4RRFFQ69G5FAV");
    event.setLoggerContext(
        (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory());

    MaskingJsonProvider provider = new MaskingJsonProvider();
    StringWriter writer = new StringWriter();
    JsonGenerator generator = new JsonFactory().createGenerator(writer);
    generator.writeStartObject();
    provider.writeTo(generator, event);
    generator.writeEndObject();
    generator.flush();

    String json = writer.toString();
    assertThat(json).contains("\"message\":\"key ***\"");
    assertThat(json).doesNotContain("gk_live_01ARZ3");
  }

  @Test
  void masksTheStackTraceItWrites() throws Exception {
    LoggingEvent event = new LoggingEvent();
    event.setLoggerName("test");
    event.setLevel(Level.ERROR);
    event.setMessage("boom");
    event.setThrowableProxy(
        new ch.qos.logback.classic.spi.ThrowableProxy(
            new IllegalStateException(
                "outer", new RuntimeException("rejected key gk_live_01ARZ3NDEKTSV4RRFFQ69G5FAV"))));
    event.setLoggerContext(
        (ch.qos.logback.classic.LoggerContext) LoggerFactory.getILoggerFactory());

    MaskingStackTraceJsonProvider provider = new MaskingStackTraceJsonProvider();
    provider.start();
    StringWriter writer = new StringWriter();
    JsonGenerator generator = new JsonFactory().createGenerator(writer);
    generator.writeStartObject();
    provider.writeTo(generator, event);
    generator.writeEndObject();
    generator.flush();

    String json = writer.toString();
    assertThat(json).contains("stack_trace").contains("rejected key ***");
    assertThat(json).doesNotContain("gk_live_01ARZ3");
  }
}
