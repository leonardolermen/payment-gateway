package com.gateway.kernel.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Sha256Test {

  /** FIPS 180-2's "abc" vector; the String overload hashes the UTF-8 bytes, like the byte one. */
  @Test
  void theStringOverloadHashesItsUtf8Bytes() {
    assertThat(Sha256.hex("abc"))
        .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        .isEqualTo(Sha256.hex("abc".getBytes(StandardCharsets.UTF_8)));
  }
}
