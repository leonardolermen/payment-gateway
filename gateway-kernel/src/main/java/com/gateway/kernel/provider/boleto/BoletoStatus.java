package com.gateway.kernel.provider.boleto;

import com.gateway.kernel.money.Money;
import java.time.Instant;
import java.time.LocalDate;

/**
 * The bank's view of a boleto. The identity fields (id, linha digitável, código de barras, limit
 * date) are here so a charge adopted after a timeout can be completed from the query alone;
 * {@code pixCopiaECola} is the EMV the query carries under {@code qrcode_pix}, a fallback when
 * {@code GET /cob/{txid}} cannot confirm the reconstructed txid.
 */
public record BoletoStatus(BoletoSituation situation, Money paidAmount, Instant paidAt, String paidChannel,
                           String idBoletoIndividual, String linhaDigitavel, String codigoBarras, LocalDate paymentLimitDate,
                           String pixCopiaECola) {
  public boolean paid() {
    return situation == BoletoSituation.PAID || situation == BoletoSituation.SETTLED || situation == BoletoSituation.CREDITED;
  }
}
