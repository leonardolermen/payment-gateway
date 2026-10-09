package com.gateway.merchants.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MailTemplatesTest {
  @Test
  void verifyEmailNamesThePersonAndCarriesTheLinkInBothBodies() {
    Email email = MailTemplates.verifyEmail("ana@loja.com", "Ana", "https://painel/verify/gt_abc");

    assertThat(email.to()).isEqualTo("ana@loja.com");
    assertThat(email.subject()).isEqualTo("Confirme seu e-mail");
    assertThat(email.text()).contains("Ana").contains("https://painel/verify/gt_abc");
    assertThat(email.html()).contains("href=\"https://painel/verify/gt_abc\"");
  }

  @Test
  void inviteNamesTheStore() {
    Email email = MailTemplates.invite("bia@loja.com", "Loja da Ana", "https://painel/invite/gt_x");

    assertThat(email.subject()).isEqualTo("Você foi convidado para Loja da Ana");
    assertThat(email.text()).contains("Loja da Ana").contains("/invite/gt_x");
  }

  @Test
  void aLongStoreNameIsCutToTheSubjectColumn() {
    String storeName = "L".repeat(250);

    Email email = MailTemplates.invite("bia@loja.com", storeName, "https://painel/invite/gt_x");

    assertThat(email.subject())
        .hasSize(200)
        .startsWith("Você foi convidado para LLL")
        .endsWith("…");
    assertThat(email.text()).contains(storeName);
  }
}
