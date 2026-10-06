package com.gateway.payments.payment;

/**
 * Who triggered a payment event — the transition table decides which source may cause which
 * transition.
 */
public enum EventSource {
  API,
  /**
   * The payer, through the public checkout link; the merchant reads it on the payment events to
   * tell its own API calls from the payer's.
   */
  CHECKOUT,
  PROVIDER_WEBHOOK,
  RECONCILIATION,
  EXPIRATION_JOB,
  SYSTEM,
  /**
   * The boleto query (GET /boletos) run by the POLL_BOLETO job: a provider-side fact, like a
   * webhook.
   */
  PROVIDER_POLL
}
