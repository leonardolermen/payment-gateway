package com.gateway.app.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class LoggingPropertiesTest {

  @Test
  void defaultsToJson() {
    assertThat(new LoggingProperties(null).format()).isEqualTo("json");
    assertThat(new LoggingProperties(" ").format()).isEqualTo("json");
  }

  @Test
  void acceptsJsonAndPretty() {
    assertThat(new LoggingProperties("json").format()).isEqualTo("json");
    assertThat(new LoggingProperties("pretty").format()).isEqualTo("pretty");
  }

  @Test
  void rejectsAnUnknownFormat() {
    assertThatThrownBy(() -> new LoggingProperties("prety"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("LOG_FORMAT")
        .hasMessageContaining("prety")
        .hasMessageContaining("json")
        .hasMessageContaining("pretty");
  }
}
