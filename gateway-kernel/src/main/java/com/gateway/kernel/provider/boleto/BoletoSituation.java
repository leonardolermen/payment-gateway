package com.gateway.kernel.provider.boleto;

/**
 * The bank's {@code situacao_geral_boleto}, normalized. PAID/SETTLED/CREDITED are the three "money
 * came in" states; AWAITING_CREDIT is paid at another bank but not yet ours (the poll keeps
 * waiting); PAYMENT_REJECTED and CANCELED end nothing on their own — a human decides.
 */
public enum BoletoSituation {
  OPEN,
  PAID,
  SETTLED,
  AWAITING_CREDIT,
  CREDITED,
  PAYMENT_REJECTED,
  CANCELED
}
