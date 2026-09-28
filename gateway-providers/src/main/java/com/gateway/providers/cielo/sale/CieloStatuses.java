package com.gateway.providers.cielo.sale;

import com.gateway.kernel.provider.card.CardStatus;
import java.util.Map;

/**
 * Payment.Status → {@link CardStatus}. The table is reference/payment-status (read 2026-09-28): 0,
 * 1, 2, 3, 10, 11, 12, 13, 20. The spec also listed 14 Processing and 15 Refunded from an older
 * list; both are tolerated so a Cielo that still sends them is understood (plan D1).
 *
 * <p>Everything unknown — 20 Scheduled (recurrence, out of scope) included — is PROCESSING: in
 * doubt, so the flow asks again instead of adopting something it does not understand.
 */
public final class CieloStatuses {
  private static final Map<Integer, CardStatus> BY_CODE =
      Map.ofEntries(
          Map.entry(0, CardStatus.NOT_FINISHED),
          Map.entry(1, CardStatus.AUTHORIZED),
          Map.entry(2, CardStatus.PAID),
          Map.entry(3, CardStatus.DENIED),
          Map.entry(10, CardStatus.VOIDED),
          Map.entry(11, CardStatus.REFUNDED),
          Map.entry(12, CardStatus.PENDING),
          Map.entry(13, CardStatus.ABORTED),
          Map.entry(14, CardStatus.PROCESSING),
          Map.entry(15, CardStatus.REFUNDED));

  private CieloStatuses() {}

  public static CardStatus of(Integer status) {
    if (status == null) {
      return CardStatus.PROCESSING;
    }

    return BY_CODE.getOrDefault(status, CardStatus.PROCESSING);
  }
}
