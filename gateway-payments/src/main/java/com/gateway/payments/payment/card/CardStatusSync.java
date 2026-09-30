package com.gateway.payments.payment.card;

import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.persistence.PaymentRepository;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import com.gateway.payments.reconciliation.Divergences;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Cielo's word on a card payment, from GET /1/sales/{PaymentId} — reached by a notification or
 * by the reconciliation, never by a body alone (spec §8). Moves the payment only where the state
 * machine has a row for it (AUTHORIZED → COMPLETED or CANCELED); everything else that disagrees is
 * a divergence for a human, because there is no legal way out of COMPLETED, FAILED or CANCELED and
 * guessing would be worse.
 */
public class CardStatusSync {
  private static final Set<CardStatus> MONEY_WENT_BACK =
      EnumSet.of(CardStatus.VOIDED, CardStatus.REFUNDED);
  private static final Set<CardStatus> ACTIVE = EnumSet.of(CardStatus.AUTHORIZED, CardStatus.PAID);

  private final PaymentRepository payments;
  private final PaymentEvents events;
  private final ProviderGateway providers;
  private final Divergences divergences;
  private final UnitOfWork unitOfWork;

  public CardStatusSync(
      PaymentRepository payments,
      PaymentEvents events,
      ProviderGateway providers,
      Divergences divergences,
      UnitOfWork unitOfWork) {
    this.payments = payments;
    this.events = events;
    this.providers = providers;
    this.divergences = divergences;
    this.unitOfWork = unitOfWork;
  }

  /** The Cielo unreachable propagates: the inbox job and the reconciliation both retry. */
  public void sync(Payment payment, EventSource by) {
    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(payment.merchantId(), payment.environment(), payment.provider());

    Optional<CardAuthorization> atCielo =
        providers.call(
            payment.id(),
            "findCard",
            resolved,
            target -> target.provider().find(target.credentials(), payment.card().paymentId()));

    if (atCielo.isEmpty()) {
      // The query answers only for the last three months (consulta-merchantorderid-api); inside the
      // reconciliation window an unknown PaymentId is a real disagreement.
      divergences.open(
          payment,
          "NOT_FOUND_AT_PROVIDER",
          "GET /1/sales/" + payment.card().paymentId() + " empty");
      return;
    }

    apply(payment.id(), atCielo.get(), by);
  }

  private void apply(String paymentId, CardAuthorization sale, EventSource by) {
    unitOfWork.run(
        () -> {
          Payment payment = payments.findById(paymentId).orElseThrow();

          switch (payment.status()) {
            case AUTHORIZED -> fromAuthorized(payment, sale, by);
            case COMPLETED -> fromCompleted(payment, sale);
            case FAILED, CANCELED -> fromClosed(payment, sale);
            default -> {
              // CREATED is the flow's and the sweeper's; PENDING and EXPIRED do not exist for a
              // card.
            }
          }
        });
  }

  private void fromAuthorized(Payment payment, CardAuthorization sale, EventSource by) {
    if (sale.status() == CardStatus.PAID) {
      Payment saved =
          payments.save(
              payment,
              List.of(
                  payment.markCaptured(
                      CardAdoption.capturedAmount(payment, sale),
                      // No clock here: the sale's own receivedAt is the closest instant we have.
                      CardAdoption.capturedAt(sale, sale.receivedAt()),
                      by)));
      events.emit(saved.merchantId(), "payment.completed", saved);
    } else if (sale.status() == CardStatus.VOIDED) {
      Payment saved = payments.save(payment, List.of(payment.markCanceled(by)));
      events.emit(saved.merchantId(), "payment.canceled", saved);
    }
  }

  private void fromCompleted(Payment payment, CardAuthorization sale) {
    if (MONEY_WENT_BACK.contains(sale.status()) && !payment.fullyRefunded()) {
      divergences.open(
          payment,
          "REFUNDED_AT_PROVIDER",
          "the Cielo shows "
              + sale.status()
              + "; the gateway refunded "
              + payment.refundedAmount().cents());
    }
  }

  private void fromClosed(Payment payment, CardAuthorization sale) {
    if (ACTIVE.contains(sale.status())) {
      divergences.open(
          payment,
          "CARD_ACTIVE_AT_PROVIDER",
          "the Cielo shows " + sale.status() + " for a " + payment.status() + " payment");
    }
  }
}
