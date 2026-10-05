package com.gateway.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class StuckPaymentsIntegrationTest extends ServiceIntegrationTestBase {
  /**
   * The lists are oldest first and capped, and other tests share the table: a date this old puts
   * our rows at the head of the list no matter what else is stuck there.
   */
  private static final Instant LONG_AGO = Instant.parse("2001-01-01T00:00:00Z");

  @Autowired StuckPayments stuck;
  @Autowired PaymentRepository payments;
  @Autowired PlatformTransactionManager txManager;

  @Test
  void aCreatedPaymentPastTheThresholdIsStuck() {
    Instant start = clock.instant();
    long before = stuck.counts(start).createdTooLong();
    Payment payment = createdOnly();

    assertThat(ids(stuck.createdTooLong(clock.instant()))).doesNotContain(payment.id());

    clock.advance(Duration.ofMinutes(11));
    jdbc.update(
        "UPDATE payments.payments SET created_at = ? WHERE id = ?",
        Timestamp.from(LONG_AGO),
        payment.id());

    assertThat(ids(stuck.createdTooLong(clock.instant()))).contains(payment.id());
    assertThat(stuck.counts(clock.instant()).createdTooLong()).isGreaterThan(before);
  }

  @Test
  void aPendingPaymentPastItsExpiryAndGraceIsStuck() {
    Payment payment = newCharge(1000);
    jdbc.update(
        "UPDATE payments.payments SET expires_at = ? WHERE id = ?",
        Timestamp.from(LONG_AGO),
        payment.id());

    assertThat(ids(stuck.pendingPastExpiry(clock.instant()))).contains(payment.id());
    assertThat(stuck.counts(clock.instant()).pendingPastExpiry()).isPositive();
  }

  @Test
  void aPendingPaymentInsideItsGraceIsNotStuck() {
    Payment payment = newCharge(1000);
    Instant expiresAt = payment.expiresAt();

    clock.advance(Duration.between(clock.instant(), expiresAt).plusMinutes(1));

    assertThat(ids(stuck.pendingPastExpiry(clock.instant()))).doesNotContain(payment.id());
  }

  private Payment createdOnly() {
    Payment payment =
        Payment.create(
            merchant,
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

  private static List<String> ids(List<Payment> found) {
    return found.stream().map(Payment::id).toList();
  }
}
