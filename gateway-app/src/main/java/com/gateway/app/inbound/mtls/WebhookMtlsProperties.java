package com.gateway.app.inbound.mtls;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The dedicated connector the bank's inbound webhook arrives on. {@code port <= 0} turns it off
 * (the test profile does, so the other integration tests do not all fight over 8443). {@code
 * publicHost} is only used to render the URL a merchant registers at the bank; the connector binds
 * all interfaces.
 */
@ConfigurationProperties(prefix = "gateway.webhooks.mtls")
public record WebhookMtlsProperties(
    int port,
    String keystore,
    String keystorePassword,
    String truststore,
    String truststorePassword,
    String publicHost,
    Integer maxBodyBytes,
    List<String> allowedSubjects) {

  public static final int DEFAULT_MAX_BODY_BYTES = 262_144;

  public WebhookMtlsProperties {
    if (maxBodyBytes == null || maxBodyBytes <= 0) {
      maxBodyBytes = DEFAULT_MAX_BODY_BYTES;
    }
    allowedSubjects =
        allowedSubjects == null
            ? List.of()
            : allowedSubjects.stream()
                .filter(subject -> subject != null && !subject.isBlank())
                .toList();
  }

  public boolean enabled() {
    return port > 0;
  }

  /**
   * Readiness guard: an enabled connector without key material would either fail deep inside Tomcat
   * with an unrelated-looking stack trace or, worse, be "fixed" by someone dropping the client-cert
   * requirement. Failing here names the property to set.
   */
  public void requireKeyMaterial() {
    if (!enabled()) {
      return;
    }
    if (keystore == null || keystore.isBlank() || truststore == null || truststore.isBlank()) {
      throw new IllegalStateException(
          "gateway.webhooks.mtls.port="
              + port
              + " but gateway.webhooks.mtls.keystore/truststore are not set"
              + " (WEBHOOK_MTLS_KEYSTORE, WEBHOOK_MTLS_TRUSTSTORE); set them, or set WEBHOOK_MTLS_PORT=0 to disable the inbound webhook connector");
    }
  }

  /** The URL a merchant registers at the bank, or null when the connector is off. */
  public String inboundWebhookUrl(String token) {
    if (!enabled()) {
      return null;
    }
    String host = publicHost == null || publicHost.isBlank() ? "localhost" : publicHost;
    return "https://" + host + ":" + port + "/v1/providers/itau/webhooks/" + token;
  }
}
