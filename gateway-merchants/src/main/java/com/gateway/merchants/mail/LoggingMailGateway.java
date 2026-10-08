package com.gateway.merchants.mail;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Used when no SMTP host is configured. The link is a bearer token, so it reaches the log only
 * where {@code revealLinks} says so (local and test): there it is how a developer clicks through.
 */
public class LoggingMailGateway implements MailGateway {
  private static final Logger log = LoggerFactory.getLogger(LoggingMailGateway.class);
  private static final Pattern LINK = Pattern.compile("https?://\\S+");

  private final boolean revealLinks;

  public LoggingMailGateway(boolean revealLinks) {
    this.revealLinks = revealLinks;
  }

  @Override
  public void send(Email email) {
    Matcher link = LINK.matcher(email.text());

    if (revealLinks && link.find()) {
      log.info(
          "e-mail not sent (logged): to={} subject={} link={}",
          email.to(),
          email.subject(),
          link.group());
      return;
    }

    log.info("e-mail not sent (logged): to={} subject={}", email.to(), email.subject());
  }
}
