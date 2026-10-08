package com.gateway.app.mail;

import com.gateway.merchants.mail.MailGateway;
import com.gateway.merchants.mail.OutboundEmailService;
import com.gateway.payments.jobs.JobBackoff;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** SEND_EMAIL lives here: the job type is payments', the outbox and the gateway are merchants'. */
@Configuration(proxyBeanMethods = false)
public class MailJobConfiguration {
  @Bean
  public SendEmailJob sendEmailJob(
      OutboundEmailService outbound, MailGateway mail, JobBackoff backoff) {
    return new SendEmailJob(outbound, mail, backoff);
  }
}
