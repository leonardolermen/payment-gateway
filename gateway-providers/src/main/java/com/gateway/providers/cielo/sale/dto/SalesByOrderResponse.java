package com.gateway.providers.cielo.sale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * GET /1/sales?merchantOrderId= (reference/consulta-merchantorderid-api). The date field is spelled
 * {@code ReceveidDate} by the Cielo, in the example and in the schema (plan D11).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SalesByOrderResponse(
    @JsonProperty("ReasonCode") Integer reasonCode,
    @JsonProperty("ReasonMessage") String reasonMessage,
    @JsonProperty("Payments") List<Item> payments) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Item(
      @JsonProperty("PaymentId") String paymentId,
      @JsonProperty("ReceveidDate") String receivedDate) {}
}
