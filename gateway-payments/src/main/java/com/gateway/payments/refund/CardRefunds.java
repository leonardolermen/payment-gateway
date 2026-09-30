package com.gateway.payments.refund;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardRefundResult;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderErrors;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import com.gateway.payments.reconciliation.Divergences;
import com.gateway.payments.refund.persistence.RefundRepository;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A card refund: the Cielo's void with an amount, which answers in the same call (spec §4). So the
 * refund goes REQUESTED → COMPLETED | FAILED inside the request, with no POLL_REFUND job.
 *
 * <p>The reserve is the Pix one — the payment row locked, everything not FAILED counted — against
 * the paid amount, which a partial capture makes smaller than the amount. A timeout is not a
 * failure: the void may have gone through, so the refund stays PROCESSING with its amount reserved
 * and a REFUND_UNKNOWN divergence for a human (plan C11); the notification or the reconciliation
 * shows what the Cielo did.
 */
public class CardRefunds {
  private static final Logger log = LoggerFactory.getLogger(CardRefunds.class);
  private static final Set<ProviderException.Code> MAY_HAVE_LANDED =
      Set.of(ProviderException.Code.TIMEOUT, ProviderException.Code.UNAVAILABLE);

  private final RefundRepository refunds;
  private final PaymentRepository payments;
  private final ProviderGateway providers;
  private final PaymentEvents events;
  private final Divergences divergences;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public CardRefunds(
      RefundRepository refunds,
      PaymentRepository payments,
      ProviderGateway providers,
      PaymentEvents events,
      Divergences divergences,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.refunds = refunds;
    this.payments = payments;
    this.providers = providers;
    this.events = events;
    this.divergences = divergences;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  public Refund request(MerchantId merchantId, Payment payment, Money amountOrNull) {
    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(merchantId, payment.environment(), payment.provider());

    Refund refund = reserve(merchantId, payment.id(), amountOrNull);

    CardRefundResult result;
    try {
      result =
          providers.call(
              payment.id(),
              "refundCard",
              resolved,
              target ->
                  target
                      .provider()
                      .refund(
                          target.credentials(),
                          payment.card().paymentId(),
                          Optional.of(refund.amount())));
    } catch (ProviderException failure) {
      throw afterFailure(merchantId, payment, refund, failure);
    }

    if (!result.completed()) {
      ProviderException refused =
          new ProviderException(
              ProviderException.Code.DECLINED,
              200,
              result.returnCode(),
              "void answered return code " + result.returnCode());
      // The code is the reason support needs (100 = partial before settlement); it is the Cielo's
      // table key, not its wording, so it carries none of the payer data ProviderErrors keeps out.
      String reason =
          ProviderErrors.message("PROVIDER_DECLINED") + " Return code " + result.returnCode() + ".";
      throw markFailed(merchantId, refund, refused, reason);
    }

    return complete(merchantId, refund);
  }

  private Refund reserve(MerchantId merchantId, String paymentId, Money amountOrNull) {
    return unitOfWork.inTransaction(
        () -> {
          Payment locked = payments.findByIdForUpdate(paymentId).orElseThrow();
          if (locked.status() != PaymentStatus.COMPLETED) {
            throw new DomainException(
                "INVALID_STATE",
                "only a captured card payment can be refunded, this one is "
                    + locked.status()
                    + "; an authorization is canceled");
          }

          Money amount =
              RefundReservation.reserve(
                  refunds.findByPayment(paymentId),
                  locked.refundable(),
                  amountOrNull,
                  "paid amount");

          return refunds.save(Refund.request(paymentId, merchantId, amount, clock));
        });
  }

  private Refund complete(MerchantId merchantId, Refund refund) {
    return unitOfWork.inTransaction(
        () -> {
          Payment payment = payments.findByIdForUpdate(refund.paymentId()).orElseThrow();
          Refund loaded = refunds.findById(refund.id()).orElseThrow();
          loaded.markCompleted(clock.instant());
          Refund saved = refunds.save(loaded);
          Payment updated =
              payments.save(
                  payment,
                  List.of(payment.recordRefund(saved.id(), saved.amount(), true, EventSource.API)));
          events.emitRefund(merchantId, "refund.completed", saved, updated);
          return saved;
        });
  }

  private DomainException afterFailure(
      MerchantId merchantId, Payment payment, Refund refund, ProviderException failure) {
    if (!MAY_HAVE_LANDED.contains(failure.code())) {
      return markFailed(merchantId, refund, failure, ProviderErrors.message("PROVIDER_DECLINED"));
    }

    unitOfWork.run(
        () -> {
          Refund loaded = refunds.findById(refund.id()).orElseThrow();
          loaded.markProcessing();
          Refund saved = refunds.save(loaded);
          events.emitRefund(merchantId, "refund.requested", saved, payment);
          divergences.open(
              payment,
              "REFUND_UNKNOWN",
              "card refund " + refund.id() + " ended in " + failure.code() + "; check the Cielo");
        });

    String code =
        failure.code() == ProviderException.Code.TIMEOUT
            ? "PROVIDER_TIMEOUT"
            : "PROVIDER_UNAVAILABLE";
    return ProviderErrors.toDomain(code, failure, log, "refundCard", refund.id());
  }

  private DomainException markFailed(
      MerchantId merchantId, Refund refund, ProviderException cause, String reason) {
    String code = "PROVIDER_DECLINED";
    unitOfWork.run(
        () -> {
          Payment payment = payments.findByIdForUpdate(refund.paymentId()).orElseThrow();
          Refund loaded = refunds.findById(refund.id()).orElseThrow();
          loaded.markFailed(reason);
          events.emitRefund(merchantId, "refund.failed", refunds.save(loaded), payment);
        });
    return ProviderErrors.toDomain(code, cause, log, "refundCard", refund.id());
  }
}
