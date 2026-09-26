package com.gateway.payments.payment;

import com.gateway.payments.payment.boleto.BoletoDetails;
import com.gateway.payments.payment.boleto.BoletoDetailsJson;
import com.gateway.payments.payment.pix.PixDetails;
import com.gateway.payments.payment.pix.PixDetailsJson;

/**
 * The {@code details} column: {@code {"pix": {...}, "boleto": {...}|null}} (spec 2026-09-25 §2).
 * Both block readers scan the whole document by key, which works because the two key sets are
 * disjoint (PaymentDetailsJsonTest pins that) — no JSON parser in the domain, same as
 * PixDetailsJson.
 */
public final class PaymentDetailsJson {
  private PaymentDetailsJson() {}

  public static String write(PixDetails pix, BoletoDetails boleto) {
    return "{\"pix\":"
        + PixDetailsJson.write(pix)
        + ",\"boleto\":"
        + BoletoDetailsJson.write(boleto)
        + "}";
  }

  public static PixDetails readPix(String details) {
    return PixDetailsJson.read(details);
  }

  public static BoletoDetails readBoleto(String details) {
    return BoletoDetailsJson.read(details);
  }
}
