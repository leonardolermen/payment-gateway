package com.gateway.billing.order;

import com.gateway.payments.payment.create.CardChoice;
import java.time.LocalDate;

/** What a caller may choose for an attempt; amount, currency and payer come from the order. */
public sealed interface AttemptRequest {
  record PixAttempt(Integer expiresInSeconds) implements AttemptRequest {}

  record BolecodeAttempt(LocalDate dueDate, Integer paymentLimitDays) implements AttemptRequest {}

  record CardAttempt(CardChoice card, Integer installments, Boolean capture, String softDescriptor)
      implements AttemptRequest {}

  /** A subscription cycle: the stored card, no CVV, captured at once (spec §6). */
  record RecurringCardAttempt(String cardId, Integer installments) implements AttemptRequest {}
}
