package com.gateway.providers.cielo.card.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** POST /1/card/ (reference/criar-cardtoken). Holds a card number: toString is overridden. */
public record CardTokenRequest(
    @JsonProperty("CustomerName") String customerName,
    @JsonProperty("CardNumber") String cardNumber,
    @JsonProperty("Holder") String holder,
    @JsonProperty("ExpirationDate") String expirationDate,
    @JsonProperty("Brand") String brand) {

  @Override
  public String toString() {
    return "CardTokenRequest[" + brand + "]";
  }
}
