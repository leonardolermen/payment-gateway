package com.gateway.payments.provider;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.CredentialLookup;
import com.gateway.kernel.provider.MethodProvider;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.payments.provider.persistence.ProviderRequestRepository;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The only door to a bank: resolves which provider and which credential, times every call and
 * leaves a {@code provider_requests} row behind it — including for the calls that failed, which are
 * the ones support needs.
 *
 * <p>Resolution is per method, and each door is typed: a caller that needs a boleto asks for one
 * and either gets it or gets {@code METHOD_NOT_SUPPORTED}. It used to hand back an {@code
 * Optional<BoletoProvider>}, and the six callers that unwrapped it each repeated that decision.
 *
 * <p>Request and response bodies are not recorded here: the provider interface returns domain
 * records, not wire bodies, and credentials must never reach that table.
 */
public class ProviderGateway {
  private static final Logger log = LoggerFactory.getLogger(ProviderGateway.class);

  /** The operations that create a resource at the bank answer 201 (PUT /cob, PUT /devolucao). */
  private static final Set<String> CREATING = Set.of("createCharge", "requestRefund");

  /**
   * A provider resolved together with the credential of the merchant and environment that asked.
   */
  public record ResolvedProvider<P extends MethodProvider<?, ?, ?>>(
      P provider, ProviderCredentials credentials) {}

  private final List<PixMethodProvider> pixProviders;
  private final List<BoletoMethodProvider> boletoProviders;
  private final CredentialLookup credentials;
  private final ProviderRequestRepository requests;

  public ProviderGateway(
      List<PixMethodProvider> pixProviders,
      List<BoletoMethodProvider> boletoProviders,
      CredentialLookup credentials,
      ProviderRequestRepository requests) {
    this.pixProviders = pixProviders;
    this.boletoProviders = boletoProviders;
    this.credentials = credentials;
    this.requests = requests;
  }

  public ResolvedProvider<PixMethodProvider> resolvePix(
      MerchantId merchantId, ProviderEnvironment environment, String providerId) {
    return new ResolvedProvider<>(
        pixProvider(providerId), credential(merchantId, environment, providerId));
  }

  /**
   * The boleto product is optional in the contract (no provider lacks it today). Answering
   * METHOD_NOT_SUPPORTED here, once, is why the callers no longer each decide what an absent
   * product means.
   */
  public ResolvedProvider<BoletoMethodProvider> resolveBoleto(
      MerchantId merchantId, ProviderEnvironment environment, String providerId) {
    BoletoMethodProvider provider =
        boletoProviders.stream()
            .filter(candidate -> candidate.id().equalsIgnoreCase(providerId))
            .findFirst()
            .orElseThrow(
                () ->
                    new DomainException(
                        "METHOD_NOT_SUPPORTED", providerId + " has no boleto product"));

    return new ResolvedProvider<>(provider, credential(merchantId, environment, providerId));
  }

  /** Parsing a webhook needs no credential — the inbox must not fail because one was rotated. */
  public PixMethodProvider pixProvider(String providerId) {
    return pixProviders.stream()
        .filter(candidate -> candidate.id().equalsIgnoreCase(providerId))
        .findFirst()
        .orElseThrow(
            () -> new DomainException("PROVIDER_UNKNOWN", "no provider named " + providerId));
  }

  private ProviderCredentials credential(
      MerchantId merchantId, ProviderEnvironment environment, String providerId) {
    return credentials
        .find(merchantId, providerId, environment)
        .orElseThrow(
            () ->
                new DomainException(
                    "PROVIDER_CREDENTIALS_MISSING",
                    "no " + providerId + " " + environment + " credentials for this merchant"));
  }

  public <P extends MethodProvider<?, ?, ?>, T> T call(
      String paymentId,
      String operation,
      ResolvedProvider<P> resolved,
      Function<ResolvedProvider<P>, T> fn) {
    long start = System.nanoTime();

    try {
      T result = fn.apply(resolved);
      record(paymentId, resolved, operation, null, CREATING.contains(operation) ? 201 : 200, start);
      return result;
    } catch (ProviderException e) {
      record(paymentId, resolved, operation, e.code() + ": " + e.getMessage(), statusOf(e), start);
      throw e;
    } catch (RuntimeException e) {
      record(
          paymentId,
          resolved,
          operation,
          e.getClass().getSimpleName() + ": " + e.getMessage(),
          0,
          start);
      throw e;
    }
  }

  public <P extends MethodProvider<?, ?, ?>> void run(
      String paymentId,
      String operation,
      ResolvedProvider<P> resolved,
      Consumer<ResolvedProvider<P>> fn) {
    call(
        paymentId,
        operation,
        resolved,
        target -> {
          fn.accept(target);
          return null;
        });
  }

  /** A timeout has no HTTP status of its own; 504 is what support expects to see for it. */
  private static int statusOf(ProviderException e) {
    if (e.httpStatus() > 0) {
      return e.httpStatus();
    }
    return e.code() == ProviderException.Code.TIMEOUT ? 504 : 0;
  }

  // Recording is audit, not the operation: a failure to write the row must not turn a charge the
  // bank accepted into an error for the merchant.
  private void record(
      String paymentId,
      ResolvedProvider<?> resolved,
      String operation,
      String response,
      int status,
      long start) {
    try {
      long latencyMs = (System.nanoTime() - start) / 1_000_000;
      requests.record(
          paymentId,
          resolved.provider().id(),
          operation,
          null,
          truncate(response),
          status,
          latencyMs);
    } catch (RuntimeException e) {
      log.warn("could not record provider request {} for payment {}", operation, paymentId, e);
    }
  }

  private static String truncate(String s) {
    return s == null || s.length() <= 2000 ? s : s.substring(0, 2000);
  }
}
