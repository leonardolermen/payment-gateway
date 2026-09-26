package com.gateway.payments.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.boleto.PaidVia;
import com.gateway.payments.payment.pix.PixDetails;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class PaymentDetailsJsonTest {
  @Test
  void writesBothBlocksAndReadsThemBack() {
    PixDetails pix = new PixDetails("BL1", "emv", null, "E1");
    BoletoDetails boleto =
        new BoletoDetails(
            "00000001",
            "u",
            "1".repeat(47),
            "1".repeat(44),
            LocalDate.of(2026, 10, 1),
            LocalDate.of(2026, 10, 31),
            PaidVia.PIX);
    String json = PaymentDetailsJson.write(pix, boleto);
    assertThat(json).startsWith("{\"pix\":{").contains(",\"boleto\":{");
    assertThat(PaymentDetailsJson.readPix(json)).isEqualTo(pix);
    assertThat(PaymentDetailsJson.readBoleto(json)).isEqualTo(boleto);
  }

  @Test
  void pixOnlyHasANullBoleto() {
    String json = PaymentDetailsJson.write(new PixDetails("t", null, null, null), null);
    assertThat(json)
        .isEqualTo(
            "{\"pix\":{\"txid\":\"t\",\"pixCopiaECola\":null,\"location\":null,\"endToEndId\":null},\"boleto\":null}");
    assertThat(PaymentDetailsJson.readBoleto(json)).isNull();
    assertThat(PaymentDetailsJson.readPix(json).txid()).isEqualTo("t");
  }

  /**
   * The keys of the two blocks must stay disjoint: both readers scan the whole document (see
   * BoletoDetailsJson).
   */
  @Test
  void theTwoBlocksShareNoKey() {
    String pixJson =
        com.gateway.payments.payment.pix.PixDetailsJson.write(new PixDetails("a", "b", "c", "d"));
    String boletoJson =
        com.gateway.payments.payment.boleto.BoletoDetailsJson.write(
            new BoletoDetails(
                "a", "b", "c", "d", LocalDate.EPOCH, LocalDate.EPOCH, PaidVia.BOLETO));
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\"([A-Za-z]+)\":").matcher(pixJson);
    while (m.find()) assertThat(boletoJson).doesNotContain("\"" + m.group(1) + "\":");
  }
}
