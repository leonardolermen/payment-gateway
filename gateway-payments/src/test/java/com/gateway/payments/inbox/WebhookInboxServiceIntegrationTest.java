package com.gateway.payments.inbox;

import com.gateway.payments.jobs.JobRunner;
import com.gateway.payments.refund.RefundService;
import com.gateway.payments.support.ServiceIntegrationTestBase;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.pix.RefundResult;
import com.gateway.kernel.provider.pix.RefundStatus;
import com.gateway.payments.refund.Refund;
import com.gateway.payments.refund.RefundState;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.reconciliation.persistence.ReconciliationDivergenceRepository;
import com.gateway.payments.jobs.JobType;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.inbox.persistence.WebhookInboxRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class WebhookInboxServiceIntegrationTest extends ServiceIntegrationTestBase {

  @Autowired WebhookInboxService inbox;
  @Autowired WebhookInboxRepository inboxRows;
  @Autowired PaymentRepository payments;
  @Autowired JobRepository jobs;

  String accept(String body) {
    return inbox.accept("ITAU", merchant, "content-type: application/json", body.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void acceptStoresRawAndEnqueues() {
    String id = accept("E2E1 TX1 100");

    WebhookInboxEntry row = inboxRows.findById(id).orElseThrow();
    assertThat(row.status()).isEqualTo("RECEIVED");
    assertThat(new String(row.rawBody(), StandardCharsets.UTF_8)).isEqualTo("E2E1 TX1 100");
    assertThat(jobs.findByTypeAndRef(JobType.PROCESS_WEBHOOK, id)).isPresent();
  }

  @Test
  void processCompletesThePendingPaymentTheBankConfirms() {
    Payment p = newCharge(1500);
    String e2e = "E" + Ulid.next();
    bank.markPaid(p.id(), e2e, Money.brl(1500));

    String id = accept(e2e + " " + p.id() + " 1500");
    inbox.process(id);

    Payment done = payments.findById(p.id()).orElseThrow();
    assertThat(done.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(done.paidAt()).isNotNull();
    assertThat(done.pix().endToEndId()).isEqualTo(e2e);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
    assertThat(outboxPayload(p.id(), "payment.completed")).contains("\"end_to_end_id\":\"" + e2e + "\"").contains("\"paid_amount\":1500");
    assertThat(inboxRows.findById(id).orElseThrow().status()).isEqualTo("PROCESSED");
  }

  @Test
  void duplicateWebhookIsIgnoredOnce() {
    Payment p = newCharge(1500);
    String e2e = "E" + Ulid.next();
    bank.markPaid(p.id(), e2e, Money.brl(1500));
    inbox.process(accept(e2e + " " + p.id() + " 1500"));

    inbox.process(accept(e2e + " " + p.id() + " 1500"));

    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending", "completed", "ignored");
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
  }

  @Test
  void processingTheSameInboxRowTwiceIsANoOp() {
    Payment p = newCharge(1500);
    String e2e = "E" + Ulid.next();
    bank.markPaid(p.id(), e2e, Money.brl(1500));
    String id = accept(e2e + " " + p.id() + " 1500");
    inbox.process(id);
    inbox.process(id);

    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending", "completed");
  }

  @Autowired JobRunner runner;
  @Autowired ReconciliationDivergenceRepository divergences;

  /** The bank never answered, we said FAILED — and the payer paid anyway. Money must surface. */
  @Test
  void lateWebhookOnAFailedPaymentOpensADivergence() {
    // The PUT landed but we were told it was refused: FAILED here, a payable QR at the bank.
    bank.landNextCreateThenFailWith(new ProviderException(ProviderException.Code.INVALID, 400, "CobOperacaoInvalida", "invalid"));
    assertThatThrownBy(() -> newCharge(900)).isInstanceOf(DomainException.class);
    Payment failed = paymentService.list(merchant, 10, null).getFirst();
    assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);
    String e2e = "E" + Ulid.next();
    bank.markPaid(failed.id(), e2e, Money.brl(900));

    String id = accept(e2e + " " + failed.id() + " 900");
    while (runner.runDue(clock.instant()) > 0) {}

    assertThat(inboxRows.findById(id).orElseThrow().status()).isEqualTo("PROCESSED");
    assertThat(jobs.findByTypeAndRef(JobType.PROCESS_WEBHOOK, id).orElseThrow().status()).isEqualTo("DONE");
    assertThat(payments.findById(failed.id()).orElseThrow().status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payments.events(failed.id())).extracting(e -> e.type()).containsExactly("created", "failed", "ignored");
    assertThat(outboxTypes(failed.id())).containsExactly("payment.failed");
    assertThat(divergences.open())
        .filteredOn(d -> d.paymentId().equals(failed.id()))
        .singleElement()
        .satisfies(d -> assertThat(d.detail()).startsWith("paid at bank while FAILED"));
  }

  @Test
  void aDifferentPixOnACompletedPaymentOpensADivergence() {
    Payment p = newCharge(1500);
    String first = "E" + Ulid.next();
    bank.markPaid(p.id(), first, Money.brl(1500));
    inbox.process(accept(first + " " + p.id() + " 1500"));

    inbox.process(accept("E" + Ulid.next() + " " + p.id() + " 1500"));

    assertThat(divergences.open()).filteredOn(d -> d.paymentId().equals(p.id())).hasSize(1);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
  }

  /** Critical: anyone who learns the webhook URL could POST "paid". The bank still says ACTIVE. */
  @Test
  void aForgedWebhookTheBankDoesNotConfirmCompletesNothing() {
    Payment p = newCharge(1500);
    String e2e = "E" + Ulid.next();

    String id = accept(e2e + " " + p.id() + " 1500");
    inbox.process(id);

    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(bank.callsFor(p.id())).contains("findCharge:" + p.id());
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending", "ignored");
    assertThat(payments.events(p.id()).getLast().payload()).contains("unconfirmed webhook");
    assertThat(divergences.open()).filteredOn(d -> d.paymentId().equals(p.id())).singleElement()
        .satisfies(d -> assertThat(d.providerStatus()).isEqualTo("UNCONFIRMED_WEBHOOK"));
  }

  @Test
  void aWebhookWhoseEndToEndIdTheBankDoesNotHaveCompletesNothing() {
    Payment p = newCharge(1500);
    bank.markPaid(p.id(), "E" + Ulid.next(), Money.brl(1500));

    inbox.process(accept("E" + Ulid.next() + " " + p.id() + " 1500"));

    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
    assertThat(divergences.open()).filteredOn(d -> d.paymentId().equals(p.id()) && d.providerStatus().equals("UNCONFIRMED_WEBHOOK")).hasSize(1);
  }

  @Test
  void aConfirmedPixOfTheWrongAmountOpensADivergenceInsteadOfCompleting() {
    Payment p = newCharge(1500);
    String e2e = "E" + Ulid.next();
    bank.markPaid(p.id(), e2e, Money.brl(100));

    inbox.process(accept(e2e + " " + p.id() + " 1500"));

    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
    assertThat(divergences.open()).filteredOn(d -> d.paymentId().equals(p.id())).singleElement()
        .satisfies(d -> {
          assertThat(d.providerStatus()).isEqualTo("AMOUNT_MISMATCH");
          assertThat(d.detail()).contains("100 cents").contains("1500");
        });
  }

  @Test
  void theBankUnreachableLeavesTheRowForTheRetry() {
    Payment p = newCharge(1500);
    String e2e = "E" + Ulid.next();
    bank.markPaid(p.id(), e2e, Money.brl(1500));
    bank.failNextFindWith(p.id(), new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "unavailable"));
    String id = accept(e2e + " " + p.id() + " 1500");

    assertThatThrownBy(() -> inbox.process(id)).isInstanceOf(ProviderException.class);
    assertThat(inboxRows.findById(id).orElseThrow().status()).isEqualTo("RECEIVED");
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.PENDING);

    inbox.process(id);
    assertThat(payments.findById(p.id()).orElseThrow().status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Autowired RefundService refunds;

  Payment completed(long cents, String e2e) {
    Payment p = newCharge(cents);
    bank.markPaid(p.id(), e2e, Money.brl(cents));
    inbox.process(accept(e2e + " " + p.id() + " " + cents));
    return payments.findById(p.id()).orElseThrow();
  }

  @Test
  void aRefundUpdateAppliesWhatTheBankSaysNotTheBody() {
    bank.nextRefundStatus(RefundStatus.PROCESSING);
    String e2e = "E" + Ulid.next();
    Payment p = completed(1000, e2e);
    Refund r = refunds.request(merchant, p.id(), Money.brl(400));
    bank.refundResult(new RefundResult(r.id(), RefundStatus.COMPLETED, Money.brl(400), null, clock.instant(), clock.instant()));

    String id = accept("REFUND " + r.id() + " " + e2e + " FAILED");
    inbox.process(id);

    assertThat(refunds.get(merchant, r.id()).state()).isEqualTo(RefundState.COMPLETED);
    assertThat(bank.callsFor(r.id())).contains("findRefund:" + r.id());
    assertThat(inboxRows.findById(id).orElseThrow().status()).isEqualTo("PROCESSED");
  }

  @Test
  void aRefundUpdateForAnotherMerchantsRefundIsIgnoredWithADivergence() {
    bank.nextRefundStatus(RefundStatus.PROCESSING);
    String e2e = "E" + Ulid.next();
    Payment victim = completed(1000, e2e);
    Refund r = refunds.request(merchant, victim.id(), Money.brl(400));
    bank.refundResult(new RefundResult(r.id(), RefundStatus.COMPLETED, Money.brl(400), null, clock.instant(), clock.instant()));

    // Another merchant's webhook URL, naming the first merchant's refund.
    String id = inbox.accept("ITAU", MerchantId.next(), "{}", ("REFUND " + r.id() + " " + e2e + " COMPLETED").getBytes(StandardCharsets.UTF_8));
    inbox.process(id);

    assertThat(refunds.get(merchant, r.id()).state()).isEqualTo(RefundState.PROCESSING);
    assertThat(bank.callsFor(r.id())).doesNotContain("findRefund:" + r.id());
    assertThat(inboxRows.findById(id).orElseThrow().status()).isEqualTo("IGNORED");
    assertThat(divergences.open()).filteredOn(d -> d.paymentId().equals(victim.id())).singleElement()
        .satisfies(d -> assertThat(d.providerStatus()).isEqualTo("UNCONFIRMED_REFUND_WEBHOOK"));
  }

  @Test
  void aRefundUpdateUnderAnotherPixIsIgnored() {
    bank.nextRefundStatus(RefundStatus.PROCESSING);
    Payment p = completed(1000, "E" + Ulid.next());
    Refund r = refunds.request(merchant, p.id(), Money.brl(400));

    inbox.process(accept("REFUND " + r.id() + " E" + Ulid.next() + " COMPLETED"));

    assertThat(refunds.get(merchant, r.id()).state()).isEqualTo(RefundState.PROCESSING);
    assertThat(divergences.open()).filteredOn(d -> d.paymentId().equals(p.id()) && d.providerStatus().equals("UNCONFIRMED_REFUND_WEBHOOK")).hasSize(1);
  }

  @Test
  void unknownTxidIsIgnored() {
    String id = accept("E" + Ulid.next() + " " + Ulid.next() + " 100");

    inbox.process(id);

    assertThat(inboxRows.findById(id).orElseThrow().status()).isEqualTo("IGNORED");
  }

  @Test
  void unreadableBodyIsFailed() {
    String id = accept("{not what the bank sends");

    inbox.process(id);

    WebhookInboxEntry row = inboxRows.findById(id).orElseThrow();
    assertThat(row.status()).isEqualTo("FAILED");
    assertThat(row.error()).contains("unreadable");
  }
}
