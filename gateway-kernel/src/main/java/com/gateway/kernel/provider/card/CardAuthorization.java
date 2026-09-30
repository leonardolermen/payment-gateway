package com.gateway.kernel.provider.card;

import com.gateway.kernel.money.Money;
import java.time.Instant;
import java.util.Optional;

/**
 * A sale as the acquirer reports it, after an authorization, a capture or a query. {@code
 * paymentId} is the acquirer's id (the bank reference every later call uses), not ours. {@code
 * declineCode} is set only for DENIED and ABORTED; {@code returnMessage} is the issuer's text, kept
 * for the log and never shown to the merchant. {@code last4} is what the acquirer echoes from its
 * masked number.
 */
public record CardAuthorization(
    String paymentId,
    CardStatus status,
    String returnCode,
    String returnMessage,
    CardDeclineCode declineCode,
    String tid,
    String authorizationCode,
    String proofOfSale,
    Money amount,
    Money capturedAmount,
    CardBrand brand,
    String last4,
    Optional<String> cardToken,
    Instant receivedAt,
    Optional<Instant> capturedAt) {

  @Override
  public String toString() {
    return "CardAuthorization[paymentId="
        + paymentId
        + ", status="
        + status
        + ", returnCode="
        + returnCode
        + ", cardToken="
        + (cardToken.isPresent() ? "***" : "none")
        + "]";
  }
}
