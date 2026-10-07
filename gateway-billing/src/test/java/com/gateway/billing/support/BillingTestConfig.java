package com.gateway.billing.support;

import com.gateway.billing.order.checkout.CheckoutLinks;
import com.gateway.billing.order.checkout.TokenHasher;
import com.gateway.kernel.security.Sha256;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

@TestConfiguration(proxyBeanMethods = false)
public class BillingTestConfig {

  /** The real hasher is the app's (it needs the merchants pepper); billing tests only need one. */
  @Bean
  TokenHasher tokenHasher() {
    return token -> Sha256.hex(token);
  }

  /** The real one is the app's CheckoutProperties; this base is what billing tests assert on. */
  @Bean
  CheckoutLinks checkoutLinks() {
    return token -> "https://pay.test/pay/" + token.value();
  }
}
