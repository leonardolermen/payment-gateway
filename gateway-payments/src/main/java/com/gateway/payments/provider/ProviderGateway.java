package com.gateway.payments.provider;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.CredentialLookup;
import com.gateway.kernel.provider.MethodProvider;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.payments.provider.persistence.ProviderRequestRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
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

  /**
   * The operations that create a resource at the bank answer 201 (PUT /cob, PUT /devolucao, POST
   * /1/sales).
   */
  private static final Set<String> CREATING =
      Set.of("createCharge", "requestRefund", "authorizeCard");

  /**
   * A provider resolved together with the credential of the merchant and environment that asked.
   */
  public record ResolvedProvider<P extends MethodProvider<?, ?, ?>>(
      P provider, ProviderCredentials credentials) {}

  private final List<PixMethodProvider> pixProviders;
  private final List<BoletoMethodProvider> boletoProviders;
  private final List<CardMethodProvider> cardProviders;
  private final CredentialLookup credentials;
  private final ProviderRequestRepository requests;
  private final MeterRegistry meters;

  public ProviderGateway(
      List<PixMethodProvider> pixProviders,
      List<BoletoMethodProvider> boletoProviders,
      List<CardMethodProvider> cardProviders,
      CredentialLookup credentials,
      ProviderRequestRepository requests,
      MeterRegistry meters) {
    this.pixProviders = pixProviders;
    this.boletoProviders = boletoProviders;
    this.cardProviders = cardProviders;
    this.credentials = credentials;
    this.requests = requests;
    this.meters = meters;
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

  /** Same door as the boleto: an acquirer without a card product is METHOD_NOT_SUPPORTED, once. */
  public ResolvedProvider<CardMethodProvider> resolveCard(
      MerchantId merchantId, ProviderEnvironment environment, String providerId) {
    CardMethodProvider provider =
        cardProviders.stream()
            .filter(candidate -> candidate.id().equalsIgnoreCase(providerId))
            .findFirst()
            .orElseThrow(
                () ->
                    new DomainException(
                        "METHOD_NOT_SUPPORTED", providerId + " has no card product"));

    return new ResolvedProvider<>(provider, credential(merchantId, environment, providerId));
  }

  /** Parsing a notification needs no credential, like {@link #pixProvider}. */
  public CardMethodProvider cardProvider(String providerId) {
    return cardProviders.stream()
        .filter(candidate -> candidate.id().equalsIgnoreCase(providerId))
        .findFirst()
        .orElseThrow(
            () -> new DomainException("PROVIDER_UNKNOWN", "no card provider named " + providerId));
  }

  /** The inbox asks this to route a stored notification to the card side. */
  public boolean hasCardProvider(String providerId) {
    return cardProviders.stream()
        .anyMatch(candidate -> candidate.id().equalsIgnoreCase(providerId));
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
      time(resolved, operation, "ok", start);
      return result;
    } catch (ProviderException e) {
      record(paymentId, resolved, operation, e.code() + ": " + e.getMessage(), statusOf(e), start);
      time(resolved, operation, outcomeOf(e), start);
      throw e;
    } catch (RuntimeException e) {
      time(resolved, operation, "unexpected", start);
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

  /**
   * A timeout is split from the other refusals because it is the one that may have landed at the
   * bank: an alert on it means reconciliation work, an alert on provider_error does not.
   */
  private static String outcomeOf(ProviderException e) {
    return e.code() == ProviderException.Code.TIMEOUT ? "timeout" : "provider_error";
  }

  // Like the row below, the timer is observation: a meter registry failure must not fail a call
  // the bank already answered.
  // The histogram is what makes a p95 computable at all (histogram_quantile over _bucket): a bare
  // timer publishes only count, sum and max. The SLO buckets put edges on the thresholds an
  // operator alerts on, 5 s being the README's.
  private void time(ResolvedProvider<?> resolved, String operation, String outcome, long start) {
    try {
      Timer.builder("gateway_provider_call_seconds")
          .tags("provider", resolved.provider().id(), "operation", operation, "outcome", outcome)
          .publishPercentileHistogram()
          .serviceLevelObjectives(
              Duration.ofMillis(500),
              Duration.ofSeconds(1),
              Duration.ofSeconds(2),
              Duration.ofSeconds(5),
              Duration.ofSeconds(10))
          .register(meters)
          .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    } catch (RuntimeException e) {
      log.warn("could not time provider call {} ({})", operation, outcome, e);
    }
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
