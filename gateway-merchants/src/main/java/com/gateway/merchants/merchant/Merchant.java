package com.gateway.merchants.merchant;

import com.gateway.kernel.ids.MerchantId;
import com.gateway.kernel.ids.Ulid;
import java.time.Instant;

/**
 * {@code inboundWebhookToken} is the opaque path segment of the URL the merchant registers at the bank
 * for inbound Pix webhooks (V101). A token rather than the merchant id, so the URL the bank logs does
 * not reveal an identifier our API also returns.
 */
public record Merchant(MerchantId id, String name, MerchantStatus status, String inboundWebhookToken, Instant createdAt, Instant updatedAt) {
  public Merchant {
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    if (inboundWebhookToken == null || inboundWebhookToken.length() != 26) {
      throw new IllegalArgumentException("inbound webhook token must be 26 characters");
    }
  }
  public static Merchant create(String name) {
    // Checked before trim(): an admin POST with {} used to reach here with null and NPE into a 500.
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("name is required");
    }
    Instant now = Instant.now();
    return new Merchant(MerchantId.next(), name.trim(), MerchantStatus.ACTIVE, Ulid.next(), now, now);
  }
  /** Rebuilds a merchant from storage; unlike {@link #create} it keeps the stored token. */
  public static Merchant rehydrate(MerchantId id, String name, MerchantStatus status, String inboundWebhookToken, Instant createdAt, Instant updatedAt) {
    return new Merchant(id, name, status, inboundWebhookToken, createdAt, updatedAt);
  }
  public Merchant suspend() {
    return new Merchant(id, name, MerchantStatus.SUSPENDED, inboundWebhookToken, createdAt, Instant.now());
  }
  public Merchant activate() {
    return new Merchant(id, name, MerchantStatus.ACTIVE, inboundWebhookToken, createdAt, Instant.now());
  }
  public boolean isActive() {
    return status == MerchantStatus.ACTIVE;
  }
}
