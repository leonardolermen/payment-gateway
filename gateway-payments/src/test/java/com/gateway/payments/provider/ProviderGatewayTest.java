package com.gateway.payments.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.money.Money;
import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.kernel.provider.CredentialLookup;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderWebhookEvent;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.PixIssueRequest;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.kernel.provider.pix.RefundRequest;
import com.gateway.kernel.provider.pix.RefundResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The resolution rules, which used to live half here and half in every caller that unwrapped an
 * Optional: an absent boleto product, an absent credential and an unknown provider name each have
 * one answer, given in one place.
 */
class ProviderGatewayTest {
  private static final MerchantId MERCHANT = MerchantId.next();

  private final CredentialLookup oneCredential =
      (merchantId, provider, environment) ->
          Optional.of(new ProviderCredentials("{}".getBytes(StandardCharsets.UTF_8), environment));
  private final CredentialLookup noCredential = (merchantId, provider, environment) -> Optional.empty();
  private final ProviderRequestRepositoryStub requests = new ProviderRequestRepositoryStub();

  @Test
  void resolveBoletoAnswersMethodNotSupportedWhenTheProviderHasNoBoletoProduct() {
    ProviderGateway gateway = new ProviderGateway(List.of(new PixOnlyProvider()), List.of(), oneCredential, requests);

    assertThatThrownBy(() -> gateway.resolveBoleto(MERCHANT, ProviderEnvironment.TEST, "ITAU"))
        .isInstanceOf(DomainException.class)
        .hasMessage("ITAU has no boleto product")
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("METHOD_NOT_SUPPORTED");
  }

  @Test
  void resolvePixAnswersCredentialsMissingWhenTheMerchantHasNone() {
    ProviderGateway gateway = new ProviderGateway(List.of(new PixOnlyProvider()), List.of(), noCredential, requests);

    assertThatThrownBy(() -> gateway.resolvePix(MERCHANT, ProviderEnvironment.LIVE, "ITAU"))
        .isInstanceOf(DomainException.class)
        .hasMessage("no ITAU LIVE credentials for this merchant")
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_CREDENTIALS_MISSING");
  }

  @Test
  void anUnknownProviderNameIsProviderUnknown() {
    ProviderGateway gateway = new ProviderGateway(List.of(new PixOnlyProvider()), List.of(), oneCredential, requests);

    assertThatThrownBy(() -> gateway.pixProvider("BRADESCO"))
        .isInstanceOf(DomainException.class)
        .hasMessage("no provider named BRADESCO")
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_UNKNOWN");
  }

  @Test
  void resolvePixCarriesTheProviderAndTheCredentialOfTheEnvironmentThatAsked() {
    PixOnlyProvider itau = new PixOnlyProvider();
    ProviderGateway gateway = new ProviderGateway(List.of(itau), List.of(), oneCredential, requests);

    ProviderGateway.ResolvedProvider<PixMethodProvider> resolved = gateway.resolvePix(MERCHANT, ProviderEnvironment.LIVE, "itau");

    assertThat(resolved.provider()).isSameAs(itau);
    assertThat(resolved.credentials().environment()).isEqualTo(ProviderEnvironment.LIVE);
  }

  /** A failed call still leaves the row support needs, with the bank's status. */
  @Test
  void aFailedCallIsRecordedToo() {
    ProviderGateway gateway = new ProviderGateway(List.of(new PixOnlyProvider()), List.of(), oneCredential, requests);
    ProviderGateway.ResolvedProvider<PixMethodProvider> resolved = gateway.resolvePix(MERCHANT, ProviderEnvironment.TEST, "ITAU");

    assertThatThrownBy(() -> gateway.call("payment-1", "createCharge", resolved, target -> {
      throw new IllegalStateException("boom");
    })).isInstanceOf(IllegalStateException.class);

    assertThat(requests.recorded).containsExactly("payment-1|ITAU|createCharge|IllegalStateException: boom|0");
  }

  private static final class ProviderRequestRepositoryStub
      implements com.gateway.payments.provider.persistence.ProviderRequestRepository {
    private final List<String> recorded = new java.util.ArrayList<>();

    @Override
    public void record(String paymentId, String provider, String operation, String request, String response, int status, long latencyMs) {
      recorded.add(String.join("|", paymentId, provider, operation, String.valueOf(response), String.valueOf(status)));
    }
  }

  /** A provider with the Pix product and nothing else: the shape that used to make callers unwrap an Optional. */
  private static final class PixOnlyProvider implements PixMethodProvider {
    @Override public String id() {
      return "ITAU";
    }

    @Override public PaymentMethod method() {
      return PaymentMethod.PIX;
    }

    @Override public void requireIssueCredentials(ProviderCredentials credentials) {}

    @Override public Charge issue(ProviderCredentials credentials, PixIssueRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override public Optional<Charge> find(ProviderCredentials credentials, String bankReference) {
      return Optional.empty();
    }

    @Override public void cancel(ProviderCredentials credentials, String bankReference) {}

    @Override public RefundResult requestRefund(ProviderCredentials credentials, RefundRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override public Optional<RefundResult> findRefund(ProviderCredentials credentials, String endToEndId, String refundId) {
      return Optional.empty();
    }

    @Override public List<Charge> listCharges(ProviderCredentials credentials, Instant from, Instant to) {
      return List.of();
    }

    @Override public ProviderWebhookEvent parseWebhook(byte[] body) {
      throw new UnsupportedOperationException();
    }
  }
}
