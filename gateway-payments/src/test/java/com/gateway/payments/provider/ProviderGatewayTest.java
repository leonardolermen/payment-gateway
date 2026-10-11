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
import com.gateway.kernel.provider.card.*;
import com.gateway.kernel.provider.pix.Charge;
import com.gateway.kernel.provider.pix.PixIssueRequest;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.kernel.provider.pix.RefundRequest;
import com.gateway.kernel.provider.pix.RefundResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

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
  private final CredentialLookup noCredential =
      (merchantId, provider, environment) -> Optional.empty();
  private final ProviderRequestRepositoryStub requests = new ProviderRequestRepositoryStub();

  @Test
  void resolveBoletoAnswersMethodNotSupportedWhenTheProviderHasNoBoletoProduct() {
    ProviderGateway gateway =
        new ProviderGateway(
            List.of(new PixOnlyProvider()),
            List.of(),
            List.of(),
            oneCredential,
            requests,
            new SimpleMeterRegistry());

    assertThatThrownBy(() -> gateway.resolveBoleto(MERCHANT, ProviderEnvironment.TEST, "ITAU"))
        .isInstanceOf(DomainException.class)
        .hasMessage("ITAU has no boleto product")
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("METHOD_NOT_SUPPORTED");
  }

  @Test
  void aBankCallRunsWithTheProviderAndOperationInTheContext() {
    ProviderGateway gateway =
        new ProviderGateway(
            List.of(new PixOnlyProvider()),
            List.of(),
            List.of(),
            oneCredential,
            requests,
            new SimpleMeterRegistry());
    var resolved = gateway.resolvePix(MERCHANT, ProviderEnvironment.TEST, "ITAU");

    String seen =
        gateway.call(
            null, "listCharges", resolved, target -> MDC.get("provider") + "/" + MDC.get("op"));

    assertThat(seen).isEqualTo("ITAU/listCharges");
    assertThat(MDC.get("provider")).isNull();
    assertThat(MDC.get("op")).isNull();
  }

  @Test
  void resolvePixAnswersCredentialsMissingWhenTheMerchantHasNone() {
    ProviderGateway gateway =
        new ProviderGateway(
            List.of(new PixOnlyProvider()),
            List.of(),
            List.of(),
            noCredential,
            requests,
            new SimpleMeterRegistry());

    assertThatThrownBy(() -> gateway.resolvePix(MERCHANT, ProviderEnvironment.LIVE, "ITAU"))
        .isInstanceOf(DomainException.class)
        .hasMessage("no ITAU LIVE credentials for this merchant")
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_CREDENTIALS_MISSING");
  }

  @Test
  void anUnknownProviderNameIsProviderUnknown() {
    ProviderGateway gateway =
        new ProviderGateway(
            List.of(new PixOnlyProvider()),
            List.of(),
            List.of(),
            oneCredential,
            requests,
            new SimpleMeterRegistry());

    assertThatThrownBy(() -> gateway.pixProvider("BRADESCO"))
        .isInstanceOf(DomainException.class)
        .hasMessage("no provider named BRADESCO")
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("PROVIDER_UNKNOWN");
  }

  @Test
  void resolvePixCarriesTheProviderAndTheCredentialOfTheEnvironmentThatAsked() {
    PixOnlyProvider itau = new PixOnlyProvider();
    ProviderGateway gateway =
        new ProviderGateway(
            List.of(itau),
            List.of(),
            List.of(),
            oneCredential,
            requests,
            new SimpleMeterRegistry());

    ProviderGateway.ResolvedProvider<PixMethodProvider> resolved =
        gateway.resolvePix(MERCHANT, ProviderEnvironment.LIVE, "itau");

    assertThat(resolved.provider()).isSameAs(itau);
    assertThat(resolved.credentials().environment()).isEqualTo(ProviderEnvironment.LIVE);
  }

  /** A failed call still leaves the row support needs, with the bank's status. */
  @Test
  void aFailedCallIsRecordedToo() {
    ProviderGateway gateway =
        new ProviderGateway(
            List.of(new PixOnlyProvider()),
            List.of(),
            List.of(),
            oneCredential,
            requests,
            new SimpleMeterRegistry());
    ProviderGateway.ResolvedProvider<PixMethodProvider> resolved =
        gateway.resolvePix(MERCHANT, ProviderEnvironment.TEST, "ITAU");

    assertThatThrownBy(
            () ->
                gateway.call(
                    "payment-1",
                    "createCharge",
                    resolved,
                    target -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(requests.recorded)
        .containsExactly("payment-1|ITAU|createCharge|IllegalStateException: boom|0");
  }

  @Test
  void cardResolvesToTheNamedAcquirerWithItsCredential() {
    CardMethodProvider cielo = new NamedCardProvider("CIELO");
    ProviderGateway gateway =
        new ProviderGateway(
            List.of(),
            List.of(),
            List.of(cielo),
            oneCredential,
            requests,
            new SimpleMeterRegistry());

    ProviderGateway.ResolvedProvider<CardMethodProvider> resolved =
        gateway.resolveCard(MERCHANT, ProviderEnvironment.TEST, "CIELO");

    assertThat(resolved.provider()).isSameAs(cielo);
    assertThat(gateway.hasCardProvider("cielo")).isTrue();
    assertThat(gateway.hasCardProvider("ITAU")).isFalse();
  }

  @Test
  void anAcquirerWithoutCardIsMethodNotSupported() {
    ProviderGateway gateway =
        new ProviderGateway(
            List.of(), List.of(), List.of(), oneCredential, requests, new SimpleMeterRegistry());

    assertThatThrownBy(() -> gateway.resolveCard(MERCHANT, ProviderEnvironment.TEST, "CIELO"))
        .isInstanceOf(DomainException.class)
        .extracting(thrown -> ((DomainException) thrown).code())
        .isEqualTo("METHOD_NOT_SUPPORTED");
  }

  /** Only what resolveCard needs; every operation is outside this test. */
  static final class NamedCardProvider implements CardMethodProvider {
    private final String id;

    NamedCardProvider(String id) {
      this.id = id;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public PaymentMethod method() {
      return PaymentMethod.CARD;
    }

    @Override
    public void requireIssueCredentials(ProviderCredentials credentials) {}

    @Override
    public CardAuthorization issue(ProviderCredentials credentials, CardIssueRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CardAuthorization> find(ProviderCredentials credentials, String reference) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void cancel(ProviderCredentials credentials, String reference) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CardAuthorization capture(
        ProviderCredentials credentials, String reference, Optional<Money> amount) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CardRefundResult refund(
        ProviderCredentials credentials, String reference, Optional<Money> amount) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CardAuthorization> findByOrder(
        ProviderCredentials credentials, String merchantOrderId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public StoredCard tokenize(
        ProviderCredentials credentials, CardData card, String customerName) {
      throw new UnsupportedOperationException();
    }

    @Override
    public CardNotification parseWebhook(byte[] body) {
      throw new UnsupportedOperationException();
    }
  }

  private static final class ProviderRequestRepositoryStub
      implements com.gateway.payments.provider.persistence.ProviderRequestRepository {
    private final List<String> recorded = new java.util.ArrayList<>();

    @Override
    public void record(
        String paymentId,
        String provider,
        String operation,
        String request,
        String response,
        int status,
        long latencyMs) {
      recorded.add(
          String.join(
              "|",
              paymentId,
              provider,
              operation,
              String.valueOf(response),
              String.valueOf(status)));
    }
  }

  /**
   * A provider with the Pix product and nothing else: the shape that used to make callers unwrap an
   * Optional.
   */
  private static final class PixOnlyProvider implements PixMethodProvider {
    @Override
    public String id() {
      return "ITAU";
    }

    @Override
    public PaymentMethod method() {
      return PaymentMethod.PIX;
    }

    @Override
    public void requireIssueCredentials(ProviderCredentials credentials) {}

    @Override
    public Charge issue(ProviderCredentials credentials, PixIssueRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Charge> find(ProviderCredentials credentials, String bankReference) {
      return Optional.empty();
    }

    @Override
    public void cancel(ProviderCredentials credentials, String bankReference) {}

    @Override
    public RefundResult requestRefund(ProviderCredentials credentials, RefundRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public Optional<RefundResult> findRefund(
        ProviderCredentials credentials, String endToEndId, String refundId) {
      return Optional.empty();
    }

    @Override
    public List<Charge> listCharges(ProviderCredentials credentials, Instant from, Instant to) {
      return List.of();
    }

    @Override
    public ProviderWebhookEvent parseWebhook(byte[] body) {
      throw new UnsupportedOperationException();
    }
  }
}
