package com.gateway.merchants.mail;

/** The panel's e-mails, in pt-BR. The caller builds the link; the template only shows it. */
public final class MailTemplates {
  // outbound_emails.subject is VARCHAR(200): a longer subject fails the insert and the invite.
  private static final int MAX_SUBJECT_LENGTH = 200;

  private MailTemplates() {}

  public static Email verifyEmail(String to, String name, String link) {
    return new Email(
        to,
        "Confirme seu e-mail",
        "Olá, "
            + name
            + ".\n\nConfirme seu e-mail para ativar o ambiente de produção:\n"
            + link
            + "\n\nO link vale por 24 horas.",
        wrap(
            "Olá, " + escape(name) + ".",
            "Confirme seu e-mail para ativar o ambiente de produção.",
            link,
            "Confirmar e-mail",
            "O link vale por 24 horas."));
  }

  public static Email resetPassword(String to, String name, String link) {
    return new Email(
        to,
        "Redefinir senha",
        "Olá, "
            + name
            + ".\n\nPara criar uma nova senha, abra:\n"
            + link
            + "\n\nO link vale por 1 hora. Se não foi você, ignore este e-mail.",
        wrap(
            "Olá, " + escape(name) + ".",
            "Para criar uma nova senha, use o botão abaixo.",
            link,
            "Redefinir senha",
            "O link vale por 1 hora. Se não foi você, ignore este e-mail."));
  }

  public static Email invite(String to, String storeName, String link) {
    return new Email(
        to,
        subject("Você foi convidado para " + storeName),
        "Olá.\n\nVocê foi convidado para o painel de "
            + storeName
            + ". Para aceitar, abra:\n"
            + link
            + "\n\nO convite vale por 7 dias.",
        wrap(
            "Olá.",
            "Você foi convidado para o painel de " + storeName + ".",
            link,
            "Aceitar convite",
            "O convite vale por 7 dias."));
  }

  private static String subject(String text) {
    if (text.length() <= MAX_SUBJECT_LENGTH) {
      return text;
    }

    return text.substring(0, MAX_SUBJECT_LENGTH - 1) + "…";
  }

  /** {@code greeting} arrives escaped (it carries a name); the other texts are escaped here. */
  private static String wrap(
      String greeting, String lead, String link, String button, String footer) {
    return "<!doctype html><html lang=\"pt-BR\">"
        + "<body style=\"font-family:sans-serif;color:#1a1a1a\">"
        + "<p>"
        + greeting
        + "</p><p>"
        + escape(lead)
        + "</p>"
        + "<p><a href=\""
        + link
        + "\" style=\"background:#1d4ed8;color:#fff;padding:10px 16px;border-radius:8px;"
        + "text-decoration:none\">"
        + escape(button)
        + "</a></p>"
        + "<p style=\"color:#666;font-size:12px\">"
        + escape(footer)
        + "</p></body></html>";
  }

  private static String escape(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;");
  }
}
