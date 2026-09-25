package com.gateway.payments.payment.boleto;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import com.gateway.payments.jobs.JobRunner;
import com.gateway.payments.jobs.JobType;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BoletoPollingIntegrationTest extends ServiceIntegrationTestBase {
  @Autowired BoletoPollingService polling;
  @Autowired PaymentRepository payments;
  @Autowired JobRepository jobs;
  @Autowired JobRunner jobRunner;
  @Autowired com.gateway.payments.payment.ExpirationService expiration;

  /** runDue claims one batch; the shared context leaves earlier tests' jobs due at the same instant, so drain it. */
  private int drain(Instant at) {
    int total = 0;
    for (int n; (n = jobRunner.runDue(at)) > 0; ) total += n;
    return total;
  }

  private String nn(Payment p) { return p.boleto().nossoNumero(); }

  private List<Map<String, Object>> divergences(String paymentId) {
    return jdbc.queryForList("SELECT provider_status, status FROM payments.reconciliation_divergences WHERE payment_id = ? ORDER BY created_at", paymentId);
  }

  @Test
  void openAndAwaitingCreditKeepPolling() {
    Payment p = newBolecode(12990);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    boletos.setSituation(nn(p), BoletoSituation.AWAITING_CREDIT);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(divergences(p.id())).isEmpty();
  }

  @Test
  void paidSettledAndCreditedCompleteWithPaidViaBoleto() {
    for (BoletoSituation s : List.of(BoletoSituation.PAID, BoletoSituation.SETTLED, BoletoSituation.CREDITED)) {
      Payment p = newBolecode(12990);
      Instant paidAt = clock.instant().minus(Duration.ofHours(1));
      boletos.markPaid(nn(p), Money.brl(12990), paidAt);
      boletos.setSituation(nn(p), s);

      assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).as("%s", s).isTrue();

      Payment done = payments.findById(p.id()).orElseThrow();
      assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
      assertThat(done.boleto().paidVia()).isEqualTo(PaidVia.BOLETO);
      assertThat(done.paidAmount()).isEqualTo(Money.brl(12990));
      assertThat(done.paidAt()).isEqualTo(paidAt);
      assertThat(done.pix().endToEndId()).isNull();
      assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
      assertThat(outboxPayload(p.id(), "payment.completed")).contains("\"paid_via\":\"BOLETO\"").contains("\"status\":\"COMPLETED\"");
      assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending", "completed");
      assertThat(payments.events(p.id()).getLast().source()).isEqualTo(EventSource.PROVIDER_POLL);
      assertThat(payments.events(p.id()).getLast().payload()).contains("\"paidVia\": \"BOLETO\"");
    }
  }

  @Test
  void differentAmountIsADivergenceNotACompletion() {
    Payment p = newBolecode(12990);
    boletos.markPaid(nn(p), Money.brl(12000), clock.instant());
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    Payment still = payments.findById(p.id()).orElseThrow();
    assertThat(still.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("AMOUNT_MISMATCH");
    assertThat(payments.events(p.id()).getLast().type()).isEqualTo("ignored");
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
  }

  @Test
  void rejectedOpensADivergenceAndKeepsPolling() {
    Payment p = newBolecode(100);
    boletos.setSituation(nn(p), BoletoSituation.PAYMENT_REJECTED);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("BOLETO_REJECTED");
    // idempotent: a second look does not open a second row
    polling.check(p.id(), EventSource.PROVIDER_POLL);
    assertThat(divergences(p.id())).hasSize(1);
  }

  @Test
  void canceledAtTheBankIsADivergenceAndStopsPolling() {
    Payment p = newBolecode(100);
    boletos.setSituation(nn(p), BoletoSituation.CANCELED);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("CANCELED_AT_BANK");
  }

  @Test
  void twoEmptyAnswersOpenNotFoundAtBank() {
    Payment p = newBolecode(100);
    boletos.remove(nn(p));
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    assertThat(divergences(p.id())).isEmpty();
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("NOT_FOUND_AT_BANK");
    assertThat(payments.events(p.id()).stream().filter(e -> "ignored".equals(e.type())).count()).isEqualTo(2);
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
  }

  private Payment completedViaPix(String e2e) {
    Payment p = newBolecode(12990);
    bank.markPaid(p.pix().txid(), e2e, Money.brl(12990));
    assertThat(paymentService.settle(merchant, p.id(), bank.findCharge(null, p.pix().txid()).orElseThrow().firstPix().orElseThrow(), EventSource.PROVIDER_WEBHOOK))
        .isEqualTo(PaymentService.Settlement.COMPLETED);
    assertThat(payments.findById(p.id()).orElseThrow().boleto().paidVia()).isEqualTo(PaidVia.PIX);
    return p;
  }

  private void assertStillCompletedViaPix(Payment p) {
    Payment still = payments.findById(p.id()).orElseThrow();
    assertThat(still.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(still.boleto().paidVia()).isEqualTo(PaidVia.PIX);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
  }

  @Test
  void aBoletoSettledByItsOwnPixIsIgnored() {
    // The Bolecode QR settles the boleto at the bank too: every Pix-paid Bolecode looks "paid" on the next poll.
    Payment p = completedViaPix("E2E-QR");
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant(), "Pagamento via PIX");
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertStillCompletedViaPix(p);
    assertThat(divergences(p.id())).isEmpty();
    assertThat(payments.events(p.id()).getLast().type()).isEqualTo("ignored");
  }

  @Test
  void completedViaPixThenPaidThroughAnotherChannelIsADoublePayment() {
    Payment p = completedViaPix("E2E-QR3");
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant(), "Guichê de caixa");
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertStillCompletedViaPix(p);
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("DOUBLE_PAYMENT");
  }

  @Test
  void completedViaPixThenPaidWithoutAChannelIsIgnored() {
    Payment p = completedViaPix("E2E-QR4");
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant(), null);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertStillCompletedViaPix(p);
    assertThat(divergences(p.id())).isEmpty();
    assertThat(payments.events(p.id()).getLast().type()).isEqualTo("ignored");
  }

  @Test
  void completedViaPixWithTheBoletoStillOpenIsIgnored() {
    Payment p = newBolecode(12990);
    bank.markPaid(p.pix().txid(), "E2E-QR2", Money.brl(12990));
    paymentService.settle(merchant, p.id(), bank.findCharge(null, p.pix().txid()).orElseThrow().firstPix().orElseThrow(), EventSource.PROVIDER_WEBHOOK);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertThat(payments.events(p.id()).getLast().type()).isEqualTo("ignored");
    assertThat(divergences(p.id())).isEmpty();
  }

  @Test
  void stopsAfterTheLimitDatePlusTheGrace() {
    Payment p = paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, payer(), BoletoDates.today(clock), 0));
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    clock.advance(Duration.ofDays(3));
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void theRunnerPollsAndReschedulesSixHoursLater() {
    Payment p = newBolecode(100);
    Instant later = clock.instant().plus(Duration.ofHours(6)).plusSeconds(1);
    // The job is the only due one for this payment; other tests' jobs are not due at `later` unless they are, so filter by ref.
    assertThat(drain(later)).isGreaterThanOrEqualTo(1);
    var job = jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id()).orElseThrow();
    assertThat(job.status()).isEqualTo("PENDING");
    assertThat(job.attempts()).isEqualTo(1);
    assertThat(job.nextRunAt()).isEqualTo(later.plus(Duration.ofHours(6)));
    assertThat(boletos.callsFor(merchant, nn(p))).contains("findBoleto:" + nn(p));

    boletos.markPaid(nn(p), Money.brl(100), clock.instant());
    jdbc.update("UPDATE payments.jobs SET next_run_at = ? WHERE type = 'POLL_BOLETO' AND ref_id = ?", Timestamp.from(later), p.id());
    drain(later);
    assertThat(jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id()).orElseThrow().status()).isEqualTo("DONE");
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  void aBankFailureBacksOffButNeverBeyondThePollPeriod() {
    Payment p = newBolecode(100);
    boletos.failNextFindWith(merchant, nn(p), new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "down"));
    Instant later = clock.instant().plus(Duration.ofHours(6)).plusSeconds(1);
    drain(later);
    var job = jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id()).orElseThrow();
    assertThat(job.status()).isEqualTo("PENDING");
    assertThat(job.nextRunAt()).isEqualTo(later.plus(Duration.ofMinutes(1)));
    assertThat(job.lastError()).contains("UNAVAILABLE");
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
  }

  // --- ruling R2: CANCELED and FAILED stay watched until the limit date plus the grace ---

  @Test
  void aCanceledBolecodeKeepsBeingPolledAndABarcodePaidAfterTheBaixaIsBoletoPaid() {
    Payment p = newBolecode(12990);
    paymentService.cancel(merchant, p.id());
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).as("baixa'd, still within the window").isFalse();
    assertThat(divergences(p.id())).as("CANCELED at the bank is the expected picture").isEmpty();

    boletos.markPaid(nn(p), Money.brl(12990), clock.instant(), "Guichê de caixa");
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.CANCELED);
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("BOLETO_PAID");
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.canceled");
  }

  @Test
  void theCanceledPollStopsOnlyAfterTheLimitDatePlusTheGrace() {
    Payment p = paymentService.createBolecode(new PaymentService.CreateBolecode(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, payer(), BoletoDates.today(clock), 0));
    paymentService.cancel(merchant, p.id());
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    clock.advance(Duration.ofDays(3));
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertThat(divergences(p.id())).isEmpty();
  }

  @Test
  void aFailedBolecodeTheBankRegisteredAnywayAndThatGetsPaidIsBoletoPaid() {
    boletos.landNextIssueThenFailWith(new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    boletos.failNextFindWith(merchant, "00000001", new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "down"));
    assertThatThrownBy(() -> newBolecode(12990)).isInstanceOf(com.gateway.kernel.errors.DomainException.class);
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    var registered = boletos.status(nn(p));
    boletos.remove(nn(p)); // the bank's 202: not visible yet when the sweeper asks
    clock.advance(Duration.ofMinutes(11));
    expiration.sweepStuckCreated(clock.instant());
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);

    boletos.restore(nn(p), registered);
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).as("open, within the window").isFalse();
    assertThat(divergences(p.id())).isEmpty();
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant(), "Guichê de caixa");
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(divergences(p.id())).extracting(d -> d.get("provider_status")).containsExactly("BOLETO_PAID");
  }

  @Test
  void aFailedBolecodeTheBankNeverSawIsQuiet() {
    boletos.failNextIssueWith(new ProviderException(ProviderException.Code.DECLINED, 422, "422", "Vencimento menor que prazo mínimo"));
    assertThatThrownBy(() -> newBolecode(100)).isInstanceOf(com.gateway.kernel.errors.DomainException.class);
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isFalse();
    assertThat(divergences(p.id())).as("no NOT_FOUND_AT_BANK for an issue the bank refused").isEmpty();
    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "failed");
  }

  // --- duplicate completion ---

  @Test
  void aBoletoAlreadyCompletedViaBoletoSeenPaidAgainIsIgnored() {
    Payment p = newBolecode(12990);
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertThat(payments.findById(p.id()).orElseThrow().boleto().paidVia()).isEqualTo(PaidVia.BOLETO);

    assertThat(polling.check(p.id(), EventSource.PROVIDER_POLL)).isTrue();
    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending", "completed", "ignored");
    assertThat(payments.events(p.id()).getLast().payload()).contains("already settled");
    assertThat(divergences(p.id())).isEmpty();
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
  }
}
