package com.gateway.payments.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.CredentialLookup;
import com.gateway.kernel.provider.ProviderCredentials;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.payments.provider.persistence.ProviderRequestRepository;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Every call to a bank lands in one timer, labeled by how it ended. */
class ProviderGatewayMetricsTest {
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final CredentialLookup oneCredential =
      (merchantId, provider, environment) ->
          Optional.of(new ProviderCredentials("{}".getBytes(StandardCharsets.UTF_8), environment));
  private final ProviderRequestRepository requests =
      (paymentId, provider, operation, request, response, status, latencyMs) -> {};
  private final ProviderGateway gateway =
      new ProviderGateway(
          List.of(),
          List.of(),
          List.of(new ProviderGatewayTest.NamedCardProvider("CIELO")),
          oneCredential,
          requests,
          registry);
  private final ProviderGateway.ResolvedProvider<CardMethodProvider> resolved =
      gateway.resolveCard(MerchantId.next(), ProviderEnvironment.TEST, "CIELO");

  @Test
  void aSuccessfulCallIsTimedAsOk() {
    gateway.call("payment-1", "authorizeCard", resolved, target -> "authorized");

    assertThat(timer("ok").count()).isEqualTo(1);
  }

  @Test
  void aProviderTimeoutIsTimedAsTimeout() {
    assertThatThrownBy(
            () ->
                gateway.call(
                    "payment-1",
                    "authorizeCard",
                    resolved,
                    target -> {
                      throw new ProviderException(
                          ProviderException.Code.TIMEOUT, 0, "CIELO", "read timed out");
                    }))
        .isInstanceOf(ProviderException.class);

    assertThat(timer("timeout").count()).isEqualTo(1);
  }

  @Test
  void aProviderRejectionIsTimedAsProviderError() {
    assertThatThrownBy(
            () ->
                gateway.call(
                    "payment-1",
                    "authorizeCard",
                    resolved,
                    target -> {
                      throw new ProviderException(
                          ProviderException.Code.DECLINED, 422, "CIELO", "refused");
                    }))
        .isInstanceOf(ProviderException.class);

    assertThat(timer("provider_error").count()).isEqualTo(1);
  }

  @Test
  void anyOtherExceptionIsTimedAsUnexpected() {
    assertThatThrownBy(
            () ->
                gateway.call(
                    "payment-1",
                    "authorizeCard",
                    resolved,
                    target -> {
                      throw new IllegalStateException("boom");
                    }))
        .isInstanceOf(IllegalStateException.class);

    assertThat(timer("unexpected").count()).isEqualTo(1);
  }

  private Timer timer(String outcome) {
    return registry
        .get("gateway_provider_call_seconds")
        .tags("provider", "CIELO", "operation", "authorizeCard", "outcome", outcome)
        .timer();
  }
}
