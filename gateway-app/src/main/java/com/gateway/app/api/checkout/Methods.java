package com.gateway.app.api.checkout;

import com.gateway.app.api.support.Environments;
import com.gateway.billing.order.Order;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.credential.Provider;
import com.gateway.merchants.credential.ProviderCredential;
import com.gateway.merchants.credential.ProviderCredentialService;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The methods the page may offer: those whose bank the merchant has an active credential for in the
 * order's environment. Offering a method without one would only end in a 422 after the payer typed
 * a card.
 */
final class Methods {
  private Methods() {}

  static List<String> available(ProviderCredentialService credentials, Order order) {
    ApiKeyEnvironment environment = Environments.toApiKey(order.environment());
    Set<Provider> providers =
        credentials.list(order.merchantId()).stream()
            .filter(ProviderCredential::active)
            .filter(credential -> credential.environment() == environment)
            .map(ProviderCredential::provider)
            .collect(Collectors.toSet());

    List<String> methods = new ArrayList<>();
    if (providers.contains(Provider.ITAU)) {
      methods.add("PIX");
      methods.add("BOLECODE");
    }
    if (providers.contains(Provider.CIELO)) {
      methods.add("CARD");
    }

    return methods;
  }
}
