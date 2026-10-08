package com.gateway.merchants;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** SMTP for the panel's e-mails. An empty host means e-mails are logged, not sent. */
@ConfigurationProperties(prefix = "gateway.mail")
public record MailProperties(
    String host, int port, String username, String password, String from, String panelBaseUrl) {
  public MailProperties {
    if (port == 0) {
      port = 587;
    }
    if (panelBaseUrl == null || panelBaseUrl.isBlank()) {
      panelBaseUrl = "http://localhost:5173";
    }
  }

  public boolean isConfigured() {
    return host != null && !host.isBlank();
  }

  public boolean hasCredentials() {
    return username != null && !username.isBlank();
  }
}
