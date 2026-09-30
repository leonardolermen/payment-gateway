package com.gateway.providers.cielo.sale;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.provider.card.CardDeclineCode;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Measured against page/abecs (read 2026-09-28, Visa table; Mastercard, Elo and Amex agree on these
 * codes) and, for the codes that exist only in the sandbox, reference/credito-sandbox (plan D7,
 * D8).
 */
class CieloDeclinesTest {

  @Test
  void theTable() {
    Map<String, CardDeclineCode> expected =
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

    expected.forEach(
        (returnCode, declineCode) ->
            assertThat(CieloDeclines.of(returnCode)).as(returnCode).isEqualTo(declineCode));
  }

  @Test
  void unknownOrMissingIsGeneric() {
    assertThat(CieloDeclines.of("BP171")).isEqualTo(CardDeclineCode.GENERIC);
    assertThat(CieloDeclines.of(null)).isEqualTo(CardDeclineCode.GENERIC);
    assertThat(CieloDeclines.of(" 51 ")).isEqualTo(CardDeclineCode.INSUFFICIENT_FUNDS);
  }
}
