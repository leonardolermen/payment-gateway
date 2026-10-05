package com.gateway.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.jobs.Job;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.persistence.PaymentRepository;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The operator's routes over the job queue and the stuck payments. A class of its own rather than
 * more of {@code AdminOperationsIntegrationTest}: no bank is involved, and the rows are written
 * straight to the tables, aged by jdbc, since the app context has no clock to advance.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "gateway.rate-limit.requests-per-minute=1000")
@ActiveProfiles("test")
@Testcontainers
class AdminJobsIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

  @LocalServerPort int port;
  @Autowired JobRepository jobs;
  @Autowired PaymentRepository payments;
  @Autowired PlatformTransactionManager txManager;
  @Autowired JdbcTemplate jdbc;
  @Autowired Clock clock;

  @Test
  @SuppressWarnings("unchecked")
  void theOperatorListsJobsDeadFirstAndRerunsOne() {
    Job dead = deadJob();
    pendingJob(null);

    List<Map<String, Object>> listed = adminGet("/v1/admin/jobs?type=EXPIRE_ORDER", List.class);
    assertThat(listed).isNotEmpty();
    assertThat(listed.getFirst())
        .containsEntry("id", dead.id())
        .containsEntry("type", "EXPIRE_ORDER")
        .containsEntry("ref_id", dead.refId())
        .containsEntry("status", "DEAD")
        .containsEntry("attempts", 1)
        .containsEntry("last_error", "boom")
        .containsKeys("next_run_at", "claimed_at", "created_at");

    List<Map<String, Object>> onlyDead =
        adminGet("/v1/admin/jobs?status=DEAD&limit=200", List.class);
    assertThat(onlyDead).extracting(row -> row.get("status")).containsOnly("DEAD");

    Map<String, Object> rerun = adminPost("/v1/admin/jobs/" + dead.id() + "/run-now", null, 200);
    assertThat(rerun)
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 1)
        .containsEntry("last_error", "boom");
  }

  @Test
  void aJobAWorkerHoldsIsA409AndAnUnknownOneA404() {
    Job claimed = pendingJob(clock.instant());

    Map<String, Object> inFlight =
        adminPost("/v1/admin/jobs/" + claimed.id() + "/run-now", null, 409);
    assertThat(inFlight.get("type")).isEqualTo("urn:gateway:JOB_IN_FLIGHT");

    Map<String, Object> held =
        adminPost("/v1/admin/jobs/" + claimed.id() + "/give-up", Map.of("note", "x"), 409);
    assertThat(held.get("type")).isEqualTo("urn:gateway:JOB_NOT_PENDING");

    adminPost("/v1/admin/jobs/01UNKNOWN/run-now", null, 404);
    adminPost("/v1/admin/jobs/01UNKNOWN/give-up", Map.of("note", "x"), 404);
  }

  @Test
  void aFinishedJobIsNotRerunnable() {
    Job finished = pendingJob(null).done();
    jobs.save(finished);

    Map<String, Object> refused =
        adminPost("/v1/admin/jobs/" + finished.id() + "/run-now", null, 409);
    assertThat(refused.get("type")).isEqualTo("urn:gateway:JOB_NOT_RERUNNABLE");
  }

  @Test
  void aLimitOutsideOneToTwoHundredIsA400() {
    for (String limit : List.of("0", "201")) {
      http()
          .get()
          .uri("/v1/admin/jobs?limit=" + limit)
          .header("X-Admin-Key", "test-admin")
          .exchange()
          .expectStatus()
          .isBadRequest();
    }
  }

  @Test
  void givingUpNeedsANoteAndOnlyTakesAPendingJob() {
    Job pending = pendingJob(null);

    adminPost("/v1/admin/jobs/" + pending.id() + "/give-up", Map.of(), 400);
    adminPost("/v1/admin/jobs/" + pending.id() + "/give-up", Map.of("note", " "), 400);
    adminPost("/v1/admin/jobs/" + pending.id() + "/give-up", Map.of("note", "x".repeat(501)), 400);

    Map<String, Object> dead =
        adminPost(
            "/v1/admin/jobs/" + pending.id() + "/give-up",
            Map.of("note", "order cancelled by hand"),
            200);
    assertThat(dead)
        .containsEntry("status", "DEAD")
        .containsEntry("last_error", "order cancelled by hand");

    Map<String, Object> again =
        adminPost("/v1/admin/jobs/" + pending.id() + "/give-up", Map.of("note", "again"), 409);
    assertThat(again.get("type")).isEqualTo("urn:gateway:JOB_NOT_PENDING");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aCreatedPaymentPastTheThresholdIsListedAsStuck() {
    Payment payment = storedCreated();
    jdbc.update(
        "UPDATE payments.payments SET created_at = ? WHERE id = ?",
        Timestamp.from(clock.instant().minus(Duration.ofMinutes(11))),
        payment.id());

    Map<String, Object> stuck = adminGet("/v1/admin/payments/stuck", Map.class);

    List<Map<String, Object>> created = (List<Map<String, Object>>) stuck.get("created_too_long");
    Map<String, Object> row =
        created.stream()
            .filter(each -> payment.id().equals(each.get("id")))
            .findFirst()
            .orElseThrow();
    assertThat(row)
        .containsEntry("merchant_id", payment.merchantId().value())
        .containsEntry("method", "PIX")
        .containsEntry("provider", "ITAU")
        .containsEntry("status", "CREATED")
        .containsEntry("amount", 1000)
        .containsEntry("currency", "BRL")
        .containsKeys("created_at", "expires_at");
    assertThat(stuck).containsKey("pending_past_expiry");
  }

  private Job deadJob() {
    Instant now = clock.instant();
    Job job = Job.expireOrder(Ulid.next(), now, clock).reschedule(now, "boom", 1);
    jobs.enqueue(job);
    return job;
  }

  /** In an hour: the scheduler must not run it to DONE while the test looks at it. */
  private Job pendingJob(Instant claimedAt) {
    Job draft = Job.expireOrder(Ulid.next(), clock.instant().plusSeconds(3600), clock);
    Job job =
        new Job(
            draft.id(),
            draft.type(),
            draft.refId(),
            draft.nextRunAt(),
            draft.attempts(),
            draft.status(),
            claimedAt,
            draft.lastError(),
            draft.createdAt());
    jobs.enqueue(job);
    return job;
  }

  private Payment storedCreated() {
    Payment payment =
        Payment.create(
            MerchantId.next(),
            ProviderEnvironment.TEST,
            "ITAU",
            Money.brl(1000),
            null,
            null,
            null,
            3600,
            null,
            clock);

    return new TransactionTemplate(txManager)
        .execute(transaction -> payments.save(payment, List.of(payment.createdEvent())));
  }

  private RestTestClient http() {
    return RestTestClient.bindToServer().baseUrl("http://localhost:" + port).build();
  }

  private <T> T adminGet(String uri, Class<T> type) {
    return http()
        .get()
        .uri(uri)
        .header("X-Admin-Key", "test-admin")
        .exchange()
        .expectStatus()
        .isOk()
        .expectBody(type)
        .returnResult()
        .getResponseBody();
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> adminPost(String uri, Object body, int status) {
    RestTestClient.RequestBodySpec request =
        http().post().uri(uri).header("X-Admin-Key", "test-admin");
    if (body != null) {
      request.contentType(MediaType.APPLICATION_JSON).body(body);
    }

    return request
        .exchange()
        .expectStatus()
        .isEqualTo(status)
        .expectBody(Map.class)
        .returnResult()
        .getResponseBody();
  }
}
