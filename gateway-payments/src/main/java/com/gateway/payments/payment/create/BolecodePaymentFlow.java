package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.party.Payer;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoIssueRequest;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.boleto.BoletoStatus;
import com.gateway.kernel.provider.boleto.IssuedBoleto;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.payment.boleto.BoletoDates;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creating a bolecode — a registered boleto with a Pix QR on the same issue. Spec 2026-09-25 §7.
 * Order matters: the credentials (beneficiary included) and the payer are checked before a row
 * exists; the number is reserved in the same transaction as CREATED; the bank call runs outside any
 * transaction; PENDING lands with both sides and both jobs.
 */
public class BolecodePaymentFlow implements PaymentFlow {
  private static final Logger log = LoggerFactory.getLogger(BolecodePaymentFlow.class);

  /**
   * CONFLICT joins the two network codes: the bank saying "this nosso número already exists"
   * (ruling R4) can only be our own earlier attempt that landed, because the number is ours and was
   * reserved before the call. Failing it would leave a payable boleto behind a FAILED payment.
   */
  private static final Set<ProviderException.Code> MAY_HAVE_LANDED =
      Set.of(
          ProviderException.Code.TIMEOUT,
          ProviderException.Code.UNAVAILABLE,
          ProviderException.Code.CONFLICT);

  private final ProviderGateway providers;
  private final PaymentDraftFactory drafts;
  private final PendingAdoption adoption;
  private final BolecodeFromQuery fromQuery;
  private final CreateFailures failures;
  private final PaymentsProperties props;
  private final Clock clock;

  public BolecodePaymentFlow(
      ProviderGateway providers,
      PaymentDraftFactory drafts,
      PendingAdoption adoption,
      BolecodeFromQuery fromQuery,
      CreateFailures failures,
      PaymentsProperties props,
      Clock clock) {
    this.providers = providers;
    this.drafts = drafts;
    this.adoption = adoption;
    this.fromQuery = fromQuery;
    this.failures = failures;
    this.props = props;
    this.clock = clock;
  }

  @Override
  public PaymentMethod method() {
    return PaymentMethod.BOLECODE;
  }

  @Override
  public Payment create(CreatePaymentCommand command) {
    CreateBolecodePayment bolecode = (CreateBolecodePayment) command;

    ResolvedProvider<BoletoMethodProvider> resolved =
        providers.resolveBoleto(
            bolecode.merchantId(), bolecode.environment(), PaymentService.PROVIDER);
    requireIssueCredentials(resolved, bolecode);

    Payer payer = PayerFactory.from(bolecode.payer());
    LocalDate dueDate = dueDateOf(bolecode);
    LocalDate paymentLimitDate = dueDate.plusDays(paymentLimitDaysOf(bolecode));

    Payment payment =
        drafts.bolecode(bolecode, PaymentService.PROVIDER, payer, dueDate, paymentLimitDate);
    String nossoNumero = payment.boleto().nossoNumero();

    BoletoIssueRequest request =
        new BoletoIssueRequest(
            nossoNumero,
            bolecode.amount(),
            dueDate,
            paymentLimitDate,
            payer,
            bolecode.description());

    IssuedBoleto issued;
    try {
      issued =
          providers.call(
              payment.id(),
              "issueBoleto",
              resolved,
              target -> target.provider().issue(target.credentials(), request));
    } catch (ProviderException failure) {
      return recover(payment, nossoNumero, resolved, failure);
    }

    return adoption.adoptBolecode(payment.id(), issued, EventSource.API);
  }

  /**
   * The beneficiary lives inside the credential, so an incomplete one is the merchant's to fix, not
   * a bank error.
   */
  private void requireIssueCredentials(
      ResolvedProvider<BoletoMethodProvider> resolved, CreateBolecodePayment bolecode) {
    try {
      resolved.provider().requireIssueCredentials(resolved.credentials());
    } catch (ProviderException e) {
      if (e.code() == ProviderException.Code.CREDENTIALS_INCOMPLETE) {
        throw new DomainException(
            "PROVIDER_CREDENTIALS_MISSING",
            "the "
                + PaymentService.PROVIDER
                + " "
                + bolecode.environment()
                + " credential is missing "
                + e.providerType());
      }
      throw e;
    }
  }

  private LocalDate dueDateOf(CreateBolecodePayment bolecode) {
    LocalDate today = BoletoDates.today(clock);
    LocalDate dueDate =
        bolecode.dueDate() == null
            ? today.plusDays(props.boletoDefaultDueInDays())
            : bolecode.dueDate();

    if (dueDate.isBefore(today)) {
      throw new DomainException(
          "INVALID_DUE_DATE", "due_date must be today or later (America/Sao_Paulo)");
    }

    return dueDate;
  }

  private int paymentLimitDaysOf(CreateBolecodePayment bolecode) {
    int limitDays =
        bolecode.paymentLimitDays() == null
            ? props.boletoDefaultPaymentLimitDays()
            : bolecode.paymentLimitDays();

    if (limitDays < 0 || limitDays > props.boletoMaxPaymentLimitDays()) {
      throw new DomainException(
          "INVALID_PAYMENT_LIMIT",
          "payment_limit_days must be between 0 and " + props.boletoMaxPaymentLimitDays());
    }

    return limitDays;
  }

  /**
   * On TIMEOUT, UNAVAILABLE or CONFLICT the query decides: found means adopted, not found means the
   * payment stays CREATED and the caller gets PROVIDER_TIMEOUT.
   *
   * <p>Unlike Pix, it is NOT failed on the spot: the bank's 202 means "operação em andamento", so
   * an empty query a second later proves nothing. {@code ExpirationService.sweepStuckCreated} asks
   * again after {@code stuckCreatedAfter} and decides then.
   */
  private Payment recover(
      Payment payment,
      String nossoNumero,
      ResolvedProvider<BoletoMethodProvider> resolved,
      ProviderException failure) {
    ProviderFailures.Outcome outcome = ProviderFailures.classify(failure, MAY_HAVE_LANDED);

    if (outcome != ProviderFailures.Outcome.MAY_HAVE_LANDED) {
      String code =
          outcome == ProviderFailures.Outcome.DECLINED
              ? "PROVIDER_DECLINED"
              : "PROVIDER_UNAVAILABLE";
      throw failures.fail(payment.id(), code, failure, null);
    }

    String code = ProviderFailures.timeoutCodeOf(failure);

    Optional<BoletoStatus> existing;
    try {
      existing =
          providers.call(
              payment.id(),
              "findBoleto",
              resolved,
              target -> target.provider().find(target.credentials(), nossoNumero));
    } catch (ProviderException again) {
      throw ProviderErrors.toDomain(code, again, log, "issueBoleto", payment.id());
    }

    if (existing.isEmpty()) {
      log.warn(
          "boleto {} for payment {} not at the bank after {}; left CREATED for the sweeper",
          nossoNumero,
          payment.id(),
          failure.code());
      throw ProviderErrors.toDomain(code, failure, log, "issueBoleto", payment.id());
    }

    try {
      return fromQuery.adopt(payment.id(), resolved, existing.get(), EventSource.API);
    } catch (ProviderException again) {
      // The boleto exists but GET /cob itself failed (bank unreachable), so nothing is known about
      // the
      // txid yet. This is not the "bank does not know the txid" case: that one is adopted and
      // flagged
      // PIX_TXID_UNCONFIRMED inside BolecodeFromQuery (ruling R1). Here the payment stays CREATED
      // and the
      // sweeper asks again after stuckCreatedAfter. Translated here, not inside BolecodeFromQuery,
      // so the
      // sweeper still sees the raw failure and retries.
      throw ProviderErrors.toDomain(code, again, log, "confirmBoletoTxid", payment.id());
    }
  }
}
