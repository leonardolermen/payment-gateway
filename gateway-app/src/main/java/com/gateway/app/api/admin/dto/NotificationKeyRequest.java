package com.gateway.app.api.admin.dto;

/** The value the merchant typed as the fixed header at the acquirer (Cielo: up to 1500 chars). */
public record NotificationKeyRequest(String key) {

  @Override
  public String toString() {
    return "NotificationKeyRequest[***]";
  }
}
