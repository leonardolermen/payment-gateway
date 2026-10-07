package com.gateway.app.observability;

import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * The pretty format's {@code %maskedEx}. Being a ThrowableHandlingConverter is what stops logback
 * from appending its own unmasked {@code %ex} to a pattern that has none — do not replace this with
 * a plain ClassicConverter.
 */
public class MaskingThrowableConverter extends ExtendedThrowableProxyConverter {

  @Override
  public String convert(ILoggingEvent event) {
    return Masker.mask(super.convert(event));
  }
}
