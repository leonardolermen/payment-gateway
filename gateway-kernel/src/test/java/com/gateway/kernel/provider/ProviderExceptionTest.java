package com.gateway.kernel.provider;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderExceptionTest {
  @Test void carriesNormalizedCodeAndHttpStatus() {
    ProviderException e = new ProviderException(ProviderException.Code.INVALID, 400, "https://pix.bcb.gov.br/api/v2/error/CobOperacaoInvalida", "schema violation");
    assertThat(e.code()).isEqualTo(ProviderException.Code.INVALID);
    assertThat(e.httpStatus()).isEqualTo(400);
    assertThat(e.providerType()).contains("CobOperacaoInvalida");
    assertThat(e.getMessage()).contains("schema violation");
  }

  @Test void credentialsNeverPrintThePayload() {
    ProviderCredentials c = new ProviderCredentials("{\"client_secret\":\"x\"}".getBytes(), ProviderEnvironment.LIVE);
    assertThat(c.toString()).doesNotContain("client_secret").contains("LIVE");
  }
}
