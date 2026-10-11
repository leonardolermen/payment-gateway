package com.gateway.merchants.mail;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggingMailGatewayTest {
  private final Logger logger = (Logger) LoggerFactory.getLogger(LoggingMailGateway.class);
  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  @BeforeEach
  void attach() {
    appender.start();
    logger.addAppender(appender);
  }

  @AfterEach
  void detach() {
    logger.detachAppender(appender);
  }

  @Test
  void revealsTheLinkOnlyWhenAskedTo() {
    Email email =
        MailTemplates.resetPassword("ana@loja.com", "Ana", "https://painel/reset/gt_secret");

    new LoggingMailGateway(false).send(email);
    assertThat(lastMessage()).contains("ana@loja.com").doesNotContain("gt_secret");

    new LoggingMailGateway(true).send(email);
    assertThat(lastMessage()).contains("https://painel/reset/gt_secret");
  }

  private String lastMessage() {
    return appender.list.getLast().getFormattedMessage();
  }
}
