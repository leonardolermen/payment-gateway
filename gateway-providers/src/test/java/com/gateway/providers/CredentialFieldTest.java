package com.gateway.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class CredentialFieldTest {
  private record Raw(@JsonProperty("client_secret") String clientSecret) {}

  @Test
  void theFirstTokenOfAParserMessageIsTheField() {
    assertThat(CredentialField.of("client_secret is required")).isEqualTo("client_secret");
    assertThat(CredentialField.of("  beneficiary_id must be 12 digits"))
        .isEqualTo("beneficiary_id");
  }

  @Test
  void aMessageWithoutATokenHasNoField() {
    assertThat(CredentialField.of((String) null)).isNull();
    assertThat(CredentialField.of("   ")).isNull();
  }

  @Test
  void aJacksonFailureNamesTheFieldThroughItsPath() {
    JacksonException failure =
        catchThrowableOfType(
            JacksonException.class,
            () ->
                new ObjectMapper()
                    .readValue(
                        "{\"client_secret\":{\"nested\":1}}".getBytes(StandardCharsets.UTF_8),
                        Raw.class));

    assertThat(CredentialField.of(failure)).isEqualTo("client_secret");
  }

  @Test
  void aJacksonFailureWithoutAPathHasNoField() {
    JacksonException failure =
        catchThrowableOfType(
            JacksonException.class,
            () ->
                new ObjectMapper()
                    .readValue("not json".getBytes(StandardCharsets.UTF_8), Raw.class));

    assertThat(CredentialField.of(failure)).isNull();
  }
}
