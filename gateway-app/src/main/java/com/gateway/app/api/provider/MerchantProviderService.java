package com.gateway.app.api.provider;

import com.gateway.app.api.support.Environments;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.security.Secret;
import com.gateway.kernel.security.Sha256;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.credential.Provider;
import com.gateway.merchants.credential.ProviderCredential;
import com.gateway.merchants.credential.ProviderCredentialService;
import com.gateway.merchants.credential.SecretMerge;
import com.gateway.merchants.notification.InboundNotificationKeyService;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A merchant's own provider configuration: credentials in (merge the secrets, validate, store,
 * audit), the notification key, and the state of each provider out — never a secret.
 */
@Service
@Transactional
public class MerchantProviderService {
  /**
   * The Cielo's own limit for a header value (docs/webhook, "VALUE — limitado a 1500 caracteres").
   */
  private static final int NOTIFICATION_KEY_MAX_LENGTH = 1500;

  /**
   * One provider as the panel sees it. {@code credential} is empty until something is stored;
   * {@code notificationKeySet} is null for a provider that has no such key.
   */
  public record ProviderStatus(
      Provider provider,
      ProviderCatalog.Entry entry,
      Optional<ProviderCredential> credential,
      Boolean notificationKeySet) {}

  private final ProviderCredentialService credentials;
  private final InboundNotificationKeyService notificationKeys;

  public MerchantProviderService(
      ProviderCredentialService credentials, InboundNotificationKeyService notificationKeys) {
    this.credentials = credentials;
    this.notificationKeys = notificationKeys;
  }

  @Transactional(readOnly = true)
  public List<ProviderStatus> status(MerchantId merchantId, ApiKeyEnvironment environment) {
    return ProviderCatalog.listed().stream()
        .map(provider -> statusOf(merchantId, environment, provider))
        .toList();
  }

  private ProviderStatus statusOf(
      MerchantId merchantId, ApiKeyEnvironment environment, Provider provider) {
    ProviderCatalog.Entry entry = ProviderCatalog.of(provider);
    Optional<ProviderCredential> credential =
        credentials.find(merchantId, provider, environment).filter(ProviderCredential::active);
    Boolean notificationKeySet =
        entry.hasNotificationKey() ? notificationKeys.isSet(merchantId, provider.name()) : null;

    return new ProviderStatus(provider, entry, credential, notificationKeySet);
  }

  public void store(
      MerchantId merchantId, Provider provider, ApiKeyEnvironment environment, byte[] submitted) {
    ProviderCatalog.Entry entry = ProviderCatalog.of(provider);

    byte[] merged =
        SecretMerge.merge(
            submitted,
            credentials.decrypt(merchantId, provider, environment),
            entry.secretFields());
    CredentialShape.validate(provider, Environments.toProvider(environment), merged);

    CredentialSummary summary = CredentialSummary.of(merged, entry);
    credentials.store(
        merchantId,
        provider,
        environment,
        merged,
        Sha256.hex(merged),
        summary.secretsSet(),
        summary.publicFields());

    ProviderEvents.credentialsSet(merchantId.value(), provider.name(), environment.name());
  }

  /** The notification key is per merchant, not per environment: the bank sends one header. */
  public void setNotificationKey(MerchantId merchantId, Provider provider, String key) {
    if (!ProviderCatalog.of(provider).hasNotificationKey()) {
      throw new IllegalArgumentException(provider + " has no notification key");
    }
    if (key == null || key.isBlank() || key.length() > NOTIFICATION_KEY_MAX_LENGTH) {
      throw new IllegalArgumentException("key must be 1 to 1500 characters");
    }

    notificationKeys.set(merchantId, provider.name(), Secret.of(key));

    ProviderEvents.notificationKeySet(merchantId.value(), provider.name());
  }
}
