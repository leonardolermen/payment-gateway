package com.gateway.app.api;

import com.gateway.app.security.MerchantContext;
import com.gateway.merchants.domain.ApiKeyEnvironment;
import com.gateway.merchants.service.MerchantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {
  private final MerchantService merchants;
  public MeController(MerchantService merchants) { this.merchants = merchants; }

  public record Me(String merchantId, String name, ApiKeyEnvironment environment) {}

  @GetMapping("/v1/me")
  public Me me() {
    var current = MerchantContext.current();
    var merchant = merchants.get(current.merchantId());
    return new Me(current.merchantId().value(), merchant.name(), current.environment());
  }
}
