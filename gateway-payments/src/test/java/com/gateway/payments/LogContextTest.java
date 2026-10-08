package com.gateway.payments;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class LogContextTest {

  @AfterEach
  void clear() {
    MDC.clear();
  }

  @Test
  void setsTheKeysAndRemovesThemOnClose() {
    try (LogContext context = LogContext.with("job", "RECONCILE").and("jobId", "01J")) {
      assertThat(MDC.get("job")).isEqualTo("RECONCILE");
      assertThat(MDC.get("jobId")).isEqualTo("01J");
    }

    assertThat(MDC.get("job")).isNull();
    assertThat(MDC.get("jobId")).isNull();
  }

  @Test
  void restoresTheOuterValueWhenNested() {
    try (LogContext outer = LogContext.with("op", "issue")) {
      try (LogContext inner = LogContext.with("op", "listCharges")) {
        assertThat(MDC.get("op")).isEqualTo("listCharges");
      }

      assertThat(MDC.get("op")).isEqualTo("issue");
    }
  }

  @Test
  void neverTouchesTheCorrelationId() {
    MDC.put("correlationId", "req-1");

    try (LogContext context = LogContext.with("job", "RECONCILE")) {
      assertThat(MDC.get("correlationId")).isEqualTo("req-1");
    }

    assertThat(MDC.get("correlationId")).isEqualTo("req-1");
  }
}
