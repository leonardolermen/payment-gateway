package com.gateway.merchants.mail;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

public class SmtpMailGateway implements MailGateway {
  private final JavaMailSender sender;
  private final String from;

  public SmtpMailGateway(JavaMailSender sender, String from) {
    this.sender = sender;
    this.from = from;
  }

  @Override
  public void send(Email email) {
    MimeMessage message = sender.createMimeMessage();

    try {
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(from);
      helper.setTo(email.to());
      helper.setSubject(email.subject());
      helper.setText(email.text(), email.html());
    } catch (MessagingException e) {
      throw new IllegalStateException("could not build the e-mail to " + email.to(), e);
    }

    sender.send(message);
  }
}
