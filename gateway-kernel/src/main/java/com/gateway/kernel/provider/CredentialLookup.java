package com.gateway.kernel.provider;

import com.gateway.kernel.ids.MerchantId;
import java.util.Optional;

/** Implemented by merchants (where credentials are stored); consumed by payments before every provider call. */
public interface CredentialLookup {
  Optional<ProviderCredentials> find(MerchantId merchantId, String provider, ProviderEnvironment env);
}
