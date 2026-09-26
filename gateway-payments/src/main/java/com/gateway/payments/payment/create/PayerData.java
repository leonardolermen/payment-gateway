package com.gateway.payments.payment.create;

/**
 * The payer exactly as the merchant sent it, before anything is checked — plain strings,
 * punctuation and all.
 *
 * <p>It exists because {@link com.gateway.kernel.party.Payer} no longer accepts bad data: with
 * typed components a malformed Payer cannot be constructed, so a create command cannot carry one.
 * {@link PayerFactory} is the single door between the two, and it lives in the domain because that
 * is where the 422 that names the field belongs.
 */
public record PayerData(String name, String document, AddressData address) {

  public record AddressData(
      String street, String district, String city, String state, String zip) {}
}
