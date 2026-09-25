package com.gateway.kernel.provider.boleto;

import java.time.LocalDate;

/** What the bank answered to an issue: the boleto's identity and the Pix side of the same charge. The QR image (base64) is discarded. */
public record IssuedBoleto(String idBoletoIndividual, String linhaDigitavel, String codigoBarras, LocalDate paymentLimitDate,
                           String pixTxid, String pixCopiaECola, String pixKey) {}
