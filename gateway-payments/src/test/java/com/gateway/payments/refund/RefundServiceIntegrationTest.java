package com.gateway.payments.refund;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.pix.RefundResult;
import com.gateway.kernel.provider.pix.RefundStatus;
import com.gateway.payments.jobs.JobRunner;
import com.gateway.payments.jobs.JobType;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class RefundServiceIntegrationTest extends ServiceIntegrationTestBase {

  @Autowired RefundService refunds;
  @Autowired RefundPollingService polling;
  @Autowired PaymentRepository payments;
  @Autowired JobRepository jobs;
  @Autowired PlatformTransactionManager txManager;

  @BeforeEach
  void bankAnswersProcessing() {
    bank.nextRefundStatus(RefundStatus.PROCESSING);
  }

  Payment paid(long cents) {
    Payment p = newCharge(cents);
    // The bank agrees, so a reconciliation pass from another test's runner finds nothing to flag.
    bank.markPaid(p.id(), "E2E" + p.id(), Money.brl(cents));
    return new TransactionTemplate(txManager)
        .execute(
            s -> {
              Payment loaded = payments.findById(p.id()).orElseThrow();
              return payments.save(
                  loaded,
                  List.of(
                      loaded.markCompleted(
                          "E2E" + p.id(),
                          Money.brl(cents),
                          clock.instant(),
                          EventSource.PROVIDER_WEBHOOK)));
            });
  }

  @Test
  void requestOnCompletedPaymentIsProcessingAndEnqueuesPolling() {
    Payment p = paid(1000);

    Refund r = refunds.request(merchant, p.id(), Money.brl(400));

    assertThat(r.state()).isEqualTo(RefundState.PROCESSING);
    assertThat(jobs.findByTypeAndRef(JobType.POLL_REFUND, r.id())).isPresent();
    assertThat(outboxTypes(r.id())).containsExactly("refund.requested");
    assertThat(outboxPayload(r.id(), "refund.requested"))
        .contains("\"payment_id\":\"" + p.id() + "\"")
        .contains("\"state\":\"PROCESSING\"");
    assertThat(refunds.list(merchant, p.id())).extracting(Refund::id).containsExactly(r.id());
  }

  @Test
  void nullAmountRefundsWhatIsLeft() {
    Payment p = paid(1000);
    refunds.request(merchant, p.id(), Money.brl(300));

    Refund rest = refunds.request(merchant, p.id(), null);

    assertThat(rest.amount()).isEqualTo(Money.brl(700));
  }

  @Test
  void sumAboveAmountIsRefused() {
    Payment p = paid(1000);
    refunds.request(merchant, p.id(), Money.brl(600));

    assertThatThrownBy(() -> refunds.request(merchant, p.id(), Money.brl(500)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("REFUND_EXCEEDS_AMOUNT");
  }

  @Test
  void pendingPaymentCannotBeRefunded() {
    Payment p = newCharge(1000);
    assertThatThrownBy(() -> refunds.request(merchant, p.id(), Money.brl(1)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("INVALID_STATE");
  }

  @Test
  void afterNinetyDaysIsRefused() {
    Payment p = paid(1000);
    clock.advance(Duration.ofDays(91));

    assertThatThrownBy(() -> refunds.request(merchant, p.id(), Money.brl(100)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("REFUND_WINDOW_CLOSED");
  }

  @Test
  void providerUpdateCompletesAndProjects() {
    Payment p = paid(1000);
    Refund r = refunds.request(merchant, p.id(), Money.brl(400));

    refunds.applyProviderUpdate(
        new RefundResult(
            r.id(),
            RefundStatus.COMPLETED,
            Money.brl(400),
            null,
            clock.instant(),
            clock.instant()));

    assertThat(refunds.get(merchant, r.id()).state()).isEqualTo(RefundState.COMPLETED);
    assertThat(payments.findById(p.id()).orElseThrow().refundedAmount()).isEqualTo(Money.brl(400));
    assertThat(outboxTypes(r.id())).containsExactly("refund.requested", "refund.completed");

    // A second notification of the same settlement is a no-op, not a double refund.
    refunds.applyProviderUpdate(
        new RefundResult(
            r.id(),
            RefundStatus.COMPLETED,
            Money.brl(400),
            null,
            clock.instant(),
            clock.instant()));
    assertThat(payments.findById(p.id()).orElseThrow().refundedAmount()).isEqualTo(Money.brl(400));
  }

  @Test
  void providerUpdateFailedKeepsPaymentUntouched() {
    Payment p = paid(1000);
    Refund r = refunds.request(merchant, p.id(), Money.brl(400));

    refunds.applyProviderUpdate(
        new RefundResult(
            r.id(),
            RefundStatus.FAILED,
            Money.brl(400),
            "saldo insuficiente",
            clock.instant(),
            null));

    Refund failed = refunds.get(merchant, r.id());
    assertThat(failed.state()).isEqualTo(RefundState.FAILED);
    assertThat(failed.failureReason()).isEqualTo("saldo insuficiente");
    assertThat(payments.findById(p.id()).orElseThrow().refundedAmount()).isEqualTo(Money.brl(0));
    assertThat(outboxTypes(r.id())).containsExactly("refund.requested", "refund.failed");
    // A failed refund frees its amount again.
    assertThat(refunds.request(merchant, p.id(), Money.brl(1000)).state())
        .isEqualTo(RefundState.PROCESSING);
  }

  @Autowired JobRunner runner;

  @Autowired
  com.gateway.payments.reconciliation.persistence.ReconciliationDivergenceRepository divergences;

  @Test
  void pollingThatNeverSettlesEndsUnknownKeepingTheReserve() {
    Payment p = paid(1000);
    Refund r = refunds.request(merchant, p.id(), Money.brl(400));
    // One poll left in the 288-attempt budget, and due now.
    jdbc.update(
        "UPDATE payments.jobs SET attempts = 287, next_run_at = ? WHERE type = 'POLL_REFUND' AND ref_id = ?",
        java.sql.Timestamp.from(clock.instant()),
        r.id());

    while (runner.runDue(clock.instant()) > 0) {}

    assertThat(jobs.findByTypeAndRef(JobType.POLL_REFUND, r.id()).orElseThrow().status())
        .isEqualTo("DEAD");
    Refund unknown = refunds.get(merchant, r.id());
    assertThat(unknown.state()).isEqualTo(RefundState.UNKNOWN);
    assertThat(outboxTypes(r.id())).containsExactly("refund.requested", "refund.unknown");
    assertThat(outboxPayload(r.id(), "refund.unknown")).contains("\"state\":\"UNKNOWN\"");
    assertThat(divergences.open())
        .filteredOn(
            d -> d.paymentId().equals(p.id()) && d.providerStatus().equals("REFUND_UNKNOWN"))
        .hasSize(1);
    // The 400 may still leave at the bank: it stays reserved.
    assertThatThrownBy(() -> refunds.request(merchant, p.id(), Money.brl(700)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("REFUND_EXCEEDS_AMOUNT");

    // A later word from the bank still settles it.
    refunds.applyProviderUpdate(
        new RefundResult(
            r.id(),
            RefundStatus.COMPLETED,
            Money.brl(400),
            null,
            clock.instant(),
            clock.instant()));
    assertThat(refunds.get(merchant, r.id()).state()).isEqualTo(RefundState.COMPLETED);
    assertThat(payments.findById(p.id()).orElseThrow().refundedAmount()).isEqualTo(Money.brl(400));
  }

  @Test
  void unavailableOnThePutIsProcessingAndPollingFailsItOnceTheBankNeverSawIt() {
    Payment p = paid(1000);
    bank.failNextRefundWith(
        new ProviderException(
            ProviderException.Code.UNAVAILABLE,
            503,
            null,
            "Servico indisponivel, cliente 123.456.789-09"));

    Refund r = refunds.request(merchant, p.id(), Money.brl(400));

    assertThat(r.state()).isEqualTo(RefundState.PROCESSING);
    assertThat(jobs.findByTypeAndRef(JobType.POLL_REFUND, r.id())).isPresent();
    // Inside the grace, "not found" may just be "not visible yet".
    clock.advance(Duration.ofMinutes(10));
    assertThat(polling.poll(r.id())).isFalse();
    assertThat(refunds.get(merchant, r.id()).state()).isEqualTo(RefundState.PROCESSING);

    clock.advance(Duration.ofMinutes(25));
    assertThat(polling.poll(r.id())).isTrue();
    Refund failed = refunds.get(merchant, r.id());
    assertThat(failed.state()).isEqualTo(RefundState.FAILED);
    assertThat(failed.failureReason()).isEqualTo("refund not found at the bank");
    assertThat(outboxTypes(r.id())).containsExactly("refund.requested", "refund.failed");
  }

  @Test
  void aRefusedPutFailsAtOnceWithAFixedMessage() {
    Payment p = paid(1000);
    bank.failNextRefundWith(
        new ProviderException(
            ProviderException.Code.INVALID,
            400,
            "ValorInvalido",
            "valor acima do permitido para 123.456.789-09"));

    assertThatThrownBy(() -> refunds.request(merchant, p.id(), Money.brl(400)))
        .isInstanceOf(DomainException.class)
        .hasMessage("The bank declined the request.")
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("PROVIDER_DECLINED");
    Refund failed = refunds.list(merchant, p.id()).getFirst();
    assertThat(failed.state()).isEqualTo(RefundState.FAILED);
    assertThat(failed.failureReason()).doesNotContain("123.456");
    assertThat(outboxPayload(failed.id(), "refund.failed"))
        .doesNotContain("123.456")
        .doesNotContain("valor acima");
  }

  @Test
  void pollingRetriesEveryFiveMinutes() {
    Payment p = paid(1000);
    Refund r = refunds.request(merchant, p.id(), Money.brl(400));
    clock.advance(Duration.ofMinutes(5));

    while (runner.runDue(clock.instant()) > 0) {}

    var job = jobs.findByTypeAndRef(JobType.POLL_REFUND, r.id()).orElseThrow();
    assertThat(job.status()).isEqualTo("PENDING");
    assertThat(job.attempts()).isEqualTo(1);
    assertThat(job.nextRunAt()).isEqualTo(clock.instant().plus(Duration.ofMinutes(5)));
  }

  @Test
  void concurrentRefundsCannotTogetherExceedTheAmount() throws Exception {
    Payment p = paid(1000);
    java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    java.util.concurrent.Callable<String> attempt =
        () -> {
          start.await();
          try {
            refunds.request(merchant, p.id(), Money.brl(600));
            return "ok";
          } catch (DomainException e) {
            return e.code();
          }
        };
    var a = pool.submit(attempt);
    var b = pool.submit(attempt);
    start.countDown();
    List<String> outcomes = List.of(a.get(), b.get());
    pool.shutdown();

    assertThat(outcomes).containsExactlyInAnyOrder("ok", "REFUND_EXCEEDS_AMOUNT");
    assertThat(refunds.list(merchant, p.id())).hasSize(1);
  }

  @Test
  void settlementBumpsThePaymentVersion() {
    Payment p = paid(1000);
    long before = payments.findById(p.id()).orElseThrow().version();
    Refund r = refunds.request(merchant, p.id(), Money.brl(400));

    refunds.applyProviderUpdate(
        new RefundResult(
            r.id(),
            RefundStatus.COMPLETED,
            Money.brl(400),
            null,
            clock.instant(),
            clock.instant()));

    assertThat(payments.findById(p.id()).orElseThrow().version()).isEqualTo(before + 1);
    assertThat(payments.events(p.id()).getLast().type()).isEqualTo("refund_completed");
  }

  @Test
  void pollingClosesTheRefundWhenTheBankSettles() {
    Payment p = paid(1000);
    Refund r = refunds.request(merchant, p.id(), Money.brl(400));

    assertThat(polling.poll(r.id())).isFalse();
    bank.refundResult(
        new RefundResult(
            r.id(),
            RefundStatus.COMPLETED,
            Money.brl(400),
            null,
            clock.instant(),
            clock.instant()));
    assertThat(polling.poll(r.id())).isTrue();

    assertThat(refunds.get(merchant, r.id()).state()).isEqualTo(RefundState.COMPLETED);
  }
}
