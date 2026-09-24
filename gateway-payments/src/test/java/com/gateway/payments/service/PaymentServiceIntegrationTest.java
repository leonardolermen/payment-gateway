package com.gateway.payments.service;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.domain.JobType;
import com.gateway.payments.domain.Payment;
import com.gateway.payments.domain.PaymentStatus;
import com.gateway.payments.repository.JobRepository;
import com.gateway.payments.repository.PaymentRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class PaymentServiceIntegrationTest extends ServiceIntegrationTestBase {

  @Autowired PaymentRepository payments;
  @Autowired JobRepository jobs;
  @Autowired PlatformTransactionManager txManager;

  @Test
  void createsAChargeAndEmitsPending() {
    Payment p = newCharge(1500);

    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(p.pix().txid()).isEqualTo(p.id());
    assertThat(p.pix().pixCopiaECola()).isNotBlank();
    assertThat(p.expiresAt()).isEqualTo(clock.instant().plusSeconds(3600));
    assertThat(p.customerDocumentHash()).hasSize(64);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
    assertThat(outboxPayload(p.id(), "payment.pending"))
        .contains("\"id\":\"" + p.id() + "\"")
        .contains("\"status\":\"pending\"")
        .contains("\"copia_e_cola\":")
        .contains("\"expires_at\":");
    List<Integer> statuses =
        jdbc.queryForList("SELECT status FROM payments.provider_requests WHERE payment_id = ? AND operation = 'createCharge'", Integer.class, p.id());
    assertThat(statuses).containsExactly(201);
    assertThat(jobs.findByTypeAndRef(JobType.EXPIRE_PAYMENT, p.id())).isPresent();
    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "pending");
  }

  @Test
  void customerDocumentIsHashedFromDigitsOnly() {
    assertThat(PaymentService.hashDocument("123.456.789-09")).isEqualTo(PaymentService.hashDocument("12345678909"));
    assertThat(PaymentService.hashDocument(null)).isNull();
    assertThat(PaymentService.hashDocument("--")).isNull();
  }

  @Test
  void missingCredentialsFailsBeforeCallingTheBank() {
    long before = jdbc.queryForObject("SELECT count(*) FROM payments.payments WHERE merchant_id = ?", Long.class, merchant.value());

    assertThatThrownBy(
            () -> paymentService.createCharge(
                new PaymentService.CreateCharge(merchant, ProviderEnvironment.LIVE, Money.brl(100), null, null, null, null)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("PROVIDER_CREDENTIALS_MISSING");

    long after = jdbc.queryForObject("SELECT count(*) FROM payments.payments WHERE merchant_id = ?", Long.class, merchant.value());
    assertThat(after).isEqualTo(before).isZero();
  }

  @Test
  void providerDeclineMarksFailed() {
    bank.failNextCreateWith(new ProviderException(ProviderException.Code.INVALID, 400, "CobOperacaoInvalida", "bad key"));

    assertThatThrownBy(() -> newCharge(100))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("PROVIDER_DECLINED");

    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(payments.events(p.id())).extracting(e -> e.type()).containsExactly("created", "failed");
    assertThat(outboxTypes(p.id())).containsExactly("payment.failed");
  }

  @Test
  void timeoutThenRetryFindsTheExistingCharge() {
    bank.timeoutNextCreateButCreateAnyway();

    Payment p = newCharge(700);

    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(p.pix().pixCopiaECola()).isEqualTo("00020101021226" + p.id());
    assertThat(bank.callsFor(p.id())).containsExactly("createCharge:" + p.id(), "findCharge:" + p.id());
  }

  @Test
  void timeoutWithNoChargeAtTheBankFails() {
    bank.failNextCreateWith(new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));

    assertThatThrownBy(() -> newCharge(700))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("PROVIDER_TIMEOUT");

    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
  }

  @Test
  void unavailableThatLandedIsAdoptedLikeATimeout() {
    bank.landNextCreateThenFailWith(new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "proxy said 503"));

    Payment p = newCharge(700);

    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(bank.callsFor(p.id())).containsExactly("createCharge:" + p.id(), "findCharge:" + p.id());
  }

  @Test
  void unknownFateFailureAlsoAsksTheBankToRemoveTheCharge() {
    bank.failNextCreateWith(new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "down"));

    assertThatThrownBy(() -> newCharge(700))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("PROVIDER_UNAVAILABLE");

    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(bank.callsFor(p.id())).containsExactly("createCharge:" + p.id(), "findCharge:" + p.id(), "cancelCharge:" + p.id());
  }

  @Test
  void declineDoesNotAskTheBankAnythingElse() {
    bank.failNextCreateWith(new ProviderException(ProviderException.Code.DECLINED, 422, null, "no"));
    assertThatThrownBy(() -> newCharge(700)).isInstanceOf(DomainException.class);
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(bank.callsFor(p.id())).containsExactly("createCharge:" + p.id());
  }

  @Test
  void cancelPendingCallsTheBankAndEmits() {
    Payment p = newCharge(100);

    Payment canceled = paymentService.cancel(merchant, p.id());

    assertThat(canceled.status()).isEqualTo(PaymentStatus.CANCELED);
    assertThat(bank.callsFor(p.id())).contains("cancelCharge:" + p.id());
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending", "payment.canceled");
  }

  @Test
  void cancelCompletedIsRefused() {
    Payment p = newCharge(100);
    new TransactionTemplate(txManager).executeWithoutResult(s -> {
      Payment loaded = payments.findById(p.id()).orElseThrow();
      payments.save(loaded, List.of(loaded.markCompleted("E2E" + p.id(), Money.brl(100), clock.instant(), com.gateway.payments.domain.EventSource.PROVIDER_WEBHOOK)));
    });

    assertThatThrownBy(() -> paymentService.cancel(merchant, p.id()))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("INVALID_STATE");
  }

  @Test
  void listIsScopedToTheMerchant() {
    Payment mine = newCharge(100);
    MerchantId other = MerchantId.next();
    paymentService.createCharge(new PaymentService.CreateCharge(other, ProviderEnvironment.TEST, Money.brl(200), null, null, null, 600));

    assertThat(paymentService.list(merchant, 10, null)).extracting(Payment::id).containsExactly(mine.id());
    assertThatThrownBy(() -> paymentService.get(other, mine.id())).isInstanceOf(DomainException.class);
    assertThat(paymentService.get(merchant, mine.id()).id()).isEqualTo(mine.id());
  }

  @Test
  void explicitExpiryIsHonoured() {
    Payment p = paymentService.createCharge(new PaymentService.CreateCharge(merchant, ProviderEnvironment.TEST, Money.brl(200), null, null, null, 600));
    assertThat(p.expiresAt()).isEqualTo(clock.instant().plus(Duration.ofSeconds(600)));
  }
}
