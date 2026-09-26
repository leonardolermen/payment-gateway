package com.gateway.payments.payment.create;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hex of the document's digits only, so {@code 123.456.789-09} and {@code 12345678909}
 * search as the same customer. The document itself is never stored.
 *
 * <p>Null for anything with no digits at all: a blank is not a customer, and hashing the empty
 * string would make every one of them look like the same person.
 */
public final class CustomerDocumentHash {

  private CustomerDocumentHash() {}

  public static String of(String document) {
    if (document == null) {
      return null;
    }

    String digits = document.replaceAll("\\D", "");
    if (digits.isEmpty()) {
      return null;
    }

    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(digits.getBytes(StandardCharsets.US_ASCII));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
