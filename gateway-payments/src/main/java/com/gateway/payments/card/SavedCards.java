package com.gateway.payments.card;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.NotFoundException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.kernel.security.Sealer;
import com.gateway.kernel.security.Secret;
import com.gateway.payments.card.persistence.SavedCardRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.YearMonth;

/**
 * Saving, reading, charging with and deleting a merchant's cards. The acquirer's token is sealed
 * with {@code merchant|provider|environment|card} as context (spec §4): a row moved to another
 * merchant, or read under the other environment, does not open.
 *
 * <p>Absent, deleted and another merchant's card are the same answer (spec §4): NOT_FOUND for the
 * card resource, CARD_NOT_FOUND for a charge — never 403, so existence does not leak.
 */
public class SavedCards {
  private final SavedCardRepository cards;
  private final Sealer sealer;
  private final Clock clock;

  public SavedCards(SavedCardRepository cards, Sealer sealer, Clock clock) {
    this.cards = cards;
    this.sealer = sealer;
    this.clock = clock;
  }

  /**
   * Joins the caller's transaction: a card is saved with the adoption of the payment that stored
   * it.
   */
  public SavedCard save(
      MerchantId merchantId,
      String provider,
      ProviderEnvironment environment,
      String acquirerToken,
      CardBrand brand,
      String last4,
      YearMonth expiry,
      String holder,
      String customerDocumentHash) {
    SavedCard card =
        new SavedCard(
            Ulid.next(),
            merchantId,
            provider,
            environment,
            brand,
            last4,
            expiry,
            holder,
            customerDocumentHash,
            clock.instant(),
            null);
    byte[] sealed =
        sealer.seal(
            acquirerToken.getBytes(StandardCharsets.UTF_8),
            context(merchantId, provider, environment));

    cards.insert(card, sealed);

    return card;
  }

  public SavedCard get(MerchantId merchantId, String cardId) {
    return cards
        .findActive(merchantId, cardId)
        .orElseThrow(() -> new NotFoundException("card", cardId));
  }

  /**
   * The stored card as a charge needs it: the token, marked USED, with the CVV the payer typed now.
   */
  public CardToken tokenFor(
      MerchantId merchantId, ProviderEnvironment environment, String cardId, Secret securityCode) {
    SavedCard card =
        cards
            .findActive(merchantId, cardId)
            .filter(found -> found.environment() == environment)
            .orElseThrow(
                () -> new DomainException("CARD_NOT_FOUND", "card_id " + cardId + " not found"));
    byte[] sealed = cards.findActiveToken(merchantId, cardId).orElseThrow();
    String token =
        new String(
            sealer.open(sealed, context(merchantId, card.provider(), card.environment())),
            StandardCharsets.UTF_8);

    return new CardToken(token, card.brand(), CardOnFileUsage.USED, securityCode);
  }

  public void delete(MerchantId merchantId, String cardId) {
    if (!cards.markDeleted(merchantId, cardId, clock.instant())) {
      throw new NotFoundException("card", cardId);
    }
  }

  private static String context(
      MerchantId merchantId, String provider, ProviderEnvironment environment) {
    return merchantId.value() + "|" + provider + "|" + environment.name() + "|card";
  }
}
