package com.gateway.payments.payment.card;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cancelling a card payment: only an authorization, by the Cielo's total void (spec §6). A captured
 * payment is refunded, never canceled — cancel on COMPLETED stays INVALID_STATE, as it is today.
 * The Cielo first, then the payment; a lost answer is read back from the query.
 */
public class CardVoid {
  private static final Logger log = LoggerFactory.getLogger(CardVoid.class);

  private final PaymentRepository payments;
  private final PaymentEvents events;
  private final ProviderGateway providers;
  private final UnitOfWork unitOfWork;

  public CardVoid(
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    this.payments = payments;
    this.events = events;
    this.providers = providers;
    this.unitOfWork = unitOfWork;
  }

  public Payment cancel(Payment current) {
    if (current.status() != PaymentStatus.AUTHORIZED) {
      throw new DomainException(
          "INVALID_STATE",
          "only an authorized card payment can be canceled, this one is "
              + current.status()
              + "; a captured payment is refunded");
    }

    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(current.merchantId(), current.environment(), current.provider());
    String cieloPaymentId = current.card().paymentId();

    try {
      providers.run(
          current.id(),
          "voidCard",
          resolved,
          target -> target.provider().cancel(target.credentials(), cieloPaymentId));
    } catch (ProviderException failure) {
      requireVoidedAtCielo(current, resolved, failure);
    }

    return unitOfWork.inTransaction(
        () -> {
          Payment payment = payments.findById(current.id()).orElseThrow();
          if (payment.status() == PaymentStatus.CANCELED) {
            return payment;
          }
          if (payment.status() != PaymentStatus.AUTHORIZED) {
            throw new DomainException(
                "INVALID_STATE", "payment changed to " + payment.status() + " while cancelling");
          }

          Payment saved = payments.save(payment, List.of(payment.markCanceled(EventSource.API)));
          events.emit(saved.merchantId(), "payment.canceled", saved);
          return saved;
        });
  }

  /** The void's answer did not say VOIDED: only the query can tell whether it happened anyway. */
  private void requireVoidedAtCielo(
      Payment current, ResolvedProvider<CardMethodProvider> resolved, ProviderException failure) {
    Optional<CardAuthorization> atCielo;
    try {
      atCielo =
          providers.call(
              current.id(),
              "findCard",
              resolved,
              target -> target.provider().find(target.credentials(), current.card().paymentId()));
    } catch (ProviderException again) {
      throw ProviderErrors.toDomain("PROVIDER_UNAVAILABLE", again, log, "findCard", current.id());
    }

    if (atCielo.isPresent() && atCielo.get().status() == CardStatus.VOIDED) {
      return;
    }
    if (failure.code() == ProviderException.Code.TIMEOUT
        || failure.code() == ProviderException.Code.UNAVAILABLE) {
      throw ProviderErrors.toDomain("PROVIDER_TIMEOUT", failure, log, "voidCard", current.id());
    }

    throw new DomainException(
        "INVALID_STATE", "the acquirer no longer accepts voiding this payment");
  }
}
