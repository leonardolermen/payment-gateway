package com.gateway.providers.itau.auth;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.UUID;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * In-memory CA + client/server certificates for mTLS tests, so no test ships a checked-in key pair.
 * Tasks 3, 4 and 9 reuse this: keep the {@link Bundle} shape stable. Public because gateway-app's
 * mTLS webhook test consumes it through this module's test-jar.
 */
public final class TestCertificates {
  static {
    if (Security.getProvider("BC") == null) {
      Security.addProvider(new BouncyCastleProvider());
    }
  }

  private TestCertificates() {}

  public record Bundle(
      KeyStore caTrust,
      String clientCertPem,
      String clientKeyPem,
      KeyStore serverKeyStore,
      char[] serverPassword) {}

  public static Bundle generate() throws Exception {
    KeyPair caKeys = rsaKeyPair();
    X500Name caSubject = new X500Name("CN=Test CA " + UUID.randomUUID());
    // CA: keyCertSign|cRLSign only — it signs other certs and CRLs, never a TLS handshake directly.
    X509Certificate caCert =
        signedBy(
            caSubject,
            caKeys.getPrivate(),
            caSubject,
            caKeys.getPublic(),
            true,
            null,
            new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign),
            null);

    String clientId = "client-" + UUID.randomUUID();
    KeyPair clientKeys = rsaKeyPair();
    // Client leaf: defensive KU/EKU so a stricter TLS stack (Task 3's WireMock/Jetty) accepts it as
    // a
    // client cert instead of silently trusting any leaf with clientAuth unset.
    X509Certificate clientCert =
        signedBy(
            caSubject,
            caKeys.getPrivate(),
            new X500Name("CN=" + clientId),
            clientKeys.getPublic(),
            false,
            null,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment),
            new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));

    KeyPair serverKeys = rsaKeyPair();
    GeneralNames serverSan =
        new GeneralNames(
            new GeneralName[] {
              new GeneralName(GeneralName.dNSName, "localhost"),
              new GeneralName(GeneralName.iPAddress, "127.0.0.1")
            });
    X509Certificate serverCert =
        signedBy(
            caSubject,
            caKeys.getPrivate(),
            new X500Name("CN=localhost"),
            serverKeys.getPublic(),
            false,
            serverSan,
            new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment),
            new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));

    KeyStore caTrust = KeyStore.getInstance("PKCS12");
    caTrust.load(null, null);
    caTrust.setCertificateEntry("ca", caCert);

    char[] serverPassword = "test-only".toCharArray();
    KeyStore serverKeyStore = KeyStore.getInstance("PKCS12");
    serverKeyStore.load(null, null);
    serverKeyStore.setKeyEntry(
        "server",
        serverKeys.getPrivate(),
        serverPassword,
        new X509Certificate[] {serverCert, caCert});

    return new Bundle(
        caTrust, toPem(clientCert), toPem(clientKeys.getPrivate()), serverKeyStore, serverPassword);
  }

  private static KeyPair rsaKeyPair() throws Exception {
    KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
    gen.initialize(2048);
    return gen.generateKeyPair();
  }

  private static X509Certificate signedBy(
      X500Name issuer,
      PrivateKey issuerKey,
      X500Name subject,
      java.security.PublicKey subjectKey,
      boolean isCa,
      GeneralNames san,
      KeyUsage keyUsage,
      ExtendedKeyUsage extendedKeyUsage)
      throws Exception {
    Instant now = Instant.now();
    JcaX509v3CertificateBuilder builder =
        new JcaX509v3CertificateBuilder(
            issuer,
            BigInteger.valueOf(now.toEpochMilli())
                .multiply(BigInteger.valueOf(1000))
                .add(BigInteger.valueOf((long) (Math.random() * 1000))),
            Date.from(now.minus(1, ChronoUnit.DAYS)),
            Date.from(now.plus(365, ChronoUnit.DAYS)),
            subject,
            subjectKey);
    builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa));
    if (san != null) {
      builder.addExtension(Extension.subjectAlternativeName, false, san);
    }
    if (keyUsage != null) {
      builder.addExtension(Extension.keyUsage, true, keyUsage);
    }
    if (extendedKeyUsage != null) {
      builder.addExtension(Extension.extendedKeyUsage, false, extendedKeyUsage);
    }
    ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey);
    return new JcaX509CertificateConverter()
        .setProvider("BC")
        .getCertificate(builder.build(signer));
  }

  private static String toPem(Object o) throws Exception {
    StringWriter sw = new StringWriter();
    try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
      w.writeObject(o);
    }
    return sw.toString();
  }
}
