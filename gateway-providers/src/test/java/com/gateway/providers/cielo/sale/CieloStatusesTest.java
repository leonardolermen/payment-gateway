package com.gateway.providers.cielo.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.card.CardStatus;
import org.junit.jupiter.api.Test;

/** reference/payment-status (read 2026-09-28), plus the two codes the spec listed (plan D1). */
class CieloStatusesTest {

  @Test
  void theDocumentedCodes() {
    assertThat(CieloStatuses.of(0)).isEqualTo(CardStatus.NOT_FINISHED);
    assertThat(CieloStatuses.of(1)).isEqualTo(CardStatus.AUTHORIZED);
    assertThat(CieloStatuses.of(2)).isEqualTo(CardStatus.PAID);
    assertThat(CieloStatuses.of(3)).isEqualTo(CardStatus.DENIED);
    assertThat(CieloStatuses.of(10)).isEqualTo(CardStatus.VOIDED);
    assertThat(CieloStatuses.of(11)).isEqualTo(CardStatus.REFUNDED);
    assertThat(CieloStatuses.of(12)).isEqualTo(CardStatus.PENDING);
    assertThat(CieloStatuses.of(13)).isEqualTo(CardStatus.ABORTED);
  }

  @Test
  void theSpecsCodesAreTolerated() {
    assertThat(CieloStatuses.of(14)).isEqualTo(CardStatus.PROCESSING);
    assertThat(CieloStatuses.of(15)).isEqualTo(CardStatus.REFUNDED);
  }

  /** 20 Scheduled is recurrence (out of scope); anything unknown is doubt, never an adoption. */
  @Test
  void anythingElseIsInDoubt() {
    assertThat(CieloStatuses.of(20)).isEqualTo(CardStatus.PROCESSING);
    assertThat(CieloStatuses.of(99)).isEqualTo(CardStatus.PROCESSING);
    assertThat(CieloStatuses.of(null)).isEqualTo(CardStatus.PROCESSING);
  }
}
