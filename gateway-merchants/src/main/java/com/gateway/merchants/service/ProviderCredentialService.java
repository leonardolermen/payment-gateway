package com.gateway.merchants.service;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.crypto.Encrypted;
import com.gateway.merchants.crypto.EnvelopeCipher;
import com.gateway.merchants.domain.ApiKeyEnvironment;
import com.gateway.merchants.domain.Provider;
import com.gateway.merchants.domain.ProviderCredential;
import com.gateway.merchants.repository.ProviderCredentialRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

/** The only place that decrypts credentials — and it returns bytes, never caches them. */
public class ProviderCredentialService {
  private final ProviderCredentialRepository repo;
  private final EnvelopeCipher cipher;

  public ProviderCredentialService(ProviderCredentialRepository repo, EnvelopeCipher cipher) { this.repo = repo; this.cipher = cipher; }

  @Transactional
  public ProviderCredential store(MerchantId m, Provider p, ApiKeyEnvironment e, byte[] plaintext) {
    Encrypted enc = cipher.encrypt(plaintext, m.value());
    ProviderCredential cred = repo.find(m, p, e).map(x -> x.withPayload(enc)).orElseGet(() -> ProviderCredential.create(m, p, e, enc));
    return repo.save(cred);
  }

  @Transactional(readOnly = true)
  public Optional<byte[]> decrypt(MerchantId m, Provider p, ApiKeyEnvironment e) {
    return repo.find(m, p, e).filter(ProviderCredential::active).map(c -> cipher.decrypt(c.payload(), m.value()));
  }

  @Transactional(readOnly = true)
  public List<ProviderCredential> list(MerchantId m) { return repo.findByMerchant(m); }
}
