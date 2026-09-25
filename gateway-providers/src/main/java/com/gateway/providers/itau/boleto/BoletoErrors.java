package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderException.Code;
import com.gateway.providers.itau.boleto.dto.BoletoProblem;
import java.text.Normalizer;
import java.util.Locale;
import tools.jackson.databind.ObjectMapper;

/**
 * Non-2xx from the boleto APIs → {@link ProviderException}. 400 and 422 are both DECLINED: the bank
 * validated the business (wallet rules, dates, payer) and said no, and nothing on our side changes
 * that. {@code campos[].valor} is deliberately left out of the message — it echoes what we sent,
 * which includes the payer's document — and the message goes to provider_requests and the log.
 */
public final class BoletoErrors {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_RAW = 300;

  private BoletoErrors() {}

  public static ProviderException from(int status, String body) {
    BoletoProblem p = parse(body);
    String message;
    if (p != null && p.mensagem() != null) {
      StringBuilder sb = new StringBuilder(p.mensagem());
      if (p.campos() != null && !p.campos().isEmpty()) {
        sb.append(" [");
        for (int i = 0; i < p.campos().size(); i++) {
          if (i > 0) sb.append("; ");
          sb.append(p.campos().get(i).campo()).append(": ").append(p.campos().get(i).mensagem());
        }
        sb.append(']');
      }
      message = sb.toString();
    } else {
      message = "Itaú boleto HTTP " + status + (body == null || body.isBlank() ? "" : ": " + truncate(body));
    }
    return new ProviderException(code(status), status, p == null ? null : p.codigo(), message);
  }

  static Code code(int status) {
    if (status == 202) return Code.TIMEOUT; // "operação em andamento": the bank has not decided yet, treat like a lost answer
    if (status == 400 || status == 422) return Code.DECLINED;
    if (status == 401 || status == 403) return Code.UNAUTHENTICATED;
    if (status == 404 || status == 410) return Code.NOT_FOUND;
    if (status == 504) return Code.TIMEOUT;
    if (status >= 500) return Code.UNAVAILABLE;
    return Code.UNKNOWN;
  }

  /** The 422 of a baixa on a boleto the bank already settled says "pago"/"liquidado" in its text (no schema, no code). */
  public static boolean mentionsAlreadyPaid(String body) {
    if (body == null) return false;
    String plain = Normalizer.normalize(body, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    return plain.contains("pago") || plain.contains("liquidado");
  }

  private static BoletoProblem parse(String body) {
    if (body == null || body.isBlank()) return null;
    try {
      return MAPPER.readValue(body, BoletoProblem.class);
    } catch (RuntimeException e) {
      return null;
    }
  }

  private static String truncate(String s) { return s.length() <= MAX_RAW ? s : s.substring(0, MAX_RAW) + "…"; }
}
