package com.gateway.providers.itau.auth;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

/**
 * PEM strings from the merchant's encrypted credential → an in-memory SSLContext for mTLS. Nothing
 * touches disk: the private key exists only inside this KeyStore for the lifetime of the client.
 */
public final class PemKeyStores {
  private PemKeyStores() {}

  static SSLContext mutualTls(String certificatePem, String privateKeyPem, KeyStore trustStore) {
    try {
      X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
          .generateCertificate(new ByteArrayInputStream(certificatePem.getBytes()));
      PrivateKey key = readPrivateKey(privateKeyPem);
      if (!keyMatches(cert, key)) {
        throw new IllegalArgumentException("private key does not match the certificate");
      }
      char[] pw = new char[0];
      KeyStore keyStore = KeyStore.getInstance("PKCS12");
      keyStore.load(null, null);
      keyStore.setKeyEntry("itau", key, pw, new X509Certificate[] {cert});
      KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(keyStore, pw);
      TrustManager[] tms = null;
      if (trustStore != null) {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        tms = tmf.getTrustManagers();
      }
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(kmf.getKeyManagers(), tms, null);
      return ctx;
    } catch (IllegalArgumentException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalArgumentException("invalid PEM material: " + e.getMessage(), e);
    }
  }

  public static KeyStore trustStoreFrom(List<X509Certificate> cas) {
    try {
      KeyStore ts = KeyStore.getInstance("PKCS12");
      ts.load(null, null);
      for (int i = 0; i < cas.size(); i++) {
        ts.setCertificateEntry("ca-" + i, cas.get(i));
      }
      return ts;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private static PrivateKey readPrivateKey(String pem) throws Exception {
    try (PEMParser pemParser = new PEMParser(new StringReader(pem))) {
      Object o = pemParser.readObject();
      JcaPEMKeyConverter converter = new JcaPEMKeyConverter();
      if (o instanceof PrivateKeyInfo info) {
        return converter.getPrivateKey(info);
      }
      if (o instanceof PEMKeyPair pair) {
        return converter.getKeyPair(pair).getPrivate();
      }
      throw new IllegalArgumentException("unsupported private key PEM: " + (o == null ? "empty" : o.getClass().getSimpleName()));
    }
  }

  /** Sign-and-verify a nonce: the cheapest proof the key and the cert belong together. */
  private static boolean keyMatches(X509Certificate cert, PrivateKey key) throws Exception {
    byte[] nonce = new byte[32];
    new SecureRandom().nextBytes(nonce);
    String alg = key.getAlgorithm().equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
    Signature signer = Signature.getInstance(alg);
    signer.initSign(key);
    signer.update(nonce);
    byte[] sig = signer.sign();
    Signature verifier = Signature.getInstance(alg);
    verifier.initVerify(cert.getPublicKey());
    verifier.update(nonce);
    return verifier.verify(sig);
  }
}
