package com.gateway.app.inbound.mtls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WebhookMtlsPropertiesTest {

  @Test
  void enabledPortWithoutKeyMaterialFailsStartupNamingTheProperties() {
    assertThatThrownBy(() -> new MtlsWebhookConnector(new WebhookMtlsProperties(8443, "", "", "", "", null, null, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("gateway.webhooks.mtls.keystore").hasMessageContaining("WEBHOOK_MTLS_PORT=0");
    assertThatThrownBy(() -> new WebhookMtlsProperties(8443, "/ks.p12", "pw", null, null, null, null, null).requireKeyMaterial())
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void disabledConnectorNeedsNothingAndHasNoUrl() {
    WebhookMtlsProperties off = new WebhookMtlsProperties(0, null, null, null, null, null, null, null);
    assertThatCode(off::requireKeyMaterial).doesNotThrowAnyException();
    assertThat(off.inboundWebhookUrl("01ARZ3NDEKTSV4RRFFQ69G5FAV")).isNull();
  }

  @Test
  void urlUsesThePublicHostAndPort() {
    assertThat(new WebhookMtlsProperties(8443, "a", "", "b", "", "hooks.example.com", null, null).inboundWebhookUrl("TOKEN"))
        .isEqualTo("https://hooks.example.com:8443/v1/providers/itau/webhooks/TOKEN");
  }
}
