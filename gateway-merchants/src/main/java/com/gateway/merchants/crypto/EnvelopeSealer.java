package com.gateway.merchants.crypto;

import com.gateway.kernel.security.Sealer;
import java.nio.ByteBuffer;

/**
 * {@link Sealer} over the same AES-256-GCM envelope the provider credentials use, so a stored card
 * token gets the fresh-DEK-per-row and master-key rotation story those already have. The four parts
 * of {@link Encrypted} are packed into one blob because the payments table has one column for it:
 * nonce (12) | DEK nonce (12) | encrypted DEK (32 + 16 tag = 48) | ciphertext (the rest).
 */
public final class EnvelopeSealer implements Sealer {
  private static final int NONCE = 12;
  private static final int ENCRYPTED_DEK = 48;
  private static final int HEADER = NONCE + NONCE + ENCRYPTED_DEK;

  private final EnvelopeCipher cipher;

  public EnvelopeSealer(EnvelopeCipher cipher) {
    this.cipher = cipher;
  }

  @Override
  public byte[] seal(byte[] plaintext, String context) {
    Encrypted encrypted = cipher.encrypt(plaintext, context);

    return ByteBuffer.allocate(HEADER + encrypted.ciphertext().length)
        .put(encrypted.nonce())
        .put(encrypted.dekNonce())
        .put(encrypted.encryptedDek())
        .put(encrypted.ciphertext())
        .array();
  }

  @Override
  public byte[] open(byte[] sealed, String context) {
    if (sealed == null || sealed.length <= HEADER) {
      throw new SecurityException("decryption failed");
    }

    ByteBuffer buffer = ByteBuffer.wrap(sealed);
    byte[] nonce = new byte[NONCE];
    byte[] dekNonce = new byte[NONCE];
    byte[] encryptedDek = new byte[ENCRYPTED_DEK];
    byte[] ciphertext = new byte[sealed.length - HEADER];
    buffer.get(nonce).get(dekNonce).get(encryptedDek).get(ciphertext);

    return cipher.decrypt(new Encrypted(nonce, ciphertext, encryptedDek, dekNonce), context);
  }
}
