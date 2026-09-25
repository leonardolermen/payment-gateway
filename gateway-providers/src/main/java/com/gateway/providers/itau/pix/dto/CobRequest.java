package com.gateway.providers.itau.pix.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.gateway.kernel.money.Money;
import com.gateway.providers.itau.pix.PixAmounts;
import java.util.List;

/**
 * PUT /cob/{txid} body. Field names are the bank's; the gateway never sees them. NON_NULL: the
 * schema types {@code devedor} as an object and each document as a string, so optional fields are
 * left out rather than sent as {@code null} (RequestSchemaValidationTest validates the result).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CobRequest(Calendario calendario, Devedor devedor, Valor valor, String chave, String solicitacaoPagador, List<InfoAdicional> infoAdicionais) {
  public record Calendario(int expiracao) {}
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Devedor(String cpf, String cnpj, String nome) {}
  public record Valor(String original) {}
  public record InfoAdicional(String nome, String valor) {}

  /** {@code solicitacaoPagador} is capped at 140 chars by the schema; longer descriptions are cut, not rejected. */
  public static CobRequest forCharge(Money amount, int expiresInSeconds, String pixKey, String payerDocument, String payerName, String description) {
    Devedor devedor = null;

    if (payerDocument != null && payerName != null) {
      String digits = payerDocument.replaceAll("\\D", "");
      devedor = digits.length() == 14 ? new Devedor(null, digits, payerName) : new Devedor(digits, null, payerName);
    }

    return new CobRequest(new Calendario(expiresInSeconds), devedor, new Valor(PixAmounts.toItau(amount)), pixKey,
        description == null ? null : description.substring(0, Math.min(140, description.length())), null);
  }
}
