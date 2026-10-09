package com.gateway.app.observability;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/**
 * Both console appenders stay attached; this decides which one writes. Anything but exactly
 * "pretty" goes to JSON: an appender-ref by name would leave logback with no appender on a typo,
 * and then the {@link LoggingProperties} failure that reports the typo would print nowhere.
 */
public class LogFormatFilter extends Filter<ILoggingEvent> {
  private String format = "json";
  private String accept = "json";

  public void setFormat(String format) {
    this.format = format;
  }

  public void setAccept(String accept) {
    this.accept = accept;
  }

  @Override
  public FilterReply decide(ILoggingEvent event) {
    boolean isPretty = "pretty".equals(format);
    boolean wantsPretty = "pretty".equals(accept);

    return isPretty == wantsPretty ? FilterReply.NEUTRAL : FilterReply.DENY;
  }
}
