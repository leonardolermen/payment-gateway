package com.gateway.kernel.provider.card;

import com.gateway.kernel.money.Money;

/**
 * One authorization. {@code merchantOrderId} is ours — the payment id — so a timeout can be
 * followed by a query for it. {@code capture} and {@code saveCard} are what the Cielo's own request
 * carries as booleans; they are fields of a request, not flags steering a method.
 */
public record CardIssueRequest(
    String merchantOrderId,
    Money amount,
    Installments installments,
    boolean capture,
    boolean saveCard,
    SoftDescriptor softDescriptor,
    CardSource source,
    CardCustomer customer) {

  @Override
  public String toString() {
    return "CardIssueRequest[" + merchantOrderId + ", " + amount.cents() + ", " + source + "]";
  }
}
