package com.gateway.merchants.crypto;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * The envelope's master key. In the MVP it comes from an environment variable
 * ({@code GATEWAY_MASTER_KEY}, base64 of 32 bytes); in phase 2 it comes from a KMS — and the swap
 * happens only here, because the per-row DEK stays the same: rotating the master key re-encrypts
 * 32 bytes per row, not every payload.
 */
public final class MasterKey {
  private final SecretKey key;

  private MasterKey(byte[] bytes) {
    if (bytes.length != 32) throw new IllegalArgumentException("master key must be 32 bytes, got " + bytes.length);
    this.key = new SecretKeySpec(bytes, "AES");
  }

  public static MasterKey fromBase64(String base64) {
    if (base64 == null || base64.isBlank()) throw new IllegalArgumentException("GATEWAY_MASTER_KEY is missing");
    return new MasterKey(Base64.getDecoder().decode(base64.trim()));
  }

  public static MasterKey randomForTests() {
    byte[] b = new byte[32];
    new SecureRandom().nextBytes(b);
    return new MasterKey(b);
  }

  SecretKey key() { return key; }

  @Override public String toString() { return "MasterKey[***]"; }
}
