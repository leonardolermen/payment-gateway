package com.gateway.app.observability;

import java.util.regex.Pattern;

/**
 * Central log masking. Regex rather than a list of fields because leaks come from where nobody
 * expected — an exception message, a DTO's toString, the body of an HTTP error from the bank.
 */
public final class Masker {
  private static final Pattern CPF = Pattern.compile("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b");
  private static final Pattern API_KEY = Pattern.compile("gk_(live|test)_[0-9A-Za-z]+");
  private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)\\S+");
  private static final Pattern FIELDS =
      Pattern.compile(
          "(\"(?:client_secret|secret|previous_secret|pix_copia_e_cola|password|token|access_token|certificate)\"\\s*:\\s*\")[^\"]*(\")");

  private Masker() {}

  public static String mask(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    String r = BEARER.matcher(s).replaceAll("$1***");
    r = API_KEY.matcher(r).replaceAll("***");
    r = CPF.matcher(r).replaceAll("***");
    r = FIELDS.matcher(r).replaceAll("$1***$2");
    return r;
  }
}
