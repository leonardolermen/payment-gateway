package com.gateway.providers.itau.boleto;

import com.gateway.kernel.provider.ProviderException;
import java.util.regex.Pattern;

/**
 * The issue OpenAPI forbids {@code [ : < > & ; ' " ` ( ) # * / | ü} and the words http/javascript/alert
 * anywhere in the payload, and each text field has its own character class. Filtering by the
 * field's class (a whitelist) is safer than removing the listed characters: it also drops what the
 * schema's pattern would reject. {@code max} is the mainframe limit from the field's description,
 * smaller than the schema's maxLength (nome_pessoa: 50 vs 100).
 */
public final class BoletoText {
  private static final String ACCENTS = "áàâãéèêíïóôõöúçñÁÀÂÃÉÈÊÍÏÓÔÕÖÚÇÑ";
  private static final Pattern NOT_NAME = Pattern.compile("[^a-zA-Z\\s" + ACCENTS + "]");
  private static final Pattern NOT_TEXT = Pattern.compile("[^a-zA-Z0-9\\s\\-.," + ACCENTS + "]");
  private static final Pattern FORBIDDEN_WORDS = Pattern.compile("(?i)http|javascript|alert");
  private static final Pattern SPACES = Pattern.compile("\\s+");

  private BoletoText() {}

  /** {@code pessoa.nome_pessoa}: letters and spaces only. */
  public static String name(String s, int max) {
    return clean(s, NOT_NAME, max);
  }

  /** Address lines, city, district, {@code texto_uso_beneficiario}: letters, digits, space, {@code - . ,}. */
  public static String text(String s, int max) {
    return clean(s, NOT_TEXT, max);
  }

  private static String clean(String s, Pattern notAllowed, int max) {
    if (s == null) {
      return null;
    }
    String out = FORBIDDEN_WORDS.matcher(s).replaceAll("");
    out = notAllowed.matcher(out).replaceAll("");
    out = SPACES.matcher(out.trim()).replaceAll(" ");
    if (out.isEmpty()) {
      // INVALID, not DECLINED: the bank never saw this; it is our input that has nothing usable.
      throw new ProviderException(ProviderException.Code.INVALID, 0, null, "text has no characters the bank accepts");
    }
    return out.length() <= max ? out : out.substring(0, max).trim();
  }
}
