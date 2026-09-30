package com.gateway.providers.cielo;

import java.util.regex.Pattern;

/**
 * Masks what must never leave the Cielo client as text (spec §7): every exception message the
 * client builds from a body goes through here before it can reach provider_requests or a log. The
 * gateway does not record request bodies at all (ProviderGateway, plan C2); this is the rule for
 * everything else.
 */
public final class CieloPayloadMasker {
  private static final Pattern CARD_NUMBER =
      Pattern.compile("\"CardNumber\"\\s*:\\s*\"(\\d{6})\\d{3,9}(\\d{4})\"");
  private static final Pattern SECRET_FIELDS =
      Pattern.compile("\"(SecurityCode|CardToken|MerchantKey)\"\\s*:\\s*\"[^\"]*\"");

  private CieloPayloadMasker() {}

  public static String mask(String text) {
    if (text == null) {
      return null;
    }

    String masked = CARD_NUMBER.matcher(text).replaceAll("\"CardNumber\":\"$1******$2\"");
    return SECRET_FIELDS.matcher(masked).replaceAll("\"$1\":\"***\"");
  }
}
