package com.gateway.kernel.ids;

public record MerchantId(String value) {
  public MerchantId {
    if (!Ulid.isValid(value)) throw new IllegalArgumentException("invalid merchant id: " + value);
  }
  public static MerchantId next() { return new MerchantId(Ulid.next()); }
}
