package com.gateway.payments.payment;

import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.boleto.BoletoDetailsJson;
import com.gateway.payments.payment.card.CardDetails;
import com.gateway.payments.payment.card.CardDetailsJson;
import com.gateway.payments.payment.pix.PixDetails;
import com.gateway.payments.payment.pix.PixDetailsJson;
import java.util.regex.Pattern;

/**
 * The {@code details} column: {@code {"pix": {...}, "boleto": {...}|null, "card": {...}}} — card
 * only on card payments (spec 2026-09-28 §4). All three block readers scan the whole document by
 * key, which works because the three key sets are disjoint (PaymentDetailsJsonTest pins that) — no
 * JSON parser in the domain, same as PixDetailsJson.
 */
public final class PaymentDetailsJson {
  /** A card payment has no Pix side: written as an empty object, read back as null. */
  private static final Pattern EMPTY_PIX = Pattern.compile("\"pix\":\\s*\\{\\s*}");

  private PaymentDetailsJson() {}

  public static String write(PixDetails pix, BoletoDetails boleto) {
    return write(pix, boleto, null);
  }

  /**
   * {@code "card"} is written only when there is one, so a Pix or Bolecode row is byte for byte
   * what it was before cards existed.
   */
  public static String write(PixDetails pix, BoletoDetails boleto, CardDetails card) {
    String pixAndBoleto =
        "{\"pix\":" + PixDetailsJson.write(pix) + ",\"boleto\":" + BoletoDetailsJson.write(boleto);

    return card == null
        ? pixAndBoleto + "}"
        : pixAndBoleto + ",\"card\":" + CardDetailsJson.write(card) + "}";
  }

  public static PixDetails readPix(String details) {
    if (details != null && EMPTY_PIX.matcher(details).find()) {
      return null;
    }

    return PixDetailsJson.read(details);
  }

  public static BoletoDetails readBoleto(String details) {
    return BoletoDetailsJson.read(details);
  }

  public static CardDetails readCard(String details) {
    return CardDetailsJson.read(details);
  }
}
