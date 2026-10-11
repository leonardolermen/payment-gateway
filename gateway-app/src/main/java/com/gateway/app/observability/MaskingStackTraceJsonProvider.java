package com.gateway.app.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import net.logstash.logback.composite.JsonWritingUtils;
import net.logstash.logback.composite.loggingevent.StackTraceJsonProvider;

/**
 * The stack trace carries every exception message in the cause chain, and {@link
 * MaskingJsonProvider} only covers the log message: before this, {@code log.error("x", e)} with an
 * exception whose message held a {@code gk_live_…} key wrote the key verbatim into {@code
 * stack_trace}. Same seam as the message provider — {@code writeTo} is the only method in encoder
 * 8.1's {@code StackTraceJsonProvider} that writes the converted string. Expected bank failures are
 * written as one line ({@link ExpectedProviderFailure}).
 */
public class MaskingStackTraceJsonProvider extends StackTraceJsonProvider {

  @Override
  public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
    if (event.getThrowableProxy() != null) {
      String trace =
          ExpectedProviderFailure.oneLine(event.getThrowableProxy())
              .orElseGet(() -> getThrowableConverter().convert(event));
      JsonWritingUtils.writeStringField(generator, getFieldName(), Masker.mask(trace));
    }
  }
}
