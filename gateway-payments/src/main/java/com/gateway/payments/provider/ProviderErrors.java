package com.gateway.payments.provider;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.provider.ProviderException;
import java.util.Map;
import org.slf4j.Logger;

/**
 * What a merchant reads when the bank fails: one fixed sentence per code. The bank's own text used
 * to be the {@code detail} of the response, which put a third party's wording (sometimes the
 * payer's data it echoes back) into our API and, through the idempotency store, replayed it for 24 h.
 * The bank's text stays where support looks: {@code provider_requests} (ProviderGateway) and one
 * log line here, which goes through the app's masking encoder.
 */
public final class ProviderErrors {
  private static final Map<String, String> MESSAGES =
      Map.of(
          "PROVIDER_DECLINED", "The bank declined the request.",
          "PROVIDER_UNAVAILABLE", "The bank could not process the request right now. Try again later.",
          "PROVIDER_TIMEOUT", "The bank did not answer in time.");

  private ProviderErrors() {}

  public static DomainException toDomain(String code, ProviderException cause, Logger log, String operation, String resourceId) {
    log.warn("{} for {} failed at the bank: {} {} {}", operation, resourceId, cause.code(), cause.providerType(), cause.getMessage());
    DomainException e = new DomainException(code, message(code));
    e.initCause(cause);
    return e;
  }

  public static String message(String code) {
    String m = MESSAGES.get(code);
    if (m == null) {
      throw new IllegalArgumentException("no fixed message for " + code);
    }
    return m;
  }
}
