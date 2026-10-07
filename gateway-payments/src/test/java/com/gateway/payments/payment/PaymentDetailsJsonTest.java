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

  @Test
  void aCardPaymentHasAnEmptyPixAndACardBlock() {
    com.gateway.payments.payment.card.CardDetails card =
        com.gateway.payments.payment.card.CardDetails.requested(1, 0, "VISA", "3171", null);

    String json = PaymentDetailsJson.write(null, null, card);

    assertThat(json).startsWith("{\"pix\":{},\"boleto\":null,\"card\":{");
    assertThat(PaymentDetailsJson.readPix(json)).isNull();
    assertThat(PaymentDetailsJson.readPix(json.replace("\"pix\":{}", "\"pix\": { }"))).isNull();
    assertThat(PaymentDetailsJson.readBoleto(json)).isNull();
    assertThat(PaymentDetailsJson.readCard(json)).isEqualTo(card);
  }

  @Test
  void theCardInterestSurvivesTheWholeDocument() {
    com.gateway.payments.payment.card.CardDetails card =
        com.gateway.payments.payment.card.CardDetails.requested(6, 1076, "VISA", "3171", null);

    String json = PaymentDetailsJson.write(null, null, card);

    assertThat(PaymentDetailsJson.readCard(json).interestAmount()).isEqualTo(1076);
  }

  /**
   * Card keys must not collide with pix or boleto keys: the three readers scan the whole document.
   */
  @Test
  void theCardBlockSharesNoKeyWithTheOthers() {
    String cardJson =
        com.gateway.payments.payment.card.CardDetailsJson.write(
            new com.gateway.payments.payment.card.CardDetails(
                "a", "b", "c", "d", "e", "f", 1, 2L, "g", "h", 3));
    String others =
        com.gateway.payments.payment.pix.PixDetailsJson.write(new PixDetails("a", "b", "c", "d"))
            + com.gateway.payments.payment.boleto.BoletoDetailsJson.write(
                new BoletoDetails(
                    "a", "b", "c", "d", LocalDate.EPOCH, LocalDate.EPOCH, PaidVia.BOLETO));
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("\"([A-Za-z0-9]+)\":").matcher(cardJson);
    while (m.find()) {
      assertThat(others).doesNotContain("\"" + m.group(1) + "\":");
    }
  }
}
