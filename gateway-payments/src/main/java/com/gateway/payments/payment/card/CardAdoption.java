package com.gateway.payments.payment.card;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardDeclineCode;
import com.gateway.kernel.provider.card.CardStatus;
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
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A decided authorization → the payment's state, its outbox row and, with save_card, the stored
 * card (spec §6.4–6.5). The one place that switches on the acquirer's status.
 *
 * <p>Idempotent, like PendingAdoption: a payment no longer CREATED is returned as it is, because
 * the stuck-CREATED sweep and a slow create may both adopt the same sale.
 */
public class CardAdoption {
  private static final Logger log = LoggerFactory.getLogger(CardAdoption.class);

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

    Optional<String> cardId = saveCard(paymentId, authorization, toSave);

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
              event = payment.markAuthorized(withCard(details, cardId), by);
              type = "payment.authorized";
            }
            case PAID -> {
              event =
                  payment.markCompletedByCard(
                      withCard(details, cardId),
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
   * Spec §6.5, as the Task 8 review ruled it: the token becomes a saved card best effort, in its
   * own transaction BEFORE the adoption's. No token means no card and the payment goes on — and so
   * does a failure to store it. The money already moved at the Cielo: if a sealer or an insert
   * failure rolled the adoption back, the payment would stay CREATED, the merchant would get a 500,
   * and a retry with a new Idempotency-Key would charge the payer twice.
   *
   * <p>Not a try/catch inside the adoption's transaction: the insert is a JPA persist, so a
   * constraint or connection failure surfaces at flush and marks the whole transaction
   * rollback-only — catching it there would still lose the payment. A card stored for an adoption
   * that then fails is the lesser harm: the sale is approved and its token is valid.
   */
  private Optional<String> saveCard(
      String paymentId, CardAuthorization authorization, CardToSave toSave) {
    boolean approved =
        authorization.status() == CardStatus.AUTHORIZED
            || authorization.status() == CardStatus.PAID;
    if (toSave == null || !approved || authorization.cardToken().isEmpty()) {
      return Optional.empty();
    }

    try {
      return unitOfWork.inTransaction(
          () -> {
            Payment payment = payments.findById(paymentId).orElseThrow();
            if (payment.status() != PaymentStatus.CREATED) {
              return Optional.<String>empty();
            }

            CardDetails details = payment.card().withAuthorization(authorization);
            SavedCard card =
                savedCards.save(
                    payment.merchantId(),
                    payment.provider(),
                    payment.environment(),
                    authorization.cardToken().get(),
                    authorization.brand() == null
                        ? CardBrand.valueOf(details.brand())
                        : authorization.brand(),
                    details.last4(),
                    toSave.expiry(),
                    toSave.holder(),
                    toSave.customerDocumentHash());

            return Optional.of(card.id());
          });
    } catch (RuntimeException e) {
      // The exception's class only: its message or cause could carry what was being sealed.
      log.warn(
          "card for payment {} was approved but could not be saved ({}); card_id stays null",
          paymentId,
          e.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  private static CardDetails withCard(CardDetails details, Optional<String> cardId) {
    return cardId.map(details::withCardId).orElse(details);
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
