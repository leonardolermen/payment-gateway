package com.gateway.payments.payment.card;

import com.gateway.kernel.provider.card.CardAuthorization;

/**
 * The card side of a payment, as {@code details.card} stores it (spec §4). {@code paymentId} is the
 * Cielo's PaymentId — the bank reference capture, void and query take — and {@code cardId} is our
 * saved-card id when the payment stored or used one. Never a number, an expiry or a CVV.
 *
 * <p>{@code brand} is the kernel CardBrand's name, kept as text so the details document reads back
 * even if a brand is ever renamed.
 */
public record CardDetails(
    String paymentId,
    String tid,
    String authorizationCode,
    String proofOfSale,
    String brand,
    String last4,
    int installments,
    Long capturedAmount,
    String cardId,
    String declineCode) {

  /** What is known before the acquirer answers: the merchant's request and the card's face. */
  public static CardDetails requested(int installments, String brand, String last4, String cardId) {
    return new CardDetails(null, null, null, null, brand, last4, installments, null, cardId, null);
  }

  /** The acquirer's identifiers; its brand and last four win when it sent them. */
  public CardDetails withAuthorization(CardAuthorization authorization) {
    return new CardDetails(
        authorization.paymentId(),
        authorization.tid(),
        authorization.authorizationCode(),
        authorization.proofOfSale(),
        authorization.brand() == null ? brand : authorization.brand().name(),
        authorization.last4() == null ? last4 : authorization.last4(),
        installments,
        authorization.capturedAmount() == null ? null : authorization.capturedAmount().cents(),
        cardId,
        authorization.declineCode() == null ? null : authorization.declineCode().name());
  }

  public CardDetails withCaptured(long cents) {
    return new CardDetails(
        paymentId,
        tid,
        authorizationCode,
        proofOfSale,
        brand,
        last4,
        installments,
        cents,
        cardId,
        declineCode);
  }

  public CardDetails withCardId(String cardId) {
    return new CardDetails(
        paymentId,
        tid,
        authorizationCode,
        proofOfSale,
        brand,
        last4,
        installments,
        capturedAmount,
        cardId,
        declineCode);
  }

  public CardDetails withDecline(String declineCode) {
    return new CardDetails(
        paymentId,
        tid,
        authorizationCode,
        proofOfSale,
        brand,
        last4,
        installments,
        capturedAmount,
        cardId,
        declineCode);
  }
}
