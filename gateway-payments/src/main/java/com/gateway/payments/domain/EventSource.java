package com.gateway.payments.domain;

/** Who triggered a payment event — the transition table decides which source may cause which transition. */
public enum EventSource {
  API,
  PROVIDER_WEBHOOK,
  RECONCILIATION,
  EXPIRATION_JOB,
  SYSTEM
}
