package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.InvalidValue;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardCustomer;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardIssueRequest;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.card.CardSource;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.provider.card.Installments;
import com.gateway.kernel.provider.card.SoftDescriptor;
import com.gateway.payments.card.SavedCard;
import com.gateway.payments.card.SavedCards;
import com.gateway.payments.payment.EventSource;
import com.gateway.payments.payment.Payment;
import com.gateway.payments.payment.PaymentStatus;
import com.gateway.payments.payment.card.CardAdoption;
import com.gateway.payments.payment.card.CardDeclinedException;
import com.gateway.payments.payment.card.CardDetails;
import com.gateway.payments.payment.card.CardToSave;
import com.gateway.payments.provider.ProviderGateway;
import com.gateway.payments.provider.ProviderGateway.ResolvedProvider;
import java.util.Set;

/**
 * Creating a card payment (spec 2026-09-28 §6). Everything that can be refused is refused before a
 * row exists: the credential, the installments against the amount, the soft descriptor, the
 * customer's name and a card_id that is not this merchant's. Then CREATED with no card data, the
 * authorization outside any transaction with MerchantOrderId = the payment id, and one adoption.
 *
 * <p>A decline is a result, not an exception from the acquirer (spec §11): the payment is FAILED
 * with its decline code and the merchant gets CARD_DECLINED — thrown only after the FAILED row and
 * its outbox row have committed.
 */
public class CardPaymentFlow implements PaymentFlow {
  /** The one acquirer the card method goes to until per-merchant routing exists (spec §2). */
  public static final String PROVIDER = "CIELO";

  /** A timeout, or a 503/504 in front of the Cielo, says nothing about whether the sale exists. */
  private static final Set<ProviderException.Code> MAY_HAVE_LANDED =
      Set.of(ProviderException.Code.TIMEOUT, ProviderException.Code.UNAVAILABLE);

  private final ProviderGateway providers;
  private final PaymentDraftFactory drafts;
  private final CardAdoption adoption;
  private final CardAuthorizationRecovery recovery;
  private final SavedCards savedCards;
  private final CreateFailures failures;

  public CardPaymentFlow(
      ProviderGateway providers,
      PaymentDraftFactory drafts,
      CardAdoption adoption,
      CardAuthorizationRecovery recovery,
      SavedCards savedCards,
      CreateFailures failures) {
    this.providers = providers;
    this.drafts = drafts;
    this.adoption = adoption;
    this.recovery = recovery;
    this.savedCards = savedCards;
    this.failures = failures;
  }

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  @Override
  public Payment create(CreatePaymentCommand command) {
    CreateCardPayment cardPayment = (CreateCardPayment) command;

    ResolvedProvider<CardMethodProvider> resolved =
        providers.resolveCard(cardPayment.merchantId(), cardPayment.environment(), PROVIDER);
    requireIssueCredentials(resolved, cardPayment);

    Installments installments =
        InstallmentPlan.of(cardPayment.amount(), cardPayment.installments());
    SoftDescriptor softDescriptor = softDescriptorOf(cardPayment);
    CardCustomer customer = CardCustomerFactory.from(cardPayment.customer());
    String documentHash =
        cardPayment.customer() == null
            ? null
            : CustomerDocumentHash.of(cardPayment.customer().document());

    ChosenCard chosen = choose(cardPayment);

    Payment payment =
        drafts.card(
            cardPayment,
            PROVIDER,
            CardDetails.requested(
                installments.count(),
                cardPayment.interest(),
                chosen.source().brand().name(),
                chosen.last4(),
                chosen.cardId()),
            documentHash);

    CardToSave toSave = toSaveOf(cardPayment, documentHash);
    CardIssueRequest request =
        new CardIssueRequest(
            payment.id(),
            cardPayment.amount(),
            installments,
            cardPayment.captures(),
            toSave != null,
            softDescriptor,
            chosen.source(),
            customer);

    return declinedOrAdopted(authorize(payment, resolved, request, toSave));
  }

  private Payment authorize(
      Payment payment,
      ResolvedProvider<CardMethodProvider> resolved,
      CardIssueRequest request,
      CardToSave toSave) {
    CardAuthorization authorization;
    try {
      authorization =
          providers.call(
              payment.id(),
              "authorizeCard",
              resolved,
              target -> target.provider().issue(target.credentials(), request));
    } catch (ProviderException failure) {
      return recover(payment, resolved, failure, toSave);
    }

    if (authorization.status().inDoubt()) {
      // A 201 that is not an answer yet (Status 0, 12, 14): the same question as a lost answer.
      ProviderException inDoubt =
          new ProviderException(
              ProviderException.Code.TIMEOUT,
              201,
              authorization.returnCode(),
              "authorization answered " + authorization.status());
      return recovery.recover(payment, resolved, inDoubt, toSave);
    }

    return adoption.adopt(payment.id(), authorization, toSave, EventSource.API);
  }

  private Payment recover(
      Payment payment,
      ResolvedProvider<CardMethodProvider> resolved,
      ProviderException failure,
      CardToSave toSave) {
    ProviderFailures.Outcome outcome = ProviderFailures.classify(failure, MAY_HAVE_LANDED);

    if (outcome != ProviderFailures.Outcome.MAY_HAVE_LANDED) {
      String code =
          outcome == ProviderFailures.Outcome.DECLINED
              ? "PROVIDER_DECLINED"
              : "PROVIDER_UNAVAILABLE";
      throw failures.fail(payment.id(), code, failure, null);
    }

    return recovery.recover(payment, resolved, failure, toSave);
  }

  /** Thrown after the FAILED row committed, so a retry with the same key replays the same 402. */
  private static Payment declinedOrAdopted(Payment payment) {
    if (payment.status() == PaymentStatus.FAILED && payment.card().declineCode() != null) {
      throw new CardDeclinedException(payment.id(), payment.card().declineCode());
    }

    return payment;
  }

  /** The source the acquirer charges, and the face of the card the payment shows. */
  private record ChosenCard(CardSource source, String last4, String cardId) {}

  private ChosenCard choose(CreateCardPayment cardPayment) {
    return switch (cardPayment.card()) {
      case CardChoice.NewCard newCard ->
          new ChosenCard(newCard.card(), newCard.card().last4(), null);
      case CardChoice.SavedCardChoice saved -> {
        // tokenFor first: CARD_NOT_FOUND (422), not the card resource's NOT_FOUND (404).
        CardToken token =
            savedCards.tokenFor(
                cardPayment.merchantId(),
                cardPayment.environment(),
                saved.cardId(),
                saved.securityCode());
        SavedCard card = savedCards.get(cardPayment.merchantId(), saved.cardId());
        yield new ChosenCard(token, card.last4(), card.id());
      }
      case CardChoice.RecurringCard recurring -> {
        CardToken token =
            savedCards.tokenForRecurring(
                cardPayment.merchantId(), cardPayment.environment(), recurring.cardId());
        SavedCard card = savedCards.get(cardPayment.merchantId(), recurring.cardId());
        yield new ChosenCard(token, card.last4(), card.id());
      }
    };
  }

  private static CardToSave toSaveOf(CreateCardPayment cardPayment, String documentHash) {
    if (!(cardPayment.card() instanceof CardChoice.NewCard newCard) || !newCard.save()) {
      return null;
    }

    CardData card = newCard.card();
    return new CardToSave(card.holder().value(), card.expiry().value(), documentHash);
  }

  private static SoftDescriptor softDescriptorOf(CreateCardPayment cardPayment) {
    try {
      return SoftDescriptor.ofNullable(cardPayment.softDescriptor());
    } catch (InvalidValue e) {
      throw new DomainException("INVALID_SOFT_DESCRIPTOR", "soft_descriptor " + e.reason());
    }
  }

  private static void requireIssueCredentials(
      ResolvedProvider<CardMethodProvider> resolved, CreateCardPayment cardPayment) {
    try {
      resolved.provider().requireIssueCredentials(resolved.credentials());
    } catch (ProviderException e) {
      if (e.code() == ProviderException.Code.CREDENTIALS_INCOMPLETE) {
        throw new DomainException(
            "PROVIDER_CREDENTIALS_MISSING",
            "the "
                + PROVIDER
                + " "
                + cardPayment.environment()
                + " credential is missing "
                + e.providerType());
      }
      throw e;
    }
  }
}
