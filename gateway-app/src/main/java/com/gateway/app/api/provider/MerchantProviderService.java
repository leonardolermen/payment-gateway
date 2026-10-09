package com.gateway.app.api.provider;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.provider.ProviderEnvironment;
import com.gateway.kernel.security.Sha256;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.credential.Provider;
import com.gateway.merchants.credential.ProviderCredentialService;
import com.gateway.merchants.credential.SecretMerge;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** A merchant saving its own provider credential: merge the secrets, validate, store, audit. */
@Service
@Transactional
public class MerchantProviderService {
  private static final ObjectMapper JSON = new ObjectMapper();

  private final ProviderCredentialService credentials;

  public MerchantProviderService(ProviderCredentialService credentials) {
    this.credentials = credentials;
  }

  public void store(
      MerchantId merchantId, Provider provider, ApiKeyEnvironment environment, byte[] submitted) {
    ProviderCatalog.Entry entry = ProviderCatalog.of(provider);

    byte[] merged =
        SecretMerge.merge(
            submitted,
            credentials.decrypt(merchantId, provider, environment),
            entry.secretFields());
    CredentialShape.validate(provider, toProviderEnvironment(environment), merged);

    credentials.store(
        merchantId,
        provider,
        environment,
        merged,
        Sha256.hex(merged),
        secretsSet(merged, entry.secretFields()));

    ProviderEvents.credentialsSet(merchantId.value(), provider.name(), environment.name());
  }

  private static Map<String, Boolean> secretsSet(byte[] merged, Set<String> secretFields) {
    JsonNode credential = JSON.readTree(merged);
    Map<String, Boolean> secretsSet = new LinkedHashMap<>();

    for (String field : secretFields) {
      JsonNode value = credential.get(field);
      boolean isSet = value != null && value.isString() && !value.asString().isBlank();
      secretsSet.put(field, isSet);
    }

    return secretsSet;
  }

  private static ProviderEnvironment toProviderEnvironment(ApiKeyEnvironment environment) {
    return environment == ApiKeyEnvironment.LIVE
        ? ProviderEnvironment.LIVE
        : ProviderEnvironment.TEST;
  }
}
