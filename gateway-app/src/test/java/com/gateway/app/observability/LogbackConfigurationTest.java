package com.gateway.app.observability;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.env.MockEnvironment;

@ExtendWith(OutputCaptureExtension.class)
class LogbackConfigurationTest {
  private final LoggingSystem system = LoggingSystem.get(getClass().getClassLoader());

  private Logger configuredWith(String format) {
    // In a full module run a cached Spring context has already initialized logback; initialize()
    // is a no-op until the logging system is marked uninitialized, and this test would then read
    // that context's JSON configuration instead of its own.
    system.cleanUp();

    MockEnvironment environment = new MockEnvironment();
    if (format != null) {
      environment.setProperty("gateway.logging.format", format);
    }
    system.beforeInitialize();
    system.initialize(
        new LoggingInitializationContext(environment), "classpath:logback-spring.xml", null);
    return LoggerFactory.getLogger(LogbackConfigurationTest.class);
  }

  @AfterEach
  void reset() {
    MDC.clear();
    system.cleanUp();

    // Leave the JVM-wide logback on the production default for the Spring tests that run after.
    configuredWith(null);
  }

  @Test
  void jsonByDefault(CapturedOutput output) {
    configuredWith(null).info("hello json");

    assertThat(output.getOut()).contains("\"message\":\"hello json\"");
  }

  @Test
  void prettyWritesOneTextLineWithContext(CapturedOutput output) {
    MDC.put("job", "RECONCILE");
    configuredWith("pretty").info("hello gk_live_01ARZ3NDEKTSV4RRFFQ69G5FAV");

    assertThat(output.getOut())
        .containsPattern(
            "\\d{2}:\\d{2}:\\d{2}\\.\\d{3} +INFO +LogbackConfigurationTest +\\[job=RECONCILE\\]"
                + " hello \\*\\*\\*")
        .doesNotContain("\"message\"")
        .doesNotContain("gk_live_01ARZ3");
  }

  @Test
  void unknownFormatStillWritesJson(CapturedOutput output) {
    configuredWith("prety").info("still visible");

    assertThat(output.getOut()).contains("\"message\":\"still visible\"");
  }

  @Test
  void startupNoiseIsQuiet(CapturedOutput output) {
    configuredWith("pretty");
    LoggerFactory.getLogger(
            "org.springframework.data.repository.config.RepositoryConfigurationDelegate")
        .info("Bootstrapping Spring Data JPA repositories in DEFAULT mode.");

    assertThat(output.getOut()).doesNotContain("Bootstrapping");
  }
}
