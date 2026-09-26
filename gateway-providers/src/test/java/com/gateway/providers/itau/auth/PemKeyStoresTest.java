package com.gateway.providers.itau.auth;

import static org.assertj.core.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;

class PemKeyStoresTest {
  @Test
  void buildsAnSslContextFromPemStrings() throws Exception {
    TestCertificates.Bundle b = TestCertificates.generate();
    SSLContext ctx = PemKeyStores.mutualTls(b.clientCertPem(), b.clientKeyPem(), b.caTrust());
    assertThat(ctx.getProtocol()).isEqualTo("TLS");
  }

  @Test
  void rejectsKeyThatDoesNotMatchCertificate() throws Exception {
    TestCertificates.Bundle a = TestCertificates.generate(), b = TestCertificates.generate();
    assertThatThrownBy(
            () -> PemKeyStores.mutualTls(a.clientCertPem(), b.clientKeyPem(), a.caTrust()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Task 3 starts WireMock (Jetty) with the server cert and requires the client cert; a stricter
   * TLS stack can reject a leaf whose EKU doesn't say serverAuth/clientAuth, so pin it here rather
   * than discover it only when Task 3's handshake fails.
   */
  @Test
  void generatedCertificatesCarryTheRightExtendedKeyUsage() throws Exception {
    TestCertificates.Bundle b = TestCertificates.generate();
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    X509Certificate clientCert =
        (X509Certificate)
            cf.generateCertificate(new ByteArrayInputStream(b.clientCertPem().getBytes()));
    X509Certificate serverCert = (X509Certificate) b.serverKeyStore().getCertificate("server");

    assertThat(serverCert.getExtendedKeyUsage()).contains("1.3.6.1.5.5.7.3.1");
    assertThat(clientCert.getExtendedKeyUsage()).contains("1.3.6.1.5.5.7.3.2");
  }
}
