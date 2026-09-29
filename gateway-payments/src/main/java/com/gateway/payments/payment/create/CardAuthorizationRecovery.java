package com.gateway.payments.payment.create;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.card.CardAdoption;
import com.gateway.payments.payment.card.CardToSave;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An authorization whose answer was lost, or came back in doubt (Status 0, 12, 14). The
 * MerchantOrderId is ours — the payment id — so the Cielo can be asked (spec §6.4):
 *
 * <ul>
 *   <li>found and decided: adopted, as if the answer had arrived;
 *   <li>not found, or still in doubt: FAILED. Unlike the boleto there is no "in progress" at the
 *       Cielo worth waiting for, and its Cancellation Guarantee undoes what stayed NotFinished;
 *   <li>the query itself failed: nothing is known, so the payment stays CREATED and the
 *       stuck-CREATED sweep asks again after stuckCreatedAfter. Failing it here would say "not
 *       charged" about a sale that may hold the payer's limit.
 * </ul>
 */
public class CardAuthorizationRecovery {
  private static final Logger log = LoggerFactory.getLogger(CardAuthorizationRecovery.class);

  private final ProviderGateway providers;
  private final CardAdoption adoption;
  private final CreateFailures failures;

  public CardAuthorizationRecovery(
      ProviderGateway providers, CardAdoption adoption, CreateFailures failures) {
    this.providers = providers;
    this.adoption = adoption;
    this.failures = failures;
  }

  public Payment recover(
      Payment payment,
      ResolvedProvider<CardMethodProvider> resolved,
      ProviderException cause,
      CardToSave toSave) {
    String code = ProviderFailures.timeoutCodeOf(cause);

    Optional<CardAuthorization> found;
    try {
      found = findByOrder(payment, resolved);
    } catch (ProviderException again) {
      throw ProviderErrors.toDomain(code, again, log, "findCardByOrder", payment.id());
    }

    if (found.isEmpty() || found.get().status().inDoubt()) {
      throw failures.fail(payment.id(), code, cause, null);
    }

    return adoption.adopt(payment.id(), found.get(), toSave, EventSource.API);
  }

  /**
   * A CREATED card payment older than stuckCreatedAfter. A failed query propagates: the sweep logs
   * it and asks again next run. The card cannot be saved from here — its holder and expiry lived
   * only in the lost request — so a token in the sale is not kept.
   */
  public void sweep(Payment payment) {
    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(payment.merchantId(), payment.environment(), payment.provider());

    Optional<CardAuthorization> found = findByOrder(payment, resolved);

    if (found.isEmpty() || found.get().status().inDoubt()) {
      failures.markFailed(payment.id(), "PROVIDER_TIMEOUT", EventSource.SYSTEM);
      return;
    }

    adoption.adopt(payment.id(), found.get(), null, EventSource.SYSTEM);
  }

  private Optional<CardAuthorization> findByOrder(
      Payment payment, ResolvedProvider<CardMethodProvider> resolved) {
    return providers.call(
        payment.id(),
        "findCardByOrder",
        resolved,
        target -> target.provider().findByOrder(target.credentials(), payment.id()));
  }
}
