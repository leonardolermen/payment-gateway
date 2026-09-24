package com.gateway.providers.itau;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderException.Code;
import com.gateway.providers.itau.dto.Problem;
import tools.jackson.databind.ObjectMapper;

/**
 * Non-2xx from Itaú → {@link ProviderException}. The RFC 7807 {@code type} decides first because
 * it is more precise than the status (a 404 {@code CobNaoEncontrado} and a 400 {@code
 * CobOperacaoInvalida} are different business facts); the status is the fallback for bodies that
 * are not Problems (401s from the gateway layer arrive empty).
 */
final class ItauErrors {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_RAW = 300;

  private ItauErrors() {}

  static ProviderException from(int status, String body) {
    Problem p = parse(body);
    String type = p == null ? null : p.type();
    String message = p != null && (p.title() != null || p.detail() != null)
        ? p.title() + ": " + p.detail()
        : "Itaú HTTP " + status + (body == null || body.isBlank() ? "" : ": " + truncate(body));
    return new ProviderException(code(status, type), status, type, message);
  }

  static Code code(int status, String type) {
    String t = type == null ? "" : type.substring(type.lastIndexOf('/') + 1);
    if (t.contains("NaoEncontrad")) return Code.NOT_FOUND;
    if (t.endsWith("OperacaoInvalida") || t.endsWith("ConsultaInvalida") || t.equals("PixDevolucaoInvalida")) return Code.INVALID;
    if (status == 400 || status == 422) return Code.INVALID;
    if (status == 401 || status == 403) return Code.UNAUTHENTICATED;
    if (status == 404 || status == 410) return Code.NOT_FOUND;
    if (status >= 500) return Code.UNAVAILABLE;
    return Code.UNKNOWN;
  }

  private static Problem parse(String body) {
    if (body == null || body.isBlank()) return null;
    try {
      return MAPPER.readValue(body, Problem.class);
    } catch (RuntimeException e) {
      return null; // not JSON (an HTML page from a proxy, say): the status alone decides
    }
  }

  private static String truncate(String s) { return s.length() <= MAX_RAW ? s : s.substring(0, MAX_RAW) + "…"; }
}
