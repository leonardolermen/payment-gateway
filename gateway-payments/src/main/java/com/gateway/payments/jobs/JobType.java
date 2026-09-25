package com.gateway.payments.jobs;

public enum JobType {
  PROCESS_WEBHOOK,
  EXPIRE_PAYMENT,
  POLL_REFUND,
  RECONCILE,
  POLL_BOLETO
}
