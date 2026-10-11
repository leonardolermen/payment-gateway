package com.gateway.app.mail;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.merchants.mail.MailTemplates;
import com.gateway.merchants.mail.OutboundEmailService;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import java.time.Clock;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** A queued e-mail goes out over SMTP through the jobs table and leaves no row behind. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "gateway.mail.host=127.0.0.1",
      "gateway.mail.port=3025",
      "gateway.mail.from=no-reply@test"
    })
@ActiveProfiles("test")
@Testcontainers
class SendEmailJobIntegrationTest {
  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @RegisterExtension
  static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP);

  @Autowired OutboundEmailService outbound;
  @Autowired JobRepository jobs;
  @Autowired Clock clock;
  @Autowired JdbcTemplate jdbc;

  @Test
  void sendsTheQueuedEmailAndDeletesTheRow() {
    String id =
        outbound.enqueue(
            MailTemplates.verifyEmail("ana@loja.com", "Ana", "http://painel/verify/gt_abc"));
    jobs.enqueue(Job.sendEmail(id, clock));

    Awaitility.await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              assertThat(MAIL.getReceivedMessages()).hasSize(1);
              assertThat(GreenMailUtil.getBody(MAIL.getReceivedMessages()[0])).contains("gt_abc");
            });

    // The row goes after the send returns: awaited too, or this races the runner's delete.
    Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () ->
                assertThat(
                        jdbc.queryForObject(
                            "SELECT count(*) FROM merchants.outbound_emails WHERE id = ?",
                            Integer.class,
                            id))
                    .isZero());
  }
}
