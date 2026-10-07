package com.gateway.billing.installment;

/**
 * One way to pay an amount by card, in cents: {@code count} installments of {@code
 * installmentAmount}, {@code total} charged. With interest, {@code total} is exactly {@code count ×
 * installmentAmount}; without, {@code total} is the amount and {@code installmentAmount} its
 * division truncated to the cent (the acquirer spreads the remaining cents).
 */
public record InstallmentOption(
    int count, long installmentAmount, long total, boolean interestFree) {

  /** What the payer pays above {@code amount}; 0 for an interest-free option. */
  public long interestOver(long amount) {
    return total - amount;
  }
}
