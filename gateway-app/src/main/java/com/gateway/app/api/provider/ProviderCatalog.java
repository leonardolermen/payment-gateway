package com.gateway.app.api.provider;

import com.gateway.kernel.payment.PaymentMethod;
import com.gateway.merchants.credential.Provider;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The providers a merchant can configure, with the methods each serves, the fields that are secrets
 * (never shown back, kept when submitted absent) and whether the bank authenticates its
 * notifications with a fixed header the merchant sets (the Cielo offers neither mTLS nor a
 * signature). FAKE is not listed: it is a test double, not something a merchant configures.
 */
public final class ProviderCatalog {
  public record Entry(
      List<PaymentMethod> methods, Set<String> secretFields, boolean hasNotificationKey) {}

  private static final Map<Provider, Entry> ENTRIES = new EnumMap<>(Provider.class);

  static {
    ENTRIES.put(
        Provider.ITAU,
        new Entry(
            List.of(PaymentMethod.PIX, PaymentMethod.BOLECODE),
            Set.of("client_secret", "x_itau_apikey", "private_key_pem"),
            false));
    ENTRIES.put(
        Provider.CIELO, new Entry(List.of(PaymentMethod.CARD), Set.of("merchant_key"), true));
  }

  private ProviderCatalog() {}

  public static Entry of(Provider provider) {
    Entry entry = ENTRIES.get(provider);

    if (entry == null) {
      throw new IllegalArgumentException("unknown provider");
    }

    return entry;
  }

  public static List<Provider> listed() {
    return List.copyOf(ENTRIES.keySet());
  }
}
