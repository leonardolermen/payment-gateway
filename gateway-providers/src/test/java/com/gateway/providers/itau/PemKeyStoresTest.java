package com.gateway.providers.itau;

import static org.assertj.core.api.Assertions.*;

import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class PemKeyStoresTest {
  @Test void buildsAnSslContextFromPemStrings() throws Exception {
    TestCertificates.Bundle b = TestCertificates.generate();
    SSLContext ctx = PemKeyStores.mutualTls(b.clientCertPem(), b.clientKeyPem(), b.caTrust());
    assertThat(ctx.getProtocol()).isEqualTo("TLS");
  }

  @Test void rejectsKeyThatDoesNotMatchCertificate() throws Exception {
    TestCertificates.Bundle a = TestCertificates.generate(), b = TestCertificates.generate();
    assertThatThrownBy(() -> PemKeyStores.mutualTls(a.clientCertPem(), b.clientKeyPem(), a.caTrust())).isInstanceOf(IllegalArgumentException.class);
  }
}
