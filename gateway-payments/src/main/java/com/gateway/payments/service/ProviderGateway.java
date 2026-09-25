package com.gateway.payments.service;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.CredentialLookup;
import com.gateway.kernel.provider.PixProvider;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.payments.repository.ProviderRequestRepository;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only door to a bank: resolves which {@link PixProvider} and which credential, times every
 * call and leaves a {@code provider_requests} row behind it — including for the calls that failed,
 * which are the ones support needs.
 *
 * <p>Request and response bodies are not recorded here: the provider interface returns domain
 * records, not wire bodies, and credentials must never reach that table.
 */
public class ProviderGateway {
  private static final Logger log = LoggerFactory.getLogger(ProviderGateway.class);
  /** The operations that create a resource at the bank answer 201 (PUT /cob, PUT /devolucao). */
  private static final Set<String> CREATING = Set.of("createCharge", "requestRefund");

  public record Resolved(PixProvider provider, ProviderCredentials credentials) {}

  private final List<PixProvider> providers;
  private final CredentialLookup credentials;
  private final ProviderRequestRepository requests;

  public ProviderGateway(List<PixProvider> providers, CredentialLookup credentials, ProviderRequestRepository requests) {
    this.providers = providers;
    this.credentials = credentials;
    this.requests = requests;
  }

  public Resolved resolve(MerchantId merchantId, ProviderEnvironment env, String provider) {
    PixProvider p = provider(provider);
    ProviderCredentials c =
        credentials
            .find(merchantId, provider, env)
            .orElseThrow(() -> new DomainException("PROVIDER_CREDENTIALS_MISSING", "no " + provider + " " + env + " credentials for this merchant"));
    return new Resolved(p, c);
  }

  /** Parsing a webhook needs no credential — the inbox must not fail because one was rotated. */
  public PixProvider provider(String provider) {
    return providers.stream()
        .filter(p -> p.id().equalsIgnoreCase(provider))
        .findFirst()
        .orElseThrow(() -> new DomainException("PROVIDER_UNKNOWN", "no provider named " + provider));
  }

  public <T> T call(String paymentId, String operation, Resolved r, Function<Resolved, T> fn) {
    long start = System.nanoTime();
    try {
      T result = fn.apply(r);
      record(paymentId, r, operation, null, CREATING.contains(operation) ? 201 : 200, start);
      return result;
    } catch (ProviderException e) {
      record(paymentId, r, operation, e.code() + ": " + e.getMessage(), statusOf(e), start);
      throw e;
    } catch (RuntimeException e) {
      record(paymentId, r, operation, e.getClass().getSimpleName() + ": " + e.getMessage(), 0, start);
      throw e;
    }
  }

  public void run(String paymentId, String operation, Resolved r, java.util.function.Consumer<Resolved> fn) {
    call(paymentId, operation, r, x -> {
      fn.accept(x);
      return null;
    });
  }

  /** A timeout has no HTTP status of its own; 504 is what support expects to see for it. */
  private static int statusOf(ProviderException e) {
    if (e.httpStatus() > 0) return e.httpStatus();
    return e.code() == ProviderException.Code.TIMEOUT ? 504 : 0;
  }

  // Recording is audit, not the operation: a failure to write the row must not turn a charge the
  // bank accepted into an error for the merchant.
  private void record(String paymentId, Resolved r, String operation, String response, int status, long start) {
    try {
      long latencyMs = (System.nanoTime() - start) / 1_000_000;
      requests.record(paymentId, r.provider().id(), operation, null, truncate(response), status, latencyMs);
    } catch (RuntimeException e) {
      log.warn("could not record provider request {} for payment {}", operation, paymentId, e);
    }
  }

  private static String truncate(String s) {
    return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
  }
}
