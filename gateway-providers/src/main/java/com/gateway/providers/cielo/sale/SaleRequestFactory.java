package com.gateway.providers.cielo.sale;

import com.gateway.kernel.provider.card.CardBrand;
import com.gateway.kernel.provider.card.CardCustomer;
import com.gateway.kernel.provider.card.CardData;
import com.gateway.kernel.provider.card.CardIssueRequest;
import com.gateway.kernel.provider.card.CardOnFileUsage;
import com.gateway.kernel.provider.card.CardToken;
import com.gateway.providers.cielo.CieloText;
import com.gateway.providers.cielo.sale.dto.SaleRequest;
import java.util.EnumSet;
import java.util.Set;

/**
 * CardIssueRequest → the Cielo's POST /1/sales body. The only place in the gateway that reveals the
 * card number and the CVV (spec §7): they go from here into the HTTP body and nowhere else.
 *
 * <p>Card On File (docs/card-on-file): only for the three brands that support it, and only on a
 * charge that stores the card (Usage First) or uses a stored one (Usage Used, Reason Unscheduled —
 * customer present, spec §6.6). Mastercard alone requires the InitiatedTransactionIndicator, C1 /
 * CredentialsOnFile for a customer-initiated charge (plan D6).
 */
public final class SaleRequestFactory {
  private static final Set<CardBrand> CARD_ON_FILE_BRANDS =
      EnumSet.of(CardBrand.VISA, CardBrand.MASTER, CardBrand.ELO);
  private static final String CREDIT_CARD = "CreditCard";
  private static final String BY_MERCHANT = "ByMerchant";

  private SaleRequestFactory() {}

  public static SaleRequest from(CardIssueRequest request) {
    SaleRequest.CreditCard creditCard =
        switch (request.source()) {
          case CardData card -> newCard(card, request.saveCard());
          case CardToken token -> storedCard(token);
        };

    boolean markedCardOnFile = creditCard.cardOnFile() != null;
    SaleRequest.InitiatedTransactionIndicator initiated =
        markedCardOnFile && request.source().brand() == CardBrand.MASTER
            ? new SaleRequest.InitiatedTransactionIndicator("C1", "CredentialsOnFile")
            : null;

    SaleRequest.Payment payment =
        new SaleRequest.Payment(
            CREDIT_CARD,
            request.amount().cents(),
            request.installments().count(),
            BY_MERCHANT,
            request.capture(),
            request.softDescriptor() == null ? null : request.softDescriptor().value(),
            creditCard,
            initiated);

    return new SaleRequest(request.merchantOrderId(), customer(request.customer()), payment);
  }

  private static SaleRequest.CreditCard newCard(CardData card, boolean saveCard) {
    SaleRequest.CardOnFile first =
        saveCard && CARD_ON_FILE_BRANDS.contains(card.brand())
            ? new SaleRequest.CardOnFile(usage(CardOnFileUsage.FIRST), null)
            : null;

    return new SaleRequest.CreditCard(
        card.number().reveal(),
        CieloText.holder(card.holder()),
        card.expiry().formatted(),
        card.securityCode().reveal(),
        CieloBrands.nameOf(card.brand()),
        saveCard,
        null,
        first);
  }

  private static SaleRequest.CreditCard storedCard(CardToken token) {
    SaleRequest.CardOnFile used =
        CARD_ON_FILE_BRANDS.contains(token.brand())
            ? new SaleRequest.CardOnFile(usage(token.usage()), "Unscheduled")
            : null;

    return new SaleRequest.CreditCard(
        null,
        null,
        null,
        token.securityCode().reveal(),
        CieloBrands.nameOf(token.brand()),
        null,
        token.value(),
        used);
  }

  private static String usage(CardOnFileUsage usage) {
    return usage == CardOnFileUsage.FIRST ? "First" : "Used";
  }

  private static SaleRequest.Customer customer(CardCustomer customer) {
    String identity = customer.document() == null ? null : customer.document().digits();
    String identityType = null;
    if (customer.document() != null) {
      identityType = customer.document().isCompany() ? "CNPJ" : "CPF";
    }

    return new SaleRequest.Customer(
        CieloText.customerName(customer.name()), identity, identityType, customer.email());
  }
}
