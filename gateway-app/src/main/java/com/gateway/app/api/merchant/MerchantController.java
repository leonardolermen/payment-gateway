package com.gateway.app.api.merchant;

import com.gateway.app.security.MerchantContext;
import com.gateway.merchants.apikey.ApiKeyEnvironment;
import com.gateway.merchants.merchant.MerchantService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The merchant behind the API key on this request: who the caller is, in the caller's own words.
 */
@RestController
public class MerchantController {
  private final MerchantService merchants;

  public MerchantController(MerchantService merchants) {
    this.merchants = merchants;
  }

  /** {@code var} at the call site below because the domain type is also called Merchant. */
  public record Merchant(String merchantId, String name, ApiKeyEnvironment environment) {}

  /**
   * {@code /v1/merchant} and not {@code /v1/me}: the resource is the merchant behind the API key,
   * and naming a route after the caller's perspective only reads well while there is one kind of
   * caller. Renamed deliberately — see DECISOES 2026-09-26 — and breaking for anyone on the old
   * path, which answers 404 now rather than redirecting.
   */
  @GetMapping("/v1/merchant")
  public Merchant merchant() {
    var caller = MerchantContext.current();
    var found = merchants.get(caller.merchantId());

    return new Merchant(caller.merchantId().value(), found.name(), caller.environment());
  }
}
