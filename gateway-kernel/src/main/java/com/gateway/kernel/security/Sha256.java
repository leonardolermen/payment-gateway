package com.gateway.kernel.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hex of a byte payload. Both Itaú and Cielo credentials hash their whole decrypted payload
 * with this once, at parse time, and use the result as the cache key for the provider's
 * token/HttpClient cache — covering every byte means any change to the credential (a rotated
 * secret, a new field) is a new cache entry, instead of the cache surviving on a stale value keyed
 * by only part of the payload.
 */
public final class Sha256 {

  private Sha256() {}

  public static String hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
