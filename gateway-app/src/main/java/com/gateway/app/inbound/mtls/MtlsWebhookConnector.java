package com.gateway.app.inbound.mtls;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.http11.AbstractHttp11Protocol;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.TomcatWebServerFactory;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.stereotype.Component;

/**
 * A second Tomcat connector for the bank's inbound webhook. The bank authenticates only with a
 * client certificate issued by its CA, no HMAC (docs/providers/itau/NOTES.md), so this connector
 * requires one, and {@link MtlsPortFilter} keeps it and the plain API port from serving each
 * other's routes. A separate connector rather than client-auth=want on the main port: "want" would
 * let every API client negotiate a certificate, and a missing certificate would become a per-route
 * check instead of a handshake failure.
 */
@Component
@EnableConfigurationProperties(WebhookMtlsProperties.class)
public class MtlsWebhookConnector
    implements WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
  private final WebhookMtlsProperties properties;

  public MtlsWebhookConnector(WebhookMtlsProperties properties) {
    properties.requireKeyMaterial();
    this.properties = properties;
  }

  @Override
  public void customize(TomcatServletWebServerFactory factory) {
    if (!properties.enabled()) {
      return;
    }
    Connector connector = new Connector(TomcatWebServerFactory.DEFAULT_PROTOCOL);
    connector.setPort(properties.port());
    connector.setScheme("https");
    connector.setSecure(true);

    SSLHostConfig ssl = new SSLHostConfig();
    ssl.setCertificateVerification("required");
    ssl.setTruststoreFile(properties.truststore());
    ssl.setTruststorePassword(properties.truststorePassword());
    ssl.setTruststoreType("PKCS12");
    SSLHostConfigCertificate cert =
        new SSLHostConfigCertificate(ssl, SSLHostConfigCertificate.Type.UNDEFINED);
    cert.setCertificateKeystoreFile(properties.keystore());
    cert.setCertificateKeystorePassword(properties.keystorePassword());
    cert.setCertificateKeystoreType("PKCS12");
    ssl.addCertificate(cert);
    connector.addSslHostConfig(ssl);
    ((AbstractHttp11Protocol<?>) connector.getProtocolHandler()).setSSLEnabled(true);

    factory.addAdditionalConnectors(connector);
  }
}
