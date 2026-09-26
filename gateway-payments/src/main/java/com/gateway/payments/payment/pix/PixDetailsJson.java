package com.gateway.payments.payment.pix;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-rolled JSON codec for the {@code details} column's pix shape: no Jackson dependency in this
 * module (same reasoning as {@code Payment}'s own hand-built event payloads — see its javadoc).
 * Only ever reads back what {@link #write} produced, so the parser only needs to handle that shape.
 */
public final class PixDetailsJson {
  private PixDetailsJson() {}

  public static String write(PixDetails pix) {
    if (pix == null) {
      return "{}";
    }
    return "{\"txid\":"
        + jsonString(pix.txid())
        + ",\"pixCopiaECola\":"
        + jsonString(pix.pixCopiaECola())
        + ",\"location\":"
        + jsonString(pix.location())
        + ",\"endToEndId\":"
        + jsonString(pix.endToEndId())
        + "}";
  }

  public static PixDetails read(String json) {
    if (json == null) {
      return new PixDetails(null, null, null, null);
    }
    return new PixDetails(
        field(json, "txid"),
        field(json, "pixCopiaECola"),
        field(json, "location"),
        field(json, "endToEndId"));
  }

  // Postgres reformats jsonb on the way back out — in particular it adds a space after ':' — so an
  // exact "key":"value" regex with no \s* read every field back as null once the value had gone
  // through a real jsonb column (an in-memory round trip alone did not catch this). Key order is
  // fixed by write() above, so the JSON text is otherwise stable; the unique index on txid reads
  // details->>'txid' directly and does not depend on key order at all.
  private static String field(String json, String key) {
    Matcher matcher =
        Pattern.compile("\"" + key + "\":\\s*(null|\"((?:\\\\.|[^\"\\\\])*)\")").matcher(json);
    if (!matcher.find()) {
      return null;
    }
    return matcher.group(2) == null ? null : unescape(matcher.group(2));
  }

  private static String jsonString(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder stringBuilder = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> stringBuilder.append("\\\"");
        case '\\' -> stringBuilder.append("\\\\");
        case '\n' -> stringBuilder.append("\\n");
        case '\r' -> stringBuilder.append("\\r");
        case '\t' -> stringBuilder.append("\\t");
        default -> stringBuilder.append(c);
      }
    }
    return stringBuilder.append('"').toString();
  }

  private static String unescape(String s) {
    StringBuilder stringBuilder = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char next = s.charAt(++i);
        switch (next) {
          case 'n' -> stringBuilder.append('\n');
          case 'r' -> stringBuilder.append('\r');
          case 't' -> stringBuilder.append('\t');
          default -> stringBuilder.append(next);
        }
      } else {
        stringBuilder.append(c);
      }
    }
    return stringBuilder.toString();
  }
}
