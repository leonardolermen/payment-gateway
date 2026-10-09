package com.gateway.providers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gateway.kernel.provider.ProbeResult;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class ProbePhrasesTest {
  private record Raw(@JsonProperty("merchant_key") String merchantKey) {}

  @Test
  void anIncompleteCredentialNamesOnlyTheField() {
    assertThat(ProbePhrases.incomplete(new IllegalArgumentException("pix_key is required")))
        .isEqualTo(new ProbeResult(false, "Credencial incompleta: pix_key"));
    assertThat(ProbePhrases.incomplete(new IllegalArgumentException((String) null)))
        .isEqualTo(new ProbeResult(false, "Credencial incompleta: ?"));
  }

  /** A secret stored as an object cannot bind: the property on the path is the field. */
  @Test
  void aFieldOfTheWrongTypeIsIncompleteWithItsName() {
    JacksonException failure = failingToBind("{\"merchant_key\":[1,2]}");

    assertThat(ProbePhrases.incomplete(failure))
        .isEqualTo(new ProbeResult(false, "Credencial incompleta: merchant_key"));
  }

  @Test
  void anUnreadablePayloadIsUnexpected() {
    JacksonException failure = failingToBind("not json at all");

    assertThat(ProbePhrases.incomplete(failure)).isEqualTo(ProbePhrases.UNEXPECTED);
  }

  private static JacksonException failingToBind(String json) {
    return catchThrowableOfType(
        JacksonException.class,
        () -> new ObjectMapper().readValue(json.getBytes(StandardCharsets.UTF_8), Raw.class));
  }
}
