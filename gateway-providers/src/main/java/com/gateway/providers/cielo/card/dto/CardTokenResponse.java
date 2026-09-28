package com.gateway.providers.cielo.card.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** The page's table says {@code Cardtoken}; the example and the schema say {@code CardToken}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CardTokenResponse(@JsonProperty("CardToken") String cardToken) {

  @Override
  public String toString() {
    return "CardTokenResponse[***]";
  }
}
