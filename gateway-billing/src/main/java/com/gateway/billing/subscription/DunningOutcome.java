package com.gateway.billing.subscription;

/** ISSUED: a PIX/Bolecode charge went out and waits for the payer; the attempt ends there. */
public enum DunningOutcome {
  PAID,
  DECLINED,
  ISSUED,
  EXPIRED,
  SKIPPED
}
