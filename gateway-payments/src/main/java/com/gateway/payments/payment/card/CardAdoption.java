package com.gateway.payments.payment.card;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardDeclineCode;
import com.gateway.payments.UnitOfWork;
import com.gateway.payments.card.SavedCard;
import com.gateway.payments.card.SavedCards;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentEvent;
import com.gateway.payments.payment.PaymentEvents;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.persistence.PaymentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * A decided authorization → the payment's state, its outbox row and, with save_card, the stored
 * card — one transaction (spec §6.4–6.5). The one place that switches on the acquirer's status.
 *
 * <p>Idempotent, like PendingAdoption: a payment no longer CREATED is returned as it is, because
 * the stuck-CREATED sweep and a slow create may both adopt the same sale.
 */
public class CardAdoption {
  private final PaymentRepository payments;
  private final PaymentEvents events;
  private final SavedCards savedCards;
  private final UnitOfWork unitOfWork;
  private final Clock clock;

  public CardAdoption(
      PaymentRepository payments,
      PaymentEvents events,
      SavedCards savedCards,
      UnitOfWork unitOfWork,
      Clock clock) {
    this.payments = payments;
    this.events = events;
    this.savedCards = savedCards;
    this.unitOfWork = unitOfWork;
    this.clock = clock;
  }

  /** {@code authorization} must be decided ({@code !status().inDoubt()}); the callers ask first. */
  public Payment adopt(
      String paymentId, CardAuthorization authorization, CardToSave toSave, EventSource by) {
    if (authorization.status().inDoubt()) {
      throw new IllegalStateException(
          "an in-doubt authorization (" + authorization.status() + ") is recovered, never adopted");
    }

    return unitOfWork.inTransaction(
        () -> {
          Payment payment = payments.findById(paymentId).orElseThrow();
          if (payment.status() != PaymentStatus.CREATED) {
            return payment;
          }

          CardDetails details = payment.card().withAuthorization(authorization);
          String type;
          PaymentEvent event;

          switch (authorization.status()) {
            case AUTHORIZED -> {
              event = payment.markAuthorized(saveCard(payment, details, authorization, toSave), by);
              type = "payment.authorized";
            }
            case PAID -> {
              event =
                  payment.markCompletedByCard(
                      saveCard(payment, details, authorization, toSave),
                      capturedAmount(payment, authorization),
                      capturedAt(authorization),
                      by);
              type = "payment.completed";
            }
            default -> {
              // DENIED and ABORTED carry the issuer's answer; VOIDED/REFUNDED on a fresh sale mean
              // the acquirer undid it (the Cancellation Guarantee), which is no approval either.
              CardDeclineCode decline =
                  authorization.declineCode() == null
                      ? CardDeclineCode.GENERIC
                      : authorization.declineCode();
              event = payment.markDeclined(details.withDecline(decline.name()), by);
              type = "payment.failed";
            }
          }

          Payment saved = payments.save(payment, List.of(event));
          events.emit(saved.merchantId(), type, saved);
          return saved;
        });
  }

  /**
   * Spec §6.5: the token becomes a saved card in this same transaction; no token means no card and
   * the payment goes on — an approved sale is never failed because the token did not come back.
   */
  private CardDetails saveCard(
      Payment payment, CardDetails details, CardAuthorization authorization, CardToSave toSave) {
    if (toSave == null || authorization.cardToken().isEmpty()) {
      return details;
    }

    SavedCard card =
        savedCards.save(
            payment.merchantId(),
            payment.provider(),
            payment.environment(),
            authorization.cardToken().get(),
            authorization.brand() == null
                ? com.gateway.kernel.provider.card.CardBrand.valueOf(details.brand())
                : authorization.brand(),
            details.last4(),
            toSave.expiry(),
            toSave.holder(),
            toSave.customerDocumentHash());

    return details.withCardId(card.id());
  }

  private static Money capturedAmount(Payment payment, CardAuthorization authorization) {
    return authorization.capturedAmount() == null
        ? payment.amount()
        : authorization.capturedAmount();
  }

  private Instant capturedAt(CardAuthorization authorization) {
    return authorization.capturedAt().orElse(clock.instant());
  }
}
