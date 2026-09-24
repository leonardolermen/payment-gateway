package com.gateway.app.api.admin.dto;

import com.gateway.merchants.domain.Merchant;
import com.gateway.merchants.domain.MerchantStatus;

public record MerchantResponse(String id, String name, MerchantStatus status) {
  public static MerchantResponse from(Merchant m) { return new MerchantResponse(m.id().value(), m.name(), m.status()); }
}
