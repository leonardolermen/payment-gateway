package com.gateway.app.api.payment.dto;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.payments.payment.create.CardChoice;
import com.gateway.payments.payment.create.CardDataFactory;
import com.gateway.payments.payment.create.CreateCardPayment;
import com.gateway.payments.payment.create.CreatePaymentCommand;
import java.time.YearMonth;
import java.time.ZoneId;

/**
 * A credit card charge (spec 2026-09-28 §9): {@code card} or {@code card_id}, never both; {@code
 * cvv} goes with {@code card_id}. The card is turned into a {@code CardData} here, where the body
 * is read, so the command carries no raw number (spec §6.1); the domain still owns the 422 text
 * ({@link CardDataFactory}).
 *
 * <p>The current month is São Paulo's, the zone every date of this gateway is decided in.
 */
public record CardPaymentRequest(
    Long amount,
    String currency,
    String reference,
    String description,
    String softDescriptor,
    CardFields card,
    String cardId,
    String cvv,
    Integer installments,
    Boolean capture,
    Boolean saveCard,
    CardCustomer customer)
    implements CreatePaymentRequest {
  private static final ZoneId SAO_PAULO = ZoneId.of("America/Sao_Paulo");

  @Override
  public PaymentMethod method() {
    return PaymentMethod.CARD;
  }

  @Override
  public void validate() {
    RequestedAmount.of(amount, currency);

    if ((card == null) == (cardId == null)) {
      throw new IllegalArgumentException("exactly one of card and card_id is required");
    }
    if (cardId != null && Boolean.TRUE.equals(saveCard)) {
      throw new IllegalArgumentException("save_card applies to a new card, not to card_id");
    }
  }

  @Override
  public CreatePaymentCommand toCommand(MerchantId merchantId, ProviderEnvironment environment) {
    return new CreateCardPayment(
        merchantId,
        environment,
        RequestedAmount.of(amount, currency),
        reference,
        description,
        choice(),
        installments,
        capture,
        softDescriptor,
        customer == null ? null : customer.toData(),
        null);
  }

  private CardChoice choice() {
    if (card != null) {
      return new CardChoice.NewCard(
          card.toCardData(YearMonth.now(SAO_PAULO)), Boolean.TRUE.equals(saveCard));
    }

    return new CardChoice.SavedCardChoice(cardId, CardDataFactory.securityCodeForSavedCard(cvv));
  }

  @Override
  public String toString() {
    return "CardPaymentRequest[amount=" + amount + ", reference=" + reference + ", card=***]";
  }
}
