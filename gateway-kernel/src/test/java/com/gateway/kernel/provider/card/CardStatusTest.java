package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

class CardStatusTest {

  /**
   * In doubt = the acquirer has not decided. The flow asks again by MerchantOrderId instead of
   * adopting (spec §6.4); Status 12 Pending is one of them (Review Focus: a 201 that is not an
   * answer).
   */
  @Test
  void inDoubtIsNotFinishedPendingAndProcessing() {
    EnumSet<CardStatus> inDoubt =
        EnumSet.of(CardStatus.NOT_FINISHED, CardStatus.PENDING, CardStatus.PROCESSING);

    for (CardStatus status : CardStatus.values()) {
      assertThat(status.inDoubt()).as("%s", status).isEqualTo(inDoubt.contains(status));
    }
  }

  @Test
  void aRefundIsCompletedWhenTheSaleIsVoidedOrRefunded() {
    for (CardStatus status : CardStatus.values()) {
      boolean expected = status == CardStatus.VOIDED || status == CardStatus.REFUNDED;
      assertThat(
              new CardRefundResult(status, com.gateway.kernel.money.Money.brl(100), "9", "ok")
                  .completed())
          .as("%s", status)
          .isEqualTo(expected);
    }
  }
}
