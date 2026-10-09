package com.gateway.app.observability;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Central log masking. Regex rather than a list of fields because leaks come from where nobody
 * expected — an exception message, a DTO's toString, the body of an HTTP error from the bank.
 */
public final class Masker {
  private static final Pattern CPF = Pattern.compile("\\b\\d{3}\\.?\\d{3}\\.?\\d{3}-?\\d{2}\\b");
  private static final Pattern API_KEY = Pattern.compile("gk_(live|test)_[0-9A-Za-z]+");
  private static final Pattern CHECKOUT_TOKEN = Pattern.compile("chk_[A-Za-z0-9_-]{43}");
  private static final Pattern BEARER = Pattern.compile("(?i)(Bearer\\s+)\\S+");
  private static final Pattern FIELDS =
      Pattern.compile(
          "(\"(?:client_secret|secret|previous_secret|pix_copia_e_cola|password|token|access_token|certificate"
              + "|merchant_key|x_itau_apikey|private_key_pem|certificate_pem|key)\"\\s*:\\s*\")[^\"]*(\")");

  /** 13–19 digits, grouped by spaces or hyphens or not; only those that pass Luhn are masked. */
  private static final Pattern CARD_NUMBER =
      Pattern.compile("(?<![\\d-])\\d(?:[ -]?\\d){12,18}(?![\\d-])");

  private static final Pattern CARD_SECURITY_CODE =
      Pattern.compile("(\"(?:cvv|SecurityCode)\"\\s*:\\s*\")\\d+(\")");

  private Masker() {}

  public static String mask(String s) {
    if (s == null || s.isEmpty()) {
      return s;
    }
    String r = maskCardNumbers(s);
    r = CARD_SECURITY_CODE.matcher(r).replaceAll("$1***$2");
    r = BEARER.matcher(r).replaceAll("$1***");
    r = API_KEY.matcher(r).replaceAll("***");
    r = CHECKOUT_TOKEN.matcher(r).replaceAll("chk_****");
    r = CPF.matcher(r).replaceAll("***");
    r = FIELDS.matcher(r).replaceAll("$1***$2");
    return r;
  }

  /**
   * Spec §7: a PAN becomes ****last4. Luhn-checked so a 13-digit epoch millisecond or an order id
   * survives nine times out of ten; the tenth is masked, which is the cheap mistake.
   */
  private static String maskCardNumbers(String s) {
    Matcher matcher = CARD_NUMBER.matcher(s);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      String digits = matcher.group().replaceAll("[ -]", "");
      String replacement =
          passesLuhn(digits) ? "****" + digits.substring(digits.length() - 4) : matcher.group();
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static boolean passesLuhn(String digits) {
    int sum = 0;
    boolean doubleIt = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int digit = digits.charAt(i) - '0';
      if (doubleIt) {
        digit *= 2;
        if (digit > 9) {
          digit -= 9;
        }
      }
      sum += digit;
      doubleIt = !doubleIt;
    }
    return sum % 10 == 0;
  }
}
