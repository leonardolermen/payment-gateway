package com.gateway.merchants.credential;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.credential.persistence.ProviderCredentialRepository;
import com.gateway.merchants.crypto.Encrypted;
import com.gateway.merchants.crypto.EnvelopeCipher;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** The only place that decrypts credentials — and it returns bytes, never caches them. */
public class ProviderCredentialService {
  private final ProviderCredentialRepository repo;
  private final EnvelopeCipher cipher;

  public ProviderCredentialService(ProviderCredentialRepository repo, EnvelopeCipher cipher) {
    this.repo = repo;
    this.cipher = cipher;
  }

  @Transactional
  public ProviderCredential store(
      MerchantId merchantId, Provider provider, ApiKeyEnvironment environment, byte[] plaintext) {
    Encrypted enc = cipher.encrypt(plaintext, aad(merchantId, provider, environment));
    ProviderCredential credential =
        repo.find(merchantId, provider, environment)
            .map(existing -> existing.withPayload(enc))
            .orElseGet(() -> ProviderCredential.create(merchantId, provider, environment, enc));
    return repo.save(credential);
  }

  @Transactional(readOnly = true)
  public Optional<byte[]> decrypt(
      MerchantId merchantId, Provider provider, ApiKeyEnvironment environment) {
    return repo.find(merchantId, provider, environment)
        .filter(ProviderCredential::active)
        .map(
            credential ->
                cipher.decrypt(credential.payload(), aad(merchantId, provider, environment)));
  }

  /**
   * Binds the ciphertext to the whole row key, not just the merchant: with merchant-only AAD a LIVE
   * row swapped with the same merchant's TEST row decrypted fine, so TEST code could end up holding
   * LIVE bank credentials. A mismatch throws {@link SecurityException} from the cipher.
   */
  private static String aad(
      MerchantId merchantId, Provider provider, ApiKeyEnvironment environment) {
    return merchantId.value() + "|" + provider + "|" + environment;
  }

  @Transactional(readOnly = true)
  public List<ProviderCredential> list(MerchantId merchantId) {
    return repo.findByMerchant(merchantId);
  }
}
