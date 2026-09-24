package com.gateway.payments.domain;

/** The Pix side of a charge: {@code txid} is the payment id itself, so a status query needs no lookup table. */
public record PixDetails(String txid, String pixCopiaECola, String location, String endToEndId) {
  public PixDetails withEndToEndId(String endToEndId) {
    return new PixDetails(txid, pixCopiaECola, location, endToEndId);
  }
}
