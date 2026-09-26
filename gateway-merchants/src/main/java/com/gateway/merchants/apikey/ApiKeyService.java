package com.gateway.merchants.apikey;

import com.gateway.kernel.errors.DomainException;
import com.gateway.kernel.ids.MerchantId;
import com.gateway.merchants.MerchantsProperties;
import com.gateway.merchants.apikey.persistence.ApiKeyRepository;
import com.gateway.merchants.merchant.persistence.MerchantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.annotation.Transactional;

public class ApiKeyService {
  public record Authenticated(
      MerchantId merchantId, ApiKeyEnvironment environment, String apiKeyId) {}

  private static final int MAX_ACTIVE = 2;
  private final ApiKeyRepository repo;
  private final MerchantRepository merchants;
  private final MerchantsProperties props;

  public ApiKeyService(
      ApiKeyRepository repo, MerchantRepository merchants, MerchantsProperties props) {
    this.repo = repo;
    this.merchants = merchants;
    this.props = props;
  }

  /**
   * At most two active: that is what overlap rotation needs, and anything beyond is a forgotten
   * key.
   */
  @Transactional
  public ApiKey.Issued issue(MerchantId merchantId, ApiKeyEnvironment environment) {
    // Counts only keys that still authenticate: a rotated key stays active=true with an expiresAt,
    // and counting it after it expired blocked issue() forever once a merchant had rotated.
    Instant now = Instant.now();
    long valid =
        repo.findActiveByMerchantAndEnvironment(merchantId, environment).stream()
            .filter(apiKey -> apiKey.isValid(now))
            .count();
    if (valid >= MAX_ACTIVE) {
      throw new DomainException(
          "API_KEY_LIMIT",
          "there are already "
              + MAX_ACTIVE
              + " active keys in "
              + environment
              + "; revoke or rotate");
    }
    ApiKey.Issued issued = ApiKey.issue(merchantId, environment, props.apiKeyPepper());
    repo.save(issued.apiKey());
    return issued;
  }

  /**
   * Issues the new key and gives the old ones a deadline: the merchant switches when it can, no
   * agreed instant needed.
   */
  @Transactional
  public ApiKey.Issued rotate(MerchantId merchantId, ApiKeyEnvironment environment) {
    Instant deadline = Instant.now().plus(props.apiKeyRotationOverlap());
    for (ApiKey k : repo.findActiveByMerchantAndEnvironment(merchantId, environment)) {
      repo.save(
          k.expiringAt(
              k.expiresAt() == null || k.expiresAt().isAfter(deadline) ? deadline : k.expiresAt()));
    }
    // The old ones stay "active" with a deadline, so the cap of 2 counts them: rotating with 2
    // active
    // must work. That is why issuing here bypasses the cap.
    ApiKey.Issued issued = ApiKey.issue(merchantId, environment, props.apiKeyPepper());
    repo.save(issued.apiKey());
    return issued;
  }

  /**
   * Looks up by prefix and compares the hash in constant time. The prefix narrows the search to one
   * row (or a few, if two merchants drew the same 4 chars — hence the full comparison).
   */
  @Transactional(readOnly = true)
  public Optional<Authenticated> authenticate(String plainKey) {
    if (ApiKey.environmentOf(plainKey).isEmpty()) {
      return Optional.empty();
    }
    byte[] hash = ApiKey.hashOf(plainKey, props.apiKeyPepper()).getBytes(StandardCharsets.UTF_8);
    Instant now = Instant.now();
    return repo.findByPrefix(ApiKey.prefixOf(plainKey)).stream()
        .filter(
            apiKey -> MessageDigest.isEqual(hash, apiKey.hash().getBytes(StandardCharsets.UTF_8)))
        .filter(apiKey -> apiKey.isValid(now))
        .filter(
            apiKey ->
                merchants
                    .findById(apiKey.merchantId())
                    .map(merchant -> merchant.isActive())
                    .orElse(false))
        .findFirst()
        .map(apiKey -> new Authenticated(apiKey.merchantId(), apiKey.environment(), apiKey.id()));
  }

  @Transactional
  public void revoke(MerchantId merchantId, String apiKeyId) {
    repo.findById(apiKeyId)
        .filter(apiKey -> apiKey.merchantId().equals(merchantId))
        .map(ApiKey::revoke)
        .ifPresent(repo::save);
  }

  @Transactional(readOnly = true)
  public List<ApiKey> list(MerchantId merchantId) {
    return repo.findByMerchant(merchantId);
  }
}
