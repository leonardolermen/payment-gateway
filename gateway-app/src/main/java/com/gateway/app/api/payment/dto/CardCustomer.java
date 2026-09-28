package com.gateway.app.api.payment.dto;

import com.gateway.payments.payment.create.CardCustomerData;

/**
 * The customer of a CARD create: name (required by the domain), document and e-mail. Not {@link
 * Customer}: that one has the boleto's address, which a card does not take, and fail-on-unknown
 * should refuse an address on a card body rather than swallow it.
 */
public record CardCustomer(String name, String document, String email) {

  CardCustomerData toData() {
    return new CardCustomerData(name, document, email);
  }
}
