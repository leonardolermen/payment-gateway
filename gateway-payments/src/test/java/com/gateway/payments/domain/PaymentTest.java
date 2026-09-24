package com.gateway.payments.domain;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import java.time.*;
import org.junit.jupiter.api.Test;

class PaymentTest {
  final Clock clock = Clock.fixed(Instant.parse("2026-09-24T12:00:00Z"), ZoneOffset.UTC);

  Payment fresh() {
    return Payment.create(MerchantId.next(), ProviderEnvironment.TEST, "ITAU", Money.brl(15990), "order-8812", "Order 8812", null, 3600, clock);
  }

  @Test
  void txidIsTheIdAndFitsBacen() {
    Payment p = fresh();
    assertThat(p.id()).matches("[a-zA-Z0-9]{26,35}");
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    // create() already produces the initial "created" event: version starts at 1, not 0.
    assertThat(p.version()).isEqualTo(1);
    assertThat(p.createdEvent().sequence()).isEqualTo(1);
  }

  @Test
  void eventsCarryAMonotonicSequenceAndBumpTheVersion() {
    Payment p = fresh();
    PaymentEvent e1 = p.markPending(new PixDetails(p.id(), "000201…", "pix.example.com/x", null), Instant.parse("2026-09-24T13:00:00Z"));
    PaymentEvent e2 = p.markCompleted("E12345678202009091221kkkkkkkkkkk", Money.brl(15990), Instant.parse("2026-09-24T12:30:00Z"), EventSource.PROVIDER_WEBHOOK);
    // sequence 1 is the "created" event produced by create(); pending is 2, completed is 3.
    assertThat(e1.sequence()).isEqualTo(2);
    assertThat(e2.sequence()).isEqualTo(3);
    assertThat(p.version()).isEqualTo(3);
    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThat(p.pix().endToEndId()).isEqualTo("E12345678202009091221kkkkkkkkkkk");
    assertThat(e2.type()).isEqualTo("completed");
  }

  @Test
  void refusedTransitionsThrow() {
    Payment p = fresh();
    assertThatThrownBy(() -> p.markCompleted("E1", Money.brl(1), Instant.now(), EventSource.PROVIDER_WEBHOOK)).isInstanceOf(IllegalStateException.class);
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    assertThatThrownBy(() -> p.markCompleted("E1", Money.brl(1), Instant.now(), EventSource.API)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void webhookOnTerminalStateIsRecordedAndIgnored() {
    Payment p = fresh();
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    p.markCanceled(EventSource.API);
    var ignored = p.recordIgnored("late webhook E1", EventSource.PROVIDER_WEBHOOK);
    assertThat(ignored).isPresent();
    assertThat(ignored.get().type()).isEqualTo("ignored");
    assertThat(p.status()).isEqualTo(PaymentStatus.CANCELED);
  }

  @Test
  void expiredThenPaidByTheBankCompletes() {
    Payment p = fresh();
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    p.markExpired(EventSource.EXPIRATION_JOB);
    p.markCompleted("E1", Money.brl(15990), Instant.now(), EventSource.RECONCILIATION);
    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
  }

  @Test
  void eventPayloadsEscapeEmbeddedStrings() {
    Payment p = fresh();
    PaymentEvent event = p.markFailed("bank said \"no\" \\ line\nbreak", EventSource.SYSTEM);
    assertThat(event.payload()).isEqualTo("{\"reason\":\"bank said \\\"no\\\" \\\\ line\\nbreak\"}");
    assertThat(event.payload()).doesNotContain("\n");
  }

  @Test
  void refundsAreProjectedNotTransitions() {
    Payment p = fresh();
    p.markPending(new PixDetails(p.id(), "x", "y", null), Instant.now());
    p.markCompleted("E1", Money.brl(15990), Instant.now(), EventSource.PROVIDER_WEBHOOK);
    p.applyRefund(Money.brl(5000));
    assertThat(p.partiallyRefunded()).isTrue();
    assertThat(p.fullyRefunded()).isFalse();
    p.applyRefund(Money.brl(10990));
    assertThat(p.fullyRefunded()).isTrue();
    assertThat(p.status()).isEqualTo(PaymentStatus.COMPLETED);
    assertThatThrownBy(() -> p.applyRefund(Money.brl(1))).isInstanceOf(IllegalArgumentException.class);
  }
}
