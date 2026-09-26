package com.gateway.merchants.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM envelope: every encryption generates a fresh DEK, encrypts the payload with it and
 * encrypts the DEK with the master key. GCM because it authenticates (a tampered payload does not
 * decrypt) and the AAD binds the payload to the merchant: copying one merchant's encrypted row to
 * another fails on decrypt.
 *
 * <p>Random 12-byte nonce per operation. With a fresh DEK per encryption the chance of repeating a
 * (nonce, key) pair is zero in practice — GCM's 2^32-encryptions-per-key limit is never approached.
 */
public final class EnvelopeCipher {
  private static final int TAG_BITS = 128;
  private static final int NONCE_BYTES = 12;
  private final MasterKey master;
  private final SecureRandom random = new SecureRandom();

  public EnvelopeCipher(MasterKey master) {
    this.master = master;
  }

  public Encrypted encrypt(byte[] plaintext, String aad) {
    try {
      byte[] dekBytes = new byte[32];
      random.nextBytes(dekBytes);
      SecretKey dek = new SecretKeySpec(dekBytes, "AES");
      byte[] nonce = nonce();
      byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, dek, nonce, aad, plaintext);
      byte[] dekNonce = nonce();
      byte[] encryptedDek = gcm(Cipher.ENCRYPT_MODE, master.key(), dekNonce, aad, dekBytes);
      return new Encrypted(nonce, ciphertext, encryptedDek, dekNonce);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("encryption failed", e);
    }
  }

  public byte[] decrypt(Encrypted e, String aad) {
    try {
      byte[] dekBytes = gcm(Cipher.DECRYPT_MODE, master.key(), e.dekNonce(), aad, e.encryptedDek());
      return gcm(
          Cipher.DECRYPT_MODE, new SecretKeySpec(dekBytes, "AES"), e.nonce(), aad, e.ciphertext());
    } catch (GeneralSecurityException ex) {
      // Deliberately vague: "bad tag" vs "wrong key" is an oracle for nobody.
      throw new SecurityException("decryption failed");
    }
  }

  private byte[] nonce() {
    byte[] n = new byte[NONCE_BYTES];
    random.nextBytes(n);
    return n;
  }

  private static byte[] gcm(int mode, SecretKey key, byte[] nonce, String aad, byte[] input)
      throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
    cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
    cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
    return cipher.doFinal(input);
  }
}
