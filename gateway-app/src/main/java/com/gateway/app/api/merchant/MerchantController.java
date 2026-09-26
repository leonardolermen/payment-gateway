package com.gateway.app.api.merchant;

import com.gateway.app.security.MerchantContext;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.merchant.MerchantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MerchantController {
  private final MerchantService merchants;
  public MerchantController(MerchantService merchants) {
    this.merchants = merchants;
  }

  public record Merchant(String merchantId, String name, ApiKeyEnvironment environment) {}

  /** The route is {@code /v1/me}: it is contract, documented in the README and asserted by
   * AuthenticationIntegrationTest. The class and the record were renamed; the path was not. */
  @GetMapping("/v1/me")
  public Merchant me() {
    var current = MerchantContext.current();
    var merchant = merchants.get(current.merchantId());

    return new Merchant(current.merchantId().value(), merchant.name(), current.environment());
  }
}
