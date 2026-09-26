package com.gateway.payments.payment.boleto;

/**
 * Which side of a Bolecode the payer used. Decides whether a refund is possible: only a Pix
 * settlement has a devolução at the bank.
 */
public enum PaidVia {
  PIX,
  BOLETO
}
