package com.gateway.app.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.core.JsonGenerator;
import java.io.IOException;
import net.logstash.logback.composite.JsonWritingUtils;
import net.logstash.logback.composite.loggingevent.MessageJsonProvider;

/**
 * Overrides {@link MessageJsonProvider#writeTo}, the exact point encoder 8.1 offers for this: read
 * its source in {@code logstash-logback-encoder-8.1-sources.jar} — {@code writeTo} is the only
 * method that touches the message text, calling {@code event.getFormattedMessage()} directly with
 * no seam in between. Masking one call earlier than the base class (before {@code writeTo} even
 * runs) would need reflection into a private field; overriding {@code writeTo} instead keeps this a
 * five-line diff against the class the brief names.
 */
public class MaskingJsonProvider extends MessageJsonProvider {

  @Override
  public void writeTo(JsonGenerator generator, ILoggingEvent event) throws IOException {
    JsonWritingUtils.writeStringField(
        generator, getFieldName(), Masker.mask(event.getFormattedMessage()));
  }
}
