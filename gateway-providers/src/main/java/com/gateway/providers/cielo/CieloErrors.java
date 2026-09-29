package com.gateway.providers.cielo;

import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.ProviderException.Code;
import com.gateway.providers.cielo.sale.dto.CieloError;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Non-2xx from the Cielo → {@link ProviderException}. The status decides the code; the list's
 * {@code Code}s travel in {@code providerType} (comma-separated) so support reads what the Cielo
 * said. A decline is NOT here: it is a 201 with Status 3 (reference/api-codes), a business answer
 * the flow handles.
 *
 * <p>Cielo messages are fixed English sentences ("Credit Card Expiration Date is invalid"), so the
 * message keeps them; a raw body that is not a list goes through the masker first.
 */
public final class CieloErrors {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final int MAX_RAW = 300;
  private static final String TRANSACTION_NOT_FOUND = "307";

  private CieloErrors() {}

  public static ProviderException from(int status, String body) {
    List<CieloError> errors = parse(body);

    if (errors.isEmpty()) {
      String raw = body == null || body.isBlank() ? "" : ": " + truncate(body);
      return new ProviderException(
          code(status), status, null, CieloPayloadMasker.mask("Cielo HTTP " + status + raw));
    }

    String codes = errors.stream().map(CieloError::code).collect(Collectors.joining(","));
    String message =
        errors.stream()
            .map(error -> error.code() + " " + error.message())
            .collect(Collectors.joining("; "));

    return new ProviderException(code(status), status, codes, CieloPayloadMasker.mask(message));
  }

  /**
   * No page documents a 404 for an unknown PaymentId; api-codes documents 307 "Transaction not
   * found" (plan D10). Both mean "the Cielo does not know it".
   */
  public static boolean isTransactionNotFound(ProviderException e) {
    if (e.code() == Code.NOT_FOUND) {
      return true;
    }

    return e.code() == Code.INVALID
        && e.providerType() != null
        && Arrays.asList(e.providerType().split(",")).contains(TRANSACTION_NOT_FOUND);
  }

  static Code code(int status) {
    if (status == 400 || status == 422) {
      return Code.INVALID;
    }
    if (status == 401 || status == 403) {
      return Code.UNAUTHENTICATED;
    }
    if (status == 404) {
      return Code.NOT_FOUND;
    }
    if (status == 504) {
      return Code.TIMEOUT;
    }
    if (status >= 500) {
      return Code.UNAVAILABLE;
    }

    return Code.UNKNOWN;
  }

  private static List<CieloError> parse(String body) {
    if (body == null || !body.trim().startsWith("[")) {
      return List.of();
    }

    try {
      return MAPPER.readValue(body, new TypeReference<List<CieloError>>() {});
    } catch (RuntimeException e) {
      return List.of();
    }
  }

  private static String truncate(String text) {
    return text.length() <= MAX_RAW ? text : text.substring(0, MAX_RAW) + "…";
  }
}
