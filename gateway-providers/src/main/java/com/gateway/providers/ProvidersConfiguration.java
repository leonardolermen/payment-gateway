package com.gateway.providers;

import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.provider.boleto.BoletoMethodProvider;
import com.gateway.kernel.provider.card.CardMethodProvider;
import com.gateway.kernel.provider.pix.PixMethodProvider;
import com.gateway.providers.cielo.CieloCardProvider;
import com.gateway.providers.cielo.CieloHttp;
import com.gateway.providers.cielo.auth.CieloEndpoints;
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
@EnableConfigurationProperties({
  ProvidersConfiguration.ProvidersProperties.class,
  ProvidersConfiguration.CieloProperties.class
})
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
      URI apiBase = api == null || api.isBlank() ? defaults.apiBase() : URI.create(api);
      URI tokenUrl = token == null || token.isBlank() ? defaults.tokenUrl() : URI.create(token);
      boolean mutualTls = mtls == null ? defaults.mutualTls() : mtls;
      return mutualTls
          ? ItauEndpoints.mutualTls(apiBase, tokenUrl)
          : ItauEndpoints.plain(apiBase, tokenUrl);
    }
  }

  @Bean
  ItauTokenClient itauTokenClient(Clock clock, ProvidersProperties properties) {
    return new ItauTokenClient(clock, Duration.ofSeconds(3), properties.readTimeout());
  }

  /**
   * {@code trustStorePem} unset → JDK default trust (Itaú's CA is public and usually in cacerts);
   * set it to the PEM from the portal's {@code ca-cert.zip} when it is not.
   */
  @Bean
  PixMethodProvider itauPixProvider(
      ItauTokenClient tokens, Clock clock, ProvidersProperties properties) {
    return new ItauPixProvider(
        tokens,
        trustStore(properties),
        properties.readTimeout(),
        clock,
        properties.live(),
        properties.test());
  }

  /** Same token client and trust store as Pix: one credential, one cache, one CA. */
  @Bean
  BoletoMethodProvider itauBoletoProvider(ItauTokenClient tokens, ProvidersProperties properties) {
    return new ItauBoletoProvider(
        tokens,
        trustStore(properties),
        properties.readTimeout(),
        properties.boletoLive(),
        properties.boletoTest());
  }

  private static KeyStore trustStore(ProvidersProperties properties) {
    return properties.trustStorePem() == null || properties.trustStorePem().isBlank()
        ? null
        : ItauPixProvider.trustStoreFromPem(properties.trustStorePem());
  }

  /**
   * Every field optional: unset means the hosts of docs/providers/cielo/NOTES.md. The read timeout
   * is 30 s because an authorization may take that long at the issuer, and the Status 0 answer
   * covers the rest (spec §5).
   */
  @ConfigurationProperties("gateway.providers.cielo")
  public record CieloProperties(
      String liveApiBase,
      String liveQueryApiBase,
      String testApiBase,
      String testQueryApiBase,
      Duration readTimeout) {
    public CieloProperties {
      if (readTimeout == null) {
        readTimeout = Duration.ofSeconds(30);
      }
    }

    public CieloEndpoints live() {
      return merge(
          CieloEndpoints.forEnvironment(ProviderEnvironment.LIVE), liveApiBase, liveQueryApiBase);
    }

    public CieloEndpoints test() {
      return merge(
          CieloEndpoints.forEnvironment(ProviderEnvironment.TEST), testApiBase, testQueryApiBase);
    }

    private static CieloEndpoints merge(CieloEndpoints defaults, String api, String query) {
      return new CieloEndpoints(
          api == null || api.isBlank() ? defaults.api() : URI.create(api),
          query == null || query.isBlank() ? defaults.apiQuery() : URI.create(query));
    }
  }

  @Bean
  CardMethodProvider cieloCardProvider(CieloProperties properties) {
    return new CieloCardProvider(
        new CieloHttp(Duration.ofSeconds(3), properties.readTimeout()),
        properties.live(),
        properties.test());
  }
}
