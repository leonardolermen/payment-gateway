package com.gateway.app.api.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.provider.CredentialProbe;
import com.gateway.kernel.provider.ProbeResult;
import com.gateway.kernel.provider.ProviderCredentials;
import java.util.List;
import org.junit.jupiter.api.Test;

class CredentialProbesTest {
  private static CredentialProbe probe(String providerId) {
    return new CredentialProbe() {
      @Override
      public String providerId() {
        return providerId;
      }

      @Override
      public ProbeResult probe(ProviderCredentials credentials) {
        return new ProbeResult(true, providerId);
      }
    };
  }

  @Test
  void indexesOneProbePerProvider() {
    CredentialProbes probes = new CredentialProbes(List.of(probe("ITAU"), probe("CIELO")));

    assertThat(probes.forProvider("ITAU").probe(null).detail()).isEqualTo("ITAU");
    assertThat(probes.forProvider("CIELO").probe(null).detail()).isEqualTo("CIELO");
  }

  @Test
  void refusesTwoProbesForTheSameProvider() {
    assertThatThrownBy(() -> new CredentialProbes(List.of(probe("ITAU"), probe("ITAU"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ITAU");
  }

  @Test
  void anUnknownProviderIsAnIllegalArgument() {
    CredentialProbes probes = new CredentialProbes(List.of(probe("ITAU")));

    assertThatThrownBy(() -> probes.forProvider("BRADESCO"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("BRADESCO");
  }
}
