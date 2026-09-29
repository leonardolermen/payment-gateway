package com.gateway.providers.cielo.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.ProviderEnvironment;
import org.junit.jupiter.api.Test;

/** The four hosts of reference/como-usar-o-sandbox and the "Produção" row of each page. */
class CieloEndpointsTest {

  @Test
  void productionAndSandboxEachHaveATransactionalAndAQueryHost() {
    CieloEndpoints live = CieloEndpoints.forEnvironment(ProviderEnvironment.LIVE);
    CieloEndpoints test = CieloEndpoints.forEnvironment(ProviderEnvironment.TEST);

    assertThat(live.api()).hasToString("https://api.cieloecommerce.cielo.com.br");
    assertThat(live.apiQuery()).hasToString("https://apiquery.cieloecommerce.cielo.com.br");
    assertThat(test.api()).hasToString("https://apisandbox.cieloecommerce.cielo.com.br");
    assertThat(test.apiQuery()).hasToString("https://apiquerysandbox.cieloecommerce.cielo.com.br");
  }
}
