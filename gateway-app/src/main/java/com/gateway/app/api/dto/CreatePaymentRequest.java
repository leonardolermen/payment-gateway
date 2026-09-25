package com.gateway.app.api.dto;

import com.gateway.kernel.provider.boleto.Payer;
import java.time.LocalDate;

/**
 * {@code amount} is integer cents and boxed: a missing amount must be a 400, not a silent charge of
 * zero. {@code method} chooses the shape: PIX takes {@code expires_in}; BOLECODE takes a complete
 * {@code customer} (checked by the service, which names the missing field), {@code due_date} and
 * {@code payment_limit_days}. Mixing the two is a 400 here — a Bolecode has no expiry other than
 * its payment limit date.
 */
public record CreatePaymentRequest(
    Long amount, String currency, String method, String reference, String description, Customer customer, Integer expiresIn,
    LocalDate dueDate, Integer paymentLimitDays) {

  public record Customer(String name, String document, Address address) {}

  public record Address(String street, String district, String city, String state, String zip) {}

  public void validate() {
    if (amount == null || amount <= 0) throw new IllegalArgumentException("amount must be a positive number of cents");
    if (!"BRL".equals(currency)) throw new IllegalArgumentException("currency must be BRL");
    if (!"PIX".equals(method) && !"BOLECODE".equals(method)) throw new IllegalArgumentException("method must be PIX or BOLECODE");
    if (expiresIn != null && expiresIn <= 0) throw new IllegalArgumentException("expires_in must be positive seconds");
    if ("PIX".equals(method) && (dueDate != null || paymentLimitDays != null)) {
      throw new IllegalArgumentException("due_date and payment_limit_days apply to BOLECODE only");
    }
    if ("BOLECODE".equals(method) && expiresIn != null) {
      throw new IllegalArgumentException("expires_in applies to PIX only; a BOLECODE expires on its payment_limit_date");
    }
    if (paymentLimitDays != null && paymentLimitDays < 0) throw new IllegalArgumentException("payment_limit_days must not be negative");
  }

  public boolean isBolecode() { return "BOLECODE".equals(method); }

  /** The kernel's shape; null when there is no customer at all (the service answers CUSTOMER_REQUIRED). */
  public Payer payer() {
    if (customer == null) return null;
    Address a = customer.address();
    return new Payer(customer.name(), customer.document(),
        a == null ? null : new com.gateway.kernel.provider.boleto.Address(a.street(), a.district(), a.city(), a.state(), a.zip()));
  }
}
