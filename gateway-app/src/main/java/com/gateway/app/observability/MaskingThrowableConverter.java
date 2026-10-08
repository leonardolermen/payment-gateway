package com.gateway.app.observability;

import ch.qos.logback.classic.pattern.ExtendedThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.CoreConstants;
import java.util.Optional;

/**
 * The pretty format's {@code %maskedEx}. Being a ThrowableHandlingConverter is what stops logback
 * from appending its own unmasked {@code %ex} to a pattern that has none — do not replace this with
 * a plain ClassicConverter. Expected bank failures are written as one line ({@link
 * ExpectedProviderFailure}).
 */
public class MaskingThrowableConverter extends ExtendedThrowableProxyConverter {

  @Override
  public String convert(ILoggingEvent event) {
    Optional<String> oneLine = ExpectedProviderFailure.oneLine(event.getThrowableProxy());
    if (oneLine.isPresent()) {
      return "    " + Masker.mask(oneLine.get()) + CoreConstants.LINE_SEPARATOR;
    }

    return Masker.mask(super.convert(event));
  }
}
