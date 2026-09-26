package com.gateway.payments.payment;

import static org.assertj.core.api.Assertions.*;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.jobs.JobType;
import com.gateway.payments.jobs.persistence.JobRepository;
import com.gateway.payments.payment.boleto.BoletoDates;
import com.gateway.payments.payment.create.CreateBolecodePayment;
import com.gateway.payments.payment.create.PayerData;
import com.gateway.payments.payment.create.PayerFactory;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.support.ServiceIntegrationTestBase;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class BolecodeServiceIntegrationTest extends ServiceIntegrationTestBase {
  @Autowired PaymentRepository payments;
  @Autowired JobRepository jobs;
  @Autowired com.gateway.payments.provider.ProviderGateway providers;

  @Test
  void createsABolecodeWithBothSidesTwoJobsAndPending() {
    Payment p = newBolecode(12990);

    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(p.method()).isEqualTo(PaymentMethod.BOLECODE);
    assertThat(p.boleto().nossoNumero()).isEqualTo("00000001");
    assertThat(p.boleto().linhaDigitavel()).hasSize(47);
    assertThat(p.boleto().codigoBarras()).hasSize(44);
    LocalDate today = BoletoDates.today(clock);
    assertThat(p.boleto().dueDate()).isEqualTo(today.plusDays(3));
    assertThat(p.boleto().paymentLimitDate()).isEqualTo(today.plusDays(33));
    assertThat(p.boleto().paidVia()).isNull();
    assertThat(p.expiresAt()).isEqualTo(BoletoDates.endOfDay(today.plusDays(33)));
    assertThat(p.pix().txid()).isEqualTo(boletos.pixTxidFor(merchant, "00000001"));
    assertThat(p.pix().pixCopiaECola()).startsWith("00020101021226BL");
    assertThat(p.customerDocumentHash()).hasSize(64);
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
    assertThat(outboxPayload(p.id(), "payment.pending"))
        .contains("\"method\":\"BOLECODE\"")
        .contains("\"linha_digitavel\":")
        .contains("\"paid_via\":null");
    assertThat(jobs.findByTypeAndRef(JobType.EXPIRE_PAYMENT, p.id()))
        .isPresent()
        .get()
        .satisfies(
            j -> assertThat(j.nextRunAt()).isEqualTo(p.expiresAt().plus(Duration.ofMinutes(5))));
    assertThat(jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id()))
        .isPresent()
        .get()
        .satisfies(
            j -> assertThat(j.nextRunAt()).isEqualTo(clock.instant().plus(Duration.ofHours(6))));
    assertThat(payments.events(p.id()))
        .extracting(e -> e.type())
        .containsExactly("created", "pending");
    assertThat(boletos.callsFor(merchant, "00000001")).containsExactly("issueBoleto:00000001");
    assertThat(
            jdbc.queryForList(
                "SELECT status FROM payments.provider_requests WHERE payment_id = ? AND operation = 'issueBoleto'",
                Integer.class,
                p.id()))
        .containsExactly(200);
  }

  @Test
  void numbersAreSequentialPerMerchantAndEachIsItsOwnTxid() {
    Payment a = newBolecode(100);
    Payment b = newBolecode(200);
    assertThat(a.boleto().nossoNumero()).isEqualTo("00000001");
    assertThat(b.boleto().nossoNumero()).isEqualTo("00000002");
    assertThat(a.pix().txid()).isNotEqualTo(b.pix().txid());
  }

  @Test
  void explicitDueDateAndLimitDaysAreHonoured() {
    LocalDate due = BoletoDates.today(clock).plusDays(10);
    Payment p =
        paymentService.create(
            new CreateBolecodePayment(
                merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, payer(), due, 5));
    assertThat(p.boleto().dueDate()).isEqualTo(due);
    assertThat(p.boleto().paymentLimitDate()).isEqualTo(due.plusDays(5));
  }

  @Test
  void dueDateInThePastAndAbsurdLimitAreRefused() {
    LocalDate yesterday = BoletoDates.today(clock).minusDays(1);
    assertThatThrownBy(
            () ->
                paymentService.create(
                    new CreateBolecodePayment(
                        merchant,
                        ProviderEnvironment.TEST,
                        Money.brl(100),
                        null,
                        null,
                        payer(),
                        yesterday,
                        null)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("INVALID_DUE_DATE");
    assertThatThrownBy(
            () ->
                paymentService.create(
                    new CreateBolecodePayment(
                        merchant,
                        ProviderEnvironment.TEST,
                        Money.brl(100),
                        null,
                        null,
                        payer(),
                        null,
                        3651)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("INVALID_PAYMENT_LIMIT");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments.payments WHERE merchant_id = ?",
                Long.class,
                merchant.value()))
        .isZero();
  }

  @Test
  void incompletePayerIsRefusedNamingTheField() {
    PayerData noZip =
        new PayerData(
            "Joao",
            "12345678901",
            new PayerData.AddressData("Rua A", "Centro", "Sao Paulo", "SP", null));
    assertThatThrownBy(
            () ->
                paymentService.create(
                    new CreateBolecodePayment(
                        merchant,
                        ProviderEnvironment.TEST,
                        Money.brl(100),
                        null,
                        null,
                        noZip,
                        null,
                        null)))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.code()).isEqualTo("CUSTOMER_REQUIRED");
              assertThat(e.getMessage()).contains("customer.address.zip");
            });
    assertThatThrownBy(
            () ->
                paymentService.create(
                    new CreateBolecodePayment(
                        merchant,
                        ProviderEnvironment.TEST,
                        Money.brl(100),
                        null,
                        null,
                        null,
                        null,
                        null)))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.getMessage()).contains("customer"));
    PayerData badDoc =
        new PayerData(
            "Joao",
            "123",
            new PayerData.AddressData("Rua A", "Centro", "Sao Paulo", "SP", "01310100"));
    assertThatThrownBy(
            () ->
                paymentService.create(
                    new CreateBolecodePayment(
                        merchant,
                        ProviderEnvironment.TEST,
                        Money.brl(100),
                        null,
                        null,
                        badDoc,
                        null,
                        null)))
        .isInstanceOfSatisfying(
            DomainException.class, e -> assertThat(e.getMessage()).contains("customer.document"));
    PayerData badState =
        new PayerData(
            "Joao",
            "12345678901",
            new PayerData.AddressData("Rua A", "Centro", "Sao Paulo", "SPX", "01310100"));
    assertThatThrownBy(
            () ->
                paymentService.create(
                    new CreateBolecodePayment(
                        merchant,
                        ProviderEnvironment.TEST,
                        Money.brl(100),
                        null,
                        null,
                        badState,
                        null,
                        null)))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> assertThat(e.getMessage()).contains("customer.address.state"));
    assertThat(boletos.callsFor(merchant, "00000001")).isEmpty();
  }

  @Test
  void missingBeneficiaryFailsBeforeAnyRow() {
    boletos.refuseNextIssueCredentials();
    assertThatThrownBy(() -> newBolecode(100))
        .isInstanceOfSatisfying(
            DomainException.class,
            e -> {
              assertThat(e.code()).isEqualTo("PROVIDER_CREDENTIALS_MISSING");
              assertThat(e.getMessage()).contains("beneficiary_id");
            });
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments.payments WHERE merchant_id = ?",
                Long.class,
                merchant.value()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments.boleto_numbers WHERE merchant_id = ?",
                Long.class,
                merchant.value()))
        .isZero();
  }

  @Test
  void declineMarksFailedAndEmits() {
    boletos.failNextIssueWith(
        new ProviderException(
            ProviderException.Code.DECLINED, 422, "422", "Vencimento menor que prazo mínimo"));
    assertThatThrownBy(() -> newBolecode(100))
        .isInstanceOf(DomainException.class)
        .hasMessage("The bank declined the request.")
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("PROVIDER_DECLINED");
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.FAILED);
    assertThat(outboxTypes(p.id())).containsExactly("payment.failed");
  }

  /**
   * The POST timed out (or answered 202) but the bank issued the boleto: the query finds it and the
   * payment is adopted, EMV confirmed via GET /cob.
   */
  @Test
  void timeoutThenQueryAdoptsTheIssuedBoleto() {
    boletos.landNextIssueThenFailWith(
        new ProviderException(ProviderException.Code.TIMEOUT, 202, "202", "Operação em andamento"));
    Payment p = newBolecode(700);
    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(p.boleto().linhaDigitavel()).hasSize(47);
    assertThat(p.pix().txid()).isEqualTo(boletos.pixTxidFor(merchant, "00000001"));
    assertThat(p.pix().pixCopiaECola()).startsWith("00020101021226BL");
    assertThat(boletos.callsFor(merchant, "00000001"))
        .containsExactly("issueBoleto:00000001", "findBoleto:00000001");
    assertThat(bank.callsFor(p.pix().txid())).containsExactly("findCharge:" + p.pix().txid());
    assertThat(jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id())).isPresent();
  }

  /**
   * A timeout and no boleto at the bank: the payment stays CREATED for the stuck-CREATED sweeper
   * (the 202 says the bank may still be working).
   */
  @Test
  void timeoutWithNothingAtTheBankLeavesCreatedForTheSweeper() {
    boletos.failNextIssueWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    assertThatThrownBy(() -> newBolecode(700))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("PROVIDER_TIMEOUT");
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    assertThat(outboxTypes(p.id())).isEmpty();
    assertThat(boletos.callsFor(merchant, "00000001"))
        .containsExactly("issueBoleto:00000001", "findBoleto:00000001");
  }

  @Test
  void unavailableThatLandedIsAdoptedLikeATimeout() {
    boletos.landNextIssueThenFailWith(
        new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "proxy said 503"));
    Payment p = newBolecode(700);
    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void missingCredentialsFailsBeforeCallingTheBank() {
    assertThatThrownBy(
            () ->
                paymentService.create(
                    new CreateBolecodePayment(
                        merchant,
                        ProviderEnvironment.LIVE,
                        Money.brl(100),
                        null,
                        null,
                        payer(),
                        null,
                        null)))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("PROVIDER_CREDENTIALS_MISSING");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments.payments WHERE merchant_id = ?",
                Long.class,
                merchant.value()))
        .isZero();
  }

  /**
   * A timeout and then the query itself fails: nothing is known, so nothing changes and the sweeper
   * decides later.
   */
  @Test
  void timeoutThenFailedQueryLeavesCreatedWithNothingEmitted() {
    boletos.failNextIssueWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    boletos.failNextFindWith(
        "00000001",
        new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "proxy said 503"));
    assertThatThrownBy(() -> newBolecode(700))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("PROVIDER_TIMEOUT");
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    assertThat(outboxTypes(p.id())).isEmpty();
    assertThat(jobs.findByTypeAndRef(JobType.EXPIRE_PAYMENT, p.id())).isEmpty();
    assertThat(jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id())).isEmpty();
    assertThat(boletos.callsFor(merchant, "00000001"))
        .containsExactly("issueBoleto:00000001", "findBoleto:00000001");
  }

  /**
   * Ruling R1: the query finds the boleto but GET /cob does not know the derived txid. The payment
   * is ADOPTED anyway (PENDING, derived txid, the query's EMV, both jobs, payment.pending) and
   * flagged PIX_TXID_UNCONFIRMED; refusing would leave a boleto the bank issued in CREATED.
   */
  @Test
  void unconfirmedTxidAdoptsWithTheQueryEmvAndOpensADivergence() {
    boletos.skipNextPixRegistration();
    boletos.landNextIssueThenFailWith(
        new ProviderException(ProviderException.Code.TIMEOUT, 202, "202", "Operação em andamento"));
    Payment p = newBolecode(700);
    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(p.pix().txid()).isEqualTo(boletos.pixTxidFor(merchant, "00000001"));
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
    assertThat(jobs.findByTypeAndRef(JobType.POLL_BOLETO, p.id())).isPresent();
    assertThat(jobs.findByTypeAndRef(JobType.EXPIRE_PAYMENT, p.id())).isPresent();
    assertThat(p.pix().pixCopiaECola())
        .isNotNull()
        .isEqualTo(boletos.status("00000001").pixCopiaECola());
    assertThat(
            jdbc.queryForList(
                "SELECT provider_status FROM payments.reconciliation_divergences WHERE payment_id = ?",
                String.class,
                p.id()))
        .containsExactly("PIX_TXID_UNCONFIRMED");
  }

  /**
   * The boleto is at the bank but GET /cob fails: an unconfirmed txid is never adopted, the payment
   * waits for the sweeper.
   */
  @Test
  void failedTxidConfirmationLeavesCreated() {
    boletos.landNextIssueThenFailWith(
        new ProviderException(ProviderException.Code.TIMEOUT, "read timed out", null));
    bank.failNextFindWith(
        boletos.pixTxidFor(merchant, "00000001"),
        new ProviderException(ProviderException.Code.UNAVAILABLE, 503, null, "proxy said 503"));
    assertThatThrownBy(() -> newBolecode(700))
        .isInstanceOf(DomainException.class)
        .extracting(e -> ((DomainException) e).code())
        .isEqualTo("PROVIDER_TIMEOUT");
    Payment p = paymentService.list(merchant, 10, null).getFirst();
    assertThat(p.status()).isEqualTo(PaymentStatus.CREATED);
    assertThat(outboxTypes(p.id())).isEmpty();
  }

  /**
   * Ruling R4: CONFLICT on the issue is "this number already exists at the bank" — our own earlier
   * attempt — so the query adopts it.
   */
  @Test
  void conflictOnIssueIsAdoptedFromTheQuery() {
    boletos.landNextIssueThenFailWith(
        new ProviderException(
            ProviderException.Code.CONFLICT, 422, "422", "Nosso número já existente"));
    Payment p = newBolecode(700);
    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(p.boleto().linhaDigitavel()).hasSize(47);
    assertThat(boletos.callsFor(merchant, "00000001"))
        .containsExactly("issueBoleto:00000001", "findBoleto:00000001");
    assertThat(outboxTypes(p.id())).containsExactly("payment.pending");
  }

  /**
   * A second adopter that finds the payment already PENDING did not adopt anything: no
   * PIX_TXID_UNCONFIRMED from it.
   */
  @Test
  void anAdopterThatLostTheRaceDoesNotFlagTheTxid() {
    boletos.skipNextPixRegistration(); // GET /cob on the derived txid will be empty
    Payment p = newBolecode(700);
    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
    var resolved =
        providers.resolveBoleto(merchant, ProviderEnvironment.TEST, PaymentService.PROVIDER);
    Payment again =
        paymentService.adoptBolecodeFromStatus(
            p.id(), resolved, boletos.status("00000001"), EventSource.SYSTEM);
    assertThat(again.status()).isEqualTo(PaymentStatus.PENDING);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM payments.reconciliation_divergences WHERE payment_id = ?",
                Long.class,
                p.id()))
        .isZero();
  }

  @Test
  void aLowercaseStateIsNormalizedNotRefused() {
    PayerData lower =
        new PayerData(
            "Joao",
            "12345678901",
            new PayerData.AddressData("Rua A", "Centro", "Sao Paulo", "sp", "01310-100"));
    assertThat(PayerFactory.from(lower).address().state().value()).isEqualTo("SP");
    Payment p =
        paymentService.create(
            new CreateBolecodePayment(
                merchant, ProviderEnvironment.TEST, Money.brl(100), null, null, lower, null, null));
    assertThat(p.status()).isEqualTo(PaymentStatus.PENDING);
  }
}
