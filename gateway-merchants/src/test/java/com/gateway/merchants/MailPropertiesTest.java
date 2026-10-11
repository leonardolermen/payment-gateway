package com.gateway.merchants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Properties;
import org.junit.jupiter.api.Test;

class MailPropertiesTest {
  private static MailProperties mail(String host, String username, String panelBaseUrl) {
    return new MailProperties(host, 587, username, "secret", "no-reply@loja.com", panelBaseUrl);
  }

  @Test
  void productionRefusesAnEmptyHost() {
    assertThatThrownBy(() -> mail("", null, "https://painel.loja.com").requireProductionReady())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("GATEWAY_MAIL_HOST");
  }

  @Test
  void productionRefusesALocalhostOrMissingPanelUrl() {
    assertThatThrownBy(
            () -> mail("smtp.loja.com", null, "http://localhost:5173").requireProductionReady())
        .hasMessageContaining("GATEWAY_PANEL_BASE_URL");
    assertThatThrownBy(() -> mail("smtp.loja.com", null, "").requireProductionReady())
        .hasMessageContaining("GATEWAY_PANEL_BASE_URL");
  }

  @Test
  void productionAcceptsARealHostAndPanel() {
    mail("smtp.loja.com", null, "https://painel.loja.com").requireProductionReady();
  }

  @Test
  void credentialsRequireStartTlsAndEveryConnectionHasTimeouts() {
    Properties withCredentials =
        MerchantsConfiguration.smtpProperties(
            mail("smtp.loja.com", "relay-user", "https://painel.loja.com"));

    assertThat(withCredentials)
        .containsEntry("mail.smtp.auth", "true")
        .containsEntry("mail.smtp.starttls.enable", "true")
        .containsEntry("mail.smtp.starttls.required", "true")
        .containsEntry("mail.smtp.connectiontimeout", "10000")
        .containsEntry("mail.smtp.timeout", "10000")
        .containsEntry("mail.smtp.writetimeout", "10000");

    Properties localRelay =
        MerchantsConfiguration.smtpProperties(mail("localhost", null, "http://localhost:5173"));

    assertThat(localRelay)
        .doesNotContainKey("mail.smtp.starttls.required")
        .containsEntry("mail.smtp.timeout", "10000");
  }
}
