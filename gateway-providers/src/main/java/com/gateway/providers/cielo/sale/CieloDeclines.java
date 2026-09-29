package com.gateway.providers.cielo.sale;

import com.gateway.kernel.provider.card.CardDeclineCode;
import java.util.Map;

/**
 * ReturnCode of a declined sale → the gateway's {@link CardDeclineCode}. Measured against
 * page/abecs ("Códigos de Retorno padrão ABECS", read 2026-09-28), not reference/api-codes: that
 * page only lists the API's own codes (100–841) and points to ABECS for issuer declines (plan D7).
 *
 * <p>Three codes exist only in the sandbox (reference/credito-sandbox: 99 timeout, 77 canceled, 70
 * card problems) and none is an ABECS code, so mapping them shadows no production answer. 57
 * follows production ("transação não permitida para o cartão"), not the sandbox's "cartão expirado"
 * (plan D8). ReturnMessage is never read here: the issuer's text never reaches a merchant.
 */
public final class CieloDeclines {
  private static final Map<String, CardDeclineCode> BY_RETURN_CODE =
      Map.ofEntries(
          Map.entry("51", CardDeclineCode.INSUFFICIENT_FUNDS),
          Map.entry("54", CardDeclineCode.EXPIRED_CARD),
          Map.entry("78", CardDeclineCode.BLOCKED_CARD),
          Map.entry("62", CardDeclineCode.BLOCKED_CARD),
          Map.entry("41", CardDeclineCode.CANCELED_CARD),
          Map.entry("43", CardDeclineCode.CANCELED_CARD),
          Map.entry("46", CardDeclineCode.CANCELED_CARD),
          Map.entry("91", CardDeclineCode.TIMEOUT),
          Map.entry("96", CardDeclineCode.TIMEOUT),
          Map.entry("57", CardDeclineCode.DO_NOT_HONOR),
          Map.entry("14", CardDeclineCode.DO_NOT_HONOR),
          Map.entry("59", CardDeclineCode.DO_NOT_HONOR),
          Map.entry("83", CardDeclineCode.DO_NOT_HONOR),
          Map.entry("N7", CardDeclineCode.DO_NOT_HONOR),
          Map.entry("05", CardDeclineCode.GENERIC),
          Map.entry("99", CardDeclineCode.TIMEOUT),
          Map.entry("77", CardDeclineCode.CANCELED_CARD),
          Map.entry("70", CardDeclineCode.DO_NOT_HONOR));

  private CieloDeclines() {}

  public static CardDeclineCode of(String returnCode) {
    if (returnCode == null) {
      return CardDeclineCode.GENERIC;
    }

    return BY_RETURN_CODE.getOrDefault(returnCode.trim(), CardDeclineCode.GENERIC);
  }
}
