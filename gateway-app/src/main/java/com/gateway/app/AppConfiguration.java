package com.gateway.app;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Deviation from the brief, hit while wiring {@code @Import(MerchantsConfiguration.class)} in this
 * task rather than in Task 6 as the module's docstring anticipated: {@code MerchantsConfiguration}'s
 * own {@code @EntityScan}/{@code @EnableJpaRepositories}, scoped to {@code com.gateway.merchants.repository},
 * makes Boot skip the package {@code AutoConfigurationPackages} would otherwise register for
 * webhook-delivery ({@code com.barrier.webhookdelivery.repository}) — context refresh failed with
 * {@code NoSuchBeanDefinitionException} for {@code DeliveryJpaRepository}. Scanning only that one
 * package here (not {@code com.gateway.merchants.repository} too — declaring it in both places
 * threw {@code BeanDefinitionOverrideException} for {@code merchantJpaRepository}) fixes it, per the
 * docstring's "Task 6 note".
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AppConfiguration.AppProperties.class)
@EntityScan("com.barrier.webhookdelivery.repository")
@EnableJpaRepositories("com.barrier.webhookdelivery.repository")
public class AppConfiguration {
  /** Same "gateway" prefix as MerchantsProperties; each record binds only the fields it declares. */
  @ConfigurationProperties(prefix = "gateway")
  public record AppProperties(String adminKey, RateLimit rateLimit) {
    public AppProperties { if (rateLimit == null) rateLimit = new RateLimit(600); }
    public record RateLimit(int requestsPerMinute) { public RateLimit { if (requestsPerMinute <= 0) requestsPerMinute = 600; } }
  }
}
