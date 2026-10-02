package com.gateway.payments.jobs;

public enum JobType {
  PROCESS_WEBHOOK,
  EXPIRE_PAYMENT,
  POLL_REFUND,
  RECONCILE,
  POLL_BOLETO,

  /** Billing's; the handler lives in gateway-billing, the runner does not care. */
  EXPIRE_ORDER,

  /** Billing's: one cycle of a subscription; the handler lives in gateway-billing. */
  BILL_SUBSCRIPTION
}
