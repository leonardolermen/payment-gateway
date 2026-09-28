package com.gateway.merchants.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EnvelopeSealerTest {
  final EnvelopeSealer sealer = new EnvelopeSealer(new EnvelopeCipher(MasterKey.randomForTests()));
  static final String CONTEXT = "01K0MERCHANT0000000000000|CIELO|TEST|card";

  @Test
  void opensWhatItSealed() {
    byte[] token = "6e1bf77a-b28b-4660-b14f-455e2a1c95e9".getBytes(StandardCharsets.UTF_8);

    byte[] sealed = sealer.seal(token, CONTEXT);

    assertThat(sealed).hasSizeGreaterThan(token.length + 12 + 12 + 48);
    assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("6e1bf77a");
    assertThat(sealer.open(sealed, CONTEXT)).isEqualTo(token);
  }

  /** The AAD binds the token to its merchant: a row copied to another merchant does not open. */
  @Test
  void anotherContextDoesNotOpen() {
    byte[] sealed = sealer.seal("tok".getBytes(StandardCharsets.UTF_8), CONTEXT);

    assertThatThrownBy(() -> sealer.open(sealed, "01K0OTHER|CIELO|TEST|card"))
        .isInstanceOf(SecurityException.class);
  }

  @Test
  void aTruncatedBlobIsRefused() {
    assertThatThrownBy(() -> sealer.open(new byte[10], CONTEXT))
        .isInstanceOf(SecurityException.class);
  }
}
