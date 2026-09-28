package com.gateway.kernel.provider.card;

import com.gateway.kernel.money.Money;
import java.util.Set;

/**
 * The answer to a void with an amount. Synchronous at the Cielo, and decided by the void call's own
 * ReturnCode, never by the sale's {@code status}: after a PARTIAL void the sale stays Status 2
 * (PAID), so reading the status turned every successful partial refund into FAILED while the
 * money had gone back. The Cielo's "códigos de retorno de cancelamento" table: 0 "Cancelamento
 * aprovado com sucesso" (partial), 9 "Solicitação de cancelamento total aprovada"; anything else
 * (100 = partial before settlement, …) means the void was not performed. {@code status} stays for
 * the record: 2 after a partial, 10 on the sale's day, 11 after it (reference/payment-status).
 */
public record CardRefundResult(
    CardStatus status, Money refundedAmount, String returnCode, String returnMessage) {
  private static final Set<String> VOID_APPROVED = Set.of("0", "9");

  public boolean completed() {
    return returnCode != null && VOID_APPROVED.contains(returnCode);
  }
}
