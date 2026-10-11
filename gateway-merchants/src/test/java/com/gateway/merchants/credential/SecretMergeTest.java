package com.gateway.merchants.credential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class SecretMergeTest {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Set<String> SECRETS = Set.of("client_secret", "private_key_pem");
  private static final Optional<byte[]> STORED =
      Optional.of(bytes("{\"client_id\":\"old-id\",\"client_secret\":\"old-secret\"}"));

  @Test
  void anAbsentSecretKeepsTheStoredOne() {
    JsonNode merged = merge("{\"client_id\":\"new-id\"}", STORED);

    assertThat(merged.get("client_secret").asString()).isEqualTo("old-secret");
    assertThat(merged.get("client_id").asString()).isEqualTo("new-id");
  }

  @Test
  void aSubmittedSecretReplacesTheStoredOne() {
    JsonNode merged = merge("{\"client_id\":\"id\",\"client_secret\":\"new-secret\"}", STORED);

    assertThat(merged.get("client_secret").asString()).isEqualTo("new-secret");
  }

  @Test
  void anEmptySecretRemovesIt() {
    JsonNode merged = merge("{\"client_id\":\"id\",\"client_secret\":\"\"}", STORED);

    assertThat(merged.has("client_secret")).isFalse();
  }

  @Test
  void anAbsentNonSecretStaysAbsent() {
    JsonNode merged = merge("{\"client_secret\":\"s\"}", STORED);

    assertThat(merged.has("client_id")).isFalse();
  }

  @Test
  void withNothingStoredTheSubmittedPayloadIsUnchanged() {
    JsonNode merged = merge("{\"client_id\":\"id\",\"client_secret\":\"s\"}", Optional.empty());

    assertThat(merged).isEqualTo(JSON.readTree("{\"client_id\":\"id\",\"client_secret\":\"s\"}"));
  }

  @Test
  void anEmptySecretWithNothingStoredIsAbsent() {
    JsonNode merged = merge("{\"client_id\":\"id\",\"private_key_pem\":\"\"}", Optional.empty());

    assertThat(merged.has("private_key_pem")).isFalse();
  }

  @Test
  void aPayloadThatIsNotAnObjectIsRejected() {
    assertThatThrownBy(() -> SecretMerge.merge(bytes("[1]"), STORED, SECRETS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("payload must be a JSON object");
    assertThatThrownBy(() -> SecretMerge.merge(bytes("{nope"), STORED, SECRETS))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("payload must be a JSON object");
  }

  private static JsonNode merge(String submitted, Optional<byte[]> stored) {
    return JSON.readTree(SecretMerge.merge(bytes(submitted), stored, SECRETS));
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
