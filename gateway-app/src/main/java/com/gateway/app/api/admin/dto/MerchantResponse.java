package com.gateway.app.api.admin.dto;

import com.gateway.merchants.merchant.Merchant;
import com.gateway.merchants.merchant.MerchantStatus;

/** {@code inboundWebhookUrl} is what the merchant registers at the bank ({@code PUT /webhook/{chave}}); null when the mTLS connector is off. */
public record MerchantResponse(String id, String name, MerchantStatus status, String inboundWebhookUrl) {
  public static MerchantResponse from(Merchant m, String inboundWebhookUrl) {
    return new MerchantResponse(m.id().value(), m.name(), m.status(), inboundWebhookUrl);
  }
}
