package com.gateway.payments.support;

import com.gateway.kernel.security.Sealer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * The payments module's stand-in for merchants' EnvelopeSealer: real AES-GCM with the context as
 * AAD, one random key per test context. Real encryption on purpose — a test that "seals" by copying
 * would let a plaintext token in the table pass.
 */
public class TestSealer implements Sealer {
  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();
  private volatile RuntimeException failNextSeal;

  public TestSealer() {
    try {
      KeyGenerator generator = KeyGenerator.getInstance("AES");
      generator.init(256);
      this.key = generator.generateKey();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  /** The key service is down, or the envelope cipher refuses: the next seal throws {@code e}. */
  public void failNextSealWith(RuntimeException e) {
    this.failNextSeal = e;
  }

  @Override
  public byte[] seal(byte[] plaintext, String context) {
    RuntimeException fail = failNextSeal;
    if (fail != null) {
      failNextSeal = null;
      throw fail;
    }

    byte[] nonce = new byte[12];
    random.nextBytes(nonce);
    byte[] ciphertext = gcm(Cipher.ENCRYPT_MODE, nonce, context, plaintext);
    return ByteBuffer.allocate(12 + ciphertext.length).put(nonce).put(ciphertext).array();
  }

  @Override
  public byte[] open(byte[] sealed, String context) {
    ByteBuffer buffer = ByteBuffer.wrap(sealed);
    byte[] nonce = new byte[12];
    byte[] ciphertext = new byte[sealed.length - 12];
    buffer.get(nonce).get(ciphertext);
    return gcm(Cipher.DECRYPT_MODE, nonce, context, ciphertext);
  }

  private byte[] gcm(int mode, byte[] nonce, String context, byte[] input) {
    try {
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(mode, key, new GCMParameterSpec(128, nonce));
      cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
      return cipher.doFinal(input);
    } catch (GeneralSecurityException e) {
      throw new SecurityException("decryption failed");
    }
  }
}
