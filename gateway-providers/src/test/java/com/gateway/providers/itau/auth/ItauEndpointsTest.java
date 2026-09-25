package com.gateway.providers.itau.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.providers.itau.boleto.ItauBoletoEndpoints;
import java.net.URI;
import org.junit.jupiter.api.Test;

/** The URLs are the spec's (2026-09-25); production instruction calls use the STS URL the cash_management OpenAPI declares. */
class ItauEndpointsTest {
  @Test void liveBoletoEndpointsUseTheirOwnBasesAndTwoTokenUrls() {
    ItauBoletoEndpoints e = ItauBoletoEndpoints.forEnvironment(ProviderEnvironment.LIVE);
    assertThat(e.issue().apiBase()).isEqualTo(URI.create("https://pix-pj.api.itau.com/recebimentos-pix/v1"));
    assertThat(e.query().apiBase()).isEqualTo(URI.create("https://secure.api.cloud.itau.com.br/boletoscash/v2"));
    assertThat(e.instruction().apiBase()).isEqualTo(URI.create("https://api.gateway.itau.com.br/cash_management/v2"));
    assertThat(e.issue().tokenUrl()).isEqualTo(URI.create("https://sts.itau.com.br/as/token.oauth2"));
    assertThat(e.query().tokenUrl()).isEqualTo(URI.create("https://sts.itau.com.br/as/token.oauth2"));
    assertThat(e.instruction().tokenUrl()).isEqualTo(ItauEndpoints.CASH_MANAGEMENT_TOKEN_URL).isEqualTo(URI.create("https://sts.itau.com.br/api/oauth/token"));
    assertThat(e.issue().mutualTls()).isTrue();
    assertThat(e.instruction().mutualTls()).isTrue();
  }

  @Test void sandboxBoletoEndpointsShareThePortalTokenAndNoMtls() {
    ItauBoletoEndpoints e = ItauBoletoEndpoints.forEnvironment(ProviderEnvironment.TEST);
    assertThat(e.issue().apiBase()).isEqualTo(URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-api-recebimentos-v1-externo/v1"));
    assertThat(e.query().apiBase()).isEqualTo(URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-gtw-boletoscash-boletoscash-v2-ext-aws/v1"));
    assertThat(e.instruction().apiBase()).isEqualTo(URI.create("https://sandbox.devportal.itau.com.br/itau-ep9-gtw-cash-management-ext-v2/v2"));
    for (ItauEndpoints x : new ItauEndpoints[] {e.issue(), e.query(), e.instruction()}) {
      assertThat(x.tokenUrl()).isEqualTo(URI.create("https://sandbox.devportal.itau.com.br/api/oauth/jwt"));
      assertThat(x.mutualTls()).isFalse();
    }
  }
}
