package com.gateway.merchants.domain;

import com.gateway.kernel.ids.MerchantId;
import java.time.Instant;

public record Merchant(MerchantId id, String name, MerchantStatus status, Instant createdAt, Instant updatedAt) {
  public Merchant {
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
  }
  public static Merchant create(String name) {
    // Checked before trim(): an admin POST with {} used to reach here with null and NPE into a 500.
    if (name == null || name.isBlank()) throw new IllegalArgumentException("name is required");
    Instant now = Instant.now();
    return new Merchant(MerchantId.next(), name.trim(), MerchantStatus.ACTIVE, now, now);
  }
  public Merchant suspend() { return new Merchant(id, name, MerchantStatus.SUSPENDED, createdAt, Instant.now()); }
  public Merchant activate() { return new Merchant(id, name, MerchantStatus.ACTIVE, createdAt, Instant.now()); }
  public boolean isActive() { return status == MerchantStatus.ACTIVE; }
}
