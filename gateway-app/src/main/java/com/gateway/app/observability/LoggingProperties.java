package com.gateway.app.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Readiness guard for {@code LOG_FORMAT}. logback-spring.xml cannot fail on its own, so a typo
 * would quietly pick a format; failing here names the variable. The logback filter sends every
 * value that is not exactly "pretty" to JSON, which is what keeps this failure visible.
 */
@ConfigurationProperties(prefix = "gateway.logging")
public record LoggingProperties(String format) {

  public LoggingProperties {
    if (format == null || format.isBlank()) {
      format = "json";
    }
    if (!format.equals("json") && !format.equals("pretty")) {
      throw new IllegalStateException(
          "gateway.logging.format="
              + format
              + " is not a log format; set LOG_FORMAT to json (default) or pretty");
    }
  }
}
