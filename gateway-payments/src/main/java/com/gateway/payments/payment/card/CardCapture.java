package com.gateway.payments.payment.card;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentQueries;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.create.ProviderFailures;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Capturing an authorization (spec §6). The Cielo takes one capture per sale — total or one partial
 * — so a second call is ALREADY_CAPTURED, and 20 cents is its floor
 * (reference/capturar-apos-autorizacao: "valor inferior a 20 centavos … não são liquidadas").
 *
 * <p>The Cielo call runs outside any transaction, like every bank call here; a lost answer is read
 * back from the query before anything is decided.
 */
public class CardCapture {
  private static final Logger log = LoggerFactory.getLogger(CardCapture.class);
  private static final long MINIMUM_CENTS = 20;
  private static final String NOT_AVAILABLE_TO_CAPTURE = "308";

  private final PaymentQueries queries;
  private final PaymentRepository payments;
  private final PaymentEvents events;
  private final ProviderGateway providers;
  private final UnitOfWork unitOfWork;

  public CardCapture(
      PaymentQueries queries,
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      UnitOfWork unitOfWork) {
    this.queries = queries;
    this.payments = payments;
    this.events = events;
    this.providers = providers;
    this.unitOfWork = unitOfWork;
  }

  public Payment capture(MerchantId merchantId, String paymentId, Money amountOrNull) {
    Payment current = queries.get(merchantId, paymentId);
    requireAuthorizedCard(current);
    Optional<Money> amount = amountOf(current, amountOrNull);

    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(merchantId, current.environment(), current.provider());
    String cieloPaymentId = current.card().paymentId();

    CardAuthorization captured;
    try {
      captured =
          providers.call(
              paymentId,
              "captureCard",
              resolved,
              target -> target.provider().capture(target.credentials(), cieloPaymentId, amount));
    } catch (ProviderException failure) {
      captured = readBack(current, resolved, failure, amount);
    }

    return complete(merchantId, paymentId, captured, amount);
  }

  private static void requireAuthorizedCard(Payment current) {
    if (current.method() == PaymentMethod.CARD && current.status() == PaymentStatus.COMPLETED) {
      throw new DomainException("ALREADY_CAPTURED", "this payment was already captured");
    }
    if (current.method() != PaymentMethod.CARD || current.status() != PaymentStatus.AUTHORIZED) {
      throw new DomainException(
          "CAPTURE_NOT_ALLOWED",
          "only an authorized card payment can be captured, this one is "
              + current.method()
              + " "
              + current.status());
    }
  }

  private static Optional<Money> amountOf(Payment current, Money amountOrNull) {
    if (amountOrNull == null) {
      return Optional.empty();
    }
    if (amountOrNull.cents() < MINIMUM_CENTS || amountOrNull.greaterThan(current.amount())) {
      throw new DomainException(
          "CAPTURE_AMOUNT_INVALID",
          "amount must be between 20 and " + current.amount().cents() + " cents");
    }

    return Optional.of(amountOrNull);
  }

  /**
   * A timeout or a 503 may have captured; the Cielo's 308 says the sale is not capturable, most
   * likely because it already was. Either way the query decides: PAID is adopted (and, after a 308,
   * reported as ALREADY_CAPTURED); anything else leaves the payment AUTHORIZED.
   */
  private CardAuthorization readBack(
      Payment current,
      ResolvedProvider<CardMethodProvider> resolved,
      ProviderException failure,
      Optional<Money> requested) {
    boolean notCapturable =
        failure.code() == ProviderException.Code.INVALID
            && NOT_AVAILABLE_TO_CAPTURE.equals(failure.providerType());
    boolean mayHaveLanded =
        failure.code() == ProviderException.Code.TIMEOUT
            || failure.code() == ProviderException.Code.UNAVAILABLE;

    if (!notCapturable && !mayHaveLanded) {
      throw ProviderErrors.toDomain("PROVIDER_DECLINED", failure, log, "captureCard", current.id());
    }

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

    if (atCielo.isEmpty() || atCielo.get().status() != CardStatus.PAID) {
      String code = notCapturable ? "PROVIDER_DECLINED" : ProviderFailures.timeoutCodeOf(failure);
      throw ProviderErrors.toDomain(code, failure, log, "captureCard", current.id());
    }

    if (notCapturable) {
      // The capture that landed was an earlier one (a retry, or made outside the gateway), so the
      // amount this request asked for says nothing about it: the acquirer's CapturedAmount wins.
      // Passing the request's amount here understated the refundable total when the sale had been
      // captured in full elsewhere and this call asked for a part (final re-review, 2026-09-28).
      complete(current.merchantId(), current.id(), atCielo.get(), Optional.empty());
      throw new DomainException("ALREADY_CAPTURED", "this payment was already captured");
    }

    return atCielo.get();
  }

  /**
   * Idempotent: a payment already COMPLETED (a racing notification) is returned as it is. One that
   * a concurrent cancel moved to CANCELED is INVALID_STATE, not the IllegalStateException of the
   * forbidden transition, which surfaced as a 500.
   *
   * <p>The amount is the one requested whenever there was one: the Cielo captures exactly that, and
   * the query host lags the PUT — a re-query answering without CapturedAmount made a partial
   * capture of 50 on 100 store paidAmount 100, and every refund above 50 would then be accepted
   * here and refused at the Cielo.
   */
  private Payment complete(
      MerchantId merchantId,
      String paymentId,
      CardAuthorization captured,
      Optional<Money> requested) {
    return unitOfWork.inTransaction(
        () -> {
          Payment payment = payments.findByMerchantAndId(merchantId, paymentId).orElseThrow();
          if (payment.status() == PaymentStatus.COMPLETED) {
            return payment;
          }
          if (payment.status() != PaymentStatus.AUTHORIZED) {
            throw new DomainException(
                "INVALID_STATE",
                "the capture reached the bank, but this payment is now " + payment.status());
          }

          Money capturedAmount =
              requested.orElse(
                  captured.capturedAmount() == null ? payment.amount() : captured.capturedAmount());
          Payment saved =
              payments.save(
                  payment,
                  List.of(
                      payment.markCaptured(
                          capturedAmount,
                          captured.capturedAt().orElse(captured.receivedAt()),
                          EventSource.API)));
          events.emit(saved.merchantId(), "payment.completed", saved);
          return saved;
        });
  }
}
