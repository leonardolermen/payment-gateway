package com.gateway.payments.domain;

public enum JobType {
  PROCESS_WEBHOOK,
  EXPIRE_PAYMENT,
  POLL_REFUND,
  RECONCILE
}
