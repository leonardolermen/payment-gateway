package com.gateway.kernel.provider.card;

/**
 * What an acquirer notification is about, normalized from the Cielo's ChangeType (docs/webhook): 1
 * status changed, 25 partial cancel or refund, 5 cancel denied, 8 fraud alert; the rest (2, 3, 4,
 * 6, 7 — recurrence, antifraud, boleto, legacy chargeback) is not this phase's business.
 */
public enum CardNotificationKind {
  STATUS_CHANGED,
  PARTIAL_REFUND,
  VOID_DENIED,
  FRAUD_ALERT,
  IGNORED
}
