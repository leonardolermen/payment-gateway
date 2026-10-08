package com.gateway.merchants;

import com.gateway.kernel.security.Sealer;
import com.gateway.merchants.apikey.ApiKeyService;
import com.gateway.merchants.apikey.persistence.ApiKeyRepository;
import com.gateway.merchants.apikey.persistence.ApiKeyRepositoryImpl;
import com.gateway.merchants.credential.ProviderCredentialService;
import com.gateway.merchants.credential.persistence.ProviderCredentialRepository;
import com.gateway.merchants.credential.persistence.ProviderCredentialRepositoryImpl;
import com.gateway.merchants.crypto.EnvelopeCipher;
import com.gateway.merchants.crypto.EnvelopeSealer;
import com.gateway.merchants.crypto.MasterKey;
import com.gateway.merchants.merchant.MerchantService;
import com.gateway.merchants.merchant.persistence.MerchantRepository;
import com.gateway.merchants.merchant.persistence.MerchantRepositoryImpl;
import com.gateway.merchants.notification.InboundNotificationKeyService;
import com.gateway.merchants.notification.persistence.InboundNotificationKeyRepository;
import com.gateway.merchants.notification.persistence.InboundNotificationKeyRepositoryImpl;
import com.gateway.merchants.session.SessionService;
import com.gateway.merchants.session.persistence.SessionRepository;
import com.gateway.merchants.session.persistence.SessionRepositoryImpl;
import com.gateway.merchants.user.PasswordService;
import com.gateway.merchants.user.UserService;
import com.gateway.merchants.user.persistence.UserRepository;
import com.gateway.merchants.user.persistence.UserRepositoryImpl;
import com.gateway.merchants.usertoken.UserTokenService;
import com.gateway.merchants.usertoken.persistence.UserTokenRepository;
import com.gateway.merchants.usertoken.persistence.UserTokenRepositoryImpl;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
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
 * annotations move to {@code gateway-app}, listing both packages, and the module's own {@code
 * TestApp} then declares them locally so this module's tests keep working standalone.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MerchantsProperties.class)
@EntityScan({
  "com.gateway.merchants.merchant.persistence",
  "com.gateway.merchants.apikey.persistence",
  "com.gateway.merchants.credential.persistence",
  "com.gateway.merchants.notification.persistence",
  "com.gateway.merchants.user.persistence",
  "com.gateway.merchants.session.persistence",
  "com.gateway.merchants.usertoken.persistence"
})
@EnableJpaRepositories({
  "com.gateway.merchants.merchant.persistence",
  "com.gateway.merchants.apikey.persistence",
  "com.gateway.merchants.credential.persistence",
  "com.gateway.merchants.notification.persistence",
  "com.gateway.merchants.user.persistence",
  "com.gateway.merchants.session.persistence",
  "com.gateway.merchants.usertoken.persistence"
})
@Import({
  MerchantRepositoryImpl.class,
  ApiKeyRepositoryImpl.class,
  ProviderCredentialRepositoryImpl.class,
  InboundNotificationKeyRepositoryImpl.class,
  UserRepositoryImpl.class,
  SessionRepositoryImpl.class,
  UserTokenRepositoryImpl.class
})
public class MerchantsConfiguration {
  @Bean
  public MasterKey masterKey(MerchantsProperties p) {
    return MasterKey.fromBase64(p.masterKey());
  }

  @Bean
  public EnvelopeCipher envelopeCipher(MasterKey m) {
    return new EnvelopeCipher(m);
  }

  /** The kernel port payments stores card tokens through (plan C8). */
  @Bean
  public Sealer sealer(EnvelopeCipher cipher) {
    return new EnvelopeSealer(cipher);
  }

  @Bean
  public MerchantService merchantService(MerchantRepository r) {
    return new MerchantService(r);
  }

  @Bean
  public ApiKeyService apiKeyService(
      ApiKeyRepository r, MerchantRepository m, MerchantsProperties p) {
    return new ApiKeyService(r, m, p);
  }

  @Bean
  public ProviderCredentialService providerCredentialService(
      ProviderCredentialRepository r, EnvelopeCipher c) {
    return new ProviderCredentialService(r, c);
  }

  @Bean
  public InboundNotificationKeyService inboundNotificationKeyService(
      InboundNotificationKeyRepository keys) {
    return new InboundNotificationKeyService(keys);
  }

  @Bean
  public PasswordService passwordService() {
    return new PasswordService();
  }

  @Bean
  public UserService userService(UserRepository users, PasswordService passwords, Clock clock) {
    return new UserService(users, passwords, clock);
  }

  @Bean
  public SessionService sessionService(
      SessionRepository sessions, MerchantsProperties properties, Clock clock) {
    return new SessionService(sessions, properties, clock);
  }

  @Bean
  public UserTokenService userTokenService(
      UserTokenRepository tokens, MerchantsProperties properties, Clock clock) {
    return new UserTokenService(tokens, properties, clock);
  }
}
