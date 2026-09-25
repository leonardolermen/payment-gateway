package com.gateway.merchants.crypto;

import java.util.Arrays;

/** The envelope: ciphertext under the DEK, plus the DEK encrypted under the master key. Everything that goes to the database. */
public record Encrypted(byte[] nonce, byte[] ciphertext, byte[] encryptedDek, byte[] dekNonce) {
  @Override public String toString() {
    return "Encrypted[***]";
  }
  @Override public boolean equals(Object o) {
    return o instanceof Encrypted e && Arrays.equals(nonce, e.nonce) && Arrays.equals(ciphertext, e.ciphertext)
        && Arrays.equals(encryptedDek, e.encryptedDek) && Arrays.equals(dekNonce, e.dekNonce);
  }
  @Override public int hashCode() {
    return Arrays.hashCode(ciphertext);
  }
}
