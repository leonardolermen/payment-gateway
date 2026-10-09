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

  /**
   * Called under the prod profile. A production box with no SMTP would log the reset and invite
   * links nobody reads, and a localhost panel URL would e-mail links to the user's own machine:
   * both look like a working signup until the first person waits for an e-mail.
   */
  public void requireProductionReady() {
    if (!isConfigured()) {
      throw new IllegalStateException(
          "gateway.mail.host is empty: set GATEWAY_MAIL_HOST (required under the prod profile)");
    }

    if (panelBaseUrl.isBlank() || panelBaseUrl.startsWith("http://localhost")) {
      throw new IllegalStateException(
          "gateway.mail.panel-base-url points at localhost: set GATEWAY_PANEL_BASE_URL to the"
              + " panel's public URL (required under the prod profile)");
    }
  }

  public boolean hasCredentials() {
    return username != null && !username.isBlank();
  }
}
