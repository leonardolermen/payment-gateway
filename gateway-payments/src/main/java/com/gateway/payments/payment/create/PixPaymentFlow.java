package com.gateway.payments.payment.create;

import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.PixIssueRequest;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.payments.PaymentsProperties;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentService;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.Optional;
import java.util.Set;

/**
 * Creating a Pix charge. The order is the invariant: the credential is resolved before any row exists,
 * the CREATED row is written before the bank is called, and the bank call itself runs outside any
 * transaction so a 30 s bank timeout cannot hold a pooled connection and row locks for 30 s.
 */
public class PixPaymentFlow implements PaymentFlow {

  /**
   * A timeout, and equally a 503/504 from a gateway in front of the bank, says nothing about whether
   * the charge was created — so neither is a failure yet.
   */
  private static final Set<ProviderException.Code> MAY_HAVE_LANDED =
      Set.of(ProviderException.Code.TIMEOUT, ProviderException.Code.UNAVAILABLE);

  private final ProviderGateway providers;
  private final PaymentDraftFactory drafts;
  private final PendingAdoption adoption;
  private final CreateFailures failures;
  private final PaymentsProperties props;

  public PixPaymentFlow(
      ProviderGateway providers,
      PaymentDraftFactory drafts,
      PendingAdoption adoption,
      CreateFailures failures,
      PaymentsProperties props) {
    this.providers = providers;
    this.drafts = drafts;
    this.adoption = adoption;
    this.failures = failures;
    this.props = props;
  }

  @Override
  public PaymentMethod method() {
    return PaymentMethod.PIX;
  }

  @Override
  public Payment create(CreatePaymentCommand command) {
    CreatePixPayment pix = (CreatePixPayment) command;

    // Fails fast, before a row exists: a merchant with no credential has nothing to clean up.
    ResolvedProvider<PixMethodProvider> resolved =
        providers.resolvePix(pix.merchantId(), pix.environment(), PaymentService.PROVIDER);

    int expires = pix.expiresInSeconds() == null ? props.defaultExpiresInSeconds() : pix.expiresInSeconds();
    Payment payment = drafts.pix(pix, PaymentService.PROVIDER, expires);

    Charge charge = issueOrRecover(payment, pix, expires, resolved);

    return adoption.adoptPix(payment.id(), charge, expires, EventSource.API);
  }

  private Charge issueOrRecover(
      Payment payment, CreatePixPayment pix, int expires, ResolvedProvider<PixMethodProvider> resolved) {
    PixIssueRequest request =
        new PixIssueRequest(payment.id(), pix.amount(), expires, pix.customerDocument(), null, pix.description());

    try {
      return providers.call(
          payment.id(), "createCharge", resolved, target -> target.provider().issue(target.credentials(), request));
    } catch (ProviderException failure) {
      return recover(payment, resolved, failure);
    }
  }

  /**
   * The PUT may have landed. The txid is ours, so we ask the bank before deciding (spec section 3.2)
   * instead of failing a charge the payer may be looking at.
   *
   * <p>Unlike a bolecode, an empty answer decides here: the charge is failed on the spot, because
   * GET /cob is authoritative about a txid we chose ourselves.
   */
  private Charge recover(
      Payment payment, ResolvedProvider<PixMethodProvider> resolved, ProviderException failure) {
    ProviderFailures.Outcome outcome = ProviderFailures.classify(failure, MAY_HAVE_LANDED);

    if (outcome != ProviderFailures.Outcome.MAY_HAVE_LANDED) {
      String code = outcome == ProviderFailures.Outcome.DECLINED ? "PROVIDER_DECLINED" : "PROVIDER_UNAVAILABLE";
      throw failures.fail(payment.id(), code, failure, null);
    }

    String code = ProviderFailures.timeoutCodeOf(failure);

    Optional<Charge> existing;
    try {
      existing =
          providers.call(
              payment.id(), "findCharge", resolved, target -> target.provider().find(target.credentials(), payment.id()));
    } catch (ProviderException again) {
      throw failures.fail(payment.id(), code, again, resolved);
    }

    if (existing.isEmpty()) {
      throw failures.fail(payment.id(), code, failure, resolved);
    }

    return existing.get();
  }
}
