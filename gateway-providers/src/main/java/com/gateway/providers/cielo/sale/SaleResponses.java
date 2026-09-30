package com.gateway.providers.cielo.sale;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardAuthorization;
import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardSource;
import com.gateway.kernel.provider.card.CardStatus;
import com.gateway.providers.cielo.sale.dto.SaleResponse;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * A Cielo sale → {@link CardAuthorization}. The decline code is read only for DENIED and ABORTED;
 * brand and last four come from the Cielo's echo and, when a tokenized answer has no number
 * (reference/cartao-tokenizado-api's 201 has none), from what we sent.
 */
public final class SaleResponses {
  private static final Set<CardStatus> DECLINED = EnumSet.of(CardStatus.DENIED, CardStatus.ABORTED);

  private SaleResponses() {}

  /** {@code source} is null for a query: then only the Cielo's echo is used. */
  public static CardAuthorization toAuthorization(SaleResponse response, CardSource source) {
    SaleResponse.Payment payment = response.payment();
    if (payment == null || payment.paymentId() == null) {
      throw new ProviderException(
          ProviderException.Code.UNKNOWN, 0, null, "Cielo sale without Payment.PaymentId");
    }

    CardStatus status = CieloStatuses.of(payment.status());
    SaleResponse.CreditCard creditCard = payment.creditCard();

    return new CardAuthorization(
        payment.paymentId(),
        status,
        payment.returnCode(),
        payment.returnMessage(),
        DECLINED.contains(status) ? CieloDeclines.of(payment.returnCode()) : null,
        payment.tid(),
        payment.authorizationCode(),
        payment.proofOfSale(),
        payment.amount() == null ? null : Money.brl(payment.amount()),
        payment.capturedAmount() == null ? null : Money.brl(payment.capturedAmount()),
        brand(creditCard, source),
        last4(creditCard, source),
        Optional.ofNullable(creditCard == null ? null : creditCard.cardToken()),
        CieloDates.parse(payment.receivedDate()),
        Optional.ofNullable(CieloDates.parse(payment.capturedDate())));
  }

  private static CardBrand brand(SaleResponse.CreditCard creditCard, CardSource source) {
    CardBrand echoed = creditCard == null ? null : CieloBrands.of(creditCard.brand());
    if (echoed != null || source == null) {
      return echoed;
    }

    return source.brand();
  }

  private static String last4(SaleResponse.CreditCard creditCard, CardSource source) {
    String masked = creditCard == null ? null : creditCard.cardNumber();
    if (masked != null && masked.length() >= 4) {
      return masked.substring(masked.length() - 4);
    }

    return source instanceof CardData card ? card.last4() : null;
  }
}
