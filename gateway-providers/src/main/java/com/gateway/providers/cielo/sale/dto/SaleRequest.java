package com.gateway.providers.cielo.sale.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * POST /1/sales, the fields this phase sends (reference/criar-pagamento-credito). Null fields are
 * left out: a tokenized charge has no CardNumber, and a first charge no CardOnFile.Reason.
 *
 * <p>Every toString is overridden: this is the one object that holds the card number as a plain
 * string, and a generated toString would print it into the first log line that touched it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SaleRequest(
    @JsonProperty("MerchantOrderId") String merchantOrderId,
    @JsonProperty("Customer") Customer customer,
    @JsonProperty("Payment") Payment payment) {

  @Override
  public String toString() {
    return "SaleRequest[" + merchantOrderId + "]";
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Customer(
      @JsonProperty("Name") String name,
      @JsonProperty("Identity") String identity,
      @JsonProperty("IdentityType") String identityType,
      @JsonProperty("Email") String email) {

    @Override
    public String toString() {
      return "Customer[***]";
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payment(
      @JsonProperty("Type") String type,
      @JsonProperty("Amount") long amount,
      @JsonProperty("Installments") int installments,
      @JsonProperty("Interest") String interest,
      @JsonProperty("Capture") boolean capture,
      @JsonProperty("SoftDescriptor") String softDescriptor,
      @JsonProperty("CreditCard") CreditCard creditCard,
      @JsonProperty("InitiatedTransactionIndicator")
          InitiatedTransactionIndicator initiatedTransactionIndicator) {

    @Override
    public String toString() {
      return "Payment[" + amount + "]";
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CreditCard(
      @JsonProperty("CardNumber") String cardNumber,
      @JsonProperty("Holder") String holder,
      @JsonProperty("ExpirationDate") String expirationDate,
      @JsonProperty("SecurityCode") String securityCode,
      @JsonProperty("Brand") String brand,
      @JsonProperty("SaveCard") Boolean saveCard,
      @JsonProperty("CardToken") String cardToken,
      @JsonProperty("CardOnFile") CardOnFile cardOnFile) {

    @Override
    public String toString() {
      return "CreditCard[***]";
    }
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record CardOnFile(
      @JsonProperty("Usage") String usage, @JsonProperty("Reason") String reason) {}

  public record InitiatedTransactionIndicator(
      @JsonProperty("Category") String category, @JsonProperty("Subcategory") String subcategory) {}
}
