package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoSituation;
import java.text.Normalizer;
import java.util.Locale;

/**
 * {@code situacao_geral_boleto} → {@link BoletoSituation}. The OpenAPI enum spells "Aguardando
 * Crédito" with the accent; mainframe-fed text often does not. Accents are stripped (NFD, drop the
 * marks) and case is ignored before matching, so both spellings land on the same value.
 */
public final class BoletoSituations {
  private BoletoSituations() {}

  public static BoletoSituation parse(String raw) {
    if (raw == null) {
      throw new ProviderException(
          ProviderException.Code.UNKNOWN, 200, null, "boleto without situacao_geral_boleto");
    }
    String plain =
        Normalizer.normalize(raw, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .trim()
            .toLowerCase(Locale.ROOT);
    return switch (plain) {
      case "em aberto" -> BoletoSituation.OPEN;
      case "pago" -> BoletoSituation.PAID;
      case "liquidado" -> BoletoSituation.SETTLED;
      case "pagamento rejeitado" -> BoletoSituation.PAYMENT_REJECTED;
      case "aguardando credito" -> BoletoSituation.AWAITING_CREDIT;
      case "creditado" -> BoletoSituation.CREDITED;
      case "baixado" -> BoletoSituation.CANCELED;
      default ->
          throw new ProviderException(
              ProviderException.Code.UNKNOWN, 200, null, "unknown situacao_geral_boleto: " + raw);
    };
  }
}
