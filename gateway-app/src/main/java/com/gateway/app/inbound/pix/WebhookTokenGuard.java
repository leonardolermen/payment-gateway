package com.gateway.app.inbound.pix;

import com.gateway.kernel.errors.NotFoundException;
import com.gateway.merchants.domain.Merchant;
import com.gateway.merchants.service.MerchantService;
import org.springframework.stereotype.Component;

/**
 * Resolves the merchant a webhook URL was issued to. Every unknown token gets the same 404 and the
 * token is never echoed: the handshake already proved the caller holds a bank certificate, but a
 * probe must still not learn which tokens exist.
 */
@Component
public class WebhookTokenGuard {
  private final MerchantService merchants;

  public WebhookTokenGuard(MerchantService merchants) { this.merchants = merchants; }

  public Merchant resolve(String token) {
    return merchants.findByInboundWebhookToken(token).orElseThrow(() -> new NotFoundException("webhook", "unknown"));
  }
}
