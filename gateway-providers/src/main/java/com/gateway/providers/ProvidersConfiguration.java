package com.gateway.providers;

import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.providers.itau.auth.ItauEndpoints;
import com.gateway.providers.itau.auth.ItauTokenClient;
import com.gateway.providers.itau.boleto.ItauBoletoEndpoints;
import com.gateway.providers.itau.boleto.ItauBoletoProvider;
import com.gateway.providers.itau.pix.ItauPixProvider;
import java.net.URI;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProvidersConfiguration.ProvidersProperties.class)
// Clock is injected, never defined here: the app owns it (a conditional bean in a plain
// @Configuration would depend on registration order).
public class ProvidersConfiguration {

  /**
   * Every field optional: the defaults are the URLs in docs/providers/itau/NOTES.md and the
   * Bolecode spec. Overriding them is how the app's tests point the provider at WireMock.
   * Mutual-TLS flags are boxed so an unset property keeps the environment's real auth model. The
   * boleto block has one base and one token URL per API because the three products live on three
   * hosts and cash_management authenticates at a different STS path.
   */
  @ConfigurationProperties("gateway.providers.itau")
  public record ProvidersProperties(
      String liveApiBase,
      String liveTokenUrl,
      Boolean liveMutualTls,
      String testApiBase,
      String testTokenUrl,
      Boolean testMutualTls,
      String trustStorePem,
      Duration readTimeout,
      Boleto boleto) {
    public ProvidersProperties {
      // Itaú recommends a 30 s client timeout for refunds (NOTES.md); charges answer well within
      // it.
      if (readTimeout == null) {
        readTimeout = Duration.ofSeconds(30);
      }
      if (boleto == null) {
        boleto = new Boleto(null, null, null, null, null, null, null, null, null, null, null, null);
      }
    }

    public record Boleto(
        String liveIssueApiBase,
        String liveIssueTokenUrl,
        String liveQueryApiBase,
        String liveQueryTokenUrl,
        String liveInstructionApiBase,
        String liveInstructionTokenUrl,
        String testIssueApiBase,
        String testIssueTokenUrl,
        String testQueryApiBase,
        String testQueryTokenUrl,
        String testInstructionApiBase,
        String testInstructionTokenUrl) {}

    ItauEndpoints live() {
      return merge(
          ItauEndpoints.forEnvironment(ProviderEnvironment.LIVE),
          liveApiBase,
          liveTokenUrl,
          liveMutualTls);
    }

    ItauEndpoints test() {
      return merge(
          ItauEndpoints.forEnvironment(ProviderEnvironment.TEST),
          testApiBase,
          testTokenUrl,
          testMutualTls);
    }

    ItauBoletoEndpoints boletoLive() {
      ItauBoletoEndpoints defaults = ItauBoletoEndpoints.forEnvironment(ProviderEnvironment.LIVE);
      return new ItauBoletoEndpoints(
          merge(
              defaults.issue(),
              boleto.liveIssueApiBase(),
              boleto.liveIssueTokenUrl(),
              liveMutualTls),
          merge(
              defaults.query(),
              boleto.liveQueryApiBase(),
              boleto.liveQueryTokenUrl(),
              liveMutualTls),
          merge(
              defaults.instruction(),
              boleto.liveInstructionApiBase(),
              boleto.liveInstructionTokenUrl(),
              liveMutualTls));
    }

    ItauBoletoEndpoints boletoTest() {
      ItauBoletoEndpoints defaults = ItauBoletoEndpoints.forEnvironment(ProviderEnvironment.TEST);
      return new ItauBoletoEndpoints(
          merge(
              defaults.issue(),
              boleto.testIssueApiBase(),
              boleto.testIssueTokenUrl(),
              testMutualTls),
          merge(
              defaults.query(),
              boleto.testQueryApiBase(),
              boleto.testQueryTokenUrl(),
              testMutualTls),
          merge(
              defaults.instruction(),
              boleto.testInstructionApiBase(),
              boleto.testInstructionTokenUrl(),
              testMutualTls));
    }

    private static ItauEndpoints merge(
        ItauEndpoints defaults, String api, String token, Boolean mtls) {
      return ItauEndpoints.custom(
          api == null || api.isBlank() ? defaults.apiBase() : URI.create(api),
          token == null || token.isBlank() ? defaults.tokenUrl() : URI.create(token),
          mtls == null ? defaults.mutualTls() : mtls);
    }
  }

  @Bean
  ItauTokenClient itauTokenClient(Clock clock, ProvidersProperties props) {
    return new ItauTokenClient(clock, Duration.ofSeconds(3), props.readTimeout());
  }

  /**
   * {@code trustStorePem} unset → JDK default trust (Itaú's CA is public and usually in cacerts);
   * set it to the PEM from the portal's {@code ca-cert.zip} when it is not.
   */
  @Bean
  PixMethodProvider itauPixProvider(
      ItauTokenClient tokens, Clock clock, ProvidersProperties props) {
    return new ItauPixProvider(
        tokens, trustStore(props), props.readTimeout(), clock, props.live(), props.test());
  }

  /** Same token client and trust store as Pix: one credential, one cache, one CA. */
  @Bean
  BoletoMethodProvider itauBoletoProvider(ItauTokenClient tokens, ProvidersProperties props) {
    return new ItauBoletoProvider(
        tokens, trustStore(props), props.readTimeout(), props.boletoLive(), props.boletoTest());
  }

  private static KeyStore trustStore(ProvidersProperties props) {
    return props.trustStorePem() == null || props.trustStorePem().isBlank()
        ? null
        : ItauPixProvider.trustStoreFromPem(props.trustStorePem());
  }
}
