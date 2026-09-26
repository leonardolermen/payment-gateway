package com.gateway.kernel.provider.boleto;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.kernel.money.Money;
import com.gateway.kernel.provider.ProviderException;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BoletoContractTest {
  @Test
  void paidIsTheThreeSettledSituations() {
    for (BoletoSituation s : BoletoSituation.values()) {
      BoletoStatus st =
          new BoletoStatus(
              s,
              Money.brl(100),
              Instant.parse("2026-10-02T12:00:00Z"),
              "01",
              "uuid",
              "1".repeat(47),
              "1".repeat(44),
              LocalDate.of(2026, 10, 31),
              null);
      boolean expected =
          s == BoletoSituation.PAID
              || s == BoletoSituation.SETTLED
              || s == BoletoSituation.CREDITED;
      assertThat(st.paid()).as("%s", s).isEqualTo(expected);
    }
  }

  @Test
  void theTwoNewCodesExist() {
    assertThat(ProviderException.Code.valueOf("CONFLICT")).isNotNull();
    assertThat(ProviderException.Code.valueOf("CREDENTIALS_INCOMPLETE")).isNotNull();
    ProviderException e =
        new ProviderException(
            ProviderException.Code.CREDENTIALS_INCOMPLETE,
            0,
            "beneficiary_id",
            "credential lacks beneficiary_id");
    assertThat(e.providerType()).isEqualTo("beneficiary_id");
  }
}
