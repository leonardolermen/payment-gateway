package com.gateway.app.api.dto;

/**
 * {@code amount} is integer cents and boxed: a missing amount must be a 400, not a silent charge of
 * zero. {@code currency} and {@code method} are required even though only BRL/PIX exist today, so a
 * client's request already says what it means the day a second method arrives.
 */
public record CreatePaymentRequest(
    Long amount, String currency, String method, String reference, String description, Customer customer, Integer expiresIn) {

  public record Customer(String document) {}

  public void validate() {
    if (amount == null || amount <= 0) throw new IllegalArgumentException("amount must be a positive number of cents");
    if (!"BRL".equals(currency)) throw new IllegalArgumentException("currency must be BRL");
    if (!"PIX".equals(method)) throw new IllegalArgumentException("method must be PIX");
    if (expiresIn != null && expiresIn <= 0) throw new IllegalArgumentException("expires_in must be positive seconds");
  }
}
