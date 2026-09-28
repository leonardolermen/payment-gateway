package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.InvalidValue;
import com.gateway.kernel.party.Document;
import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.card.CardCustomer;

/**
 * The card customer: the name is required (the Cielo refuses a sale without Customer.Name, code
 * 105), the document and e-mail are optional. Same code and field spelling as PayerFactory.
 */
public final class CardCustomerFactory {
  private static final String CODE = "CUSTOMER_REQUIRED";

  private CardCustomerFactory() {}

  public static CardCustomer from(CardCustomerData data) {
    if (data == null) {
      throw new DomainException(CODE, "customer.name is required");
    }

    PersonName name;
    try {
      name = PersonName.of(data.name());
    } catch (InvalidValue e) {
      throw new DomainException(CODE, "customer.name " + e.reason());
    }

    return new CardCustomer(name, documentOf(data), blankToNull(data.email()));
  }

  private static Document documentOf(CardCustomerData data) {
    if (data.document() == null || data.document().isBlank()) {
      return null;
    }

    try {
      return Document.of(data.document());
    } catch (InvalidValue e) {
      throw new DomainException(CODE, "customer.document " + e.reason());
    }
  }

  private static String blankToNull(String text) {
    return text == null || text.isBlank() ? null : text.trim();
  }
}
