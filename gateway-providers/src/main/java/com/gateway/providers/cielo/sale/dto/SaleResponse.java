package com.gateway.providers.cielo.sale.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A sale as POST /1/sales (201) and GET /1/sales/{PaymentId} (200) return it. ignoreUnknown on
 * every level: "Os retornos de autorização estão sujeitos a inserção de novos campos advindos das
 * bandeiras/emissores" (reference/criar-pagamento-credito). {@code Interest} is not modeled on
 * purpose: the examples echo it as "ByMerchant" in one and 0 in another (plan D16).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SaleResponse(
    @JsonProperty("MerchantOrderId") String merchantOrderId,
    @JsonProperty("Payment") Payment payment) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Payment(
      @JsonProperty("PaymentId") String paymentId,
      @JsonProperty("Status") Integer status,
      @JsonProperty("ReturnCode") String returnCode,
      @JsonProperty("ReturnMessage") String returnMessage,
      @JsonProperty("Tid") String tid,
      @JsonProperty("AuthorizationCode") String authorizationCode,
      @JsonProperty("ProofOfSale") String proofOfSale,
      @JsonProperty("Amount") Long amount,
      @JsonProperty("CapturedAmount") Long capturedAmount,
      @JsonProperty("ReceivedDate") String receivedDate,
      @JsonProperty("CapturedDate") String capturedDate,
      @JsonProperty("CreditCard") CreditCard creditCard) {}

  /** CardNumber here is the Cielo's masked echo ("409168******7641"). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record CreditCard(
      @JsonProperty("CardNumber") String cardNumber,
      @JsonProperty("Brand") String brand,
      @JsonProperty("CardToken") String cardToken) {

    @Override
    public String toString() {
      return "CreditCard[" + brand + ", token=" + (cardToken == null ? "none" : "***") + "]";
    }
  }
}
