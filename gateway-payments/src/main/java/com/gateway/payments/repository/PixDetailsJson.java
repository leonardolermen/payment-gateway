package com.gateway.payments.repository;

import com.gateway.payments.domain.PixDetails;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hand-rolled JSON codec for the {@code details} column's pix shape: no Jackson dependency in this
 * module (same reasoning as {@code Payment}'s own hand-built event payloads — see its javadoc).
 * Only ever reads back what {@link #write} produced, so the parser only needs to handle that shape.
 */
final class PixDetailsJson {
  private PixDetailsJson() {}

  static String write(PixDetails pix) {
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

  static PixDetails read(String json) {
    if (json == null) {
      return new PixDetails(null, null, null, null);
    }
    return new PixDetails(field(json, "txid"), field(json, "pixCopiaECola"), field(json, "location"), field(json, "endToEndId"));
  }

  // Postgres normalizes jsonb on the way back out (e.g. a space after ':', key order changed by
  // uq_payments_provider_txid's own reasoning does not apply here, but the whitespace does) — this
  // read side tolerates that reformatting; \\s* is what a naive exact-string regex missed and read
  // back "null" for every field the round trip test alone (in-memory, no Postgres) never caught.
  private static String field(String json, String key) {
    Matcher m = Pattern.compile("\"" + key + "\":\\s*(null|\"((?:\\\\.|[^\"\\\\])*)\")").matcher(json);
    if (!m.find()) {
      return null;
    }
    return m.group(2) == null ? null : unescape(m.group(2));
  }

  private static String jsonString(String s) {
    if (s == null) {
      return "null";
    }
    StringBuilder sb = new StringBuilder(s.length() + 2).append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        default -> sb.append(c);
      }
    }
    return sb.append('"').toString();
  }

  private static String unescape(String s) {
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c == '\\' && i + 1 < s.length()) {
        char next = s.charAt(++i);
        switch (next) {
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          default -> sb.append(next);
        }
      } else {
        sb.append(c);
      }
    }
    return sb.toString();
  }
}
