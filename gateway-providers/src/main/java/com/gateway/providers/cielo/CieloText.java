package com.gateway.providers.cielo;

import com.gateway.kernel.party.PersonName;
import com.gateway.kernel.provider.ProviderException;
import com.gateway.kernel.provider.card.CardHolder;
import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * The Cielo's text rules, applied on the way out. Holder: "Não aceita caracteres especiais ou
 * acentuação. Tamanho: 25"; Customer.Name: "apenas a-z, A-Z", 255
 * (reference/criar-pagamento-credito). Transliterated (Ã → A), not dropped: "JOÃO" must reach the
 * issuer as "JOAO", not "JO".
 */
public final class CieloText {
  private static final Pattern MARKS = Pattern.compile("\\p{M}");
  private static final Pattern NOT_ASCII_LETTER_OR_SPACE = Pattern.compile("[^A-Za-z ]");
  private static final Pattern SPACES = Pattern.compile(" +");
  private static final int HOLDER_MAX = 25;
  private static final int NAME_MAX = 255;

  private CieloText() {}

  public static String holder(CardHolder holder) {
    return clean(holder.value(), HOLDER_MAX);
  }

  public static String customerName(PersonName name) {
    return clean(name.value(), NAME_MAX);
  }

  private static String clean(String text, int max) {
    String plain = MARKS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("");
    String letters = NOT_ASCII_LETTER_OR_SPACE.matcher(plain).replaceAll("");
    String single = SPACES.matcher(letters.trim()).replaceAll(" ");

    if (single.isEmpty()) {
      // INVALID, not DECLINED: the Cielo never saw it; our input has nothing it accepts.
      throw new ProviderException(
          ProviderException.Code.INVALID, 0, null, "name has no letters the Cielo accepts");
    }

    return single.length() <= max ? single : single.substring(0, max).trim();
  }
}
