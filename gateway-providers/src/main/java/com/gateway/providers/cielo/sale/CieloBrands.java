package com.gateway.providers.cielo.sale;

import com.gateway.kernel.provider.card.CardBrand;
import java.util.Arrays;

/**
 * The Brand spelling: "Visa / Master / Amex / Elo / Aura / JCB / Diners / Discover"
 * (reference/criar-pagamento-credito). Read case-insensitively: an echo in another case must not
 * fail a sale that was approved.
 */
public final class CieloBrands {

  private CieloBrands() {}

  public static String nameOf(CardBrand brand) {
    return switch (brand) {
      case VISA -> "Visa";
      case MASTER -> "Master";
      case AMEX -> "Amex";
      case ELO -> "Elo";
      case AURA -> "Aura";
      case JCB -> "JCB";
      case DINERS -> "Diners";
      case DISCOVER -> "Discover";
    };
  }

  /** Null for a name outside the list: an echo the gateway cannot place is not a failure. */
  public static CardBrand of(String cieloName) {
    if (cieloName == null) {
      return null;
    }

    return Arrays.stream(CardBrand.values())
        .filter(brand -> nameOf(brand).equalsIgnoreCase(cieloName.trim()))
        .findFirst()
        .orElse(null);
  }
}
