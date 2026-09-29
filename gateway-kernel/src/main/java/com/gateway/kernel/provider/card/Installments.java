package com.gateway.kernel.provider.card;

import com.gateway.kernel.errors.InvalidValue;

/**
 * 1 to 12. Twelve is the Cielo's default ceiling (reference/criar-pagamento-credito: "Por padrão, a
 * API E-commerce aceita até 12 parcelas"); more needs the merchant to ask the Cielo, and is out of
 * this phase. The per-installment minimum needs the amount, so it lives with the amount ({@code
 * InstallmentPlan} in payments).
 */
public record Installments(int count) {

  public static Installments of(Integer raw) {
    int count = raw == null ? 1 : raw;

    if (count < 1 || count > 12) {
      throw new InvalidValue("must be between 1 and 12");
    }

    return new Installments(count);
  }
}
