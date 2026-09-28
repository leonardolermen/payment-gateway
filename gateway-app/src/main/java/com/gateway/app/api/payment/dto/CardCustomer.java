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

  /**
   * The record's default toString printed CPF/CNPJ and e-mail into any log line or exception
   * message that touched the request (CardPaymentRequest masks only the card); the name stays, it
   * is what support searches by.
   */
  @Override
  public String toString() {
    return "CardCustomer[name=" + name + ", document=***, email=***]";
  }
}
