package com.gateway.app.api.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.merchants.credential.Provider;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CredentialSummaryTest {
  @Test
  void onlyAllowListedPublicKeysAreCopiedAndSecretsAreOnlyFlagged() {
    String json =
        "{\"client_id\":\"id\",\"client_secret\":\"s\",\"pix_key\":\"k\","
            + "\"clientSecret\":\"leak\",\"client_secret \":\"leak2\",\"x_itau_apikey\":\"\"}";

    CredentialSummary summary =
        CredentialSummary.of(
            json.getBytes(StandardCharsets.UTF_8), ProviderCatalog.of(Provider.ITAU));

    assertThat(summary.publicFields())
        .containsOnly(entry("client_id", "id"), entry("pix_key", "k"));
    assertThat(summary.secretsSet())
        .containsOnly(
            entry("client_secret", true),
            entry("x_itau_apikey", false),
            entry("private_key_pem", false));
  }

  @Test
  void noneHasNothingToSay() {
    assertThat(CredentialSummary.none().publicFields()).isEmpty();
    assertThat(CredentialSummary.none().secretsSet()).isEmpty();
  }

  private static <V> java.util.Map.Entry<String, V> entry(String key, V value) {
    return java.util.Map.entry(key, value);
  }
}
