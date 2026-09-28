package com.gateway.providers;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.providers.ProvidersConfiguration.CieloProperties;
import com.gateway.providers.cielo.auth.CieloEndpoints;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class CieloPropertiesTest {

  @Test
  void unsetMeansTheDocumentedHostsAndThirtySeconds() {
    CieloProperties properties = new CieloProperties(null, null, null, null, null);

    assertThat(properties.live())
        .isEqualTo(CieloEndpoints.forEnvironment(ProviderEnvironment.LIVE));
    assertThat(properties.test())
        .isEqualTo(CieloEndpoints.forEnvironment(ProviderEnvironment.TEST));
    assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(30));
  }

  /** How the app's tests point the provider at WireMock. */
  @Test
  void eachHostCanBeOverridden() {
    CieloProperties properties =
        new CieloProperties(null, null, "http://localhost:1/api", "http://localhost:1/query", null);

    assertThat(properties.test().api()).hasToString("http://localhost:1/api");
    assertThat(properties.test().apiQuery()).hasToString("http://localhost:1/query");
  }
}
