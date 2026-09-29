package com.gateway.payments.payment.create;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.errors.InvalidValue;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.card.Installments;

/**
 * The installment count against the amount. The per-installment minimum is the Cielo's rule for
 * ByMerchant, R$ 5,00 (reference/criar-pagamento-credito, Interest; plan D5). Checked as {@code
 * amount >= 500 × n}: the acquirer splits a non-exact division itself, so 1600 in 3 is fine.
 */
public final class InstallmentPlan {
  private static final String CODE = "INVALID_INSTALLMENTS";
  private static final long MINIMUM_INSTALLMENT_CENTS = 500;

  private InstallmentPlan() {}

  public static Installments of(Money amount, Integer installments) {
    Installments count;
    try {
      count = Installments.of(installments);
    } catch (InvalidValue e) {
      throw new DomainException(CODE, "installments " + e.reason());
    }

    if (count.count() > 1 && amount.cents() < MINIMUM_INSTALLMENT_CENTS * count.count()) {
      throw new DomainException(
          CODE,
          "each installment must be at least 500 cents ("
              + amount.cents()
              + " in "
              + count.count()
              + ")");
    }

    return count;
  }
}
