package com.gateway.merchants;

import com.gateway.merchants.crypto.EnvelopeCipher;
import com.gateway.merchants.crypto.MasterKey;
import com.gateway.merchants.repository.*;
import com.gateway.merchants.service.*;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * What the module exposes, chosen one by one. No component scan: the app imports this class and
 * knows exactly what came in. {@code @EntityScan}/{@code @EnableJpaRepositories} point only at this
 * module's package — each module declares its own and Boot merges them.
 *
 * <p>Task 6 note: an explicit {@code @EntityScan}/{@code @EnableJpaRepositories} here makes Boot
 * skip the package the webhook-delivery library registers via {@code AutoConfigurationPackages}
 * (documented by that library). If the app cannot find the library's entities, these two
 * annotations move to {@code gateway-app}, listing both packages, and the module's own
 * {@code TestApp} then declares them locally so this module's tests keep working standalone.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantsProperties.class)
@EntityScan("com.gateway.merchants.repository")
@EnableJpaRepositories("com.gateway.merchants.repository")
@Import({MerchantRepositoryImpl.class, ApiKeyRepositoryImpl.class, ProviderCredentialRepositoryImpl.class})
public class MerchantsConfiguration {
  @Bean public MasterKey masterKey(MerchantsProperties p) { return MasterKey.fromBase64(p.masterKey()); }
  @Bean public EnvelopeCipher envelopeCipher(MasterKey m) { return new EnvelopeCipher(m); }
  @Bean public MerchantService merchantService(MerchantRepository r) { return new MerchantService(r); }
  @Bean public ApiKeyService apiKeyService(ApiKeyRepository r, MerchantRepository m, MerchantsProperties p) { return new ApiKeyService(r, m, p); }
  @Bean public ProviderCredentialService providerCredentialService(ProviderCredentialRepository r, EnvelopeCipher c) { return new ProviderCredentialService(r, c); }
}
