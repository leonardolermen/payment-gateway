package com.gateway.payments.payment.boleto;

import com.gateway.payments.payment.create.CreateBolecodePayment;
import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import com.gateway.payments.inbox.WebhookInboxService;
import com.gateway.payments.payment.ExpirationService;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.reconciliation.ReconciliationService;
import com.gateway.payments.refund.Refund;
import com.gateway.payments.refund.RefundService;
import com.gateway.payments.refund.RefundState;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BolecodeLifecycleIntegrationTest extends ServiceIntegrationTestBase {
  @Autowired PaymentRepository payments;
  @Autowired ExpirationService expiration;
  @Autowired ReconciliationService reconciliation;
  @Autowired WebhookInboxService webhookInbox;
  @Autowired RefundService refunds;
  @Autowired BoletoPollingService polling;

  private String nn(Payment p) {
    return p.boleto().nossoNumero();
  }

  private java.util.List<String> divergences(String paymentId) {
    return jdbc.queryForList("SELECT provider_status FROM payments.reconciliation_divergences WHERE payment_id = ? ORDER BY created_at", String.class, paymentId);
  }

  // --- cancel (baixa) ---

  @Test
  void cancelOpenBolecodeAsksTheBankThenIssuesTheBaixa() {
    Payment p = newBolecode(100);
    Payment canceled = paymentService.cancel(merchant, p.id());
    assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
    assertThat(boletos.callsFor(merchant, nn(p))).containsExactly("issueBoleto:" + nn(p), "findBoleto:" + nn(p), "cancelBoleto:" + nn(p));
    assertThat(boletos.status(nn(p)).situation()).isEqualTo(BoletoSituation.CANCELED);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.canceled");
  }

  @Test
  void cancelOfAPaidBolecodeCompletesItAndIsAlreadyPaid() {
    Payment p = newBolecode(12990);
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    assertThatThrownBy(() -> paymentService.cancel(merchant, p.id())).isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("ALREADY_PAID");
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.boleto().paidVia()).isEqualTo(PaidVia.BOLETO);
    assertThat(boletos.callsFor(merchant, nn(p))).doesNotContain("cancelBoleto:" + nn(p));
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
  }

  @Test
  void cancelThatLosesTheRaceToThePayerAsksAgainAndCompletes() {
    Payment p = newBolecode(12990);
    boletos.markPaidAfterNextFind(nn(p), Money.brl(12990), clock.instant());
    assertThatThrownBy(() -> paymentService.cancel(merchant, p.id())).isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("ALREADY_PAID");
    assertThat(boletos.callsFor(merchant, nn(p))).containsExactly("issueBoleto:" + nn(p), "findBoleto:" + nn(p), "cancelBoleto:" + nn(p), "findBoleto:" + nn(p));
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  void cancelWhoseRequeryAfterAConflictFailsIsAProviderErrorAndLeavesThePaymentPending() {
    Payment p = newBolecode(12990);
    boletos.afterNextFind(nn(p), () -> {
      boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
      boletos.failNextFindWith(merchant, nn(p), new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    });
    assertThatThrownBy(() -> paymentService.cancel(merchant, p.id())).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).startsWith("PROVIDER_"));
    assertThat(boletos.callsFor(merchant, nn(p))).containsExactly("issueBoleto:" + nn(p), "findBoleto:" + nn(p), "cancelBoleto:" + nn(p), "findBoleto:" + nn(p));
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
  }

  @Test
  void cancelWhosePreCheckFailsIsAProviderErrorAndSendsNoBaixa() {
    Payment p = newBolecode(100);
    boletos.failNextFindWith(merchant, nn(p), new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "down"));
    assertThatThrownBy(() -> paymentService.cancel(merchant, p.id())).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).startsWith("PROVIDER_"));
    assertThat(boletos.callsFor(merchant, nn(p))).doesNotContain("cancelBoleto:" + nn(p));
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void cancelOfABoletoPaidWithAnotherAmountIsRefusedWithoutClaimingCompleted() {
    Payment p = newBolecode(12990);
    boletos.markPaid(nn(p), Money.brl(12000), clock.instant());
    assertThatThrownBy(() -> paymentService.cancel(merchant, p.id()))
        .isInstanceOfSatisfying(DomainException.class, e -> {
          assertThat(e.code()).isEqualTo("ALREADY_PAID");
          assertThat(e.getMessage()).contains("under review").doesNotContain("now COMPLETED");
        });
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(divergences(p.id())).contains("AMOUNT_MISMATCH");
    assertThat(boletos.callsFor(merchant, nn(p))).doesNotContain("cancelBoleto:" + nn(p));
  }

  @Test
  void cancelIsRefusedWhenNotPending() {
    Payment p = newBolecode(100);
    paymentService.cancel(merchant, p.id());
    assertThatThrownBy(() -> paymentService.cancel(merchant, p.id())).isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo("INVALID_STATE");
  }

  // --- expiration on the limit date ---

  private Payment bolecodeExpiringToday() {
    return paymentService.create(new CreateBolecodePayment(merchant, ProviderEnvironment.TEST, Money.brl(12990), null, null, payer(), BoletoDates.today(clock), 0));
  }

  @Test
  void expirationWaitsForTheLimitDateNotTheDueDate() {
    Payment p = paymentService.create(new CreateBolecodePayment(merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, payer(), BoletoDates.today(clock), 5));
    clock.advance(Duration.ofDays(2));
    assertThat(expiration.expireOne(p.id(), clock.instant())).isFalse();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(boletos.callsFor(merchant, nn(p))).containsExactly("issueBoleto:" + nn(p));
  }

  @Test
  void expirationAfterTheLimitDateAsksTheBankAndExpiresAnOpenBoletoWithoutABaixa() {
    Payment p = bolecodeExpiringToday();
    clock.advance(Duration.between(clock.instant(), p.expiresAt()).plus(Duration.ofMinutes(6)));
    assertThat(expiration.expireOne(p.id(), clock.instant())).isTrue();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.EXPIRED);
    assertThat(boletos.callsFor(merchant, nn(p))).containsExactly("issueBoleto:" + nn(p), "findBoleto:" + nn(p));
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.expired");
  }

  @Test
  void expirationCompletesABoletoPaidOnTheLastDay() {
    Payment p = bolecodeExpiringToday();
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    clock.advance(Duration.between(clock.instant(), p.expiresAt()).plus(Duration.ofMinutes(6)));
    assertThat(expiration.expireOne(p.id(), clock.instant())).isTrue();
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.boleto().paidVia()).isEqualTo(PaidVia.BOLETO);
  }

  @Test
  void expirationLeavesAnAwaitingCreditBoletoPending() {
    Payment p = bolecodeExpiringToday();
    boletos.setSituation(nn(p), BoletoSituation.AWAITING_CREDIT);
    clock.advance(Duration.between(clock.instant(), p.expiresAt()).plus(Duration.ofMinutes(6)));
    assertThat(expiration.expireOne(p.id(), clock.instant())).isFalse();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void aLatePaymentAfterExpiryCompletesThroughThePoll() {
    Payment p = bolecodeExpiringToday();
    clock.advance(Duration.between(clock.instant(), p.expiresAt()).plus(Duration.ofMinutes(6)));
    expiration.expireOne(p.id(), clock.instant());
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.EXPIRED);
    reconciliation.reconcileAll(clock.instant());
    // The reconciliation's boleto pass looks at PENDING, CANCELED and FAILED, not EXPIRED; the poll job (still within limit + 2 days) is what completes an EXPIRED one.
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.EXPIRED);
    assertThat(polling.check(p.id(), com.gateway.payments.payment.EventSource.PROVIDER_POLL)).isTrue();
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payments.events(p.id()).getLast().source()).isEqualTo(com.gateway.payments.payment.EventSource.PROVIDER_POLL);
  }

  // --- stuck CREATED ---

  @Test
  void stuckCreatedBolecodeUnknownToTheBankFails() {
    boletos.failNextIssueWith(new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    assertThatThrownBy(() -> newBolecode(100)).isInstanceOf(DomainException.class);
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    clock.advance(Duration.ofMinutes(11));
    assertThat(expiration.sweepStuckCreated(clock.instant())).isGreaterThanOrEqualTo(1);
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(outboxTypes(p.id())).containsExactly("payment.failed");
  }

  @Test
  void stuckCreatedBolecodeTheBankIssuedIsAdoptedAndSettledIfPaid() {
    boletos.landNextIssueThenFailWith(new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    boletos.failNextFindWith(merchant, "00000001", new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "down"));
    assertThatThrownBy(() -> newBolecode(12990)).isInstanceOf(DomainException.class);
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    boletos.markPaid("00000001", Money.brl(12990), clock.instant());
    clock.advance(Duration.ofMinutes(11));
    expiration.sweepStuckCreated(clock.instant());
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.boleto().linhaDigitavel()).hasSize(47);
    assertThat(done.pix().txid()).isEqualTo(boletos.pixTxidFor(merchant, "00000001"));
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending", "completed");
  }

  // --- reconciliation ---

  @Test
  void reconciliationChecksPendingBolecodesOlderThanMinAge() {
    Payment p = newBolecode(12990);
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    reconciliation.reconcileAll(clock.instant());
    assertThat(payments.findById(p.id()).orElseThrow().status()).as("too young").isEqualTo(PaymentStatus.PENDING);
    clock.advance(Duration.ofMinutes(11));
    reconciliation.reconcileAll(clock.instant());
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(payments.events(p.id()).getLast().source()).isEqualTo(com.gateway.payments.payment.EventSource.RECONCILIATION);
  }

  @Test
  void theBoletoPassQueriesBolecodesOnlySoOldPixRowsCannotFillItsCap() {
    Payment pix = newCharge(500);
    clock.advance(Duration.ofMinutes(1));
    Payment bolecode = newBolecode(12990);
    var rows = payments.findByMethodAndStatusIn(com.gateway.kernel.payment.PaymentMethod.BOLECODE, java.util.EnumSet.of(PaymentStatus.PENDING), clock.instant().minus(Duration.ofHours(1)), 1000);
    assertThat(rows).extracting(Payment::method).containsOnly(com.gateway.kernel.payment.PaymentMethod.BOLECODE);
    assertThat(rows).extracting(Payment::id).contains(bolecode.id()).doesNotContain(pix.id());
  }

  // --- the QR side ---

  @Test
  void aPixWebhookOnABolecodeIsMatchedByTxidAndCompletesWithPaidViaPix() {
    Payment p = newBolecode(12990);
    String txid = p.pix().txid();
    bank.markPaid(txid, "E2E-QR-" + nn(p), Money.brl(12990));
    String inboxId = webhookInbox.accept("ITAU", merchant, "{}", ("E2E-QR-" + nn(p) + " " + txid + " 12990").getBytes(StandardCharsets.UTF_8));
    webhookInbox.process(inboxId);
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.boleto().paidVia()).isEqualTo(PaidVia.PIX);
    assertThat(done.pix().endToEndId()).isEqualTo("E2E-QR-" + nn(p));
    assertThat(bank.callsFor(txid)).contains("findCharge:" + txid);
    assertThat(jdbc.queryForObject("SELECT status FROM payments.webhook_inbox WHERE id = ?", String.class, inboxId)).isEqualTo("PROCESSED");

    Refund r = refunds.request(merchant, p.id(), Money.brl(1000));
    assertThat(r.state()).isEqualTo(RefundState.PROCESSING);
  }

  @Test
  void aRefundOfABoletoSettlementIsRefused() {
    Payment p = newBolecode(12990);
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant());
    paymentService.settleBoleto(merchant, p.id(), boletos.status(nn(p)), com.gateway.payments.payment.EventSource.PROVIDER_POLL);
    assertThatThrownBy(() -> refunds.request(merchant, p.id(), Money.brl(1000))).isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("REFUND_NOT_SUPPORTED"));
    assertThat(bank.callsFor(p.id())).noneMatch(c -> c.startsWith("requestRefund"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.refunds WHERE payment_id = ?", Long.class, p.id())).isZero();
  }

  // --- ruling R2 through reconciliation ---

  @Test
  void reconciliationSeesABarcodePaidAfterTheBaixaAsBoletoPaidWithoutGrowingTheLog() {
    Payment p = newBolecode(12990);
    paymentService.cancel(merchant, p.id());
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant(), "Guichê de caixa");
    reconciliation.reconcileAll(clock.instant());
    reconciliation.reconcileAll(clock.instant().plusSeconds(900));
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.CANCELED);
    assertThat(divergences(p.id())).containsExactly("BOLETO_PAID");
    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending", "canceled");
  }

  @Test
  void reconciliationSeesAFailedBolecodePaidAtTheBank() {
    boletos.landNextIssueThenFailWith(new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    boletos.failNextFindWith(merchant, "00000001", new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "down"));
    assertThatThrownBy(() -> newBolecode(12990)).isInstanceOf(DomainException.class);
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    var registered = boletos.status(nn(p));
    boletos.remove(nn(p));
    clock.advance(Duration.ofMinutes(11));
    expiration.sweepStuckCreated(clock.instant());
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    boletos.restore(nn(p), registered);
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant(), "Guichê de caixa");
    reconciliation.reconcileAll(clock.instant());
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(divergences(p.id())).containsExactly("BOLETO_PAID");
  }

  // --- ruling R3: the poll sees the QR's payment first ---

  private void pixWebhook(Payment p, String e2e) {
    String inboxId = webhookInbox.accept("ITAU", merchant, "{}", (e2e + " " + p.pix().txid() + " 12990").getBytes(StandardCharsets.UTF_8));
    webhookInbox.process(inboxId);
  }

  @Test
  void aPixChannelPaymentSeenByThePollCompletesViaPixAndIsRefundable() {
    Payment p = newBolecode(12990);
    String e2e = "E2E-POLL-" + nn(p);
    bank.markPaid(p.pix().txid(), e2e, Money.brl(12990));
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant(), "Pagamento via PIX");
    assertThat(polling.check(p.id(), com.gateway.payments.payment.EventSource.PROVIDER_POLL)).isTrue();
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.boleto().paidVia()).isEqualTo(PaidVia.PIX);
    assertThat(done.pix().endToEndId()).isEqualTo(e2e);

    pixWebhook(p, e2e);
    assertThat(payments.events(p.id()).getLast().type()).isEqualTo("ignored");
    assertThat(divergences(p.id())).isEmpty();
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");

    Refund r = refunds.request(merchant, p.id(), Money.brl(1000));
    assertThat(r.state()).isEqualTo(RefundState.PROCESSING);
  }

  @Test
  void aPixChannelPaymentSeenWhileTheChargeIsUnreadableStillCompletesViaPix() {
    Payment p = newBolecode(12990);
    String e2e = "E2E-POLL2-" + nn(p);
    bank.failNextFindWith(p.pix().txid(), new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "down"));
    boletos.markPaid(nn(p), Money.brl(12990), clock.instant(), "Pagamento via PIX");
    assertThat(polling.check(p.id(), com.gateway.payments.payment.EventSource.PROVIDER_POLL)).isTrue();
    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.boleto().paidVia()).isEqualTo(PaidVia.PIX);
    assertThat(done.pix().endToEndId()).isNull();

    // The webhook arrives afterwards: the same money, not a second Pix.
    bank.markPaid(p.pix().txid(), e2e, Money.brl(12990));
    pixWebhook(p, e2e);
    assertThat(payments.events(p.id()).getLast().type()).isEqualTo("ignored");
    assertThat(divergences(p.id())).isEmpty();

    // Without an endToEndId there is nothing to address a devolução to: refused before any row.
    assertThatThrownBy(() -> refunds.request(merchant, p.id(), Money.brl(1000)))
        .isInstanceOfSatisfying(DomainException.class, e -> assertThat(e.code()).isEqualTo("REFUND_NOT_SUPPORTED"));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM payments.refunds WHERE payment_id = ?", Long.class, p.id())).isZero();
  }

  // --- stuck CREATED, bank unreachable ---

  @Test
  void stuckCreatedSweepWithTheBankUnreachableLeavesCreatedAndKeepsGoing() {
    boletos.failNextIssueWith(new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    assertThatThrownBy(() -> newBolecode(100)).isInstanceOf(DomainException.class);
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    clock.advance(Duration.ofMinutes(11));
    boletos.failNextFindWith(merchant, nn(p), new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "down"));
    assertThatCode(() -> expiration.sweepStuckCreated(clock.instant())).doesNotThrowAnyException();
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.CREATED);
    assertThat(outboxTypes(p.id())).isEmpty();
    // The next sweep, with the bank back, decides.
    expiration.sweepStuckCreated(clock.instant());
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
  }
}
