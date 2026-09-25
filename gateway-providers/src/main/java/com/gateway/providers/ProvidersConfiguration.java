package com.gateway.providers;

import com.gateway.kernel.provider.PixProvider;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.providers.itau.ItauEndpoints;
import com.gateway.providers.itau.ItauPixProvider;
import com.gateway.providers.itau.ItauTokenClient;
import java.net.URI;
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
   * Every field optional: the defaults are the URLs in docs/providers/itau/NOTES.md. Overriding
   * them is how the app's tests point the provider at WireMock. Mutual-TLS flags are boxed so an
   * unset property keeps the environment's real auth model instead of collapsing to {@code false}.
   */
  @ConfigurationProperties("gateway.providers.itau")
  public record ProvidersProperties(String liveApiBase, String liveTokenUrl, Boolean liveMutualTls,
                                    String testApiBase, String testTokenUrl, Boolean testMutualTls,
                                    String trustStorePem, Duration readTimeout) {
    public ProvidersProperties {
      // Itaú recommends a 30 s client timeout for refunds (NOTES.md); charges answer well within it.
      if (readTimeout == null) readTimeout = Duration.ofSeconds(30);
    }

    ItauEndpoints live() { return merge(ItauEndpoints.forEnvironment(ProviderEnvironment.LIVE), liveApiBase, liveTokenUrl, liveMutualTls); }
    ItauEndpoints test() { return merge(ItauEndpoints.forEnvironment(ProviderEnvironment.TEST), testApiBase, testTokenUrl, testMutualTls); }

    private static ItauEndpoints merge(ItauEndpoints d, String api, String token, Boolean mtls) {
      return ItauEndpoints.custom(api == null || api.isBlank() ? d.apiBase() : URI.create(api),
          token == null || token.isBlank() ? d.tokenUrl() : URI.create(token), mtls == null ? d.mutualTls() : mtls);
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
  PixProvider itauPixProvider(ItauTokenClient tokens, Clock clock, ProvidersProperties props) {
    var trust = props.trustStorePem() == null || props.trustStorePem().isBlank() ? null : ItauPixProvider.trustStoreFromPem(props.trustStorePem());
    return new ItauPixProvider(tokens, trust, props.readTimeout(), clock, props.live(), props.test());
  }
}
