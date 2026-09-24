package com.gateway.payments.service;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.repository.ReconciliationDivergenceRepository;
import com.gateway.payments.domain.JobType;
import com.gateway.payments.domain.Payment;
import com.gateway.payments.domain.PaymentStatus;
import com.gateway.payments.domain.WebhookInboxEntry;
import com.gateway.payments.repository.JobRepository;
import com.gateway.payments.repository.PaymentRepository;
import com.gateway.payments.repository.WebhookInboxRepository;
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
  void processCompletesThePendingPayment() {
    Payment p = newCharge(1500);
    String e2e = "E" + Ulid.next();

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
    inbox.process(accept(e2e + " " + p.id() + " 1500"));

    inbox.process(accept(e2e + " " + p.id() + " 1500"));

    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending", "completed", "ignored");
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
  }

  @Test
  void processingTheSameInboxRowTwiceIsANoOp() {
    Payment p = newCharge(1500);
    String id = accept("E" + Ulid.next() + " " + p.id() + " 1500");
    inbox.process(id);
    inbox.process(id);

    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending", "completed");
  }

  @Autowired JobRunner runner;
  @Autowired ReconciliationDivergenceRepository divergences;

  /** The bank never answered, we said FAILED — and the payer paid anyway. Money must surface. */
  @Test
  void lateWebhookOnAFailedPaymentOpensADivergence() {
    bank.failNextCreateWith(new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    assertThatThrownBy(() -> newCharge(900)).isInstanceOf(DomainException.class);
    Payment failed = paymentService.list(merchant, 10, null).getFirst();
    assertThat(failed.status()).isEqualTo(PaymentStatus.FAILED);

    String id = accept("E" + Ulid.next() + " " + failed.id() + " 900");
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
    inbox.process(accept("E" + Ulid.next() + " " + p.id() + " 1500"));

    inbox.process(accept("E" + Ulid.next() + " " + p.id() + " 1500"));

    assertThat(divergences.open()).filteredOn(d -> d.paymentId().equals(p.id())).hasSize(1);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.completed");
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
