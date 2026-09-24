package com.gateway.merchants.crypto;

import static org.assertj.core.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EnvelopeCipherTest {
  final EnvelopeCipher cipher = new EnvelopeCipher(MasterKey.randomForTests());

  @Test void encryptsAndDecryptsWithAad() {
    byte[] plain = "client_secret=abc".getBytes(StandardCharsets.UTF_8);
    Encrypted e = cipher.encrypt(plain, "merchant-1");
    assertThat(cipher.decrypt(e, "merchant-1")).isEqualTo(plain);
    assertThat(new String(e.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("client_secret");
  }

  /** AAD = merchant id: a payload copied to another merchant does not decrypt. */
  @Test void wrongAadFails() {
    Encrypted e = cipher.encrypt("x".getBytes(), "merchant-1");
    assertThatThrownBy(() -> cipher.decrypt(e, "merchant-2")).isInstanceOf(SecurityException.class);
  }

  @Test void freshNonceAndDekPerEncryption() {
    Encrypted a = cipher.encrypt("x".getBytes(), "m"), b = cipher.encrypt("x".getBytes(), "m");
    assertThat(a.nonce()).isNotEqualTo(b.nonce());
    assertThat(a.encryptedDek()).isNotEqualTo(b.encryptedDek());
  }

  @Test void wrongMasterKeyFails() {
    Encrypted e = cipher.encrypt("x".getBytes(), "m");
    assertThatThrownBy(() -> new EnvelopeCipher(MasterKey.randomForTests()).decrypt(e, "m")).isInstanceOf(SecurityException.class);
  }

  @Test void masterKeyMustBe32Bytes() {
    assertThatThrownBy(() -> MasterKey.fromBase64("AAAA")).isInstanceOf(IllegalArgumentException.class);
  }
}
