package com.gateway.app.observability;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/** The pretty format's {@code %maskedMsg}: same masking as {@link MaskingJsonProvider}. */
public class MaskingMessageConverter extends MessageConverter {

  @Override
  public String convert(ILoggingEvent event) {
    return Masker.mask(super.convert(event));
  }
}
