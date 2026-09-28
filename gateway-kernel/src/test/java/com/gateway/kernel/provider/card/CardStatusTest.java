package com.gateway.kernel.provider.card;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.money.Money;
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

  /**
   * The void's ReturnCode decides, not the sale status: a partial void leaves the sale PAID (2) and
   * answers 0; a total one answers 9; 100 is "partial before settlement", not performed.
   */
  @Test
  void aRefundIsCompletedByTheVoidReturnCodeNotByTheSaleStatus() {
    assertThat(refund(CardStatus.PAID, "0").completed()).isTrue();
    assertThat(refund(CardStatus.VOIDED, "9").completed()).isTrue();
    assertThat(refund(CardStatus.REFUNDED, "9").completed()).isTrue();
    assertThat(refund(CardStatus.PAID, "100").completed()).isFalse();
    assertThat(refund(CardStatus.REFUNDED, "100").completed()).isFalse();
    assertThat(refund(CardStatus.PAID, null).completed()).isFalse();
  }

  private static CardRefundResult refund(CardStatus status, String returnCode) {
    return new CardRefundResult(status, Money.brl(100), returnCode, "message");
  }
}
