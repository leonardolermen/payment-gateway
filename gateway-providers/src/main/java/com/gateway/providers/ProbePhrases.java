package com.gateway.providers;

import com.gateway.kernel.provider.ProbeResult;
import com.gateway.kernel.provider.ProviderException;

/**
 * The fixed phrases a "test connection" may answer with (spec 2026-10-09, section 3). They are the
 * only text that reaches the credential row and the panel: the bank's own body never does, because
 * a token endpoint's error can echo the client id or the key that was sent.
 */
public final class ProbePhrases {
  public static final ProbeResult CONNECTED = new ProbeResult(true, "Conectado");
  public static final ProbeResult REFUSED =
      new ProbeResult(false, "Credencial recusada pelo banco");
  public static final ProbeResult INVALID_CERTIFICATE =
      new ProbeResult(false, "Certificado ou chave privada inválidos");
  public static final ProbeResult NO_ANSWER = new ProbeResult(false, "O banco não respondeu");
  public static final ProbeResult UNEXPECTED =
      new ProbeResult(false, "O banco respondeu de forma inesperada");

  private ProbePhrases() {}

  /**
   * The parsers' messages start with the field name ({@code client_secret is required}); only that
   * token travels, so a message that one day quoted a value would still not reach the panel.
   */
  public static ProbeResult incomplete(IllegalArgumentException cause) {
    String message = cause.getMessage() == null ? "" : cause.getMessage().trim();
    String field = message.isEmpty() ? "?" : message.split("\\s+", 2)[0];

    return new ProbeResult(false, "Credencial incompleta: " + field);
  }

  /**
   * Timeout and unavailable are the same phrase: neither says anything about the credential. Any
   * other 4xx is still "refused": the STS answers 400 {@code invalid_client} to a wrong secret as
   * readily as it answers 401.
   */
  public static ProbeResult from(ProviderException failure) {
    return switch (failure.code()) {
      case UNAUTHENTICATED -> REFUSED;
      case TIMEOUT, UNAVAILABLE -> NO_ANSWER;
      default -> isClientError(failure.httpStatus()) ? REFUSED : UNEXPECTED;
    };
  }

  private static boolean isClientError(int httpStatus) {
    return httpStatus >= 400 && httpStatus < 500;
  }
}
